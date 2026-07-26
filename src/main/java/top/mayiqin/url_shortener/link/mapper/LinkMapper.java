package top.mayiqin.url_shortener.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.mayiqin.url_shortener.link.entity.Link;

@Mapper
public interface LinkMapper extends BaseMapper<Link> {
}
