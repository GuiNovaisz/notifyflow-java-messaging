# NotifyFlow — Reliable Messaging Foundation

Java 21 · Spring Boot · PostgreSQL · RabbitMQ · Transactional Outbox

**Versão de desenvolvimento: `0.1.0-dev`. Backend-first, sem frontend ou provedor pago.**

## Português

NotifyFlow demonstra processamento assíncrono confiável sob falhas. O objetivo é provar o que acontece quando banco, broker, worker ou provider falham, com infraestrutura real e invariantes verificáveis. O envio de e-mail é simulado; o problema central é evitar perda silenciosa e duplicação de efeito.

Complementa LedgerFlow, OrderSphere e SupportHub com engenharia de mensageria. Não é outro CRUD full-stack e não afirma ser production-ready.

### Arquitetura

```text
Client → API → PostgreSQL API (notification + intake key + outbox)
                         ↓ relay / mandatory publisher confirm
                      RabbitMQ → Worker → Provider Simulator
                                    ↓             ↓
                              PostgreSQL Worker  PostgreSQL Provider
                              attempts + dedup   keys + logical effects
                              retry/DLQ outbox
```

Três deployables e bancos com ownership independente. Maven multi-module: `notifyflow-api`, `notifyflow-worker`, `provider-simulator`; DTOs imutáveis em `notifyflow-contracts` e infraestrutura de transporte em `notifyflow-messaging`. Sem tabelas de negócio compartilhadas. O módulo `notifyflow-tests` contém as provas de integração.

### Garantias demonstradas

- Intake com `Idempotency-Key`: mesma chave/payload retorna a mesma identidade; payload diferente retorna 409. Constraint PostgreSQL protege a concorrência.
- Notification, chave de intake e evento inicial de outbox commitam na mesma transação; rollback não deixa estado parcial.
- Broker offline mantém o evento pendente. Relay limitado, `SKIP LOCKED`, backoff e confirms permitem recuperação.
- Outbox só fica publicado após confirm positivo **sem mandatory return**.
- Transporte at-least-once, consumidor idempotente por eventId e provider idempotente por deliveryId estável.
- Tentativa, resultado e intenção de retry/DLQ commitam antes do ACK. Duplicatas antigas não consomem outra tentativa lógica.
- Crash após efeito no provider e antes de commit/ACK gera redelivery com a mesma chave: duas chamadas físicas, um efeito lógico.
- Quatro tentativas no máximo; falha permanente vai diretamente à DLQ. Poison messages preservam os bytes na quarantine/DLQ.

Não há exactly-once transport, ordering global, autenticação pública, retenção automática, redrive automático, alta disponibilidade do broker de nó único ou garantia contra perda dos volumes. Timeout pode ter concluído o efeito externo e ainda terminar em DLQ: reconciliação é necessária antes de redrive.

### API

| Operação | Comportamento |
|---|---|
| `POST /api/v1/notifications` | 202, Location, correlation ID; header Idempotency-Key obrigatório |
| `GET /api/v1/notifications/{id}` | Fato imutável de aceitação (`ACCEPTED`) |
| `GET /api/v1/notifications/{id}/deliveries` | Estado autoritativo consultado no worker |
| `GET /api/v1/notifications/{id}/attempts` | Até quatro tentativas commitadas |

Canal único `EMAIL`. Recipient validado, subject até 200 caracteres, body até 10.000, request até 64 KiB. Erros usam Problem Details sem detalhes internos. Estado de intake, publicação e delivery são separados semanticamente. OpenAPI: `/v3/api-docs`; Swagger UI: `/swagger-ui/index.html`.

### RabbitMQ, retry e falhas

Exchange principal `notifyflow.notification.v1`, routing `notification.requested.v1`, queue `notifyflow.delivery.email.v1`. Retry exchange `notifyflow.retry.v1` encaminha para três queues TTL com 5/10/20 segundos por padrão; safe quorum DLX retorna à principal. DLQ `notifyflow.delivery.email.dlq.v1` recebe dispatch terminal pelo exchange `notifyflow.dead.v1` ou rejeição de poison pelo DLX. Exchanges/queues duráveis, mensagens persistentes, confirms, mandatory returns e manual ACK são declarados em código.

