package io.notifyflow.worker;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/notifications/{id}")
public class DeliveryController {
  private final JdbcTemplate db;

  public DeliveryController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/deliveries")
  List<Map<String, Object>> deliveries(@PathVariable UUID id) {
    return db.queryForList(
        "select id as \"deliveryId\",event_id as \"eventId\",status,attempt_count as"
            + " \"attemptCount\",updated_at as \"updatedAt\" from deliveries where"
            + " notification_id=?",
        id);
  }

  @GetMapping("/attempts")
  List<Map<String, Object>> attempts(@PathVariable UUID id) {
    return db.queryForList(
        "select a.attempt_number as \"attemptNumber\",a.started_at as \"startedAt\",a.finished_at"
            + " as \"finishedAt\",a.outcome,a.failure_category as"
            + " \"failureCategory\",a.provider_status as \"providerStatus\",a.next_retry_at as"
            + " \"nextRetryAt\" from delivery_attempts a join deliveries d on d.id=a.delivery_id"
            + " where d.notification_id=? order by a.attempt_number limit 4",
        id);
  }
}
