# ADR 002 — RabbitMQ for worker queues

Status: accepted.

Context: NotifyFlow models asynchronous work assigned to workers, not a long-lived event stream.

Decision: use RabbitMQ durable quorum queues, manual acknowledgements, publisher confirms, routing and safe dead lettering. Declare topology in Java.

Alternatives: Kafka offers partitioned event logs, replay and stream fan-out, but would require different retry/consumer bookkeeping for this workload. Database-only polling would not demonstrate broker acknowledgements/redelivery.

Consequences/tradeoffs: queue semantics align with the failure proofs. RabbitMQ is not claimed to be better than Kafka. Kafka may be evaluated when event-stream retention, replay or independent fan-out becomes a real requirement. Compose has one broker node and does not demonstrate replicated availability.
