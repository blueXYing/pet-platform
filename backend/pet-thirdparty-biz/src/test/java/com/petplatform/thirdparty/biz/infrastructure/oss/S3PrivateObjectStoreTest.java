package com.petplatform.thirdparty.biz.infrastructure.oss;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3PrivateObjectStoreTest {
  static final String KEY = "merchant-materials/test/1/normalized-v1";
  static final String ETAG = "\"0123456789abcdef0123456789abcdef\"";
  static final OssConnection CONNECTION =
      new OssConnection("https://oss.example.invalid", "test", "test", "test", "test", null);

  @Test
  void malformedOrPublicAclNeverPassesAsPrivate() {
    for (var acl :
        new GetBucketAclResponse[] {
          GetBucketAclResponse.builder().build(),
          GetBucketAclResponse.builder()
              .owner(Owner.builder().id("owner").build())
              .grants(
                  Grant.builder()
                      .permission(Permission.READ)
                      .grantee(
                          Grantee.builder()
                              .type(Type.GROUP)
                              .uri("http://acs.amazonaws.com/groups/global/AllUsers")
                              .build())
                      .build())
              .build()
        }) {
      var s3 = mock(S3Client.class);
      when(s3.getBucketAcl(any(GetBucketAclRequest.class))).thenReturn(acl);
      assertThrows(IllegalStateException.class, () -> new S3PrivateObjectStore(CONNECTION, s3));
      verify(s3).close();
    }
  }

  @Test
  void writesAreConditionalAndDoNotSetAclOrReturnPublicUrl() throws Exception {
    var s3 = client();
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().eTag(ETAG).build());
    var store = new S3PrivateObjectStore(CONNECTION, s3);
    byte[] bytes = {1, 2, 3};
    var stored = store.putIfAbsent(KEY, bytes, "image/png", hash(bytes));
    assertEquals("etag:" + ETAG, stored.versionRef());
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(request.capture(), any(RequestBody.class));
    assertEquals("*", request.getValue().ifNoneMatch());
    assertNull(request.getValue().acl());
    assertEquals(
        "true",
        request
            .getValue()
            .overrideConfiguration()
            .orElseThrow()
            .headers()
            .get("x-oss-forbid-overwrite")
            .getFirst());
  }

  @Test
  void replayVerifiesActualBytesAndNeverOverwrites() throws Exception {
    byte[] bytes = {1, 2, 3};
    var s3 = client();
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            HeadObjectResponse.builder()
                .contentLength(3L)
                .contentType("image/png")
                .eTag(ETAG)
                .metadata(Map.of("sha256", hash(bytes)))
                .build());
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            invocation ->
                new ResponseInputStream<>(
                    GetObjectResponse.builder()
                        .eTag(ETAG)
                        .contentType("image/png")
                        .metadata(Map.of("sha256", hash(bytes)))
                        .build(),
                    AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
    var store = new S3PrivateObjectStore(CONNECTION, s3);
    assertEquals(hash(bytes), store.putIfAbsent(KEY, bytes, "image/png", hash(bytes)).sha256());
    verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    var request = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3).getObject(request.capture());
    assertEquals(ETAG, request.getValue().ifMatch());
    assertThrows(
        IllegalStateException.class,
        () ->
            store.putIfAbsent(KEY, new byte[] {4, 5, 6}, "image/png", hash(new byte[] {4, 5, 6})));
  }

  @Test
  void corruptObjectBodyCannotBeAcceptedFromMatchingMetadata() throws Exception {
    var s3 = client();
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder()
                    .eTag(ETAG)
                    .contentType("image/png")
                    .metadata(Map.of("sha256", hash(new byte[] {1})))
                    .build(),
                AbortableInputStream.create(new java.io.ByteArrayInputStream(new byte[] {2}))));
    var store = new S3PrivateObjectStore(CONNECTION, s3);
    assertThrows(IllegalStateException.class, () -> store.get(KEY, "etag:" + ETAG));
    assertThrows(IllegalArgumentException.class, () -> store.get(KEY, "etag:null"));
    assertThrows(IllegalArgumentException.class, () -> store.get("public/a", "etag:" + ETAG));
  }

  static S3Client client() {
    var s3 = mock(S3Client.class);
    when(s3.getBucketAcl(any(GetBucketAclRequest.class)))
        .thenReturn(
            GetBucketAclResponse.builder()
                .owner(Owner.builder().id("owner").build())
                .grants(
                    Grant.builder()
                        .permission(Permission.FULL_CONTROL)
                        .grantee(Grantee.builder().type(Type.CANONICAL_USER).id("owner").build())
                        .build())
                .build());
    return s3;
  }

  static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
