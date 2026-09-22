package com.petplatform.notification.api.command;

import com.petplatform.common.CommandContext;

public final class NotificationReadCommandApi {

  private NotificationReadCommandApi() {}

  /** Result-idempotent write: CAS read_at; replays return the current state. */
  public record MarkReadCommand(String notificationId, CommandContext context) {}
}
