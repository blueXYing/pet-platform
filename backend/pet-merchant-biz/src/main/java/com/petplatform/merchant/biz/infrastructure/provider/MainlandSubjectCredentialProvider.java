package com.petplatform.merchant.biz.infrastructure.provider;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mainland credential policy backed by GB 11643-1999 and GB 32100-2015.
 *
 * <p>Authoritative standard records:
 * https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=080D6FBF2BB468F9007657F26D60013E and
 * https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=24691C25985C1073D3A7C85629378AC0. Keys and
 * the persistently pinned key version are injected; this class never generates or loads secret
 * material.
 */
public final class MainlandSubjectCredentialProvider implements SubjectCredentialPort {
  public static final String NORMALIZATION_SCHEME = "CN-ID15-18-USCC18-v1";
  private static final int POLICY_SLOT = 1;
  private static final int LOOKUP_KEY_BYTES = 32;
  private static final byte[] LOOKUP_DOMAIN =
      "pet-platform:merchant:subject-lookup:v1".getBytes(StandardCharsets.US_ASCII);
  private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
  private static final char[] ID_CHECK = "10X98765432".toCharArray();
  // Mainland provincial address prefixes; 81/82/83 residence permits are outside this approved
  // scope.
  private static final java.util.Set<String> MAINLAND_PREFIXES =
      java.util.Set.of(
          "11", "12", "13", "14", "15", "21", "22", "23", "31", "32", "33", "34", "35", "36", "37",
          "41", "42", "43", "44", "45", "46", "50", "51", "52", "53", "54", "61", "62", "63", "64",
          "65");
  private static final String USCC_ALPHABET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
  private static final int[] USCC_WEIGHTS = {
    1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28
  };
  private static final Pattern INDUSTRY_LICENSE =
      Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}()（）./·_-]{0,127}");

  private static final String SUBJECT_PURPOSE =
      "merchant-credential-subject-name:" + NORMALIZATION_SCHEME;
  private static final String IDENTIFIER_PURPOSE_PREFIX =
      "merchant-credential-identifier:" + NORMALIZATION_SCHEME + ":";
  private static final String VALIDITY_PURPOSE_PREFIX =
      "merchant-credential-validity-basis:" + NORMALIZATION_SCHEME + ":";

  private final ApplicationValidationPorts.ProtectedValuePort protector;
  private final String keyVersion;
  private final byte[] keyVersionBytes;
  private final byte[] lookupKey;

  public MainlandSubjectCredentialProvider(
      ApplicationValidationPorts.ProtectedValuePort protector,
      String keyVersion,
      byte[] lookupKey) {
    this.protector = Objects.requireNonNull(protector, "protector");
    if (keyVersion == null || !keyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")) {
      throw new IllegalArgumentException(
          "keyVersion must match the persisted policy version format");
    }
    if (lookupKey == null || lookupKey.length != LOOKUP_KEY_BYTES) {
      throw new IllegalArgumentException("a 256-bit subject lookup key is required");
    }
    this.keyVersion = keyVersion;
    this.keyVersionBytes = keyVersion.getBytes(StandardCharsets.US_ASCII);
    this.lookupKey = lookupKey.clone();
  }

  @Override
  public ProtectedCredential protect(
      String credentialType, String subjectName, String identifier, String validityBasis) {
    String type = requireType(credentialType);
    String canonicalSubject = normalizeSubject(subjectName);
    String canonicalIdentifier = normalizeIdentifier(type, identifier);
    String canonicalBasis = normalizeValidityBasis(validityBasis);
    var protectedSubject = protector.protect(SUBJECT_PURPOSE, canonicalSubject);
    var protectedIdentifier =
        protector.protect(IDENTIFIER_PURPOSE_PREFIX + type, canonicalIdentifier);
    byte[] protectedBasis =
        canonicalBasis == null
            ? null
            : protector.protect(VALIDITY_PURPOSE_PREFIX + type, canonicalBasis).ciphertext();
    return new ProtectedCredential(
        protectedSubject.ciphertext(),
        protectedIdentifier.ciphertext(),
        lookup(type, canonicalIdentifier),
        POLICY_SLOT,
        keyVersion,
        protectedBasis);
  }

  @Override
  public String availablePolicyVersion() {
    return keyVersion;
  }

  @Override
  public boolean subjectMatches(byte[] subjectNameProtected, String expectedMerchantName) {
    if (subjectNameProtected == null) return false;
    String expected = normalizeSubject(expectedMerchantName);
    String actual = protector.reveal(SUBJECT_PURPOSE, subjectNameProtected);
    return MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] lookup(String type, String canonicalIdentifier) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
      updateFramed(mac, LOOKUP_DOMAIN);
      updateFramed(mac, NORMALIZATION_SCHEME.getBytes(StandardCharsets.US_ASCII));
      updateFramed(mac, keyVersionBytes);
      updateFramed(mac, type.getBytes(StandardCharsets.US_ASCII));
      updateFramed(mac, canonicalIdentifier.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal();
    } catch (GeneralSecurityException failure) {
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "credential protection provider is unavailable");
    }
  }

  private static void updateFramed(Mac mac, byte[] value) {
    mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    mac.update(value);
  }

  private static String requireType(String credentialType) {
    if (!"IDENTITY_NUMBER".equals(credentialType)
        && !"CREDIT_CODE".equals(credentialType)
        && !"INDUSTRY_LICENSE".equals(credentialType)) {
      throw invalid("credential type is unsupported");
    }
    return credentialType;
  }

  private static String normalizeIdentifier(String type, String identifier) {
    String normalized = normalizeIdentifierText(identifier);
    return switch (type) {
      case "IDENTITY_NUMBER" -> normalizeIdentity(normalized);
      case "CREDIT_CODE" -> normalizeCreditCode(normalized);
      case "INDUSTRY_LICENSE" -> normalizeIndustryLicense(normalized);
      default -> throw invalid("credential type is unsupported");
    };
  }

  private static String normalizeIdentifierText(String value) {
    if (value == null) throw invalid("credential identifier is required");
    String normalized =
        trimAsciiWhitespace(Normalizer.normalize(value, Normalizer.Form.NFKC))
            .toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()
        || normalized.chars().anyMatch(MainlandSubjectCredentialProvider::isAsciiWhitespace)) {
      throw invalid("credential identifier format is invalid");
    }
    return normalized;
  }

  private static String normalizeIdentity(String value) {
    if (value.length() < 2 || !MAINLAND_PREFIXES.contains(value.substring(0, 2)))
      throw invalid("identity address prefix is outside the mainland scope");
    if (value.matches("[0-9]{15}")) {
      String body = value.substring(0, 6) + "19" + value.substring(6);
      validateDate(body.substring(6, 14));
      return body + identityCheck(body);
    }
    if (!value.matches("[0-9]{17}[0-9X]")) {
      throw invalid("mainland identity number format is invalid");
    }
    validateDate(value.substring(6, 14));
    if (identityCheck(value.substring(0, 17)) != value.charAt(17)) {
      throw invalid("mainland identity number checksum is invalid");
    }
    return value;
  }

  private static char identityCheck(String body) {
    int sum = 0;
    for (int i = 0; i < ID_WEIGHTS.length; i++) {
      sum += (body.charAt(i) - '0') * ID_WEIGHTS[i];
    }
    return ID_CHECK[sum % 11];
  }

  private static void validateDate(String yyyyMMdd) {
    try {
      LocalDate birth =
          LocalDate.of(
              Integer.parseInt(yyyyMMdd.substring(0, 4)),
              Integer.parseInt(yyyyMMdd.substring(4, 6)),
              Integer.parseInt(yyyyMMdd.substring(6, 8)));
      if (birth.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
        throw invalid("mainland identity birth date is invalid");
    } catch (DateTimeException | NumberFormatException failure) {
      throw invalid("mainland identity birth date is invalid");
    }
  }

  private static String normalizeCreditCode(String value) {
    if (value.length() != 18) throw invalid("unified social credit code format is invalid");
    int sum = 0;
    for (int i = 0; i < 17; i++) {
      int index = USCC_ALPHABET.indexOf(value.charAt(i));
      if (index < 0) throw invalid("unified social credit code format is invalid");
      sum += index * USCC_WEIGHTS[i];
    }
    int expected = (31 - sum % 31) % 31;
    if (USCC_ALPHABET.indexOf(value.charAt(17)) != expected) {
      throw invalid("unified social credit code checksum is invalid");
    }
    return value;
  }

  private static String normalizeIndustryLicense(String value) {
    if (!INDUSTRY_LICENSE.matcher(value).matches()) {
      throw invalid("industry license identifier format is unsupported");
    }
    return value;
  }

  private static String normalizeSubject(String value) {
    if (value == null) throw invalid("credential subject is required");
    String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (normalized.isEmpty()
        || normalized.codePointCount(0, normalized.length()) > 256
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid("credential subject format is invalid");
    }
    return normalized;
  }

  private static String normalizeValidityBasis(String value) {
    if (value == null) return null;
    String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (normalized.isEmpty()
        || normalized.length() > 500
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid("credential validity basis format is invalid");
    }
    return normalized;
  }

  private static String trimAsciiWhitespace(String value) {
    int start = 0, end = value.length();
    while (start < end && isAsciiWhitespace(value.charAt(start))) start++;
    while (end > start && isAsciiWhitespace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean isAsciiWhitespace(int value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
  }

  private static ApiException invalid(String message) {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }
}
