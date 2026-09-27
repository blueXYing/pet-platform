package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.BookingMerchantFactsApi;
import com.petplatform.order.api.command.OrderCreationApi;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.order.biz.application.OrderCreationInputProtection;
import com.petplatform.order.biz.application.OrderCreationRemarkPolicy;
import com.petplatform.order.biz.application.OrderCreationService;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.service.api.query.BookingServiceFactsApi;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.time.Clock;
import javax.sql.DataSource;

/** Internal-only create command. The boot switch remains off until the later checkout gates. */
public final class OrderCreationApiImpl implements OrderCreationApi {
    private final OrderCreationService service;

    public OrderCreationApiImpl(DataSource source, SnowflakeIdGenerator ids,
            BookingUserFactsApi users, BookingMerchantFactsApi merchants,
            BookingServiceFactsApi services, ScheduleCapacityGuardApi guard,
            ReservationHoldApi reservations, OrderCreationInputProtection protection,
            OrderCreationRemarkPolicy remarkPolicy, Clock clock) {
        this.service = new OrderCreationService(source, ids, users, merchants, services,
                guard, reservations, protection, remarkPolicy, clock);
    }

    @Override public CreateOrderResult create(CreateOrderCommand command) {
        return service.create(command);
    }
}
