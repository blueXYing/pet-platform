package com.petplatform.boot.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the private-material cache and sniffing policy even when parsing/auth fails early. */
public final class PrivateAssetResponseHeadersFilter extends OncePerRequestFilter {
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.equals("/api/v1/c/private-assets")
        && !path.contains("/private-assets/")
        && !path.startsWith("/api/v1/admin/private-asset-read-grants/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store, private");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    chain.doFilter(request, response);
  }
}
