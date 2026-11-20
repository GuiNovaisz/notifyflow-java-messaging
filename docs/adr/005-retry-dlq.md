# ADR 005 — Bounded exponential TTL queues and durable dispatch

Status: accepted.

Context: transient failures need delayed retry; permanent/poison failures must not loop. Broker transfer must remain safe if it fails after worker bookkeeping.

Decision: four total attempts; transient HTTP 5xx/408/429 or connection/timeout selects 5/10/20-second TTL queues. Worker commits a dispatch outbox before ACK. Main/retry queues are quorum with at-least-once DLX and reject-publish overflow. Terminal outbox publishes to DLQ. Poison rejection uses the safe main DLX.

Alternatives: sleeping in consumer blocks capacity; immediate requeue hot-loops; classic DLX can lose messages during transfer; plugins or database-only retry scheduling add another mechanism.

Consequences/tradeoffs: retry timing is a lower-bound operational delay, not an exact schedule. Three queues are explicit and bounded; changing TTL arguments requires migration. No jitter or redrive automation. Terminal delivery state and confirmed DLQ publication are distinct observable stages.
