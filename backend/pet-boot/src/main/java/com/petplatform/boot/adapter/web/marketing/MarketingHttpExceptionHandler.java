package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped advice for the contract-71/72 marketing and coupon-campaign faces. These routes live
 * under their own package, so the C/merchant/admin face advices do not apply; without this map
 * the registry codes below fall through the global handler as an unmapped 500. Statuses follow
 * docs/04-api/12-Error-Code-Registry-v0.5.md §“营销活动中心 HTTP 状态映射”.
 */
@RestControllerAdvice(assignableTypes = {
        MarketingAdminController.class,
        MarketingMerchantController.class,
        MarketingCController.class,
        MarketingAssetController.class,
        AdminCouponCampaignController.class,
        CCouponCampaignController.class})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MarketingHttpExceptionHandler {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry(CommonApiCodes.INVALID_ARGUMENT, 400),
            Map.entry(CommonApiCodes.UNAUTHORIZED, 401),
            Map.entry(CommonApiCodes.FORBIDDEN, 403),
            Map.entry(CommonApiCodes.NOT_FOUND, 404),
            Map.entry(CommonApiCodes.CONFLICT, 409),
            Map.entry(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, 409),
            Map.entry(CommonApiCodes.RATE_LIMITED, 429),
            Map.entry(CommonApiCodes.INTERNAL_ERROR, 500),
            Map.entry(CommonApiCodes.DEPENDENCY_UNAVAILABLE, 503),
            // Contract-71/72 domain codes (12号 registry, 2026-10-27 batch).
            Map.entry("CAMPAIGN_NOT_FOUND", 404),
            Map.entry("CAMPAIGN_STATE_NOT_ALLOWED", 409),
            Map.entry("CAMPAIGN_LANDING_INVALID", 409),
            Map.entry("CAMPAIGN_PAGE_INVALID", 400),
            Map.entry("PLACEMENT_LIMIT_EXCEEDED", 409),
            // Contract 71 v0.3: campaign serviceCategoryCodes dictionary validation (12号 registry).
            Map.entry("SERVICE_CATEGORY_INVALID", 400),
            Map.entry("ENROLLMENT_NOT_OPEN", 409),
            Map.entry("ENROLLMENT_ALREADY_EXISTS", 409),
            Map.entry("ENROLLMENT_QUOTA_EXCEEDED", 409),
            Map.entry("ENROLLMENT_STATE_NOT_ALLOWED", 409),
            Map.entry("MARKETING_STORE_NOT_ELIGIBLE", 409),
            Map.entry("COUPON_CAMPAIGN_NOT_FOUND", 404),
            Map.entry("COUPON_CAMPAIGN_NOT_CLAIMABLE", 409),
            Map.entry("COUPON_CAMPAIGN_SOLD_OUT", 409),
            Map.entry("COUPON_CLAIM_LIMIT_REACHED", 409),
            Map.entry("COUPON_CLAIMED", 409),
            Map.entry("MARKETING_ASSET_INVALID", 400),
            Map.entry("MARKETING_ASSET_TOO_LARGE", 413));

    @ExceptionHandler(ApiException.class)
    ApiResponse<Void> apiException(ApiException error, HttpServletResponse response) {
        int status = STATUS.getOrDefault(error.code(), 500);
        return reply(status, error.code(), error.getMessage(), response);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ApiResponse<Void> tooLarge(Exception error, HttpServletResponse response) {
        return reply(413, "MARKETING_ASSET_TOO_LARGE", "素材不能超过 2MiB", response);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestValueException.class,
            IllegalArgumentException.class})
    ApiResponse<Void> invalid(Exception error, HttpServletResponse response) {
        return reply(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }

    private static ApiResponse<Void> reply(
            int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store, private");
        return ApiResponse.error(code, message, MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    }
}
