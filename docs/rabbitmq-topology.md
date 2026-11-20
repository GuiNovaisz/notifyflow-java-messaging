# RabbitMQ topology and ACK policy

All topology is declared by `Topology.java`; nothing is configured manually in Management.

| Element | Name / routing | Properties |
|---|---|---|
| Main direct exchange | `notifyflow.notification.v1` | Durable |
| Main queue | `notifyflow.delivery.email.v1` | Durable quorum, prefetch 1 |
| Main binding | `notification.requested.v1` | Exact direct route |
| Retry direct exchange | `notifyflow.retry.v1` | Durable |
| Retry queues | `notifyflow.delivery.email.retry.{1,2,3}.v1` | Durable quorum, TTL 5/10/20 seconds by default |
| Retry bindings | `retry.1`, `retry.2`, `retry.3` | Each to its corresponding TTL queue |
| Retry dead-letter route | Main exchange / `notification.requested.v1` | Safe at-least-once DLX |
| Terminal direct exchange | `notifyflow.dead.v1` | Durable |
| Terminal queue | `notifyflow.delivery.email.dlq.v1` | Durable quorum |
| Terminal binding | `dead` | Receives terminal dispatch and poison rejection |

Main and retry queues set `x-dead-letter-strategy=at-least-once` and `x-overflow=reject-publish`. Safe quorum dead lettering retains the source until confirmed transfer; ordinary classic queue DLX would leave a loss window. Single-node Compose does not survive loss of its data volume. Quorum replication requires multiple nodes, outside this milestone.

Persistent delivery mode 2 + durable queues + mandatory publication + correlated publisher confirms are required together. A positive confirm with a mandatory return is still failure. `ConfirmedPublisher` waits up to five seconds and checks both. Relay failures never mark publication complete.

## Retry and terminal flow

Initial attempt plus at most three retries; completed attempts 1/2/3 select retry queues 1/2/3. `notifyflow.retry-base-ms` configures exponential TTLs. Do not change TTL arguments against existing queues without a topology migration: RabbitMQ rejects inequivalent declarations. Tests use 100ms and failure Compose uses 300ms on fresh queues.

No consumer sleeps for retry. The worker's own transactional outbox transfers retries and terminal messages after committing its database decision. A crash after dispatch confirm and before marking dispatch published can create duplicate retry messages. Delivery generation and row lock prevent a stale retry from consuming another attempt. Broker dead-letter delivery limit is a separate safety mechanism, not the provider retry policy.

| Condition | Consumer action | Durable protection |
|---|---|---|
| Success | ACK after transaction returns | Delivery + attempt committed |
| Duplicate/stale generation | ACK after locked check | Unique eventId + existing state |
| Transient provider error | ACK after retry outbox commit | Retry survives broker outage |
| Permanent/exhausted | ACK after terminal outbox commit | DLQ dispatch survives broker outage |
| Poison contract | Reject, requeue=false | Main quorum safe DLX retains original body, x-death reason |
| DB/bookkeeping failure | Physically close channel | Unacked message redelivers; listener recovery interval 3s |
| Worker process dies | No ACK | Broker requeues on lost connection |

Terminal dispatch preserves event/notification IDs, correlation, original occurrence time and generation; failureCategory is an AMQP header. The worker attempts API holds categorized outcomes/status/attempt count and timestamps. Poison records may have no parseable IDs; original bytes, messageId and `x-death` are the evidence. Never print message bodies indiscriminately: they contain recipient/content.

Global ordering is not guaranteed. Multiple consumers and retries deliberately permit reordering. No per-recipient ordering is promised.
