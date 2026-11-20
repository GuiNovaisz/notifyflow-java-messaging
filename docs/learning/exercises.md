# Exercises (no supplied solutions)

1. Add a test for concurrent same-key intake where half the calls use a conflicting payload. Define allowed responses and assert one persisted operation.
2. Stop the broker after provider failure but before worker retry relay publication. Prove the retry intent remains durable, then recover it.
3. Inject relay termination after confirm but before publication commit. Demonstrate duplicate publication and one effect without modifying dedup data.
4. Add an unknown top-level field to a v1 event. Observe current strict compatibility behavior, then propose and justify a migration strategy.
5. Measure provider latency's effect on occupied worker DB connections using bounded non-stress experiments. Document the machine, inputs and limitations.
6. Design a safe retention policy that considers client replay, broker retention, DLQ retention and provider key retention together. Do not implement deletion first.
7. Simulate a provider that ignores idempotency. Show the exact guarantee that fails and propose an honest API/operations response.
8. Propose an explicit manual DLQ reconciliation/redrive protocol for ambiguous timeouts. Identify necessary operator evidence and audit records before coding.
9. Add a worker readiness signal for consumer connectivity while preserving liveness semantics. Explain how broker outage should affect intake API readiness.
10. Add one new channel without sharing business tables between worker deployables. Evaluate whether a separate deployable is actually justified.
