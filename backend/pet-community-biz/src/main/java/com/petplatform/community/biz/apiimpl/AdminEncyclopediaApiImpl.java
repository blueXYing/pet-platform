package com.petplatform.community.biz.apiimpl;

import com.petplatform.community.api.command.AdminEncyclopediaCommandApi;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort.Port;
import com.petplatform.community.biz.application.AdminEncyclopediaService;
import com.petplatform.common.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the contract-65 encyclopedia admin face. Boot wires the kernel with
 * the module-local authorization port (same shape as the contract-63 order impl).
 */
public final class AdminEncyclopediaApiImpl
        implements AdminEncyclopediaQueryApi, AdminEncyclopediaCommandApi {

    private final AdminEncyclopediaService service;

    public AdminEncyclopediaApiImpl(
            DataSource dataSource, SnowflakeIdGenerator ids, Port authorization, Clock clock) {
        this.service = new AdminEncyclopediaService(
                new com.petplatform.community.biz.infrastructure.persistence.EncyclopediaAdminStore(
                        Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(clock, "clock is required"));
    }

    @Override
    public com.petplatform.common.PageResult<AdminEncyclopediaQueryApi.AdminCategoryRow>
            listCategories(AdminEncyclopediaQueryApi.AdminCategoryListQuery query) {
        return service.listCategories(query);
    }

    @Override
    public com.petplatform.common.PageResult<AdminEncyclopediaQueryApi.AdminBreedCard>
            listBreeds(AdminEncyclopediaQueryApi.AdminBreedListQuery query) {
        return service.listBreeds(query);
    }

    @Override
    public AdminEncyclopediaQueryApi.AdminBreedDetail getBreed(
            AdminEncyclopediaQueryApi.AdminBreedDetailQuery query) {
        return service.getBreed(query);
    }

    @Override
    public AdminEncyclopediaQueryApi.EncyclopediaExport exportAll(
            AdminEncyclopediaQueryApi.AdminExportQuery query) {
        return service.exportAll(query);
    }

    @Override
    public AdminEncyclopediaCommandApi.CategoryMutation createCategory(
            AdminEncyclopediaCommandApi.CreateCategoryCommand command) {
        return service.createCategory(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.BreedMutation createBreed(
            AdminEncyclopediaCommandApi.CreateBreedCommand command) {
        return service.createBreed(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.CategoryMutation updateCategory(
            AdminEncyclopediaCommandApi.UpdateCategoryCommand command) {
        return service.updateCategory(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.CategoryMutation enableCategory(
            AdminEncyclopediaCommandApi.CategoryStatusCommand command) {
        return service.enableCategory(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.CategoryMutation disableCategory(
            AdminEncyclopediaCommandApi.CategoryStatusCommand command) {
        return service.disableCategory(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.BreedMutation updateBreed(
            AdminEncyclopediaCommandApi.UpdateBreedCommand command) {
        return service.updateBreed(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.BreedMutation publishBreed(
            AdminEncyclopediaCommandApi.BreedStatusCommand command) {
        return service.publishBreed(command);
    }

    @Override
    public AdminEncyclopediaCommandApi.BreedMutation offlineBreed(
            AdminEncyclopediaCommandApi.BreedStatusCommand command) {
        return service.offlineBreed(command);
    }
}
