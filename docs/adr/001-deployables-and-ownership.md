# ADR 001 — Small multi-deployable architecture

Status: accepted.

Context: asynchronous delivery needs independent failure/restart boundaries, while the portfolio already has CRUD full-stack projects.

Decision: API, worker and provider simulator are separate Spring Boot deployables in a Maven reactor. Each owns its PostgreSQL database. Contracts and messaging infrastructure are the only shared modules. JPA validates the API entity; explicit JDBC expresses transactional SQL and locking. The API proxies bounded worker reads rather than querying worker tables.

Alternatives: one application would obscure worker outage; separate repositories/many microservices would add operations without strengthening guarantees; shared business tables would hide ownership.

Consequences/tradeoffs: independent deployments and clear failure testing, at the cost of three database instances in Compose and read-endpoint dependency on worker HTTP availability. Shared transport code coordinates topology versions, but shares no business state.
