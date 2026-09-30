package com.petplatform.thirdparty.biz.application.port;

import java.time.OffsetDateTime;

/** Produces a per-read image; raw private bytes never cross the terminal adapter boundary. */
@FunctionalInterface
public interface PrivateAssetWatermarkRenderer {
  RenderedImage render(byte[] source, String mediaType, Watermark watermark);

  /** Separate typed resources; legacy renderers must fail rather than silently omit an AFS mark. */
  default RenderedImage renderResource(byte[] source,String mediaType,ResourceWatermark watermark) {
    throw new IllegalStateException("Typed resource watermark renderer required");
  }
  record ResourceWatermark(String operatorId,String resourceType,String resourceId,OffsetDateTime renderedAt) {}

  record Watermark(String operatorId, String applicationId, OffsetDateTime renderedAt) {}

  record RenderedImage(byte[] content, String mediaType) {
    public RenderedImage {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }
  }
}
