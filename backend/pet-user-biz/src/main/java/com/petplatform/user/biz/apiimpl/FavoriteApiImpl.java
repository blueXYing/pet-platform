package com.petplatform.user.biz.apiimpl;

import com.petplatform.common.PageResult;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.user.api.command.FavoriteCommandApi;
import com.petplatform.user.api.command.FavoriteCommands.Add;
import com.petplatform.user.api.command.FavoriteCommands.Cancel;
import com.petplatform.user.api.dto.FavoriteItemDTO;
import com.petplatform.user.api.query.FavoriteQueryApi;
import com.petplatform.user.biz.application.FavoriteService;
import com.petplatform.user.biz.application.UserTargetCatalog;
import javax.sql.DataSource;

/** Local implementation of the Contract 58 favorite surface (commands + reads). */
public final class FavoriteApiImpl implements FavoriteCommandApi, FavoriteQueryApi {
    private final FavoriteService service;

    public FavoriteApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            ServiceQueryApi services, MerchantStoreDisplayApi stores) {
        this.service = new FavoriteService(dataSource, ids, new UserTargetCatalog(services, stores));
    }

    @Override
    public FavoriteCommandApi.AddOutcome addFavorite(Add command) {
        return service.addFavorite(command);
    }

    @Override
    public FavoriteCommandApi.CancelOutcome cancelFavorite(Cancel command) {
        return service.cancelFavorite(command);
    }

    @Override
    public PageResult<FavoriteItemDTO> listMyFavorites(MyFavoriteListQuery query) {
        return service.listMyFavorites(query);
    }

    @Override
    public FavoriteStatus favoriteStatus(FavoriteStatusQuery query) {
        return service.favoriteStatus(query);
    }
}
