# ADR 006 — Stable provider key across crash windows

Status: accepted.

Context: provider HTTP effects and worker PostgreSQL commit cannot participate in one local transaction. A timeout can hide a successful provider effect.

Decision: deliveryId equals stable eventId and is the provider Idempotency-Key. Provider serializes a primary-key request row and inserts one unique effect. Same key/payload returns the same effect identity; different payload returns conflict. Deterministic scenarios are disabled by default.

Alternatives: changing the key per retry creates duplicate effects; marking processed before HTTP can lose work; marking after HTTP without provider dedup leaves a crash gap.

Consequences/tradeoffs: multiple physical requests are allowed, one logical effect is guaranteed by this simulator's database. Real providers must offer equivalent key semantics/retention. Timeout exhaustion can end in DLQ even when effect occurred; operators must reconcile before redrive.
