package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.user.api.command.FootprintCommandApi;
import com.petplatform.user.api.command.FootprintCommands.Delete;
import com.petplatform.user.api.command.FootprintCommands.Record;
import com.petplatform.user.api.dto.FootprintItemDTO;
import com.petplatform.user.api.query.FootprintQueryApi;
import com.petplatform.user.biz.application.FootprintService;
import com.petplatform.user.biz.application.UserTargetCatalog;
import javax.sql.DataSource;

/** Local implementation of the Contract 58 footprint surface (commands + reads). */
public final class FootprintApiImpl implements FootprintCommandApi, FootprintQueryApi {
    private final FootprintService service;

    public FootprintApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            ServiceQueryApi services, MerchantStoreDisplayApi stores) {
        this.service = new FootprintService(dataSource, ids, new UserTargetCatalog(services, stores));
    }

    @Override
    public FootprintCommandApi.RecordOutcome recordFootprint(Record command) {
        return service.recordFootprint(command);
    }

    @Override
    public FootprintCommandApi.DeleteOutcome deleteFootprint(Delete command) {
        return service.deleteFootprint(command);
    }

    @Override
    public PageResult<FootprintItemDTO> listMyFootprints(MyFootprintListQuery query) {
        return service.listMyFootprints(query);
    }
}
