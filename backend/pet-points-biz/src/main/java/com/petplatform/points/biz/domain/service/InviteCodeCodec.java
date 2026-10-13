package com.petplatform.points.biz.domain.service;

import java.util.Locale;

/**
 * Deterministic invite-code codec (CCR-POINTS-TASK-001 R1). Layout: 'P' + 13-char zero-padded
 * unsigned base36(userId) + 1 weighted-sum check char over the base36 block. Reversible without
 * storage so an inviter's code is always derivable from the session user id; the check char
 * rejects typos and casual guesses before any user-existence lookup. Codes are shared openly by
 * design — secrecy is not a control here (binding rules R2~R4 are).
 */
public final class InviteCodeCodec {
  private static final int BODY_LENGTH = 13;
  private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

  private InviteCodeCodec() {}

  /** Encodes an internal BIGINT user id into its invite code. */
  public static String encode(long userId) {
    if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    String digits = Long.toUnsignedString(userId, 36);
    StringBuilder body = new StringBuilder(BODY_LENGTH);
    for (int pad = 0; pad < BODY_LENGTH - digits.length(); pad++) body.append('0');
    body.append(digits);
    return "P" + body + check(body.toString());
  }

  /** Decodes an invite code back to the user id; {@code null} when the shape is invalid. */
  public static Long decode(String code) {
    if (code == null || code.length() != BODY_LENGTH + 2) return null;
    if (!code.startsWith("P")) return null;
    String body = code.substring(1, 1 + BODY_LENGTH);
    for (int index = 0; index < body.length(); index++) {
      if (ALPHABET.indexOf(body.charAt(index)) < 0) return null;
    }
    String expected = String.valueOf(check(body));
    if (!expected.equals(code.substring(1 + BODY_LENGTH))) return null;
    try {
      long value = Long.parseUnsignedLong(body, 36);
      return value > 0 ? value : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static char check(String body) {
    long weighted = 0;
    for (int index = 0; index < body.length(); index++) {
      weighted += (long) ALPHABET.indexOf(body.charAt(index)) * (index + 1);
    }
    int modulus = (int) Long.remainderUnsigned(weighted, ALPHABET.length());
    return ALPHABET.charAt(modulus);
  }

  /** Syntactic pre-check shared by the HTTP layer (rejects before any store work). */
  public static boolean wellFormed(String code) {
    return code != null && code.length() <= 32 && code.length() >= 3
        && code.chars().allMatch(character ->
            character == 'P' || ALPHABET.indexOf(character) >= 0
                || ALPHABET.toUpperCase(Locale.ROOT).indexOf(character) >= 0);
  }
}
