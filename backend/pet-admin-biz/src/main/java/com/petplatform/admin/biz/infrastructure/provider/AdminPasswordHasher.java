package com.petplatform.admin.biz.infrastructure.provider;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import java.nio.CharBuffer;
import java.util.concurrent.Semaphore;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class AdminPasswordHasher {
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
  private final Semaphore lanes = new Semaphore(2);
  private final String dummy;

  public AdminPasswordHasher() {
    dummy = encoder.encode("not-an-account-" + java.util.UUID.randomUUID());
  }

  public String encode(char[] password) {
    require(password);
    return bounded(() -> encoder.encode(CharBuffer.wrap(password)));
  }

  public boolean matches(char[] password, String encoded) {
    require(password);
    return bounded(
        () -> {
          boolean ok =
              encoder.matches(CharBuffer.wrap(password), encoded == null ? dummy : encoded);
          return encoded != null && ok;
        });
  }

  private <T> T bounded(java.util.function.Supplier<T> action) {
    if (!lanes.tryAcquire()) throw new AdminAuthFailure(429, "COMMON_RATE_LIMITED");
    try {
      return action.get();
    } finally {
      lanes.release();
    }
  }

  public static void require(char[] password) {
    if (password == null || password.length < 8 || password.length > 64)
      throw AdminAuthFailure.invalid();
  }

  public static void requireInitialStrength(char[] password) {
    require(password);
    boolean upper = false, lower = false, digit = false, other = false;
    for (char c : password) {
      upper |= Character.isUpperCase(c);
      lower |= Character.isLowerCase(c);
      digit |= Character.isDigit(c);
      other |= !Character.isLetterOrDigit(c);
    }
    if ((upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0) < 3)
      throw AdminAuthFailure.invalid();
  }
}
