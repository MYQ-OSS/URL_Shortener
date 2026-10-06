package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import top.mayiqin.url_shortener.link.LinkValidity;

/** 短链主表，主键采用内置策略；短码唯一、逻辑删除保留历史统计。 */
@Data
@TableName("t_link")
public class Link {

    /** 统一使用 MyBatis-Plus 内置主键策略，与随机短码互不依赖。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 短码，不含域名，有数据库唯一索引。 */
    private String shortUri;

    private String domain;

    private String originUrl;

    /** 兼容历史表的 MD5 字段；防重已由 t_link_dedup 的 SHA-256 键承担。 */
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
