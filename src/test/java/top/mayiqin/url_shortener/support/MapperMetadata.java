package top.mayiqin.url_shortener.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

public final class MapperMetadata {
    private MapperMetadata() { }
    public static void initialize(Class<?>... types) {
        for (Class<?> type : types) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        }
    }
}
