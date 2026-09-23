package top.mayiqin.url_shortener.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 持久化的规范短码预约；即使创建事务失败，后续请求仍能接续同一短码。 */
@Data
@TableName("t_link_dedup")
public class LinkDedup {
    @TableId(type = IdType.INPUT)
    private String dedupKey;
    private String shortUri;
}
