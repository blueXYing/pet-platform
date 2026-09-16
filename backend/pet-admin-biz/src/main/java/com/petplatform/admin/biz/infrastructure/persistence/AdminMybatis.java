package com.petplatform.admin.biz.infrastructure.persistence;

import com.petplatform.admin.biz.infrastructure.persistence.mapper.AdminAuthMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * MyBatis wiring for the admin-auth tables (PLAT-006 / 22号裁决). The 5-second default
 * statement timeout mirrors the per-statement query timeout of the pre-migration code.
 */
final class AdminMybatis {
  private AdminMybatis() {}

  static SqlSessionTemplate template(DataSource dataSource) {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(Objects.requireNonNull(dataSource));
    try {
      SqlSessionFactory factory = factoryBean.getObject();
      factory.getConfiguration().setMapUnderscoreToCamelCase(true);
      factory.getConfiguration().setDefaultStatementTimeout(5);
      factory.getConfiguration().addMapper(AdminAuthMapper.class);
      return new SqlSessionTemplate(factory);
    } catch (Exception failure) {
      throw new IllegalStateException("admin auth SqlSessionFactory build failed", failure);
    }
  }
}
