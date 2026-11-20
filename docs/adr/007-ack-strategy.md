# ADR 007 — ACK after durable local decision

Status: accepted.

Context: automatic acknowledgement before business persistence risks loss; publishing retries without durable intent risks a second dual-write gap.

Decision: manual ACK after worker transaction completes, including retry/terminal outbox when needed. Poison is rejected to safe quorum DLX. Infrastructure/bookkeeping exceptions force physical consumer-channel closure so RabbitMQ redelivers unacked work and the container recovers with its 3-second interval.

Alternatives: ACK on receive loses work; ACK only after immediate retry publish complicates atomicity; immediate NACK requeue can hot-loop during dependency outage. A cached channel's ordinary close may not close the actual channel, so physical close is explicitly required.

Consequences/tradeoffs: recovery may repeat provider calls; stable provider key prevents duplicate logical effect. Attempt history describes committed decisions, not all physical calls. Runtime crash proof terminates the actual JVM after provider success and before transaction commit/ACK.
