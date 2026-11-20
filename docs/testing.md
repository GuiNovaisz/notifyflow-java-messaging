# Tests and reproduction

Prerequisites: Java 21 JDK and running Docker Linux engine. Maven is downloaded and checksum-verified by the checked-in Wrapper. No tests skip themselves when Docker is missing: lack of real infrastructure fails verification.

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -B -ntp clean verify
```

Linux/CI: `chmod +x mvnw && ./mvnw -B -ntp clean verify`.

| Layer | Class | Cases | Evidence |
|---|---|---:|---|
| Unit | `PolicyTest` | 6 | Exponential delay bounds and status classification |
| Integration | `IntegrationIT` | 5 | DB rollback, real routing/persistence/confirm/return and topology |
| Component | `ComponentIT` | 6 | Spring HTTP servers, validation, concurrent intake/provider dedup |
| Failure | `FailureIT` | 10 | Broker app interruption, worker outage, channel crash/redelivery, duplicates, provider failures, DLQ, poison, two instances |

The integration fixture starts one isolated PostgreSQL Testcontainer with three independently owned databases and a real RabbitMQ Testcontainer; each deployable runs a Spring application on an ephemeral HTTP port. Cases stop listeners, purge task-owned queues, and truncate only the fixture databases between tests. Polling uses Awaitility; no arbitrary sleep-based assertions. Delays in the provider simulate network ambiguity, never consumer backoff.

In-process crash injection rolls back worker bookkeeping and physically closes its consumer channel. The separate Compose failure smoke kills the actual JVM with exit 91 *after provider commit* and checks Docker restart count, provider calls=2, logical effects=1, worker committed attempts=1. It also stops/starts only its own broker and worker containers and checks retry outcomes. `failure-test` profile is opt-in and is not part of normal Compose.

Reports: `notifyflow-tests/target/surefire-reports` and `notifyflow-tests/target/failsafe-reports`. CI uploads raw reports. Test counts are disjoint categories, not coverage percentages. Compose smoke assertions are additional scripted proofs and are not counted as JUnit cases.

## Fresh Compose proof

Use a new project name so volumes are fresh and existing stacks remain untouched. Set random local credentials in environment, then:

```powershell
$env:DB_PASSWORD=[guid]::NewGuid().ToString('N')
$env:RABBIT_PASSWORD=[guid]::NewGuid().ToString('N')
$env:API_PORT='18080'
$env:MANAGEMENT_PORT='15673'
docker compose -p notifyflow-proof -f compose.yaml -f compose.failure.yaml up -d --build --wait --wait-timeout 180
.\scripts\smoke.ps1 -BaseUrl http://localhost:18080
.\scripts\failure-smoke.ps1 -BaseUrl http://localhost:18080 -Project notifyflow-proof
docker compose -p notifyflow-proof down -v
```

The final command deletes **only the named proof project's volumes**. Do not use it against data you intend to retain. The scripts run in PowerShell 7 on Windows and Linux CI. CI has separate verify and container jobs with bounded timeouts. Dependency Review runs only on PRs; Dependabot groups compatible Maven updates and does not propose Maven major upgrades automatically.
