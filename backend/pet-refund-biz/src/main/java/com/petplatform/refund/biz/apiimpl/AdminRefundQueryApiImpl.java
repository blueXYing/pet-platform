package com.petplatform.refund.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.refund.api.query.AdminRefundQueryApi;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundDetail;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundListQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundSummary;
import com.petplatform.refund.biz.application.AdminRefundQueryService;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort;
import com.petplatform.refund.biz.infrastructure.persistence.AdminRefundQueryStore;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 platform refund read face (HTTP10 §5.2). */
public final class AdminRefundQueryApiImpl implements AdminRefundQueryApi {
    private final AdminRefundQueryService service;

    public AdminRefundQueryApiImpl(DataSource dataSource, AdminTradeAuthorizationPort.RefundAuthorizationPort authorization) {
        this.service = new AdminRefundQueryService(
                new AdminRefundQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override
    public PageResult<AdminRefundSummary> listRefunds(AdminRefundListQuery query) {
        return service.listRefunds(query);
    }

    @Override
    public AdminRefundDetail getRefund(AdminRefundQuery query) {
        return service.getRefund(query);
    }
}
