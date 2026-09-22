package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（ADR-005 的最终一致性载体）。
 *
 * <h3>★ 分片键为什么是 shard_key(=short_uri) 而不是独立一套</h3>
 * 本地消息表模式的前提是"业务表和消息表<b>同库事务</b>"。
 * t_link 按 short_uri 分片，消息表用同一个分片表达式（见 sharding.yaml 的
 * msg_db/msg_tbl），同一行链接和它的消息必然落同一个库——
 * 一个本地事务同时写两张表，不需要任何分布式事务框架。
 *
 * <h3>为什么不用 Seata</h3>
 * 双写的两张表都在 MySQL 内，"同库事务 + 定时补偿投递"就拿到最终一致，
 * 引入分布式事务框架的复杂度完全不值。
 */
@Data
@TableName("t_local_message")
public class LocalMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务类型，如 LINK_GOTO_SYNC。 */
    private String bizType;

    /** 分片键：存 short_uri，与 t_link 同分片表达式 → 同库。 */
    private String shardKey;

    /** 消息内容 JSON。补偿任务按它执行。 */
    private String payload;

    /** 0 待处理 / 1 成功 / 2 超过重试上限（人工介入）。 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetry;

    private LocalDateTime createTime;
}
