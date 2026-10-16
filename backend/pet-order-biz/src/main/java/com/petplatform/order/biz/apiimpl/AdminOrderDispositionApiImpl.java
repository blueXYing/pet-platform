package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.command.AdminOrderDispositionApi;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseCommand;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseReceipt;
import com.petplatform.order.biz.application.AdminOrderDispositionService;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort.OrderAuthorizationPort;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.command.AdminAbnormalCloseRefundApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 v0.2 order abnormal close (HTTP10 §5.5). */
public final class AdminOrderDispositionApiImpl implements AdminOrderDispositionApi {
    private final AdminOrderDispositionService service;

    public AdminOrderDispositionApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderAuthorizationPort authorization,
            PaymentSuccessFactsApi payments, RefundOrderFactsApi refundPresence,
            AdminAbnormalCloseRefundApi createRefund) {
        this.service = new AdminOrderDispositionService(Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids is required"), Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(payments, "payments is required"),
                Objects.requireNonNull(refundPresence, "refundPresence is required"),
                Objects.requireNonNull(createRefund, "createRefund is required"));
    }

    @Override public AbnormalCloseReceipt abnormalClose(AbnormalCloseCommand command) { return service.abnormalClose(command); }
}
