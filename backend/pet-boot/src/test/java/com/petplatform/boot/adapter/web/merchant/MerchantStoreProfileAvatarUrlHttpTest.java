package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.command.MerchantStoreProfileCommandApi;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CCR-M-STORE-AVATAR-001 controller acceptance for GET /api/v1/merchant/store-profile/avatar-url:
 * the store guard + requireOwnerRead reuse the §6.4 read (foreign/absent stores keep the same
 * COMMON_NOT_FOUND anti-enumeration), no assetId is accepted from the client, no avatar and any
 * un-signable asset degrade to the three-null shape, and a missing signing assembly fails closed
 * with COMMON_DEPENDENCY_UNAVAILABLE instead of any bare URL. The signed URL passes through
 * verbatim with its real expiry; the object key/version never crosses this boundary.
 */
class MerchantStoreProfileAvatarUrlHttpTest {

    private static final Instant EXPIRY = Instant.parse("2026-11-06T02:00:00Z");

    private static MerchantStoreProfileDTO profile(String avatarAssetId) {
        return new MerchantStoreProfileDTO("101", "102",
                new MerchantStoreProfileDTO.ReadonlyFacts("小李宠物店", "张江路 88 号", "310115",
                        "138****8000", "121.4737", "31.2304", "ACTIVE", "ACTIVE", "OPERATING", null),
                new MerchantStoreProfileDTO.Profile(null, null, List.of(), List.of(), null, null,
                        null, avatarAssetId, "OPEN", "3"));
    }

    /** Records the owner/asset pair handed to signing and answers the configured outcome. */
    private static final class RecordingSigning implements StorePhotoSigningApi {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> owner = new AtomicReference<>();
        final AtomicReference<String> asset = new AtomicReference<>();
        RuntimeException failure;

        @Override
        public SignedStorePhoto signStorePhoto(String assetId, QueryContext context) {
            throw new UnsupportedOperationException("the M face never signs without an owner");
        }

        @Override
        public SignedStorePhoto signOwnedStorePhoto(String ownerUserId, String assetId, QueryContext context) {
            calls.incrementAndGet();
            owner.set(ownerUserId);
            asset.set(assetId);
            if (failure != null) throw failure;
            return new SignedStorePhoto(assetId, "https://cdn.example.invalid/signed/" + assetId, EXPIRY);
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static MockHttpServletRequest request(String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/merchant/store-profile/avatar-url");
        request.setAttribute(CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", "601", Instant.now(), "138****0000", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    @Test
    void ownerReadSignsOnlyThisStoresAvatarForTheSessionOwnerItself() {
        RecordingSigning signing = new RecordingSigning();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<MerchantStoreProfileQueryApi.StoreProfileQuery> seen = new AtomicReference<>();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> {
                    reads.incrementAndGet();
                    seen.set(query);
                    return profile("710402");
                },
                command -> { throw new UnsupportedOperationException(); },
                provider(signing));
        Map<String, Object> envelope = controller.avatarUrl("101", "102", request("merchantId=101&storeId=102"));
        assertEquals("SUCCESS", envelope.get("code"));
        Map<String, Object> data = data(envelope);
        assertEquals("710402", data.get("assetId"));
        assertEquals("https://cdn.example.invalid/signed/710402", data.get("url"));
        assertEquals("2026-11-06T02:00:00.000Z", data.get("expiresAt"), "expiry must match the real signature");
        assertEquals(1, reads.get());
        assertEquals(1, signing.calls.get());
        // Ownership is re-proven against the session owner, and the store guard saw the same
        // coordinates/context as the §6.4 read.
        assertEquals("601", signing.owner.get());
        assertEquals("710402", signing.asset.get());
        assertEquals("101", seen.get().merchantId());
        assertEquals("102", seen.get().storeId());
        assertEquals("601", seen.get().context().operatorId());
    }

    @Test
    void noAvatarAnswersTheThreeNullShapeWithoutTouchingSigning() {
        RecordingSigning signing = new RecordingSigning();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> profile(null),
                command -> { throw new UnsupportedOperationException(); },
                provider(signing));
        Map<String, Object> data = data(controller.avatarUrl("101", "102", request("merchantId=101&storeId=102")));
        assertEquals(3, data.size());
        assertNull(data.get("assetId"));
        assertNull(data.get("url"));
        assertNull(data.get("expiresAt"));
        assertEquals(0, signing.calls.get());
    }

    @Test
    void foreignStoreOrNonOwnerKeepsTheReadSideRejectionAndNeverSigns() {
        RecordingSigning signing = new RecordingSigning();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> { throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant store not found"); },
                command -> { throw new UnsupportedOperationException(); },
                provider(signing));
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.avatarUrl("101", "102", request("merchantId=101&storeId=102")));
        assertEquals(CommonApiCodes.NOT_FOUND, failure.code(), "same anti-enumeration as the §6.4 read");
        assertEquals(0, signing.calls.get());
    }

    @Test
    void unSignableAssetDegradesToNullsWithoutLeakingWhy() {
        RecordingSigning signing = new RecordingSigning();
        signing.failure = new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "门店图片暂不可用");
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> profile("710402"),
                command -> { throw new UnsupportedOperationException(); },
                provider(signing));
        Map<String, Object> data = data(controller.avatarUrl("101", "102", request("merchantId=101&storeId=102")));
        assertEquals(3, data.size());
        assertNull(data.get("assetId"));
        assertNull(data.get("url"));
        assertNull(data.get("expiresAt"));
        assertEquals(1, signing.calls.get());
    }

    @Test
    void missingSigningAssemblyFailsClosedWithDependencyUnavailable() {
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> profile("710402"),
                command -> { throw new UnsupportedOperationException(); },
                provider(null));
        ApiException failure = assertThrows(ApiException.class,
                () -> controller.avatarUrl("101", "102", request("merchantId=101&storeId=102")));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    }

    @Test
    void unauthenticatedOrMalformedRequestsAreRejectedBeforeAnySigning() {
        RecordingSigning signing = new RecordingSigning();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                query -> profile("710402"),
                command -> { throw new UnsupportedOperationException(); },
                provider(signing));
        ApiException invalid = assertThrows(ApiException.class,
                () -> controller.avatarUrl("abc", "102", request("merchantId=abc&storeId=102")));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
        ApiException extra = assertThrows(ApiException.class,
                () -> controller.avatarUrl("101", "102", request("merchantId=101&storeId=102&assetId=710402")));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, extra.code(), "client assetId is never accepted");
        MockHttpServletRequest anonymous = request(null);
        anonymous.setAttribute(CBearerSessionFilter.VIEW, null);
        ApiException unauthenticated = assertThrows(ApiException.class,
                () -> controller.avatarUrl("101", "102", anonymous));
        assertEquals(CommonApiCodes.UNAUTHORIZED, unauthenticated.code());
        assertEquals(0, signing.calls.get());
    }
}
