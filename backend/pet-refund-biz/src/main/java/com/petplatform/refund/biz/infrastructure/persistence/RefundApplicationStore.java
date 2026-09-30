package com.petplatform.refund.biz.infrastructure.persistence;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
public final class RefundApplicationStore {
    private RefundApplicationStore() {}
    public static RefundApplicationMapper mapper(DataSource source){try{var bean=new SqlSessionFactoryBean();bean.setDataSource(source);bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/RefundApplicationMapper.xml"));var f=Objects.requireNonNull(bean.getObject());f.getConfiguration().setMapUnderscoreToCamelCase(true);f.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);return new SqlSessionTemplate(f).getMapper(RefundApplicationMapper.class);}catch(Exception e){throw new IllegalStateException("Refund application persistence unavailable",e);}}
}
