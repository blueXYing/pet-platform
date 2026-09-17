package com.petplatform.merchant.biz.application;

/**
 * Fixed-policy normalization/protection/HMAC boundary; key material remains outside MER storage.
 */
@FunctionalInterface
public interface SubjectCredentialPort {
  ProtectedCredential protect(
      String credentialType, String subjectName, String identifier, String validityBasis);

  /** Returns the configured immutable policy version, or null when the secret is unavailable. */
  default String availablePolicyVersion() {
    return null;
  }

  /** Compares a protected verified subject with the submitted merchant name. */
  default boolean subjectMatches(byte[] subjectNameProtected, String expectedMerchantName) {
    throw new UnsupportedOperationException("protected subject comparison is unavailable");
  }

  record ProtectedCredential(
      byte[] subjectNameProtected,
      byte[] identifierProtected,
      byte[] lookupDigest,
      int policySlot,
      String keyVersion,
      byte[] validityBasisProtected) {
    public ProtectedCredential {
      subjectNameProtected = clone(subjectNameProtected);
      identifierProtected = clone(identifierProtected);
      lookupDigest = clone(lookupDigest);
      validityBasisProtected = clone(validityBasisProtected);
    }

    private static byte[] clone(byte[] value) {
      return value == null ? null : value.clone();
    }

    @Override
    public byte[] subjectNameProtected() {
      return clone(subjectNameProtected);
    }

    @Override
    public byte[] identifierProtected() {
      return clone(identifierProtected);
    }

    @Override
    public byte[] lookupDigest() {
      return clone(lookupDigest);
    }

    @Override
    public byte[] validityBasisProtected() {
      return clone(validityBasisProtected);
    }
  }
}
