package com.petplatform.user.biz.application;

/**
 * Port for the WeChat mini-program identity exchange behind POST /api/v1/c/auth/wechat-login
 * (HTTP10 §3.1 and the accepted AUTH-001 mapping). The real provider is NOT authorized in this
 * phase: no implementation with real credentials ships here and the C-end auth assembly stays
 * off until an authorized provider bean is supplied externally.
 *
 * <p>Failures are typed so the application service can apply the approved unified semantics:
 * a rejected proof is a 401-class "verification failed" while an unavailable provider is a
 * 503-class dependency failure; the two must never be conflated.
 */
public interface WechatSessionProvider {

    /** Exchanges a one-time wx.login code for the durable WeChat identity of one appid. */
    WechatIdentity exchangeIdentity(String wechatCode);

    /**
     * Exchanges a one-time getPhoneNumber code for the carrier phone number (11 ASCII digits,
     * {@code ^1[0-9]{10}$}). The phone is only ever trusted from this exchange, never from
     * request bodies.
     */
    String exchangePhone(String phoneCode);

    /** appId/openId/unionId exactly as returned by the provider; unionId may be null. */
    record WechatIdentity(String appId, String openId, String unionId) {

        public WechatIdentity {
            if (appId == null || appId.isBlank() || appId.length() > 128
                    || openId == null || openId.isBlank() || openId.length() > 128
                    || (unionId != null && unionId.length() > 128)) {
                throw new IllegalArgumentException("Invalid WeChat identity from provider");
            }
        }
    }

    /** The provider positively rejected the one-time code (used, forged or expired). */
    final class ProofRejected extends RuntimeException {
        public ProofRejected() {
            super("WeChat proof rejected", null, false, false);
        }
    }

    /** The provider fact could not be established (network, unknown or outage). */
    final class ProviderUnavailable extends RuntimeException {
        public ProviderUnavailable() {
            super("WeChat provider unavailable", null, false, false);
        }
    }
}
