package com.petplatform.refund.biz.infrastructure.persistence;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundAfterSaleMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
public final class RefundAfterSaleStore {
 private RefundAfterSaleStore(){}
 public static RefundAfterSaleMapper mapper(DataSource source){try{var b=new SqlSessionFactoryBean();b.setDataSource(source);b.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/RefundAfterSaleMapper.xml"));var f=Objects.requireNonNull(b.getObject());f.getConfiguration().setMapUnderscoreToCamelCase(true);f.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);return new SqlSessionTemplate(f).getMapper(RefundAfterSaleMapper.class);}catch(Exception e){throw new IllegalStateException("After-sale refund persistence unavailable",e);}}
}
