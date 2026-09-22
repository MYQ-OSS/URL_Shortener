package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * gid 维度异构索引表（ADR-005）。
 *
 * <h3>为什么需要它</h3>
 * t_link 按 short_uri 分片（跳转主链路必须单分片命中），
 * 但"查某个 gid 创建的所有短链"是按 gid 查——直接查 t_link 会广播 16 个分片
 * 再归并排序。这张表按 gid 分片，让管理链路也单分片命中。
 *
 * <h3>代价</h3>
 * 创建时要双写（一致性靠本地消息表补偿，见 {@link LocalMessage}），
 * 列表查询变两步（先查这里拿 short_uri，再回查 t_link）。
 * 换来的是可扩容——基因法做不到这一点（分片数固化在短码里，
 * 而短码可能已经印在海报上了，改不了）。
 */
@Data
@TableName("t_link_goto")
public class LinkGoto {

    /** 跨分片不能 AUTO_INCREMENT，用 MyBatis-Plus 雪花。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分片键。 */
    private String gid;

    private String shortUri;
}
