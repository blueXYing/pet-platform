package com.petplatform.notification.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.dto.NotificationTypes.PreferenceView;

/**
 * C-side notification preference surface (SSOT §16.4; schema 06 §11 notification_preference).
 * Exactly two user-togglable switches exist: the ordinary interaction reminder and the WeChat
 * external push preference. The mandatory in-site kinds (order/refund/verification/aftersale/
 * audit) are never preference items and cannot be disabled. The receiver is always the current
 * session user. The update is a supplement-23 bound command: the same requestId replays the
 * first success receipt; the same key with different parameters is an
 * {@code IDEMPOTENCY_KEY_CONFLICT}; a missing row reads as the schema defaults (both enabled).
 */
public interface NotificationPreferenceApi {

  /** Reads the caller's current preference; receiver coordinates are server-derived. */
  PreferenceView getPreference(QueryContext context);

  /** Full-state replace of both switches (strict full PUT; partial bodies are rejected). */
  PreferenceView updatePreference(UpdatePreferenceCommand command);

  record UpdatePreferenceCommand(
      boolean interactionEnabled, boolean externalPushEnabled, CommandContext context) {}
}
