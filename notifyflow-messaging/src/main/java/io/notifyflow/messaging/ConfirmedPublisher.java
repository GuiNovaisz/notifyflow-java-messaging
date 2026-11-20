package io.notifyflow.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
    private final RabbitTemplate rabbit;
    public ConfirmedPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; rabbit.setMandatory(true); }
    public void publish(String exchange, String key, String payload, String eventId, String category) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        properties.setHeader("failureCategory", category);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(exchange, key, new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
        try {
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Publication not confirmed or unroutable");
        } catch (Exception e) { throw new IllegalStateException("Publication failed", e); }
    }
}
