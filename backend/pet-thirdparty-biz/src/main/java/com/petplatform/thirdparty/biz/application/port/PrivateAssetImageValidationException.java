package com.petplatform.thirdparty.biz.application.port;

/** Deterministic image-rejection reason; safe to project to the upload error message. */
public final class PrivateAssetImageValidationException extends IllegalArgumentException {
  private final String reasonCode;

  public PrivateAssetImageValidationException(String reasonCode, String message) {
    super(message);
    this.reasonCode = reasonCode;
  }

  public String reasonCode() {
    return reasonCode;
  }
}