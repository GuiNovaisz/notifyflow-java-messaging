package io.notifyflow.worker;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.Channel;
import io.notifyflow.contracts.NotificationEvent;
import io.notifyflow.messaging.Topology;
import jakarta.validation.Validator;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConsumer {
  private final ObjectMapper json;
  private final Validator validator;
  private final DeliveryService delivery;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public DeliveryConsumer(
      ObjectMapper json,
      Validator validator,
      DeliveryService delivery,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.json = json;
    this.validator = validator;
    this.delivery = delivery;
    this.metrics = metrics;
  }

  @RabbitListener(
      queues = Topology.QUEUE,
      ackMode = "MANUAL",
      concurrency = "${notifyflow.consumer-concurrency:2}")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    if (message.getMessageProperties().isRedelivered())
      metrics.counter("notifyflow.consumer.redelivered").increment();
    NotificationEvent event;
    try {
      if (message.getBody().length > 65536) throw new IllegalArgumentException();
      event =
          json.readerFor(NotificationEvent.class)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(message.getBody());
      if (event.eventId() == null
          || event.notificationId() == null
          || event.occurredAt() == null
          || event.notification() == null
          || event.eventVersion() != 1
          || !NotificationEvent.TYPE.equals(event.eventType())
          || event.generation() < 0
          || event.generation() >= RetryPolicy.MAX_ATTEMPTS
          || event.correlationId() == null
          || event.correlationId().length() > 128
          || !validator.validate(event.notification()).isEmpty())
        throw new IllegalArgumentException();
    } catch (Exception poison) {
      // Quorum main queue forwards rejection to DLQ with at-least-once DLX; original bytes
      // retained.
      channel.basicReject(tag, false);
      metrics.counter("notifyflow.poison.count").increment();
      metrics.counter("notifyflow.dlq.count").increment();
      return;
    }
    try {
      delivery.process(event);
      channel.basicAck(tag, false);
    } catch (RuntimeException infrastructure) {
      // Close this consumer channel: unacked delivery survives. Container recovery interval
      // prevents a hot requeue loop.
      try {
        org.springframework.amqp.rabbit.connection.RabbitUtils.setPhysicalCloseRequired(
            channel, true);
        channel.close();
      } catch (Exception closeFailure) {
        throw new IOException(closeFailure);
      }
    }
  }
}
