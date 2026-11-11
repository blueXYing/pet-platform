package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.command.MerchantStoreProfileCommandApi;
import com.petplatform.merchant.api.command.UpdateStoreProfileCommand;
import com.petplatform.merchant.api.dto.MerchantStoreProfileDTO;
import com.petplatform.merchant.api.query.MerchantStoreProfileQueryApi;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * P-04 口径钉子（2026-11-11）：contract 27 §6.4 说「promoLevel 以 0 表示未设置（存储
 * NULL）」，2026-11-09 验收曾登记「实现只认 null、传 0 报 400」。经代码核实实现与契约一致
 * （{@code promoLevel(JsonNode)} 的 {@code value == 0 -> null}；StrictProfileBody 对**所有**
 * 键拒绝显式 null），本测试把该口径钉死：0 合法且净空、1-9 直存、显式 null/超范围 400。
 * 若哪边再漂移（契约改 null 或实现改拒绝 0），此处先红。
 */
class MerchantStoreProfilePromoLevelHttpTest {

    private static final MerchantStoreProfileDTO STORE =
            new MerchantStoreProfileDTO("101", "102",
                    new MerchantStoreProfileDTO.ReadonlyFacts("小李宠物店", "张江路 88 号",
                            "310115", "138****8000", "121.4737", "31.2304", "ACTIVE", "ACTIVE",
                            "OPERATING", null),
                    new MerchantStoreProfileDTO.Profile(null, null, List.of(), List.of(), null,
                            null, null, null, "OPEN", "3"));

    /** Captures the full-replace command handed to the kernel and answers the stored facts. */
    private static final class RecordingCommands implements MerchantStoreProfileCommandApi {
        final AtomicReference<UpdateStoreProfileCommand> captured = new AtomicReference<>();

        @Override
        public MerchantStoreProfileDTO updateStoreProfile(UpdateStoreProfileCommand command) {
            captured.set(command);
            return STORE;
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

    private static MockHttpServletRequest putRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT",
                "/api/v1/merchant/store-profile");
        request.setAttribute(CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", "601", Instant.now(), "138****0000", "ACTIVE"));
        request.addHeader("X-Request-Id", "0f1e2d3c-4b5a-4678-879a-bcdef0123456");
        return request;
    }

    /** Strict full-replace body with every editable key present; only promoLevel varies. */
    private static String body(String promoLevelJson) {
        return "{\"merchantId\":\"101\",\"storeId\":\"102\",\"storeName\":\"小李宠物店\","
                + "\"address\":\"张江路 88 号\",\"phone\":\"13800008000\",\"intro\":\"\","
                + "\"announcement\":\"\",\"photoAssetIds\":[],\"businessHours\":[],"
                + "\"promoLevel\":" + promoLevelJson + ",\"promoTitle\":\"\",\"promoText\":\"\","
                + "\"avatarAssetId\":\"\",\"businessStatus\":\"OPEN\",\"expectedVersion\":\"3\"}";
    }

    @Test
    void promoLevelZeroIsTheContractedUnsetSentinelAndStoresNull() {
        RecordingCommands commands = new RecordingCommands();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                queries(), commands, provider(null));
        assertNotNull(controller.update(body("0"), putRequest()));
        assertNull(commands.captured.get().promoLevel(), "0 must clear promoLevel to stored NULL");
    }

    @Test
    void promoLevelOneToNinePassesThrough() {
        RecordingCommands commands = new RecordingCommands();
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                queries(), commands, provider(null));
        controller.update(body("5"), putRequest());
        assertEquals(5, commands.captured.get().promoLevel());
    }

    @Test
    void explicitNullIsRejectedLikeEveryOtherKey() {
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                queries(), new RecordingCommands(), provider(null));
        ApiException error = assertThrows(ApiException.class,
                () -> controller.update(body("null"), putRequest()));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, error.code());
    }

    @Test
    void outOfRangeLevelIsRejected() {
        MerchantStoreProfileController controller = new MerchantStoreProfileController(
                queries(), new RecordingCommands(), provider(null));
        ApiException error = assertThrows(ApiException.class,
                () -> controller.update(body("10"), putRequest()));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, error.code());
    }

    private static MerchantStoreProfileQueryApi queries() {
        return new MerchantStoreProfileQueryApi() {
            @Override
            public MerchantStoreProfileDTO getStoreProfile(StoreProfileQuery query) {
                throw new UnsupportedOperationException("PUT never reads");
            }
        };
    }
}
