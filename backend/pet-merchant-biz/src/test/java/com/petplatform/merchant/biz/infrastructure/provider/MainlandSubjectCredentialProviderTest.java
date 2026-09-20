package com.petplatform.merchant.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MainlandSubjectCredentialProviderTest {
  private static final byte[] ENCRYPTION_KEY = key(0x11);
  private static final byte[] EQUALITY_KEY = key(0x33);
  private static final byte[] LOOKUP_KEY = key(0x55);

  @Test
  void canonicalizesSyntheticFifteenAndEighteenDigitIdentityToSameLookup() {
    var provider = provider("subject-v1", LOOKUP_KEY);
    String oldSynthetic = "519999900101001";
    String currentSynthetic = withIdentityCheck("51999919900101001");

    var oldValue = provider.protect("IDENTITY_NUMBER", " 合成测试人 ", oldSynthetic, null);
    var currentValue =
        provider.protect("IDENTITY_NUMBER", "合成测试人", currentSynthetic.toLowerCase(), null);

    assertArrayEquals(oldValue.lookupDigest(), currentValue.lookupDigest());
    assertEquals(32, oldValue.lookupDigest().length);
    assertTrue(provider.subjectMatches(oldValue.subjectNameProtected(), "合成测试人"));
    assertFalse(provider.subjectMatches(oldValue.subjectNameProtected(), "另一合成主体"));
  }

  @Test
  void validatesIdentityDateChecksumAndRejectsForeignOrQaIdentifiers() {
    var provider = provider("subject-v1", LOOKUP_KEY);
    String valid = withIdentityCheck("51999920000229001");
    provider.protect("IDENTITY_NUMBER", "合成测试人", valid, null);

    assertInvalid(
        () ->
            provider.protect(
                "IDENTITY_NUMBER",
                "合成测试人",
                valid.substring(0, 17) + different(valid.charAt(17)),
                null));
    assertInvalid(() -> provider.protect("IDENTITY_NUMBER", "合成测试人", "519999200102290010", null));
    assertInvalid(() -> provider.protect("IDENTITY_NUMBER", "合成测试人", "QA-ID-001", null));
    assertInvalid(() -> provider.protect("IDENTITY_NUMBER", "合成测试人", "519999 900101001", null));
  }

  @Test
  void rejectsResidencePermitPrefixesAndFutureBirthDatesEvenWithValidChecksum() {
    var provider = provider("subject-v1", LOOKUP_KEY);
    for (String prefix : java.util.List.of("810000", "820000", "830000", "999999")) {
      assertInvalid(
          () ->
              provider.protect(
                  "IDENTITY_NUMBER", "合成测试人", withIdentityCheck(prefix + "19900101001"), null));
    }
    assertInvalid(
        () ->
            provider.protect(
                "IDENTITY_NUMBER", "合成测试人", withIdentityCheck("51999929900101001"), null));
  }

  @Test
  void normalizesFullWidthCreditCodeAndChecksGb32100Checksum() {
    var provider = provider("subject-v1", LOOKUP_KEY);
    String canonical = withCreditCheck("91510100MA0000000");
    String fullWidth = toFullWidth(canonical.toLowerCase());

    var first = provider.protect("CREDIT_CODE", "合成测试商户", canonical, " 长期依据 ");
    var second = provider.protect("CREDIT_CODE", "合成测试商户", fullWidth, "长期依据");

    assertArrayEquals(first.lookupDigest(), second.lookupDigest());
    assertTrue(provider.subjectMatches(first.subjectNameProtected(), " 合成测试商户 "));
    assertInvalid(
        () ->
            provider.protect(
                "CREDIT_CODE",
                "合成测试商户",
                canonical.substring(0, 17) + different(canonical.charAt(17)),
                null));
    assertInvalid(() -> provider.protect("CREDIT_CODE", "合成测试商户", "91510100-MA0000000", null));
  }

  @Test
  void treatsIndustryLicenseAsBoundedOpaqueIssuerIdentifierWithoutInventedChecksum() {
    var provider = provider("subject-v1", LOOKUP_KEY);
    var first = provider.protect("INDUSTRY_LICENSE", "合成宠物医院", " 川动诊字（测试）001号 ", null);
    var second = provider.protect("INDUSTRY_LICENSE", "合成宠物医院", "川动诊字(测试)001号", null);

    assertArrayEquals(first.lookupDigest(), second.lookupDigest());
    assertInvalid(() -> provider.protect("INDUSTRY_LICENSE", "合成宠物医院", "编号 含空格", null));
    assertInvalid(() -> provider.protect("INDUSTRY_LICENSE", "合成宠物医院", "#unsupported", null));
  }

  @Test
  void separatesCredentialTypeSchemeKeyVersionAndLookupKeyDomains() {
    String opaque = "TEST001";
    var v1 = provider("subject-v1", LOOKUP_KEY);
    var v2 = provider("subject-v2", LOOKUP_KEY);
    var otherKey = provider("subject-v1", key(0x77));

    byte[] first = v1.protect("INDUSTRY_LICENSE", "合成主体", opaque, null).lookupDigest();
    assertFalse(
        Arrays.equals(first, v2.protect("INDUSTRY_LICENSE", "合成主体", opaque, null).lookupDigest()));
    assertFalse(
        Arrays.equals(
            first, otherKey.protect("INDUSTRY_LICENSE", "合成主体", opaque, null).lookupDigest()));
    assertEquals(MainlandSubjectCredentialProvider.NORMALIZATION_SCHEME, "CN-ID15-18-USCC18-v1");
    assertEquals("subject-v1", v1.availablePolicyVersion());
  }

  @Test
  void clonesLookupKeyAndRejectsInvalidConfigurationAndTypes() {
    byte[] lookup = LOOKUP_KEY.clone();
    var provider = provider("subject-v1", lookup);
    byte[] before = provider.protect("INDUSTRY_LICENSE", "合成主体", "TEST001", null).lookupDigest();
    Arrays.fill(lookup, (byte) 0);
    byte[] after = provider.protect("INDUSTRY_LICENSE", "合成主体", "TEST001", null).lookupDigest();
    assertArrayEquals(before, after);

    var protector = new AesGcmProtectedValueProvider("pii-v1", ENCRYPTION_KEY, EQUALITY_KEY);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MainlandSubjectCredentialProvider(protector, "subject-v1", new byte[16]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MainlandSubjectCredentialProvider(protector, "bad version!", LOOKUP_KEY));
    assertInvalid(() -> provider.protect("PASSPORT", "合成主体", "TEST001", null));
  }

  private static MainlandSubjectCredentialProvider provider(String version, byte[] lookupKey) {
    return new MainlandSubjectCredentialProvider(
        new AesGcmProtectedValueProvider("pii-v1", ENCRYPTION_KEY, EQUALITY_KEY),
        version,
        lookupKey);
  }

  private static void assertInvalid(Runnable call) {
    ApiException failure = assertThrows(ApiException.class, call::run);
    assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure.code());
  }

  private static String withIdentityCheck(String body) {
    int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    String checks = "10X98765432";
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += (body.charAt(i) - '0') * weights[i];
    return body + checks.charAt(sum % 11);
  }

  private static String withCreditCheck(String body) {
    String alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += alphabet.indexOf(body.charAt(i)) * weights[i];
    return body + alphabet.charAt((31 - sum % 31) % 31);
  }

  private static String toFullWidth(String value) {
    StringBuilder result = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c >= '!' && c <= '~') result.append((char) (c + 0xfee0));
      else result.append(c);
    }
    return result.toString();
  }

  private static char different(char value) {
    return value == '0' ? '1' : '0';
  }

  private static byte[] key(int value) {
    byte[] result = new byte[32];
    Arrays.fill(result, (byte) value);
    return result;
  }
}
