# Interview guide: explain using code and evidence

- Show the exact transaction covering intake key, notification and initial outbox. Why does concurrent `ON CONFLICT` wait instead of creating a second operation?
- Walk through `OutboxRelay.poll` when the broker accepted the message but the process died before the database update committed.
- Explain why mandatory return must be checked even with a positive publisher confirm. Point to the unroutable integration test.
- Compare a RabbitMQ ACK with a PostgreSQL commit and a provider HTTP 200. Which one proves what?
- Demonstrate the after-provider crash test. Why are two physical provider calls expected, but one logical effect required?
- Explain the worker's lock duration and connection-pool tradeoff. How would increased provider latency affect capacity?
- Trace retry generation and how duplicated TTL messages fail to consume additional logical attempts.
- Explain why timeout exhaustion can coexist with a completed provider effect, and why automatic DLQ redrive is unsafe.
- Show poison rejection preserving bytes and x-death. How does this differ from permanent provider failure?
- Compare RabbitMQ work queues with Kafka event logs without claiming either universally superior.
- Explain why notification acceptance status is separate from delivery and outbox publication status.
- Identify limitations honestly: no global ordering/authentication/retention/replicated broker guarantee, and no production-readiness claim.

Use the actual test name, SQL constraint and failure window in answers. Do not substitute architecture buzzwords for evidence.
