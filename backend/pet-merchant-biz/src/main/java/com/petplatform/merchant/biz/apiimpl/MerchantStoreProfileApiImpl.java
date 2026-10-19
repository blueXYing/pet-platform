package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.MerchantStoreProfileCommandApi;
import com.petplatform.merchant.api.command.UpdateStoreProfileCommand;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi.StoreProfileQuery;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantStoreProfileService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStoreProfileStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local facade for the store profile read/write face (contract 27 §6.4 supplement). The
 * application-status fact rides the same review-facts seam the staff write gate uses: an
 * unconfigured reader leaves the field null (the HTTP adapter renders it as unknown) instead
 * of inventing a status.
 */
public final class MerchantStoreProfileApiImpl implements MerchantStoreProfileQueryApi,
        MerchantStoreProfileCommandApi {
    private final MerchantStoreProfileService service;
    private final ApplicationReviewFactsReader applicationFacts;

    public MerchantStoreProfileApiImpl(DataSource source, SnowflakeIdGenerator ids,
                                       ApplicationReviewFactsReader applicationFacts,
                                       MerchantStoreProfileService.PhotoAssetPort photos, Clock clock) {
        this.service = new MerchantStoreProfileService(new MerchantStoreProfileStore(source, ids),
                applicationFacts == null ? unavailableReader() : applicationFacts, photos,
                clock == null ? Clock.systemUTC() : clock);
        this.applicationFacts = applicationFacts;
    }

    @Override
    public MerchantStoreProfileDTO getStoreProfile(StoreProfileQuery query) {
        MerchantStoreProfileDTO dto = service.getStoreProfile(query);
        return service.withApplicationStatus(dto, applicationStatus(query));
    }

    @Override
    public MerchantStoreProfileDTO updateStoreProfile(UpdateStoreProfileCommand command) {
        MerchantStoreProfileDTO dto = service.updateStoreProfile(command);
        return service.withApplicationStatus(dto, applicationStatus(command == null ? null
                : new StoreProfileQuery(command.merchantId(), command.storeId(), null)));
    }

    private String applicationStatus(StoreProfileQuery query) {
        if (applicationFacts == null || query == null || query.merchantId() == null) return null;
        try {
            long merchantId = Long.parseLong(query.merchantId());
            ApplicationReviewFactsReader.Facts facts = applicationFacts.read(merchantId);
            return facts == null ? null : facts.applicationStatus();
        } catch (NumberFormatException malformed) {
            return null;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "application review facts unavailable");
        }
    }

    private static ApplicationReviewFactsReader unavailableReader() {
        return merchantId -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not configured");
        };
    }

    /** Adapter shim: keeps the boot composition free of merchant persistence types. */
    public static MerchantStoreProfileService.PhotoAssetPort photoPort(
            com.petplatform.merchant.biz.application.PrivateAssetQueryPort assets) {
        Objects.requireNonNull(assets, "assets port is required");
        return (ownerUserId, assetIds) -> {
            List<MerchantStoreProfileService.OwnedAsset> resolved = new ArrayList<>();
            for (com.petplatform.merchant.biz.application.PrivateAssetQueryPort.PrivateAssetRef ref
                    : assets.resolveOwned(ownerUserId, assetIds)) {
                resolved.add(new MerchantStoreProfileService.OwnedAsset(ref.assetId(), ref.mediaType(), ref.status()));
            }
            return resolved;
        };
    }
}
