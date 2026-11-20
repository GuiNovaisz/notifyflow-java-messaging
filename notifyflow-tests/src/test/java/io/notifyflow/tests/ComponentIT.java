package io.notifyflow.tests;

import static org.assertj.core.api.Assertions.*;

import io.notifyflow.contracts.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComponentIT extends Fixture {
  @Test
  void acceptsAndQueriesNotification() throws Exception {
    UUID id = accept("SUCCESS");
    var response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port(api) + "/api/v1/notifications/" + id))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("ACCEPTED");
    assertThat(count(adb, "outbox_events")).isEqualTo(1);
  }

  @Test
  void sameHttpKeyIsIdempotent() throws Exception {
    var first = post("same", request("SUCCESS"));
    var second = post("same", request("SUCCESS"));
    assertThat(first.body()).isEqualTo(second.body());
    assertThat(count(adb, "notifications")).isEqualTo(1);
    assertThat(count(adb, "outbox_events")).isEqualTo(1);
  }

  @Test
  void conflictingPayloadReturnsProblemDetails() throws Exception {
    post("same", request("SUCCESS"));
    var response = post("same", request("different"));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.headers().firstValue("content-type").orElse(""))
        .contains("application/problem+json");
    assertThat(response.body()).doesNotContain("SQL", "password");
  }

  @Test
  void validatesInput() throws Exception {
    assertThat(post("invalid", new NotificationRequest("bad", "SMS", "", "x")).statusCode())
        .isEqualTo(400);
    assertThat(post("", request("SUCCESS")).statusCode()).isEqualTo(400);
    assertThat(count(adb, "notifications")).isZero();
    assertThat(
            post(
                    "too-large",
                    new NotificationRequest(
                        "a@example.test", "EMAIL", "subject", "x".repeat(70000)))
                .statusCode())
        .isEqualTo(413);
  }

  @Test
  void concurrentHttpKeyCreatesOneLogicalOperation() throws Exception {
    assertThat(concurrent(() -> post("racing", request("SUCCESS"))))
        .allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
    assertThat(count(adb, "notifications")).isEqualTo(1);
    assertThat(count(adb, "intake_keys")).isEqualTo(1);
    assertThat(count(adb, "outbox_events")).isEqualTo(1);
  }

  @Test
  void providerConcurrentIdempotencyAndConflict() throws Exception {
    var payload = new ProviderRequest(UUID.randomUUID(), request("SUCCESS"));
    var results = concurrent(() -> call(provider, "/deliveries", "provider-key", payload));
    assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    assertThat(results.stream().map(HttpResponse::body).distinct()).hasSize(1);
    assertThat(count(pdb, "provider_effects")).isEqualTo(1);
    assertThat(
            call(
                    provider,
                    "/deliveries",
                    "provider-key",
                    new ProviderRequest(UUID.randomUUID(), request("other")))
                .statusCode())
        .isEqualTo(409);
  }
}
