package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerNoteCommandApi;
import com.petplatform.order.biz.application.MerchantCustomerNoteService;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.util.Objects;
import javax.sql.DataSource;

/** Local binding of the customer note write face (contract 69 expansion). */
public final class MerchantCustomerNoteCommandApiImpl implements MerchantCustomerNoteCommandApi {
    private final MerchantCustomerNoteService service;

    public MerchantCustomerNoteCommandApiImpl(DataSource dataSource, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, SnowflakeIdGenerator ids) {
        this.service = new MerchantCustomerNoteService(Objects.requireNonNull(dataSource),
                Objects.requireNonNull(guard), Objects.requireNonNull(authority),
                Objects.requireNonNull(ids));
    }

    @Override
    public com.petplatform.order.api.query.MerchantCustomerQueryApi.NoteView updateNote(
            UpdateNoteCommand command) {
        return service.updateNote(command);
    }
}