HTTP 5xx/408/429, timeout e conexão indisponível são transitórios; outros resultados não-success são permanentes. O provider pode simular sucesso, falhar duas vezes, sempre 500, 400, resposta lenta e timeout. Cenários especiais exigem configuração explícita e estão desabilitados no Compose normal.

### Executar e testar

Requisitos: JDK 21, Docker Linux engine e PowerShell 7 (Windows/Linux). Maven Wrapper baixa a distribuição com checksum.

```powershell
.\mvnw.cmd -B -ntp clean verify
Copy-Item .env.example .env
# Substitua os dois placeholders em .env por valores locais aleatórios distintos.
docker compose up -d --build --wait
.\scripts\smoke.ps1
```

Linux: `chmod +x mvnw && ./mvnw -B -ntp clean verify`. Não exponha esta API sem autenticação a uma rede não confiável. API e RabbitMQ Management são publicados apenas em loopback; bancos, AMQP, provider e worker ficam internos. Containers de aplicação executam como non-root. Segredos vêm do ambiente; `.env` é ignorado.

As 27 provas JUnit estão divididas em 6 unit, 5 integration, 6 component e 10 failure. PostgreSQL/RabbitMQ reais via Testcontainers; Awaitility e concorrência real. CI executa verify, build de imagens, Compose com volumes novos, smoke e falha de processo real. [Testing](docs/testing.md) explica reprodução e contagem. [Failure model](docs/failure-model.md) distingue evidência de risco residual.

### Observabilidade, operação e decisões

Actuator distingue liveness de readiness. Readiness da API exige banco, permitindo intake durante outage do broker; não representa conclusão de delivery. Logs JSON incluem IDs sem conteúdo/secrets. Micrometer/Prometheus expõe backlog/idade do outbox, publicação, consumo, duplicata, retries, DLQ e duração. [Operations](docs/operations.md) contém consultas e runbooks.

Leia [arquitetura e versões](docs/architecture.md), [topologia/ACK](docs/rabbitmq-topology.md), [ADRs](docs/adr), [guia de estudo](docs/learning/README.md), [entrevista](docs/learning/interview-guide.md) e [exercícios sem respostas](docs/learning/exercises.md). RabbitMQ foi escolhido para trabalho assíncrono com workers, ACK/routing/redelivery/DLQ; Kafka pode ser avaliado futuramente se streams/replay/fan-out se tornarem requisitos.

Roadmap: revisão independente e release posterior; `v0.2.0 — Operations Console` poderá acrescentar UI operacional. Retenção, reconciliação/redrive auditável e integração de provider real exigem análise própria. Nesta missão: sem merge em dev/main, tag ou release.

The complete English version follows below.

## English

NotifyFlow demonstrates reliable asynchronous processing under failure, with real infrastructure and verifiable invariants. Email is simulated: the central problem is avoiding silent loss and duplicated business effects when the database, broker, worker or external provider fails.

It complements LedgerFlow, OrderSphere and SupportHub with messaging engineering. This is a backend-first development milestone, `0.1.0-dev`, without a frontend or paid provider, and makes no production-ready claim.

### Architecture

Three independently deployable Spring applications own three PostgreSQL databases. API commits notification, intake key and outbox together. Relay publishes through RabbitMQ to the worker. Worker owns deliveries, attempts, deduplication and retry/terminal dispatch outbox. Provider simulator independently owns idempotency keys and logical effects.

The Maven reactor contains `notifyflow-api`, `notifyflow-worker`, `provider-simulator`, minimal immutable `notifyflow-contracts`, transport-only `notifyflow-messaging`, and non-deployable `notifyflow-tests`. No component queries another component's business tables. API proxies bounded delivery/attempt reads to the worker. Flyway owns schema; Hibernate validates the API entity. Explicit JDBC SQL expresses PostgreSQL arbitration and locking.

### Demonstrated guarantees and limits

- Same intake Idempotency-Key and payload return the same identity; different payload returns 409. A database constraint protects concurrent calls.
- Intake and initial event are one transaction; rollback leaves no partial state.
- Broker outage retains committed work. Bounded relay polling, SKIP LOCKED, backoff and confirms support recovery.
- Publication is complete only with a positive confirm **and no mandatory return**.
- At-least-once transport plus database-backed consumer idempotency and stable provider delivery key.
- Attempt, result and retry/DLQ dispatch intent commit before manual ACK; stale duplicates do not consume another logical attempt.
- Crash after provider effect and before worker commit/ACK causes redelivery with the same key: two physical requests, one logical effect.
- At most four attempts; permanent failure bypasses retries; poison messages remain inspectable in quarantine/DLQ.

