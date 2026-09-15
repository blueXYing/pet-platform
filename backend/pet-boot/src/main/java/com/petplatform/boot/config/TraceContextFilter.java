package com.petplatform.boot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chain-of-custody for every inbound HTTP request (HTTP10 §2.4, baseline 21 §1):
 * accepts or generates X-Trace-Id and always echoes it; a well-formed X-Request-Id
 * UUID joins the MDC and is echoed back. MDC keys feed the logging pattern that
 * application.yml already declares, and are always cleared afterwards.
 */
public final class TraceContextFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String TRACE_MDC_KEY = "traceId";
    public static final String REQUEST_MDC_KEY = "requestId";

    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = header(request, TRACE_HEADER, TRACE_ID);
        if (traceId == null) traceId = UUID.randomUUID().toString();
        String requestId = header(request, REQUEST_HEADER, REQUEST_ID);
        response.setHeader(TRACE_HEADER, traceId);
        if (requestId != null) response.setHeader(REQUEST_HEADER, requestId);
        MDC.put(TRACE_MDC_KEY, traceId);
        if (requestId != null) MDC.put(REQUEST_MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_MDC_KEY);
            MDC.remove(REQUEST_MDC_KEY);
        }
    }

    /** Header values are used verbatim (HTTP10: no trim); malformed values are ignored, not guessed from. */
    private static String header(HttpServletRequest request, String name, Pattern format) {
        String value = request.getHeader(name);
        return value != null && format.matcher(value).matches() ? value : null;
    }
}
