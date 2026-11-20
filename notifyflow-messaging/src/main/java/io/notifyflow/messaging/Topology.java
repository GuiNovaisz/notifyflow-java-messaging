package io.notifyflow.messaging;

import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class Topology {
    public static final String EXCHANGE = "notifyflow.notification.v1";
    public static final String KEY = "notification.requested.v1";
    public static final String QUEUE = "notifyflow.delivery.email.v1";
    public static final String RETRY = "notifyflow.retry.v1";
    public static final String DEAD = "notifyflow.dead.v1";
    public static final String DLQ = "notifyflow.delivery.email.dlq.v1";
    @Bean Declarables topology(@Value("${notifyflow.retry-base-ms:5000}") long base) {
        var main = new DirectExchange(EXCHANGE, true, false);
        var retry = new DirectExchange(RETRY, true, false);
        var dead = new DirectExchange(DEAD, true, false);
        var queue = safeQueue(QUEUE, DEAD, "dead").build();
        var dlq = QueueBuilder.durable(DLQ).quorum().build();
        List<Declarable> all = new ArrayList<>(List.of(main, retry, dead, queue, dlq,
                BindingBuilder.bind(queue).to(main).with(KEY), BindingBuilder.bind(dlq).to(dead).with("dead")));
        for (int i = 1; i <= 3; i++) {
            var q = safeQueue("notifyflow.delivery.email.retry." + i + ".v1", EXCHANGE, KEY)
                    .ttl(Math.toIntExact(base * (1L << (i - 1)))).build();
            all.add(q); all.add(BindingBuilder.bind(q).to(retry).with("retry." + i));
        }
        return new Declarables(all);
    }
    private QueueBuilder safeQueue(String name, String exchange, String key) {
        return QueueBuilder.durable(name).quorum().deadLetterExchange(exchange).deadLetterRoutingKey(key)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish");
    }
}
