package com.petplatform.boot.adapter.web.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.petplatform.admin.biz.application.AdminAuthFailure;
import java.util.*;

public final class AdminAuthHttpModels {
  private AdminAuthHttpModels() {}

  public static final class Request implements AutoCloseable {
    private final Map<String, Object> fields;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Request(Map<String, Object> values) {
      if (values == null) throw AdminAuthFailure.invalid();
      fields = new LinkedHashMap<>(values);
      for (String key : List.of("password", "answer")) {
        Object value = fields.get(key);
        if (value instanceof String text) fields.put(key, text.toCharArray());
      }
    }

    public void require(Set<String> allowed, String... required) {
      if (!allowed.containsAll(fields.keySet())
          || fields.values().stream().anyMatch(Objects::isNull)) throw AdminAuthFailure.invalid();
      for (String name : required) if (!fields.containsKey(name)) throw AdminAuthFailure.invalid();
    }

    public String string(String name) {
      Object v = fields.get(name);
      if (v == null) return null;
      if (!(v instanceof String s)) throw AdminAuthFailure.invalid();
      return s;
    }

    public char[] secret(String name) {
      Object v = fields.get(name);
      if (!(v instanceof char[] chars)) throw AdminAuthFailure.invalid();
      return chars;
    }

    public long id(String name) {
      String s = string(name);
      try {
        if (s == null || !s.matches("[1-9][0-9]{0,18}")) throw AdminAuthFailure.invalid();
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        throw AdminAuthFailure.invalid();
      }
    }

    @Override
    public void close() {
      fields
          .values()
          .forEach(
              v -> {
                if (v instanceof char[] c) Arrays.fill(c, '\0');
              });
      fields.clear();
    }

    @Override
    public String toString() {
      return "AdminAuthRequest[REDACTED]";
    }
  }

  public static final class Envelope {
    private final String code, message, traceId;
    private final Object data;

    public Envelope(String code, String message, Object data, String traceId) {
      this.code = code;
      this.message = message;
      this.data = data;
      this.traceId = traceId;
    }

    public String getCode() {
      return code;
    }

    public String getMessage() {
      return message;
    }

    public Object getData() {
      return data;
    }

    public String getTraceId() {
      return traceId;
    }

    @Override
    public String toString() {
      return "AdminAuthEnvelope[code=" + code + ",data=REDACTED]";
    }
  }
}
