package com.petplatform.thirdparty.biz.infrastructure.oss;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class S3ServiceCoverObjectSignerTest {
  private OssConnection connection() {
    return new OssConnection("https://oss.example.invalid", "cn-test", "test-private", "test-key", "test-secret", null);
  }

  @Test void signatureBindsExactVersionAndNeedsNoCustomImageHeaders() {
    try (var signer = new S3ServiceCoverObjectSigner(connection(), Clock.systemUTC(), 600)) {
      var result = signer.sign("merchant-materials/77/101/normalized-v1", "version:aB+/==");
      String query = URI.create(result.url()).getRawQuery();
      assertTrue(query.contains("versionId=aB%2B%2F%3D%3D"));
      assertTrue(query.contains("X-Amz-SignedHeaders=host"));
      assertTrue(query.contains("X-Amz-Signature="));
      assertTrue(result.expiresAt().isAfter(Instant.now()));
      assertTrue(result.expiresAt().isBefore(Instant.now().plusSeconds(676)));
    }
  }

  @Test void etagOnlyAndSourceObjectsCannotBeSignedAsDisplayCovers() {
    try (var signer = new S3ServiceCoverObjectSigner(connection(), Clock.systemUTC(), 600)) {
      for (String version : new String[] {"etag:0123456789abcdef0123456789abcdef", "version:null", "", "version:bad\nvalue"})
        assertThrows(RuntimeException.class, () -> signer.sign("merchant-materials/77/101/normalized-v1", version));
      for (String key : new String[] {"merchant-materials/77/101/source", "public/normalized-v1", "merchant-materials/../normalized-v1"})
        assertThrows(RuntimeException.class, () -> signer.sign(key, "version:one"));
    }
    assertThrows(IllegalArgumentException.class, () -> new S3ServiceCoverObjectSigner(connection(), Clock.systemUTC(), 0));
    assertThrows(IllegalArgumentException.class, () -> new S3ServiceCoverObjectSigner(new OssConnection("http://oss.example.invalid", "x", "b", "k", "s", null), Clock.systemUTC(), 600));
  }
}
