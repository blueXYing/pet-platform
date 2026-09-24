package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.common.*;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.UploadPrivateAssetCommand;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.apiimpl.ServiceCoverSigningApiImpl;
import com.petplatform.thirdparty.biz.application.port.PrivateAssetGrantKeyProvider;
import com.petplatform.thirdparty.biz.infrastructure.oss.*;
import com.petplatform.thirdparty.biz.infrastructure.provider.assetimage.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit manual test: real OSS + ClamAV, synthetic image only; no production auth assertion. */
@EnabledIfEnvironmentVariable(named = "SERVICE_COVER_LIVE_ACCEPTANCE", matches = "true")
class ServiceCoverSigningLiveAcceptance {
  @Test void readySyntheticCoverIsSignedForItsExactStoredVersionAndDownloaded() throws Exception {
    var connection = OssConnection.fromEnv();
    try (var db = new CAuthHttpTest.HttpFixture();
        var objects = new S3PrivateObjectStore(connection);
        var signer = new S3ServiceCoverObjectSigner(connection, Clock.systemUTC(), 600)) {
      PrivateAssetLiveSupport.initializePrivateSchemas(db.source);
      var api = new PrivateAssetApiImpl(db.source, db.ids, objects,
          new ClamAvPrivateAssetScanner("127.0.0.1", 13310, Duration.ofSeconds(10)),
          new ImageIoPrivateAssetImageNormalizer(), new ImageIoPrivateAssetWatermarkRenderer(),
          request -> { throw new ApiException(CommonApiCodes.FORBIDDEN, "Read grant not in this test"); },
          () -> new PrivateAssetGrantKeyProvider.KeyMaterial("test-v1", new SecretKeySpec(new byte[32], "HmacSHA256")),
          (purpose, plaintext) -> new byte[32], Clock.systemUTC());
      byte[] png = PrivateAssetLiveSupport.syntheticTestPng();
      String requestId = UUID.randomUUID().toString();
      Long assetId = null;
      try {
        var receipt = api.upload(new UploadPrivateAssetCommand("77", "SERVICE_COVER", "image/png",
            png.length, new ByteArrayInputStream(png),
            new CommandContext(requestId, "cover-live", OperatorType.USER, "77", "TEST")));
        assetId = Long.parseLong(receipt.assetId());
        String version = db.jdbc.queryForObject("SELECT object_version_ref FROM private_asset WHERE id=?", String.class, assetId);
        System.out.println("COVER_LIVE upload=READY scanner=REAL_CLAMAV versionKind=" + (version.startsWith("version:") ? "VERSION_ID" : "ETAG_ONLY"));
        var signing = new ServiceCoverSigningApiImpl(db.source, signer, Clock.systemUTC());
        if (!version.startsWith("version:")) {
          assertThrows(ApiException.class, () -> signing.signServiceCover(receipt.assetId(), new QueryContext("live", OperatorType.SYSTEM, "test")));
          fail("Live bucket returned ETag-only objects; exact-version Image URL requires versioned objects. Signing correctly fails closed.");
        }
        var signed = signing.signServiceCover(receipt.assetId(), new QueryContext("live", OperatorType.SYSTEM, "test"));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(signed.signedUrl()))
            .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()));
        assertEquals(receipt.objectSha256(), digest);
        System.out.println("COVER_LIVE signature=EXACT_VERSION download=200 normalizedDigest=MATCH");
      } finally {
        // Recover the fixture-owned ID even when upload acknowledgement was lost.
        if (assetId == null) {
          var rows = db.jdbc.queryForList("SELECT id FROM private_asset WHERE owner_user_id=77 AND purpose='SERVICE_COVER'", Long.class);
          if (rows.size() == 1) assetId = rows.getFirst();
        }
        if (assetId != null) assertEquals(0, PrivateAssetLiveSupport.cleanupFixtureAsset(db.jdbc, assetId, connection));
      }
    }
  }
}
