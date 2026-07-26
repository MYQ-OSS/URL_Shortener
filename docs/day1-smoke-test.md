# Day 1 检查清单（今天就做完，约 3 小时）

> 目标不是写业务代码，而是**把全项目唯一的高风险未知项在第 1 天就问出答案**。
> 第 4 周才发现 ShardingSphere 跑不通，就来不及了。

---

## 0. 五分钟看懂现在有什么

```
URL_Shortener/
├── pom.xml                      ← 已换成 Boot 4.0.7 + 全套依赖（ShardingSphere 暂时注释）
├── docker-compose.yml           ← MySQL 8 / Redis 7 / RabbitMQ 一键起
├── sql/schema.sql               ← 全部 DDL（首次启动容器时自动执行）
├── ops/prometheus.yml           ← Week 5 用
├── docs/
│   ├── 方案评审与重构.md          ← 整体方案、5 周排期、简历模板、37 道击穿题
│   ├── adr.md                   ← 架构决策记录（10 条已写，4 条待补）
│   └── day1-smoke-test.md       ← 本文件
└── src/main/java/top/mayiqin/url_shortener/
    ├── common/
    │   ├── Base62Codec.java              定长 6 位编解码       ✅ 已测试通过
    │   └── FeistelCipher.java            FPE 备选方案         ✅ 已测试通过
    ├── idgen/
    │   ├── SegmentIdGenerator.java       双 Buffer 发号器      ✅ 已测试通过（修了 2 个 bug）
    │   └── DbSegmentStore.java           乐观锁 CAS 取号
    ├── shortcode/ShortCodeGenerator.java 随机 + BF 判重
    ├── link/
    │   ├── entity/Link.java
    │   ├── mapper/LinkMapper.java
    │   ├── service/LinkCreateService.java    创建（全同步）
    │   ├── service/LinkQueryService.java     跳转（8 层防护链）
    │   ├── service/CacheInvalidateListener.java
    │   └── controller/{LinkController,RedirectController}.java
    ├── config/{CacheConfig,IdGeneratorConfig,MybatisPlusConfig}.java
    ├── ratelimit/RateLimiter.java        Redis + Lua 令牌桶
    ├── risk/UrlRiskChecker.java          开放重定向 + SSRF 防护
    └── stats/service/StatsCollector.java Week 3 骨架
```

**所有 Java 文件里的注释都不是废话**——每处取舍都写了"为什么这样、放弃了什么"。
面试前把这些注释过一遍，就是最好的复习材料。

---

## 1. 起环境（20 分钟）

```bash
cd D:\Java\Allproject\URL_Shortener
docker compose up -d
docker compose ps          # 三个都要 healthy
```

验证 DDL 自动执行了：

```bash
docker exec -it sl-mysql mysql -uroot -proot123 shortlink -e "SHOW TABLES; SELECT * FROM t_id_segment;"
```

应该看到 5 张表 + `t_id_segment` 里有一行 `short_link / 0 / 1000`。

> ⚠️ `sql/` 目录只在**首次创建数据卷**时执行。改了 schema 要重来：
> `docker compose down -v && docker compose up -d`

---

## 2. 让主工程编译通过（30 分钟）

在 IntelliJ 里刷新 Maven（右侧 Maven 面板 → Reload），然后 `mvn -q compile`。

**预期会遇到的问题和处置**：

