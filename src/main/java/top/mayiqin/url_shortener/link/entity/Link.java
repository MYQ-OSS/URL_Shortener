package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import top.mayiqin.url_shortener.link.LinkValidity;

/**
 * 短链主表。
 *
 * <h3>四个设计决策，每个都能当面试题</h3>
 * <ol>
 *   <li><b>主键为什么用 {@code id}（发号器）而不是 {@code shortUri}？</b>
 *       InnoDB 主键是聚簇索引。{@code shortUri} 是随机字符串 → 随机位置插入 →
 *       页分裂 + 写放大 + 索引碎片。发号器 ID 趋势递增，顺序追加写，几乎不分裂。
 *       <b>这就是号段发号器在本项目里的真正用途——不是生成短码，是生成主键。</b></li>
 *   <li><b>为什么不用 {@code AUTO_INCREMENT}？</b>
 *       分库分表下多个分片各自自增会冲突，必须外部发号。</li>
 *   <li><b>幂等索引为什么建在 {@code urlHash BINARY(16)} 而不是 {@code originUrl}？</b>
 *       {@code VARCHAR(2048)} utf8mb4 = 8192 字节，超过 InnoDB 索引键 3072 字节上限，
 *       建不了索引。{@code BINARY(16)} 定长 16 字节，还比存十六进制字符串的
 *       {@code CHAR(32)} 省一半空间。</li>
 *   <li><b>{@code delFlag} 逻辑删除</b>，因为要保留历史统计数据。</li>
 * </ol>
 */
@Data
@TableName("t_link")
public class Link {

    /** 号段发号器分配，趋势递增。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 短码，不含域名。分片键。 */
    private String shortUri;

    private String domain;

    private String originUrl;

    /** MD5(originUrl)，16 字节。用于幂等判断。 */
    private byte[] urlHash;

    /** 分组 ID，管理链路的查询维度。 */
    private String gid;

    /** 0 启用 / 1 用户禁用 / 2 风控封禁。 */
    private Integer enableStatus;

    /** 0 永久 / 1 自定义有效期。 */
    private Integer validType;

    private LocalDateTime validEnd;

    /** 冗余的总点击数，由统计任务定时回写。跳转链路不更新它。 */
    private Integer clickNum;

    private String favicon;

    /** 注意：{@code describe} 是 MySQL 保留字，DDL 里必须加反引号。 */
    @TableField("`describe`")
    private String describe;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer delFlag;

    /** 是否已过期。跳转链路用它决定返回失效页而不是 302。 */
    public boolean expired() {
        return validType != null && validType == 1
                && (validEnd == null || !validEnd.isAfter(LocalDateTime.now(LinkValidity.ZONE)));
    }

    /** 是否可跳转。 */
    public boolean redirectable() {
        return (enableStatus == null || enableStatus == 0)
                && (delFlag == null || delFlag == 0)
                && (validType == null || validType == 0 || validType == 1)
                && !expired();
    }
}
