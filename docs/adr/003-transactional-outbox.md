# ADR 003 — Outbox at both publication boundaries

Status: accepted.

Context: PostgreSQL commit and RabbitMQ publish are not atomic. The same gap exists when worker schedules retries or DLQ dispatches.

Decision: intake plus initial event are one database transaction. Worker attempt/state plus retry/terminal dispatch are another local transaction. A common relay implementation operates each database's own transport table. Locks use `SKIP LOCKED`; publication is complete only after mandatory confirm without return.

Alternatives: direct publish after DB commit loses events on crash; publish before commit produces orphan work; XA adds complexity; CDC/Debezium exceeds scope.

Consequences/tradeoffs: no silent dual-write gap, but polls have latency and tables require retention planning. Locks are held through bounded confirm wait. Crash after confirm can duplicate publication, so consumer deduplication remains mandatory.
