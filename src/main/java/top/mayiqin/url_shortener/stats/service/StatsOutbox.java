package top.mayiqin.url_shortener.stats.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个事件先 fsync 再原子发布；每个实例必须独占一个持久目录。 */
@Component
public class StatsOutbox implements AutoCloseable {
    private final Path directory;
    private final int capacity;
    private final LinkedBlockingQueue<Path> ready = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final FileChannel lockChannel;
    private final FileLock lock;

    public StatsOutbox(@Value("${shortlink.stats.spool-directory:./data/stats-outbox}") String directory,
                       @Value("${shortlink.stats.max-pending:100000}") int capacity) throws IOException {
        if (capacity <= 0) throw new IllegalArgumentException("统计暂存上限必须为正数");
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.capacity = capacity;
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve("outbox.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("统计目录已被其他实例占用");
        } catch (Exception e) {
            lockChannel.close();
            throw e;
        }
        lock = acquired;
        try (var files = Files.newDirectoryStream(this.directory, "*.json")) {
            for (Path file : files) {
                ready.add(file);
                pending.incrementAndGet();
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public boolean append(String payload) throws IOException {
        byte[] content = payload.getBytes(StandardCharsets.UTF_8);
        if (content.length > 16384) throw new IOException("统计事件超过 16KB 上限");
        int count;
        do {
            count = pending.get();
            if (count >= capacity) return false;
        } while (!pending.compareAndSet(count, count + 1));
        String id = UUID.randomUUID().toString();
        Path temporary = directory.resolve(id + ".tmp");
        Path committed = directory.resolve(id + ".json");
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, committed, StandardCopyOption.ATOMIC_MOVE);
            ready.add(committed);
            return true;
        } catch (IOException | RuntimeException e) {
            pending.decrementAndGet();
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public Path poll() { return ready.poll(); }
    public void retry(Path file) { ready.add(file); }
    public String read(Path file) throws IOException { return Files.readString(file, StandardCharsets.UTF_8); }

    public void acknowledge(Path file) throws IOException {
        Files.delete(file);
        pending.decrementAndGet();
    }

    public int pendingCount() { return pending.get(); }

    @Override
    @PreDestroy
    public void close() throws IOException {
        if (lock.isValid()) lock.release();
        lockChannel.close();
    }
}
