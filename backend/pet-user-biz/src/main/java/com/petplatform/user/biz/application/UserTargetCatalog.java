package com.petplatform.user.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.user.api.dto.UserTargetDisplayDTO;
import java.util.Objects;

/**
 * Contract 58 target catalog adapter: favorites and footprints only accept targets the C reader
 * can currently see, and list rows carry the same public projection. Visibility comes from the
 * frozen C catalog contracts (ServiceQueryApi.getVisibleService / MerchantStoreDisplayApi
 * .getDisplayStore) through their *-api surfaces — never the other modules' persistence. A
 * NOT_FOUND answer means the target is missing or ineligible (anti-enumeration); a facts
 * failure (DEPENDENCY_UNAVAILABLE) keeps propagating so the whole page fails closed as 503.
 */
public final class UserTargetCatalog {

    public static final String SERVICE = "SERVICE";
    public static final String STORE = "STORE";

    private final ServiceQueryApi services;
    private final MerchantStoreDisplayApi stores;

    public UserTargetCatalog(ServiceQueryApi services, MerchantStoreDisplayApi stores) {
        this.services = Objects.requireNonNull(services, "service catalog is required");
        this.stores = Objects.requireNonNull(stores, "store catalog is required");
    }

    /** targetType is exactly SERVICE or STORE; anything else is a plain 400. */
    public static String normalize(String targetType) {
        if (SERVICE.equals(targetType) || STORE.equals(targetType)) return targetType;
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "targetType is invalid");
    }

    /**
     * Current public projection of the target, or null when the target is hidden or gone (the
     * favorite/footprint row survives with targetAvailable=false so cancel/remove still works).
     */
    public UserTargetDisplayDTO project(String targetType, String targetId, QueryContext context) {
        String type = normalize(targetType);
        long id = numericId(targetId);
        try {
            if (SERVICE.equals(type)) {
                ServiceSnapshotDTO snapshot =
                        services.getVisibleService(new ServiceSnapshotQuery(targetId, context));
                return new UserTargetDisplayDTO(SERVICE, snapshot.serviceId(), true,
                        snapshot.serviceName(), snapshot.categoryName(),
                        snapshot.salePrice() == null ? null : snapshot.salePrice().toPlainString(),
                        snapshot.storeId(), snapshot.coverUrl());
            }
            MerchantStoreDisplayDTO store =
                    stores.getDisplayStore(new MerchantStoreDisplayQuery(targetId, context));
            return new UserTargetDisplayDTO(STORE, store.storeId(), true, store.storeName(), null,
                    null, null, null);
        } catch (ApiException missing) {
            if (CommonApiCodes.NOT_FOUND.equals(missing.code())
                    || "SERVICE_NOT_FOUND".equals(missing.code())) {
                // Hidden, gone or foreign: the row stays, the display does not.
                return UserTargetDisplayDTO.unavailable(type, targetId);
            }
            throw missing; // facts failures stay fail-closed (503)
        }
    }

    /**
     * Write-path guard: only currently visible targets can be favorited or recorded. The error
     * keeps the C catalog wire codes (SERVICE_NOT_FOUND / STORE_NOT_FOUND) so this surface never
     * becomes an oracle that distinguishes hidden from absent.
     */
    public void requireVisible(String targetType, String targetId, QueryContext context) {
        UserTargetDisplayDTO display = project(targetType, targetId, context);
        if (!display.targetAvailable()) {
            throw new ApiException(targetNotFoundCode(targetType), targetNotFoundMessage(targetType));
        }
    }

    public static String targetNotFoundCode(String targetType) {
        return SERVICE.equals(targetType) ? "SERVICE_NOT_FOUND" : "STORE_NOT_FOUND";
    }

    public static String targetNotFoundMessage(String targetType) {
        return SERVICE.equals(targetType) ? "服务不存在" : "门店不存在";
    }

    private static long numericId(String value) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "targetId is invalid");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException overflow) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "targetId is invalid");
        }
    }
}
