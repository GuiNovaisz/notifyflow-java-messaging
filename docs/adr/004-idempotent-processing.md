# ADR 004 — At-least-once with database-backed deduplication

Status: accepted.

Context: confirms/ACKs can be lost, messages redelivered and relay transactions rolled back after a broker accepted publication.

Decision: unique intake key arbitrates concurrent HTTP requests; worker unique event identity and row lock arbitrate consumers; retry generation rejects stale copies. Duplicate work is observable. Never claim exactly-once transport.

Alternatives: in-memory dedup fails on restart; checking existence before insert races; a distributed lock without durable processing state does not solve replay.

Consequences/tradeoffs: effectively-once logical effect depends on provider idempotency too. Identity records grow and must outlive possible replay. No global ordering. Long-running provider calls occupy database connections, bounded by configured timeout and consumer concurrency.
