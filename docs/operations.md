# Operations

## Start and inspect

Copy `.env.example` to `.env`, replace both placeholders with distinct random values; never commit `.env`. Run `docker compose up -d --build --wait`. Only API (loopback 8080) and Management (loopback 15672) are published. Provider, worker, PostgreSQL and AMQP have no host ports. This is a local trusted-user stack; API authentication is deliberately absent. Do not expose it to an untrusted network without an authenticated boundary and rate controls.

`GET /actuator/health/liveness` only includes application liveness. Readiness includes DB, enabling API intake during broker outage. RabbitMQ health remains in aggregate `/actuator/health`; inspect it independently. Worker readiness means its HTTP/database plane is ready, not that delivery is flowing. Watch broker health and delivery/outbox metrics together. Internal services have their own probes. No dependency outage alone kills liveness.

`GET /v3/api-docs` and `/swagger-ui/index.html` describe the small API. Public operations:

- `POST /api/v1/notifications`, required `Idempotency-Key`, returns 202 + identity + Location + correlation header.
- `GET /api/v1/notifications/{id}` reads immutable acceptance status.
- `GET /api/v1/notifications/{id}/deliveries` reads worker result (at most one).
- `GET /api/v1/notifications/{id}/attempts` reads at most four committed attempts.

Provider/worker endpoints are internal network APIs. Attempts include outcome, category, provider status and next retry time. Recipient and content are not logged. Errors use Problem Details and do not expose SQL/stack traces. No full request-payload logging is configured.

## Useful queries and commands

```powershell
docker compose ps
docker compose logs --tail 100 api worker
docker compose exec -T api-db psql -U notifyflow -d notifyflow -c "select count(*),min(occurred_at) from outbox_events where published_at is null"
docker compose exec -T worker-db psql -U notifyflow -d notifyflow -c "select status,count(*) from deliveries group by status"
docker compose exec -T worker-db psql -U notifyflow -d notifyflow -c "select category,count(*) from outbox_events where published_at is null group by category"
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

Use RabbitMQ Management for main/retry/DLQ queue counts, bindings and safe individual message inspection. Consuming a DLQ message changes broker state; prefer Management's requeue inspection mode. Do not paste notification bodies into tickets/logs.

## Metrics and logs

API and worker expose Prometheus at `/actuator/prometheus`; worker is internal, reachable with `docker compose exec worker wget -qO- http://localhost:8080/actuator/prometheus`. Micrometer names use dots in code and underscores in Prometheus. Relevant names: `notifyflow.outbox.pending`, `notifyflow.outbox.oldest.age` (seconds), `notifyflow.publish.success/failure`, `notifyflow.consumer.processed/duplicate`, `notifyflow.retry.count`, `notifyflow.dlq.count`, `notifyflow.delivery.success/failure`, `notifyflow.processing.duration`. Counters reflect process lifetime and reset on restart; durable rows are authoritative. Outbox gauges query indexed pending rows; no alert thresholds are invented.

JSON structured logs carry event, notificationId, eventId, deliveryId and correlationId in worker event messages. The intake creates a bounded UUID correlation ID; deliveryId is stable eventId. Publisher failure logs contain eventId and a safe category, never message bodies or credentials.

## Runbooks

| Symptom | Inspect | Action |
|---|---|---|
| Broker returned after outage | Broker health, pending outbox count/oldest age, publish failures | Allow capped relay backoff to elapse (up to 60s), confirm pending count drains and final deliveries progress. Do not mark rows published manually. |
| DLQ grows | `x-death`, original event version, failureCategory, attempts, provider effects | Classify poison/permanent/exhausted/ambiguous timeout. Repair cause first; reconcile provider by stable key before considering any manual replay. No automatic redrive is implemented. |
| Outbox backlog grows | Dependency health, route bindings, confirm returns, DB locks, oldest age | Restore broker/routing; confirm recoveries. Review bounded poll capacity before scaling relay. `SKIP LOCKED` permits multiple relays with duplicate-safe recovery. |
| Provider fails | Attempt categories/status, timeout vs permanent, provider calls/effects | Restore provider. Permanent failures require correcting integration/input; exhausted deliveries remain terminal. A timeout can already have delivered. |
| Worker unhealthy/offline | DB readiness, consumer count, unacked/ready queue counts | Restore DB/process; broker requeues lost-channel messages. Check logs and final attempt history; never delete dedup rows to force reprocessing. |

No retention, backup automation, DLQ redrive or cloud deployment is included. Keep idempotency records for at least as long as any message/client replay can occur; deleting them can enable duplicate effects. Plan retention and recovery together before expanding beyond local demonstration.
