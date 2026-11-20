# Failure model and invariants

The guarantee is **at-least-once message delivery + idempotent processing**, with an **effectively-once logical provider effect** when the provider honors its stable idempotency key. Physical HTTP calls and RabbitMQ publications can repeat. Permanent loss of database/broker volumes and a dishonest provider are outside the guarantee.

| Failure | Expected behavior | Evidence/test | Residual risk |
|---|---|---|---|
| API crash before commit / transaction rollback | No partial intake/key/outbox | `IntegrationIT.intakeTransactionRollbackHasNoPartialRows` | Client must retry an ambiguous response using the same key |
| DB failure | Intake fails rather than falsely accepts; committed data survives process restart | Transaction rollback test; Flyway fresh-start | Storage durability/backup outside milestone |
| Broker outage | API remains able to commit; pending outbox retries after recovery | `FailureIT.brokerOutageRetainsCommittedIntakeAndRecovers`; failure smoke | Single-node broker availability, backlog growth |
| Publisher failure / unroutable | Pending state and deferred next poll | `IntegrationIT.unroutableConfirmDoesNotMarkPublished` | Missing binding requires operator repair |
| Relay crash after confirm | Possible republish; worker deduplicates | Duplicate-message proof exercises the replay consequence | Not a separate process-kill test of the relay |
| Worker offline | Durable queue backlog drains on return | `FailureIT.workerOutagePreservesBacklog`; failure smoke | Volume loss excluded |
| Worker crash after provider | Redelivery, repeated same key, one provider effect | `FailureIT.crashAfterProviderBeforeCommitCausesRedeliveryWithoutSecondEffect`; actual `halt(91)` in failure smoke | Provider must retain keys as long as replays remain possible |
| Provider timeout | Retry bounded; ambiguous completed effect deduplicated | `FailureIT.timeoutCanHaveAnEffectButNoDuplicateEffect` | Delivery can be terminal although provider delivered; reconcile before redrive |
| Provider 500 | Retry TTL queues, eventual success or exhaustion | `FailureIT.transientFailuresRetryThenSucceed`, `retryExhaustionIsBoundedAndEndsInDlq` | No jitter in milestone; correlated outage can synchronize retries |
| Provider 400 | One attempt then terminal dispatch | `FailureIT.permanentFailureGoesDirectlyToInspectableDlq` | Operators must diagnose payload/provider contract |
| Duplicate HTTP intake | Same identity; changed payload 409 | `ComponentIT.sameHttpKeyIsIdempotent`, `conflictingPayloadReturnsProblemDetails` | Keys retained indefinitely; no tenancy/auth scope |
| Concurrent duplicate HTTP | One committed operation and event | `ComponentIT.concurrentHttpKeyCreatesOneLogicalOperation` (16 calls) | Pool saturation returns errors; clients retry same key |
| Duplicate delivery | One committed attempt/effect | `FailureIT.duplicateMessageCreatesOneEffect` | Malicious event forgery excluded by internal-only broker access |
| Duplicate provider request | One effect, same result; changed payload 409 | `ComponentIT.providerConcurrentIdempotencyAndConflict` (16 calls) | Real provider integration must prove equivalent behavior |
| Poison message | Invalid JSON/version/missing fields/semantics quarantined | `FailureIT.poisonMessagesAreQuarantinedWithoutRetryLoop` | Poison may lack business IDs; inspect bytes carefully |
| Retry exhaustion / DLQ | Four attempts, inspectable durable terminal message | `FailureIT.retryExhaustionIsBoundedAndEndsInDlq` | No automatic redrive/retention policy |
| Multiple worker instances | Distributed work, no logical duplicate | `FailureIT.twoWorkersDistributeWorkAndDeduplicateDeliberateDuplicates` (24 notifications + duplicate) | No global ordering |
| Restart/recovery | Processes reconnect; migrations rerun safely; crash identity survives | Compose smoke and process crash proof | No full host-power-loss simulation |

## Acceptance invariants

1. Accepted intake has one initial outbox event (notification FK + unique eventId + atomic transaction; tests assert counts).
2. Rollback leaves no notification/key/event.
3. Broker outage retains committed outbox and recovers.
4. Redelivery creates no second logical effect.
5. Retry is bounded at four total committed attempts.
6. Permanent provider failure takes one attempt.
7. Exhausted/poison messages remain in DLQ.
8. Crash after provider commit reuses the same provider key.
9. Outbox publication requires positive confirm and no return.
10. Concurrent same-key intake creates one operation.

The worker delivery decision and retry/terminal outbox are atomic. The terminal state is not evidence of completed broker transfer until `published_at` is set. A timeout is not proof that an external action did not occur. This distinction is intentionally visible in the timeout proof (one provider effect, four timed-out requests, terminal delivery state).
