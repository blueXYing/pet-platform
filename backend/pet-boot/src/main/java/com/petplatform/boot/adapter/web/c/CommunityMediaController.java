package com.petplatform.boot.adapter.web.c;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** CCR-COM-001 immutable public image endpoint. */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"community.enabled", "community.c.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class CommunityMediaController {

    private final CommunityPublicApi community;

    public CommunityMediaController(CommunityPublicApi community) {
        this.community = community;
    }

    @GetMapping("/api/v1/c/community/media/{mediaId}")
    ResponseEntity<byte[]> media(@PathVariable String mediaId, HttpServletRequest req) {
        if (!mediaId.matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "mediaId is invalid");
        }
        CommunityViews.MediaContent content = community.media(mediaId);
        String etag = '"' + content.sha256() + '"';
        CacheControl cache = CacheControl.maxAge(java.time.Duration.ofDays(365))
                .cachePublic().immutable();
        if (etag.equals(req.getHeader("If-None-Match"))) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.content().length)
                .eTag(etag)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .body(content.content());
    }
}
