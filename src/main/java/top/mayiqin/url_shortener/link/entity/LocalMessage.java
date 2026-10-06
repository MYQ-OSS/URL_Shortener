package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 状态修改后的缓存失效待办，与状态更新同库事务提交。 */
@Data
@TableName("t_local_message")
public class LocalMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务类型，主版本仅使用 LINK_CACHE_INVALIDATE。 */
    private String bizType;

    /** 存 short_uri；保留历史列名 shard_key，避免破坏既有表结构。 */
    private String shardKey;

    /** 消息内容 JSON。补偿任务按它执行。 */
    private String payload;

    /** 0 待处理 / 1 成功 / 2 超过重试上限（人工介入）。 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetry;

    private LocalDateTime createTime;
}
