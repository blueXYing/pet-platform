package com.petplatform.merchant.biz.application;

import java.math.BigDecimal;

public final class ApplicationValidationPorts {
  private ApplicationValidationPorts() {}

  @FunctionalInterface
  public interface OpenCityReader {
    boolean isOpen(String cityCode);
  }

  @FunctionalInterface
  public interface MapValidationPort {
    boolean isReasonable(
        String cityCode, String address, BigDecimal longitude, BigDecimal latitude);
  }

  @FunctionalInterface
  public interface ProtectedValuePort {
    ProtectedValue protect(String purpose, String plaintext);

    default String reveal(String purpose, byte[] ciphertext) {
      throw new UnsupportedOperationException("protected value reveal is unavailable");
    }
  }

  public record ProtectedValue(byte[] ciphertext, byte[] equalityToken) {
    public ProtectedValue {
      ciphertext = copy(ciphertext);
      equalityToken = copy(equalityToken);
    }

    private static byte[] copy(byte[] value) {
      return value == null ? null : value.clone();
    }

    @Override
    public byte[] ciphertext() {
      return copy(ciphertext);
    }

    @Override
    public byte[] equalityToken() {
      return copy(equalityToken);
    }
  }
}
