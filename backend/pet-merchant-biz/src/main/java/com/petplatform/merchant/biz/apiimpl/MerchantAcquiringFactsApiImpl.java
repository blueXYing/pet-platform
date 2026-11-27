package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantAcquiringFactDTO;
import com.petplatform.merchant.api.query.MerchantAcquiringFactsApi;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantMybatis;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAcquiringFactsMapper;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantAcquiringFactsMapper.MerchantAcquiringFactRow;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** Local implementation of the acquiring-admission facts face (CCR-PAY-ONBOARD-001). */
public final class MerchantAcquiringFactsApiImpl implements MerchantAcquiringFactsApi {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private final SqlSessionTemplate sql;

  public MerchantAcquiringFactsApiImpl(DataSource dataSource) {
    this.sql = MerchantMybatis.joiningTemplate(Objects.requireNonNull(dataSource, "dataSource is required"));
  }

  @Override
  public MerchantAcquiringFactDTO find(String merchantId) {
    if (merchantId == null || merchantId.isBlank()) return null;
    long id;
    try {
      id = IDS.fromApi(merchantId.trim());
    } catch (RuntimeException invalid) {
      return null;
    }
    MerchantAcquiringFactRow row = sql.getMapper(MerchantAcquiringFactsMapper.class).selectFact(id);
    if (row == null || row.getId() == null) return null;
    return new MerchantAcquiringFactDTO(
        IDS.toApi(row.getId()),
        row.getOwnerUserId() == null ? null : IDS.toApi(row.getOwnerUserId()),
        row.getMerchantName(),
        row.getStatus(),
        row.getMerchantTypeCode(),
        row.getCityCode(),
        row.getApplicationStatus(),
        row.getSigningStatus());
  }
}
