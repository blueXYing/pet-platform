package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.marketing.api.MarketingPublicApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** CCR-MKT-002: public, immutable artwork bytes for backend-uploaded marketing assets. */
@RestController
@ConditionalOnProperty(prefix = "pet",
        name = {"marketing.enabled", "marketing.c.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class MarketingAssetController {

    private final MarketingPublicApi marketing;

    public MarketingAssetController(MarketingPublicApi marketing) {
        this.marketing = marketing;
    }

    @GetMapping("/api/v1/c/marketing/assets/{assetId}")
    ResponseEntity<byte[]> asset(@PathVariable String assetId, HttpServletRequest req) {
        if (!assetId.matches("[1-9][0-9]{0,18}")) throw MarketingHttpSupport.invalid();
        MarketingPublicApi.AssetContent asset = marketing.asset(assetId);
        String etag = '"' + asset.sha256() + '"';
        if (etag.equals(req.getHeader("If-None-Match"))) {
            return ResponseEntity.status(304).eTag(etag)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365))
                            .cachePublic().immutable()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.content().length)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365))
                        .cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(asset.content());
    }
}
