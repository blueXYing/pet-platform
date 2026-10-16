package com.petplatform.refund.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.refund.api.command.AdminRefundDispositionApi;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionCommand;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionReceipt;
import com.petplatform.refund.biz.application.AdminRefundDispositionService;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.RefundAuthorizationPort;
import com.petplatform.refund.biz.application.LateRefundService;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 v0.2 refund disposal actions (HTTP10 §5.2). */
public final class AdminRefundDispositionApiImpl implements AdminRefundDispositionApi {
    private final AdminRefundDispositionService service;

    public AdminRefundDispositionApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            LateRefundService refunds, RefundAuthorizationPort authorization) {
        this.service = new AdminRefundDispositionService(Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids is required"), Objects.requireNonNull(refunds, "refunds is required"),
                Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override public DispositionReceipt channelQuery(DispositionCommand command) { return service.channelQuery(command); }

    @Override public DispositionReceipt retry(DispositionCommand command) { return service.retry(command); }
}
