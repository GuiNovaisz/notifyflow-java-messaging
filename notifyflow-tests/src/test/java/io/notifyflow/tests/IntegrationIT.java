package io.notifyflow.tests;

import static org.assertj.core.api.Assertions.*;

import io.notifyflow.messaging.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationIT extends Fixture {
  @Test
  void intakeTransactionRollbackHasNoPartialRows() {
    var tx = api.getBean(TransactionTemplate.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      api.getBean(io.notifyflow.api.IntakeService.class)
                          .accept("rollback", request("SUCCESS"), "test");
                      throw new IllegalStateException("Injected rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(adb, "notifications")).isZero();
    assertThat(count(adb, "outbox_events")).isZero();
    assertThat(count(adb, "intake_keys")).isZero();
  }

  @Test
  void validConfirmMarksOutboxPublishedAndRoutesDurably() throws Exception {
    accept("SUCCESS");
    pump();
    assertThat(adb.queryForObject("select status from outbox_events", String.class))
        .isEqualTo("PUBLISHED");
    var admin =
        new org.springframework.amqp.rabbit.core.RabbitAdmin(
            api.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));
    assertThat(admin.getQueueInfo(Topology.QUEUE).getMessageCount()).isEqualTo(1);
    try (var connection =
            api.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
                .createConnection();
        var channel = connection.createChannel(false)) {
      var message = channel.basicGet(Topology.QUEUE, true);
      assertThat(message.getProps().getDeliveryMode()).isEqualTo(2);
      assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("eventVersion");
    }
  }

  @Test
  void unroutableConfirmDoesNotMarkPublished() throws Exception {
    accept("SUCCESS");
    adb.update("update outbox_events set routing_key='unroutable'");
    pump();
    assertThat(adb.queryForObject("select status from outbox_events", String.class))
        .isEqualTo("PENDING");
    assertThat(adb.queryForObject("select attempts from outbox_events", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void topologyIsDurableAndRetryHasSafeDeadLettering() throws Exception {
    String credentials =
        Base64.getEncoder()
            .encodeToString(
                (broker.getAdminUsername() + ":" + broker.getAdminPassword())
                    .getBytes(StandardCharsets.UTF_8));
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://"
                        + broker.getHost()
                        + ":"
                        + broker.getHttpPort()
                        + "/api/queues/%2F/"
                        + Topology.QUEUE))
            .header("Authorization", "Basic " + credentials)
            .build();
    var info =
        json.readTree(
            http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
    assertThat(info.get("durable").asBoolean()).isTrue();
    assertThat(info.get("arguments").get("x-queue-type").asText()).isEqualTo("quorum");
    assertThat(info.get("arguments").get("x-dead-letter-strategy").asText())
        .isEqualTo("at-least-once");
  }

  @Test
  void successfulDeliveryPersistsOneAttemptAndEffect() throws Exception {
    listeners(true);
    UUID id = accept("SUCCESS");
    delivered(id);
    assertThat(count(wdb, "delivery_attempts")).isEqualTo(1);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
  }
}
