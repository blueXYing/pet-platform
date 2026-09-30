package com.petplatform.aftersale.biz.infrastructure.persistence;
import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleWorkflowMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
public final class AfterSaleWorkflowStore {
 private AfterSaleWorkflowStore() {}
 public static AfterSaleWorkflowMapper mapper(DataSource source) {try {
  var bean=new SqlSessionFactoryBean();bean.setDataSource(source);bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/AfterSaleWorkflowMapper.xml"));
  var f=Objects.requireNonNull(bean.getObject());f.getConfiguration().setMapUnderscoreToCamelCase(true);f.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
  return new SqlSessionTemplate(f).getMapper(AfterSaleWorkflowMapper.class);
 }catch(Exception e){throw new IllegalStateException("Aftersale workflow persistence unavailable",e);}}
}
