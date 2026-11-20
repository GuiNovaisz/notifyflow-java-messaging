package io.notifyflow.contracts;

import java.time.Instant;
import java.util.UUID;

public record NotificationEvent(
    UUID eventId,
    UUID notificationId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    NotificationRequest notification,
    String correlationId,
    int generation) {
  public static final String TYPE = "notification.requested.v1";

  public NotificationEvent nextGeneration() {
    return new NotificationEvent(
        eventId,
        notificationId,
        eventType,
        eventVersion,
        occurredAt,
        notification,
        correlationId,
        generation + 1);
  }
}
