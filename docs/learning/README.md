# Reading the real implementation

Read in this order:

1. `NotificationRequest` and `NotificationEvent`: immutable API/event contracts and versioned envelope.
2. API `V1__intake.sql`, then `IntakeService.accept`: PostgreSQL key arbitration and deferred FK inside the intake transaction.
3. `ConfirmedPublisher` and `OutboxRelay.poll`: positive confirm is insufficient if mandatory return occurred; lock survives confirm/commit.
4. `Topology`: durable quorum queues, three TTL retry levels, safe dead-letter transfer.
5. `DeliveryConsumer.consume`: contract rejection, manual ACK and physical channel closure.
6. `DeliveryService.process`: durable identity, locked state/generation, bounded provider request and transactional dispatch intent.
7. `ProviderController.send`: separate transaction owning a unique logical effect, then delayed response for ambiguity tests.
8. `FailureIT` and `scripts/failure-smoke.ps1`: compare in-process rollback/channel recovery with actual JVM termination.

Run a single failure case with `./mvnw -pl notifyflow-tests -am -Dit.test=FailureIT#retryExhaustionIsBoundedAndEndsInDlq -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`. Use the full `clean verify` before proposing changes. Consult the failure model to distinguish proven behaviors from residual risks.