Exactly-once transport, global ordering, public authentication, automated retention/redrive, single-node broker high availability and protection against volume loss are not guaranteed. A timed-out provider request may already have delivered even when worker retries eventually exhaust; reconcile before replay.

### API

`POST /api/v1/notifications` requires Idempotency-Key and returns 202, Location, notificationId and correlation header. `GET /api/v1/notifications/{id}` returns the immutable acceptance fact. `/{id}/deliveries` reads authoritative worker delivery state; `/{id}/attempts` reads up to four committed attempts.

Only simulated `EMAIL` is supported. Recipient validation, 200-character subject, 10,000-character body and 64-KiB HTTP body limits apply. Errors use Problem Details without internal diagnostics. Intake, publication and delivery states are deliberately separate. OpenAPI is at `/v3/api-docs`, Swagger UI at `/swagger-ui/index.html`.

### RabbitMQ, retry and failure model

Main direct exchange `notifyflow.notification.v1`, key `notification.requested.v1`, queue `notifyflow.delivery.email.v1`. Retry exchange `notifyflow.retry.v1` routes to three TTL queues (5/10/20 seconds by default), using safe quorum dead lettering back to main. Terminal exchange `notifyflow.dead.v1` routes to `notifyflow.delivery.email.dlq.v1`; poison rejection uses the main queue's safe DLX. All topology is code, with durable queues/exchanges, persistent messages, manual ACK, confirms and mandatory publishing.

HTTP 5xx/408/429, timeout and connection failures are transient; other non-success statuses are permanent. Simulator scenarios include success, fail twice then succeed, always 500, permanent 400, slow response and timeout. Scenarios are opt-in and disabled in normal Compose. Neither consumer retry nor failure assertions use arbitrary sleeping; broker TTL and Awaitility express delays and conditions.

### Run and verify

Prerequisites: JDK 21, running Docker Linux engine and PowerShell 7 on Windows/Linux. Maven Wrapper checks its downloaded distribution checksum.

```powershell
.\mvnw.cmd -B -ntp clean verify
Copy-Item .env.example .env
# Replace both placeholders with distinct random local credentials.
docker compose up -d --build --wait
.\scripts\smoke.ps1
```

Linux uses `chmod +x mvnw && ./mvnw -B -ntp clean verify`. The 27 JUnit cases comprise 6 unit, 5 integration, 6 component and 10 failure cases. Testcontainers runs real PostgreSQL and RabbitMQ. CI additionally builds images, starts fresh Compose volumes, runs smoke and actually terminates the worker JVM after provider commit. See [testing](docs/testing.md) and the evidence/residual-risk [failure matrix](docs/failure-model.md).

### Observability, security and operations

Actuator separates application liveness from database readiness. API remains ready for intake during broker outage; readiness is not proof of delivery progress. JSON logs include correlation/event/notification/delivery IDs without content or secrets. Micrometer/Prometheus exposes outbox backlog/age, publication, consumption, duplicates, retries, DLQ and duration.

Only API and RabbitMQ Management ports bind loopback. Databases, AMQP, provider and worker stay internal. Application containers run non-root; credentials come from environment; `.env` is ignored. No public API authentication is included: keep this local trusted-user deployment behind an authenticated boundary before any untrusted exposure.

[Operations](docs/operations.md) provides inspection queries and outage/DLQ/backlog/provider runbooks. [Architecture](docs/architecture.md) records versions and boundaries; [topology](docs/rabbitmq-topology.md) documents ACKs and retry; [ADRs](docs/adr) capture alternatives and tradeoffs. RabbitMQ suits this worker-queue workload; Kafka may later suit streams/replay/fan-out if required. [Learning](docs/learning/README.md), [interview prompts](docs/learning/interview-guide.md) and [exercises](docs/learning/exercises.md) refer to the actual code, without exercise solutions.

Roadmap: independent review, later integration/release; a future `v0.2.0 — Operations Console` may add operations UI. Retention, audited reconciliation/redrive and real provider integration need their own design work. This mission performs no merge into dev/main and creates no stable tag or release.

License: [MIT](LICENSE).

