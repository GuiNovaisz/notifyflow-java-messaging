package io.notifyflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
  private final RabbitTemplate rabbit;
  private final ObjectMapper json;
  private final Clock clock;

  public ConfirmedPublisher(RabbitTemplate rabbit, ObjectMapper json, Clock clock) {
    this.rabbit = rabbit;
    this.json = json;
    this.clock = clock;
    rabbit.setMandatory(true);
  }

  public void publish(
      String exchange, String key, String payload, String eventId, String category) {
    var properties = new MessageProperties();
    properties.setContentType("application/json");
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(eventId);
    properties.setHeader("failureCategory", category);
    properties.setHeader("eventId", eventId);
    properties.setTimestamp(java.util.Date.from(clock.instant()));
    try {
      var envelope = json.readTree(payload);
      if (envelope.hasNonNull("notificationId"))
        properties.setHeader("notificationId", envelope.get("notificationId").asText());
      if (envelope.hasNonNull("correlationId"))
        properties.setCorrelationId(envelope.get("correlationId").asText());
      if (envelope.has("generation"))
        properties.setHeader("attemptNumber", envelope.get("generation").asInt() + 1);
    } catch (Exception incompatible) {
      /* Poison test publication preserves raw bytes. */
    }
    var correlation = new CorrelationData(UUID.randomUUID().toString());
    rabbit.send(
        exchange,
        key,
        new Message(payload.getBytes(StandardCharsets.UTF_8), properties),
        correlation);
    try {
      var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirm.isAck() || correlation.getReturned() != null)
        throw new IllegalStateException("Publication not confirmed or unroutable");
    } catch (Exception e) {
      throw new IllegalStateException("Publication failed", e);
    }
  }
}
