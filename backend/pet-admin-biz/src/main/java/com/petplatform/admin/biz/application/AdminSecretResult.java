package com.petplatform.admin.biz.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit serialization only; credentials never appear in an incidental toString(). */
public final class AdminSecretResult {
  private final Map<String, Object> data;
  private final String bindingCookie;

  public AdminSecretResult(Map<String, Object> data) {
    this(data, null);
  }

  public AdminSecretResult(Map<String, Object> data, String bindingCookie) {
    this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    this.bindingCookie = bindingCookie;
  }

  public Map<String, Object> data() {
    return data;
  }

  public String bindingCookie() {
    return bindingCookie;
  }

  @Override
  public String toString() {
    return "AdminSecretResult[REDACTED]";
  }
}
