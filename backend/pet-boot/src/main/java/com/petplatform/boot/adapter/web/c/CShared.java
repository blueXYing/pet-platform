package com.petplatform.boot.adapter.web.c;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.PublicContractChecks;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;

/** Small helpers shared by the C-end controllers; never logs codes or tokens. */
final class CShared {
    private CShared() {}

    static String trace(HttpServletRequest req) {
        String fromMdc = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) return fromMdc;
        String header = req.getHeader(TraceContextFilter.TRACE_HEADER);
        String trace = header != null && header.matches("[A-Za-z0-9._:-]{1,64}")
                ? header : UUID.randomUUID().toString();
        req.setAttribute("cTraceId", trace);
        return trace;
    }

    /** Terminal X-Request-Id per supplement 23; blank or malformed is a plain 400. */
    static String requestId(HttpServletRequest req) {
        try {
            return PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
        } catch (RuntimeException e) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
        }
    }

    static String attemptSecret(HttpServletRequest req) {
        String value = req.getHeader("X-Auth-Attempt");
        if (value == null || value.isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "验证失败，请重试");
        }
        return value;
    }
}