| 现象 | 处置 |
|---|---|
| `mybatis-plus-spring-boot4-starter:3.5.17` 拉不到 | 去 [Maven 目录](https://repo1.maven.org/maven2/com/baomidou/mybatis-plus-spring-boot4-starter/) 看当前最新版本号，替换 `<mybatis-plus.version>` |
| `redisson-spring-boot-starter:4.5.0` 版本冲突 | 同上，看 [Redisson releases](https://github.com/redisson/redisson/releases)；`exclusions` 里排除的 `redisson-spring-data-34` 可能要换成实际的模块名 |
| `springdoc-openapi` 报 Boot 4 不兼容 | 直接删掉这个依赖，接口用 curl / Postman 测就够了。**不要为它花超过 20 分钟** |
| Lombok 报注解处理器未启用 | IntelliJ: Settings → Build → Compiler → Annotation Processors → 勾选 Enable |

**原则：任何依赖问题超过 20 分钟就砍掉那个依赖。** 依赖调试是 0 面试价值的时间消耗。

---

## 3. 跑通已有的三个测试（10 分钟）

```bash
mvn -q test -Dtest='Base62CodecTest,FeistelCipherTest,SegmentIdGeneratorTest'
```

这三个测试类**不依赖 Spring、不依赖 DB**，纯逻辑，应该秒过。它们验证了：

- `Base62Codec`：定长 6 位、往返一致、30 万连续值无冲突、越界抛异常、非法短码返回 -1
- `FeistelCipher`：往返一致、cycle-walking 保证不越界、20 万连续 id 无冲突，
  **以及关键对照——模乘置换的连续密文差值恒等于 P**
- `SegmentIdGenerator`：**1000 线程取 100 万 ID 无重复且线程内严格递增**，
  外加 step 大小对同步取号次数的影响对比

`SegmentIdGeneratorTest` 的控制台会打印类似：

```
DB 取号 11 次 | 同步取号 4 次 | 丢弃倒序号段 0 个
step=1,000   -> 同步取号 823 次，丢弃倒序 163 个
step=100,000 -> 同步取号 4 次，丢弃倒序 0 个
```

**把这个输出截图存下来**，它是 ADR-002 的数据依据，也是简历上"双 Buffer 消除 P999 毛刺"的证据。

---

## 4. ★ ShardingSphere 冒烟验证（60 分钟，超时就止损）

**这是今天最重要的一件事。** 它是全项目唯一的高风险未知项：
ShardingSphere 5.5.3 没有 Spring Boot 4 兼容声明，而且仍依赖 **Jackson 2.x**，
Boot 4 默认 **Jackson 3.x**——这两个能不能共存，只有跑一次才知道。

### 4.1 建一个独立的最小工程（不要污染主工程）

新建 `D:\Java\Allproject\ss-smoke`，`pom.xml`：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.7</version>
</parent>
<properties><java.version>21</java.version></properties>
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.shardingsphere</groupId>
        <artifactId>shardingsphere-jdbc</artifactId>
        <version>5.5.3</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 4.2 先看依赖树（这一步 2 分钟，能提前发现 Jackson 冲突）

```bash
mvn dependency:tree -Dincludes="com.fasterxml.jackson.core:*,tools.jackson.core:*"
```

- 只出现 `com.fasterxml.jackson`（Jackson 2）→ 大概能共存
- 同时出现 `com.fasterxml.jackson` 和 `tools.jackson`（Jackson 3 的新 groupId）→ 要留意，
  但**不一定冲突**，因为 groupId 不同可以并存，关键看运行时是否报 `NoSuchMethodError`

### 4.3 `sharding.yaml`

```yaml
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/shortlink?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root123

rules:
  - !SHARDING
    tables:
      t_link:
        actualDataNodes: ds_0.t_link_${0..7}
        tableStrategy:
          standard:
            shardingColumn: short_uri
            shardingAlgorithmName: link_hash
    shardingAlgorithms:
      link_hash:
        type: HASH_MOD
        props:
          sharding-count: 8

props:
  sql-show: true      # 必须开！验收标准就是看 SQL 日志有没有广播查询
```

### 4.4 最小验证代码

```java
@SpringBootApplication
public class SmokeApp implements CommandLineRunner {
    @Autowired JdbcTemplate jdbc;
    public static void main(String[] a) { SpringApplication.run(SmokeApp.class, a); }

    public void run(String... args) {
        // 1. 建 8 张分表（先在 MySQL 里手动执行 CREATE TABLE t_link_0..7 LIKE t_link）
        // 2. 插入
        jdbc.update("INSERT INTO t_link (id, short_uri, domain, origin_url, url_hash, gid) "
                + "VALUES (?,?,?,?,?,?)",
                1L, "aB3xY9", "localhost:8080", "https://example.com",
                new byte[16], "g1");
        // 3. 按分片键查 —— SQL 日志里应该只有【1 条】SQL，命中单表
        System.out.println(jdbc.queryForList(
                "SELECT short_uri, origin_url FROM t_link WHERE short_uri = ?", "aB3xY9"));
        // 4. 不按分片键查 —— SQL 日志里会出现【8 条】SQL（广播查询）
        //    这正是 ADR-005 要用异构索引表解决的问题，跑一次亲眼看到
        System.out.println(jdbc.queryForList("SELECT COUNT(*) FROM t_link WHERE gid = ?", "g1"));
    }
}
```

`application.yaml`：

```yaml
spring:
  datasource:
    driver-class-name: org.apache.shardingsphere.driver.ShardingSphereDriver
    url: jdbc:shardingsphere:classpath:sharding.yaml
```

### 4.5 判定

| 结果 | 下一步 |
|---|---|
| ✅ 启动成功，insert/select 都通，SQL 日志能看到路由 | 主工程 `pom.xml` 里放开 ShardingSphere 依赖注释，**Week 4 按计划做** |
| ❌ `NoSuchMethodError` / `ClassNotFoundException` / 启动报 Jackson 相关错 | **立刻止损，走 Plan B。不要超过半天。** |

### 4.6 Plan B（跑不通时，按优先级）

**B1（推荐，面试价值反而更高）：手写分片路由。**

```java
// 一个 ShardingRouter 工具类算表后缀
public static String tableSuffix(String shortUri) {
    return "_" + (Math.abs(shortUri.hashCode()) % 8);
}
```

配合 MyBatis-Plus 的 `DynamicTableNameInnerInterceptor` 动态改表名（`MybatisPlusConfig` 里已留了位置）。
**代码量约 100 行。**

> **为什么这反而更好**：面试官问"ShardingSphere 内部怎么改写 SQL 的"，
> 配 YAML 的人答不上来；而你自己写了路由，**你就是答案本身**。
> "我配置了 ShardingSphere" 和 "我手写了分片路由并理解了它的代价（跨分片查询、
> 分布式主键、全局唯一索引失效）" 是两个档次。

**B2**：用 `dynamic-datasource-spring-boot4-starter`（baomidou 自家，和 MyBatis-Plus 同步维护）做多数据源，分表自己路由。

**B3**：只分表不分库（16 张表，单库），完全手写路由。最简单，也足够展示问题。

---

## 5. 建 ADR 习惯（10 分钟，但收益最大）

`docs/adr.md` 里已经写好 10 条。**从今天起，每做一个技术决策就加一条。**

格式固定四段：**背景 → 考虑过的方案（对比表）→ 决策 → 放弃了什么（+ 数据依据）**。

> 这份文档是面试时"这个项目是我自己想的"的**唯一硬证据**——
> 因为它记录了你**放弃过什么**，而照抄开源项目的人没有放弃过任何东西。

今天要补的第一条：**ADR-011 记录 ShardingSphere 冒烟验证的结果**（不管通没通都要记）。

---

## Day 1 完成标准

- [ ] `docker compose ps` 三个容器 healthy，5 张表已建好
- [ ] `mvn compile` 通过（砍掉了哪些依赖要记进 ADR）
- [ ] 三个测试类全绿，`SegmentIdGeneratorTest` 的输出已截图
- [ ] ShardingSphere 有明确结论（通 / 不通 → 走 B1），并已写进 ADR-011
- [ ] 明白了接下来 Week 1 剩下要做的事：`DbSegmentStore` 接真 DB 跑通、
      创建 + 跳转两个接口端到端通、**拿到裸 MySQL 的压测基线**

---

## 提醒：Week 1 最重要的产出不是代码，是**基线数据**

在加 Redis 之前，一定要先压一组"裸 MySQL"的 QPS / P99 存档。
后面所有优化（+Redis、+Caffeine、+热点探测、+连接池调优、+日志异步化）
都要对比它。**没有基线，所有"提升了 X 倍"都是空话。**

压测记得用 **Zipf 分布**而不是均匀随机——
均匀随机会让缓存命中率虚低到 12%，那是在测一个不存在的场景。
