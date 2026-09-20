package com.petplatform.thirdparty.api;

/** Stable private-asset errors mapped by the terminal HTTP adapter. */
public final class PrivateAssetApiCodes {
  private PrivateAssetApiCodes() {}

  public static final String ASSET_NOT_READY = "PRIVATE_ASSET_NOT_READY";
  public static final String ASSET_REJECTED = "PRIVATE_ASSET_REJECTED";
  public static final String GRANT_GONE = "PRIVATE_ASSET_GRANT_GONE";
}
