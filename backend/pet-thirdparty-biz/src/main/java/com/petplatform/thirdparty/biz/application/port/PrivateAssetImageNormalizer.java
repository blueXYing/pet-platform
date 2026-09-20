package com.petplatform.thirdparty.biz.application.port;

/** Fully decodes and normalizes a supported image, stripping metadata. */
@FunctionalInterface
public interface PrivateAssetImageNormalizer {
  NormalizedImage normalize(byte[] source, String declaredMediaType);

  record NormalizedImage(byte[] content, String mediaType, int width, int height) {
    public NormalizedImage {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }
  }
}
