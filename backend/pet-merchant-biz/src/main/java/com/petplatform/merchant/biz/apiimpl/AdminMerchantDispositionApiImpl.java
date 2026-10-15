package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableCommand;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi.DisableReceipt;
import com.petplatform.merchant.biz.application.AdminDispositionAuthorizationPort.MerchantDispositionAuthorizationPort;
import com.petplatform.merchant.biz.application.AdminMerchantDispositionService;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-63 v0.2 merchant disable action (HTTP10 §5.6). */
public final class AdminMerchantDispositionApiImpl implements AdminMerchantDispositionApi {
    private final AdminMerchantDispositionService service;

    public AdminMerchantDispositionApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            MerchantDispositionAuthorizationPort authorization) {
        this.service = new AdminMerchantDispositionService(Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids is required"), Objects.requireNonNull(authorization, "authorization is required"));
    }

    @Override public DisableReceipt disable(DisableCommand command) { return service.disable(command); }
}
