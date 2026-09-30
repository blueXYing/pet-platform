package com.petplatform.boot.booking;

import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.application.*;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl;
import java.time.*;
import java.util.concurrent.atomic.*;

/** Real REFUND/PAYMENT protocol; only the signed-channel boundary is a deterministic QA double. */
final class AfterSaleChannelFixture {
    final AtomicInteger sends=new AtomicInteger(),queries=new AtomicInteger();
    final AtomicReference<PaymentRefundChannel.RefundRequest> lastRequest=new AtomicReference<>();
    final LateRefundService refunds;
    final RefundExecutionService execution;
    final ReservationRefundReleaseApiImpl release;
    final OrderAfterSaleRefundProjectionConsumer projection;
    AfterSaleChannelFixture(AfterSaleFixture f,boolean loseChannelAck) {
        this(f,loseChannelAck,true);
    }
    AfterSaleChannelFixture(AfterSaleFixture f,boolean loseChannelAck,boolean fundingEnabled) {
        var b=f.ordinary;
        var late=new OrderLatePaymentFactsApiImpl(b.source,b.guard,b.t.r.f.f.reservationExpiry);
        var merchant=new OrderMerchantRejectFactsApiImpl(b.source,b.guard,b.reservations);
        refunds=new LateRefundService(b.source,AfterSaleFixture.IDS::incrementAndGet,b.guard,late,b.payments,b.outbox,
                merchant,false,f.ordinaryOrders,b.approvals::get,f.orderAftersales,()->f.aftersales,f.refundAftersales);
        var channel=new PaymentRefundChannel(){
            public VerifiedResult submit(RefundRequest request){sends.incrementAndGet();lastRequest.set(request);
                if(loseChannelAck)throw new IllegalStateException("QA-only lost channel acknowledgement");return success(request);}
            public VerifiedResult query(RefundRequest request){queries.incrementAndGet();lastRequest.set(request);return success(request);}
            private VerifiedResult success(RefundRequest request){long cents=request.amount().movePointRight(2).longValueExact();
                return new VerifiedResult("SUCCESS",request.refundNo(),"QA_AFS_REFUND",cents,cents,
                        f.now().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0),"a".repeat(64));}
        };
        var payment=new PaymentRefundService(b.source,AfterSaleFixture.IDS::incrementAndGet,b.guard,late,b.payments,refunds,channel,
                new PaymentRefundService.Settings("127.0.0.1","https://qa.invalid/aftersale-refund",ZoneId.of("Asia/Shanghai")),
                f.clock,merchant,f.ordinaryOrders,b.apps,f.orderAftersales,f.aftersales,fundingEnabled?f.funding:null);
        execution=new RefundExecutionService(refunds,payment,new PaymentRefundResultFactsApiImpl(b.source,b.guard));
        release=new ReservationRefundReleaseApiImpl(b.source,AfterSaleFixture.IDS::incrementAndGet,b.guard,refunds);
        projection=new OrderAfterSaleRefundProjectionConsumer(b.source,AfterSaleFixture.IDS::incrementAndGet,b.guard,
                f.orderAftersales,f.orderAftersales,refunds,release);
    }
}
