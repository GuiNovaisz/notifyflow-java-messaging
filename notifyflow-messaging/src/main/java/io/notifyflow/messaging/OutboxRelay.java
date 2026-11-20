package io.notifyflow.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Same transport table shape in independently owned databases; no shared business state. */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ConfirmedPublisher publisher;
  private final Clock clock;
  private final MeterRegistry metrics;

  public OutboxRelay(
      JdbcTemplate db,
      TransactionTemplate tx,
      ConfirmedPublisher publisher,
      Clock clock,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.publisher = publisher;
    this.clock = clock;
    this.metrics = metrics;
    metrics.gauge(
        "notifyflow.outbox.pending",
        this,
        r ->
            r.db.queryForObject(
                "select count(*) from outbox_events where published_at is null", Long.class));
    metrics.gauge(
        "notifyflow.outbox.oldest.age",
        this,
        r ->
            r.db.queryForObject(
                "select coalesce(extract(epoch from (now()-min(occurred_at))),0) from outbox_events"
                    + " where published_at is null",
                Double.class));
  }

  @Scheduled(
      fixedDelayString = "${notifyflow.relay-delay-ms:1000}",
      initialDelayString = "${notifyflow.relay-initial-delay-ms:1000}")
  public void poll() {
    // Each lock lives through confirm + commit; SKIP LOCKED permits independent relay instances.
    for (int i = 0; i < 20; i++) {
      Boolean found =
          tx.execute(
              status -> {
                var rows =
                    db.queryForList(
                        "select * from outbox_events where published_at is null and next_attempt_at"
                            + " <= ? order by occurred_at limit 1 for update skip locked",
                        Instant.now(clock).atOffset(ZoneOffset.UTC));
                if (rows.isEmpty()) return false;
                var row = rows.getFirst();
                try {
                  publisher.publish(
                      (String) row.get("exchange_name"),
                      (String) row.get("routing_key"),
                      (String) row.get("payload"),
                      row.get("event_id").toString(),
                      (String) row.get("category"));
                  db.update(
                      "update outbox_events set published_at=?, status='PUBLISHED',"
                          + " attempts=attempts+1 where id=?",
                      Instant.now(clock).atOffset(ZoneOffset.UTC),
                      row.get("id"));
                  metrics.counter("notifyflow.publish.success").increment();
                } catch (RuntimeException failure) {
                  int attempts = ((Number) row.get("attempts")).intValue() + 1;
                  long delay = Math.min(60, 1L << Math.min(attempts, 6));
                  db.update(
                      "update outbox_events set attempts=?, next_attempt_at=? where id=?",
                      attempts,
                      Instant.now(clock).plusSeconds(delay).atOffset(ZoneOffset.UTC),
                      row.get("id"));
                  metrics.counter("notifyflow.publish.failure").increment();
                  log.warn(
                      "event=publication_deferred eventId={} category=BROKER_UNAVAILABLE",
                      row.get("event_id"));
                }
                return true;
              });
      if (!Boolean.TRUE.equals(found)) return;
    }
  }
}
