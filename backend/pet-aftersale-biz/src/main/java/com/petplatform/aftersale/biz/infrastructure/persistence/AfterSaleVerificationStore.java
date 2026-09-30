package com.petplatform.aftersale.biz.infrastructure.persistence;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleVerificationMapper;
public final class AfterSaleVerificationStore {
 public static AfterSaleVerificationMapper mapper(DataSource source){try{
  var bean=new SqlSessionFactoryBean();bean.setDataSource(source);bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/AfterSaleVerificationMapper.xml"));
  var factory=bean.getObject();factory.getConfiguration().setMapUnderscoreToCamelCase(true);factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
  return new SqlSessionTemplate(factory).getMapper(AfterSaleVerificationMapper.class);
 }catch(Exception e){throw new IllegalStateException("Aftersale persistence unavailable");}}
}
