package io.notifyflow.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationEntity {
  @Id UUID id;

  @Column(nullable = false, length = 254)
  String recipient;

  @Column(nullable = false, length = 10)
  String channel;

  @Column(nullable = false, length = 200)
  String subject;

  @Column(nullable = false, columnDefinition = "text")
  String body;

  @Column(nullable = false, length = 20)
  String status;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  Instant updatedAt;

  protected NotificationEntity() {}
}
