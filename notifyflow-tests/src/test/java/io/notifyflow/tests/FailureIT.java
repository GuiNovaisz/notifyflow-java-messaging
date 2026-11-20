package io.notifyflow.tests;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.notifyflow.messaging.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class FailureIT extends Fixture {
  @Test
  void brokerOutageRetainsCommittedIntakeAndRecovers() throws Exception {
    listeners(true);
    broker.execInContainer("rabbitmqctl", "stop_app");
    UUID id;
    try {
      id = accept("SUCCESS");
      pump();
      assertThat(count(adb, "notifications")).isEqualTo(1);
      assertThat(adb.queryForObject("select status from outbox_events", String.class))
          .isEqualTo("PENDING");
    } finally {
      assertThat(broker.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
    }
    delivered(id);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
  }

  @Test
  void workerOutagePreservesBacklog() throws Exception {
    UUID id = accept("SUCCESS");
    pump();
    assertThat(count(pdb, "provider_effects")).isZero();
    listeners(true);
    delivered(id);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
  }

  @Test
  void duplicateMessageCreatesOneEffect() throws Exception {
    double before =
        worker
            .getBean(io.micrometer.core.instrument.MeterRegistry.class)
            .counter("notifyflow.consumer.duplicate")
            .count();
    UUID id = accept("SUCCESS");
    pump();
    String payload = adb.queryForObject("select payload from outbox_events", String.class);
    api.getBean(ConfirmedPublisher.class)
        .publish(Topology.EXCHANGE, Topology.KEY, payload, "duplicate", "TEST");
    listeners(true);
    delivered(id);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        worker
                            .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .counter("notifyflow.consumer.duplicate")
                            .count())
                    .isGreaterThan(before));
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(1);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
  }

  @Test
  void transientFailuresRetryThenSucceed() throws Exception {
    listeners(true);
    UUID id = accept("FAIL_TWICE");
    delivered(id);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(3);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
    assertThat(
            wdb.queryForList(
                "select provider_status from delivery_attempts order by attempt_number",
                Integer.class))
        .containsExactly(500, 500, 200);
  }

  @Test
  void permanentFailureGoesDirectlyToInspectableDlq() throws Exception {
    listeners(true);
    UUID id = accept("PERMANENT");
    dead(id);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(1);
    var rabbit = api.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
    var message = rabbit.receive(Topology.DLQ);
    assertThat(message).isNotNull();
    assertThat(message.getMessageProperties().getHeaders().get("failureCategory"))
        .isEqualTo("PROVIDER_PERMANENT");
    assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
        .contains(id.toString());
  }

  @Test
  void retryExhaustionIsBoundedAndEndsInDlq() throws Exception {
    listeners(true);
    UUID id = accept("ALWAYS_FAIL");
    dead(id);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(4);
    assertThat(count(pdb, "provider_effects")).isZero();
    assertThat(pdb.queryForObject("select calls from provider_requests", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void timeoutCanHaveAnEffectButNoDuplicateEffect() throws Exception {
    listeners(true);
    UUID id = accept("TIMEOUT");
    dead(id);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(4);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
    assertThat(pdb.queryForObject("select calls from provider_requests", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void crashAfterProviderBeforeCommitCausesRedeliveryWithoutSecondEffect() throws Exception {
    double before =
        worker
            .getBean(io.micrometer.core.instrument.MeterRegistry.class)
            .counter("notifyflow.consumer.redelivered")
            .count();
    crash.set(true);
    listeners(true);
    UUID id = accept("CRASH");
    delivered(id);
    assertThat(crash.get()).isFalse();
    assertThat(pdb.queryForObject("select calls from provider_requests", Integer.class))
        .isEqualTo(2);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(1);
    assertThat(
            worker
                .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .counter("notifyflow.consumer.redelivered")
                .count())
        .isGreaterThan(before);
  }

  @Test
  void poisonMessagesAreQuarantinedWithoutRetryLoop() throws Exception {
    var id = accept("SUCCESS");
    String valid = adb.queryForObject("select payload from outbox_events", String.class);
    var publisher = api.getBean(ConfirmedPublisher.class);
    for (String poison :
        List.of(
            "{invalid",
            valid.replace("\"eventVersion\":1", "\"eventVersion\":99"),
            "{}",
            valid.replace("EMAIL", "SMS")))
      publisher.publish(Topology.EXCHANGE, Topology.KEY, poison, "poison", "TEST");
    listeners(true);
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(dlqCount()).isEqualTo(4));
    assertThat(count(pdb, "provider_effects")).isZero();
    assertThat(count(wdb, "delivery_attempts")).isZero();
  }

  @Test
  void twoWorkersDistributeWorkAndDeduplicateDeliberateDuplicates() throws Exception {
    double before =
        worker
            .getBean(io.micrometer.core.instrument.MeterRegistry.class)
            .counter("notifyflow.consumer.processed")
            .count();
    try (var second = start(io.notifyflow.worker.WorkerApplication.class, "worker", null)) {
      second
          .getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class)
          .start();
      listeners(true);
      var ids = new ArrayList<UUID>();
      for (int i = 0; i < 24; i++) ids.add(accept("SUCCESS"));
      pump();
      String payload =
          adb.queryForObject("select payload from outbox_events limit 1", String.class);
      api.getBean(ConfirmedPublisher.class)
          .publish(Topology.EXCHANGE, Topology.KEY, payload, "duplicate", "TEST");
      await()
          .atMost(Duration.ofSeconds(40))
          .untilAsserted(
              () -> {
                pump();
                assertThat(
                        wdb.queryForObject(
                            "select count(*) from deliveries where status='DELIVERED'",
                            Integer.class))
                    .isEqualTo(24);
              });
      assertThat(count(pdb, "provider_effects")).isEqualTo(24);
      assertThat(count(wdb, "delivery_attempts")).isEqualTo(24);
      assertThat(
              worker
                  .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                  .counter("notifyflow.consumer.processed")
                  .count())
          .isGreaterThan(before);
      assertThat(
              second
                  .getBean(io.micrometer.core.instrument.MeterRegistry.class)
                  .counter("notifyflow.consumer.processed")
                  .count())
          .isGreaterThan(0);
    }
  }
}
