package com.petplatform.thirdparty.biz.application.port;

import java.time.OffsetDateTime;

/** Produces a per-read image; raw private bytes never cross the terminal adapter boundary. */
@FunctionalInterface
public interface PrivateAssetWatermarkRenderer {
  RenderedImage render(byte[] source, String mediaType, Watermark watermark);

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
