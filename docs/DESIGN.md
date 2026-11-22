# Surge: design

This is the design for all five phases of Surge. Phases 1 and 2 are **built**; phases 3 to 5 are **planned, not built**. For what actually exists, read [README.md](../README.md) (how to run it, the API, the load-test result, the limits) and [LEARN.md](../LEARN.md) (a walkthrough of the code). Where an early decision here differed from what was built, this document describes what was built.

| Phase | Scope | Status |
|---|---|---|
| 1 | Core booking: holds, checkout, outbox, load test, web app | Built |
| 2 | Microservices platform: auth, catalog, payment, notification, gateway, config, discovery | Built |
| 3 | Observability: metrics, dashboards, logs, traces, alerts | Planned |
| 4 | AI: customer assistant, on-call agent, RAG, MCP server | Planned |
| 5 | Delivery: Kubernetes, Jenkins, AWS guide, demo | Planned |

## 1. Summary

Surge is a flash-sale ticket booking platform built as Java microservices, with a planned AI on-call agent that watches the system and diagnoses its failures.

The hard problem is the moment a big sale opens: thousands of users try to book a few hundred seats in the same second. Surge must sell every seat exactly once, stay responsive, and explain itself when something breaks.

## 2. Goals and non-goals

### Goals
- Sell every seat exactly once under heavy contention. The measurable target is a load test of 10,000 concurrent buyers for 500 seats that ends with exactly 500 sold and 0 oversold.
- Show each technology doing a real job, not decoration (see section 13).
- Make each phase work, and be demonstrable, on its own.
- Document the system well enough that a reader can follow every design decision ([LEARN.md](../LEARN.md)).
- Run the whole thing locally at no cost.

### Non-goals
- Real payments (a fake payment service stands in).
- Real email delivery locally (emails go to Mailpit, a local inbox).
- A managed public deployment. [DEPLOY.md](DEPLOY.md) is a runbook for one VM; the planned AWS guide is documentation, not applied infrastructure.
- A virtual waiting room (queue-style admission control). A possible later extension.
- Multi-region, sharding, or a secret manager integration.

## 3. Constraints and decisions

| Decision | Choice | Why |
|---|---|---|
| Where it runs | Locally with Docker Compose | The full stack (Kafka, Elasticsearch, the observability stack) is too heavy for free hosting |
| LLM (phase 4) | OpenRouter free models, through an OpenAI-compatible API | No cost |
| Embeddings (phase 4) | A local ONNX model (all-MiniLM-L6-v2, 384 dimensions) in-process | No reliable free hosted embeddings; no key needed |
| Frontend | One small Vite + React + TypeScript app | Needed to drive and demonstrate the system; kept deliberately small |
| Structure | Maven multi-module monorepo; services added phase by phase | Something runnable after phase 1, nothing rewritten later |

## 4. Technology baseline

- **Java 25 (LTS).** Since JDK 24 (JEP 491), virtual threads are no longer pinned inside `synchronized` blocks.
- **Spring Boot 4.0.9 and Spring Cloud 2025.1.4**, one Boot version across all modules. The Spring AI release line for phase 4 is chosen when that phase starts, against the Boot version then in use.
- **Build:** Maven Wrapper (`mvnw`), so no global Maven install is needed.
- **Data and infrastructure (Docker images, pinned by digest):** MySQL 8.4, Redis 7.4, Kafka 4.1 (KRaft, single broker), RabbitMQ 4 (management UI), Elasticsearch 9.2 (single node), Mailpit and Adobe S3Mock (development overlay only), nginx 1.30. Planned: Logstash, Kibana, Prometheus, Alertmanager, Grafana, Zipkin.
- **Frontend:** Vite, React and TypeScript, tested with Vitest. A dark, minimal UI that works down to 320 px wide.

## 5. Architecture

Built (phases 1 and 2):

```
                 nginx (only published port)
                 /        -> web (static React)
                 /api/**  -> gateway
                              |
        gateway: JWT check, rate limits, circuit breakers, identity tokens
                              |   services register with Eureka, read config-server
     +-----------+------------+-------------+--------------------+
 auth-service  catalog-service  booking-service  payment-service  notification-service
     |            |    ^            |   |   |          (fake)            |
   MySQL     Elasticsearch       MySQL Redis |                          |
                  |   Kafka <-- outbox ------+                          |
                  |     |                                               |
                  |     +--> notification-service <-- RabbitMQ `tickets` queue
                  |              (PDF ticket, S3, email, audit log)
                  +-- seats left, sales stats, from orders.v1
```

Planned additions: every service exports metrics to Prometheus, JSON logs to Logstash then Elasticsearch then Kibana, and traces to Zipkin (phase 3); Alertmanager posts alerts to an `ai-service`, which also runs the customer assistant and talks to an `mcp-server` that exposes read-only operations tools (phase 4).

### 5.1 Modules

| Module | Phase | Responsibility | Owns |
|---|---|---|---|
| `common` | 1 | Shared message records, Problem Details helpers, request-ID filter, internal identity tokens and access rules | none |
| `outbox` | 2 | The transactional outbox and its Kafka and RabbitMQ publishers, used by booking and catalog | none (each service owns its `outbox` table) |
| `booking-service` | 1 | Seat map, holds, checkout, orders, idempotency, hold-expiry sweeper, live seat updates (SSE), admin endpoints | MySQL `booking` database, Redis keys `hold:*`, `seat:*`, `sold:*` |
| `tools/loadtest` | 1 | CLI that simulates N concurrent buyers on virtual threads and writes a report | none |
| `tools/smoke` | 2 | End-to-end smoke test through nginx | none |
| `web` | 1 onward | React app: events, search, seat map, hold countdown, checkout, sign-up, Ops page. Planned: assistant and incident views | none |
| `auth-service` | 2 | Sign-up with an emailed 6-digit code, login, BCrypt passwords, issues RS256 JWTs, serves the JWKS | MySQL `auth` database |
| `catalog-service` | 2 | Events and venues, CSV import, Elasticsearch search, consumes `orders.v1` for seats left and daily sales | MySQL `catalog` database, Elasticsearch index `events` |
| `payment-service` | 2 | Fake payment provider with configurable decline rate, error rate and latency | in memory |
| `notification-service` | 2 | Consumes the RabbitMQ `tickets` queue (PDF ticket with QR code, stored in S3, emailed); consumes `orders.v1` into a daily audit log; dead-letter queue with replay | S3 bucket for tickets, `audit/*.jsonl` |
| `gateway` | 2 | Spring Cloud Gateway: routes, JWT validation against the JWKS, Redis rate limiting, circuit-breaker fallbacks, signs identity tokens | its own Redis instance (rate limits, token denylist) |
| `config-server` | 2 | Spring Cloud Config, native backend reading `config-repo/` | none |
| `discovery` | 2 | Eureka server | none |
| `ai-service` | 4 (planned) | Customer assistant, on-call agent, RAG ingestion, LLM cost guard, incident store | MySQL `ai` database, Elasticsearch index `runbooks` |
| `mcp-server` | 4 (planned) | MCP server exposing read-only operations tools | none |

In phase 1, `booking-service` ran alone: payment was an in-process fake behind a `PaymentGateway` interface and the user was identified by a demo header. In phase 2 the interface gained an HTTP implementation that calls `payment-service`. Only the gateway reads the user's JWT; for each routed request it signs a short-lived identity token for the one target service, and every service verifies that token and trusts nothing else. The security design is in [SECURITY.md](../SECURITY.md).

## 6. Core flow: booking without overselling

Three layers keep a seat from being sold twice: an atomic Redis hold, an optimistic lock on the seat row at checkout, and a unique constraint on `order_item.seat_id`.

### 6.1 Hold seats: `POST /api/bookings/holds`
Request: `{ eventId, seatIds[] }` (at most 8 seats). Response: `{ holdId, eventId, seatIds, expiresAt, quotedTotalInr, lines[{ seatId, priceInr }] }`.

1. Rate limit: a per-user token bucket in Redis, in booking-service, plus the gateway's limits.
2. Price lock: each seat is priced now by a `PricingRule` chain (base tier price, then an early-bird discount, then a surge multiplier when fewer than 20% of seats remain). Prices are `BigDecimal` in INR. These prices are the quote: the amount on the Pay button and exactly what checkout charges, like the price lock real ticketing sites give for the length of a hold.
3. A single Redis Lua script runs atomically:
   - If any seat is already sold (`sold:{eventId}` set) or held (`seat:{eventId}:{seatId}` exists), return the conflicting seat IDs and hold nothing.
   - Otherwise set each `seat:{eventId}:{seatId} = holdId` with a 5-minute TTL, store `hold:{holdId}` (a hash of user, event, seats, locked prices and expiry) with the same TTL, and add the hold to the sorted set `holds:expiry` scored by its expiry in epoch milliseconds.
4. Publish the seat-status change (Redis pub/sub), which is pushed to browsers on the seat map over SSE.
5. MySQL is not touched. This is what absorbs the rush.

A conflict returns `409` with the taken seat IDs.

### 6.2 Checkout: `POST /api/bookings/orders`
Request: `{ holdId }` with an `Idempotency-Key` header. Response: the order.

1. Idempotency: if the key was seen for this user, return the stored result (Redis: `IN_PROGRESS` while the request runs, then the order ID for 24 hours). A key whose stored order no longer exists (an admin reset deleted it) is taken over atomically and the request runs as new.
2. Verify the hold exists, belongs to the user and has not expired.
3. Take the price locked on the hold. Checkout does not re-price: the charge equals the quote the user saw, even if the early-bird period ended or a surge started since.
4. Charge through `PaymentGateway`, which calls `payment-service` behind a Resilience4j circuit breaker and timeout. A payment failure returns `402` and keeps the hold, so the user can retry until it expires. Payment happens outside the database transaction.
5. One MySQL transaction:
   - Mark each seat sold with an optimistic update (JPA `@Version`: `UPDATE seat SET status='SOLD', version=version+1 WHERE id=? AND status='AVAILABLE' AND version=?`). If any row is not updated, roll back, refund, release the hold without marking the seats sold in Redis, and return `409`. This is the second safety net if Redis ever lost data.
   - Insert `orders` and `order_item` rows. `order_item.seat_id` is unique, which is the third layer.
   - Insert `outbox` rows: `OrderConfirmed` (for Kafka `orders.v1`) and `TicketRequested` (for the RabbitMQ `tickets` queue).
6. After commit, a Lua script moves the seats into `sold:{eventId}`, deletes the hold keys, and publishes the seat status over SSE.

### 6.3 Outbox relay
A scheduled task polls `outbox` rows with `published_at IS NULL` in batches, locking them with `FOR UPDATE SKIP LOCKED`, publishes to Kafka or RabbitMQ, then marks them published. This avoids the dual-write problem (the database commit succeeds but the broker publish fails). Delivery is at least once, so consumers handle duplicates (section 9).

### 6.4 Hold expiry
Redis TTLs free the seats automatically. A scheduled sweeper reads `holds:expiry` entries whose score is at or before now, removes them, publishes "seat available again" over SSE, and records a `holds_expired_total` metric. Times use `Instant` and `Duration`; event times are stored in UTC with the venue's `ZoneId` and shown in the venue's local time.

### 6.5 Load test: `tools/loadtest`
The load tester runs through nginx and the gateway like browser traffic; see the README for the command and the overlay it needs.

- One virtual thread per simulated buyer, all released together by a `CountDownLatch` at "sale open".
- Each buyer picks a random seat (with deliberate contention on the best rows), holds, then checks out.
- Output: a JSON and Markdown report in `reports/` with request counts, 409s, p50/p95/p99 latency, the sold count, the **oversold count (must be 0)**, and a seat-by-seat consistency check from MySQL via an admin endpoint (a seat is SOLD exactly when it has an order line). It also checks that the number of successful checkouts equals the seats sold. Any failed check exits with code 1.
- Each run uses fresh idempotency keys, so runs can follow each other without clean-up.
- The Ops page in the web app shows the latest report.

## 7. Data model

MySQL with Flyway migrations, one database per service.

**booking:** `event_ref` (id, sale opening time, zone ID, status; created in phase 2 by consuming `EventScheduled` from the Kafka topic `catalog.v1`, which also generates the `seat` rows), `seat` (id, event, section, row, number, tier, status AVAILABLE or SOLD, version), `orders` (id, user, event, status, total in INR, idempotency key, created at), `order_item` (order, seat with a unique constraint, price in INR), `outbox` (id, aggregate, type, destination, JSON payload, created at, published at).

**auth:** `users` (id, email unique, password hash, display name, role, verification state, created at).

**catalog:** `venue` (id, name, city, zone ID, coordinates), `event` (id, venue, title, artist, category, start time, sale opening time, description, minimum and maximum price, image URL), `sales_daily` (event, date, tickets sold, revenue in INR).

**ai (planned):** `incident` (id, alert name, status, start time, report in Markdown, root cause, confidence, token counts, cost), `llm_call` (id, agent, model, token counts, cost, cached flag, latency, created at).

Seed data: venues in different time zones, events from a CSV (also used to demo the CSV import), and one flash-sale event with 500 seats.

## 8. AI (phase 4, planned)

Nothing in this section is built.

All LLM calls go through one `LlmClient` in `ai-service` (a Spring AI `ChatClient` pointed at `https://openrouter.ai/api/v1`). The OpenRouter key comes from a gitignored `.env`.

### 8.1 Cost guard
- **Model ladder (cheap first):** a small free model handles routing and simple answers; a larger free model is used only for the on-call diagnosis. Model IDs live in configuration so they can be swapped when OpenRouter changes its free list.
- **Budget:** free models allow only a small number of requests per day, so a daily request budget (a Redis counter that resets at midnight IST) is enforced. Over budget, the service returns a clear "AI budget used up for today" message, never an error.
- **Cache:** identical normalized prompts are answered from Redis (1 hour TTL).
- **Tracking:** every call records tokens, latency, cached flag and list-price cost (what it would cost on a paid model, from a configured price table) to `llm_call` and to Micrometer (`llm_tokens_total`, `llm_cost_usd_total`, tagged by agent and model), shown in a Grafana panel.

### 8.2 Customer assistant: `POST /api/ai/assistant`
- Pattern: a router and a tool-calling loop (at most 5 steps).
- Tools: `searchEvents(query, city, maxPrice, dateFrom, dateTo)` calls catalog-service; `getSeatMap(eventId)` calls booking-service; `proposeHold(eventId, seatIds)`.
- **Human in the loop:** `proposeHold` does not hold anything. It returns a proposal that the UI shows as a "Hold these seats?" button; only the user's click calls the real hold API.
- Example: "concert this weekend in Delhi under 2000 rupees" leads to a search, an answer with event cards, and an optional proposed hold.

### 8.3 On-call agent
- Trigger: an Alertmanager webhook to `POST /api/ai/incidents/alert`, or a "Diagnose now" button on the Ops page.
- Pattern: plan, gather evidence, hypothesize, self-check, report (at most 8 tool calls). It uses its tools through an MCP client connected to `mcp-server`, the same tools an external MCP client such as Claude Desktop can use.
- Evidence tools: `queryMetrics(promql, range)`, `searchLogs(query, service, since)`, `getRecentTraces(service)`, `getOrder(id)`, and `searchRunbooks(query)` (RAG).
- Output: an incident report in Markdown with a summary, root cause, evidence (the actual numbers and log lines), confidence, suggested fix and runbook link. It is stored in `incident` and shown on the Ops page.
- **Chaos scenarios** used to exercise it (admin endpoints, off by default): payment latency of 3 s with 30% failures (the circuit breaker opens), a MySQL connection pool shrunk to 2 (checkout timeouts), and a Redis hold TTL cut to 10 s (a hold-expired spike). Payment chaos already exists in phase 2; the other two are planned.

### 8.4 RAG
- Corpus: `docs/runbooks/*.md` (one per failure mode, written in phase 4) and the event catalog descriptions.
- Ingestion at startup or on demand: split by headings (about 500 tokens with 50 overlap), embed with the local ONNX model, and store in the Elasticsearch index `runbooks` (`dense_vector`, 384 dimensions, cosine).
- Retrieval: hybrid BM25 plus kNN, combined by reciprocal rank fusion, returning the top 4 chunks with source paths cited in the report.

### 8.5 MCP server
- Spring AI MCP server with the streamable HTTP transport (for `ai-service`) and stdio (for desktop MCP clients such as Claude Desktop).
- Tools are read-only: query metrics, search logs, recent traces, get order, list incidents, search runbooks. No chaos or write tools are exposed over MCP.

## 9. Error handling and resilience

Built:
- Every service returns Problem Details JSON (RFC 9457) with `type`, `title`, `status`, `detail` and `requestId`.
- A request ID (`X-Request-Id`) is propagated through nginx and the gateway to the services. Linking it to a trace ID comes with phase 3.
- Validation: Jakarta Bean Validation on request records, returning `400` with field errors.
- Common statuses: `401` bad or missing token, `403` wrong role or unverified email, `404`, `409` seat conflict, `402` payment failed, `429` rate limited (with `Retry-After`), `503` when a circuit is open or a dependency is busy (the body explains which). The README lists them all.
- The gateway wraps each route in a circuit breaker with a fallback; booking calls payment through its own breaker and timeout. Timeouts nest (nginx, then gateway, then breaker, then the stores), so a stuck dependency becomes a fast, retryable `503`.
- RabbitMQ `tickets` queue: a failing ticket message is retried with exponential backoff and then dead-lettered through a dead-letter exchange to `tickets.dlq`. An admin endpoint lists and replays dead-lettered messages. The attempt count and backoff are set in `notification-service`'s `application.yml`.
- Kafka consumers are idempotent: they record processed message IDs and skip duplicates. Messages that keep failing go to a dead-letter topic (for example `orders.v1.audit.DLT`, one per consumer).
- The outbox relay retries failed publishes on the next poll.

Planned (phases 3 and 4): an `outbox_backlog` gauge and alert; the AI never throws to the user, so an exhausted budget, an unavailable model or a reached step limit all return a readable fallback message.

## 10. Observability (phase 3, planned)

The seams exist from phase 2: Actuator and Micrometer counters in the services, request IDs, and structured events on Kafka. The planned work:

- **Metrics:** Micrometer to Prometheus (`/actuator/prometheus`; it is currently refused to every caller until a scrape identity exists). Custom metrics: `holds_created_total`, `holds_conflict_total`, `holds_expired_total`, `orders_confirmed_total`, `seats_oversold_total` (must stay 0), `outbox_backlog`, a `checkout_latency` histogram, and the LLM metrics from section 8.1. Built-in: JVM, virtual threads, HTTP, Hikari pool, Resilience4j, Kafka consumer lag.
- **Dashboards (Grafana, provisioned from JSON in the repo):** sale overview, service health, resilience, AI cost.
- **Logs:** a Logback JSON encoder to Logstash to Elasticsearch `logs-*` to Kibana, with saved searches by `traceId`, `requestId`, `service` and `level`.
- **Traces:** Micrometer Tracing (OpenTelemetry bridge) to Zipkin, with spans across gateway, booking and payment, and across Kafka and RabbitMQ hops.
- **Alerts (Prometheus rules to Alertmanager to `ai-service`):** checkout p95 above 1 s for 1 minute, payment circuit open, error rate above 5%, outbox backlog above 500, any oversold seat.

## 11. Delivery (phase 5, planned)

Today there is one parameterised service image, hardened Compose files for local, load-test and production use, and GitHub Actions CI (tests, image builds with Trivy, CodeQL, dependency scanning); see the README and [DEPLOY.md](DEPLOY.md). The planned work:

- **Docker:** extra Compose profiles for the observability and AI stacks, and a target of the full stack in about 12 GB of RAM.
- **Kubernetes:** Kustomize manifests for a local `kind` cluster: Deployments, Services, ConfigMaps and Secrets, readiness and liveness probes on actuator health groups, a horizontal pod autoscaler on booking-service, and an Ingress. Stateful infrastructure in Kubernetes stays minimal (MySQL, Redis); the rest stays in Compose for the demo.
- **CI:** a `Jenkinsfile` that mirrors the GitHub Actions stages plus a deploy to kind, with instructions to run Jenkins locally in Docker.
- **AWS (documented, not applied):** an EC2 free-tier setup, a slim Compose file for it, an S3 bucket for tickets through the same AWS SDK code (only the endpoint and credentials change), and least-privilege IAM notes.
- **Demo:** a script for recording: sale opens, load test, Grafana, chaos, AI incident report, MCP from a desktop client.

## 12. Phases and "done when"

Each phase ends with CI green, the README updated, the LEARN.md sections for that phase written, and everything committed.

1. **Core booking (built).** `common`, `booking-service`, `tools/loadtest`, `web`.
   *Done when:* the stack starts with Docker Compose; the concurrency integration test passes (2,000 concurrent buyers on 100 seats sell exactly 100); the load test of 10,000 buyers and 500 seats reports 500 sold and 0 oversold.
2. **Microservices platform (built).** auth, catalog, payment, notification, gateway, config-server, discovery; Kafka, RabbitMQ, Elasticsearch, Mailpit, S3.
   *Done when:* login, search (typo-tolerant, filters, location), hold and checkout work through the gateway with a JWT; a ticket PDF with a QR code lands in the S3 store and an email in Mailpit; Kafka updates search availability and the audit file; payment chaos opens the circuit breaker; hammering an endpoint returns `429`.
3. **Observability (planned).** Prometheus, Alertmanager, Grafana, Logstash, Kibana, Zipkin.
   *Done when:* the load test is visible live in Grafana; any request can be followed by trace ID through Kibana and Zipkin; a chaos scenario fires an alert.
4. **AI (planned).** `ai-service`, `mcp-server`, runbooks, the assistant UI, Ops incidents.
   *Done when:* the assistant answers the example query and proposes a hold the user confirms; the on-call agent writes a correct report for at least 2 chaos scenarios; the MCP server works from a desktop MCP client; the AI cost dashboard shows real calls; budget exhaustion is handled gracefully.
5. **Delivery (planned).** Polished Dockerfiles, kind manifests, a Jenkinsfile, the AWS guide, the demo script.
   *Done when:* the booking stack runs on kind; the Jenkins pipeline runs green in a local Jenkins container; the AWS guide is reviewed; the demo is recorded.

## 13. Technology map

| Technology | Where in Surge | Status |
|---|---|---|
| Core Java, OOP | Domain model (`Seat`, `Hold`, `Order`), the `PaymentGateway` interface with fake and HTTP implementations, a `PricingRule` strategy chain, sealed result types | Built |
| Collections, streams, functional style | Tier prices, the load tester's concurrent maps, report percentiles, sales aggregation, pricing rule composition | Built |
| Multithreading, virtual threads | Outbox relay and expiry sweeper, the load tester's start gate and one thread per buyer, virtual threads enabled in the MVC services | Built |
| Date-Time API | `Instant`, `ZoneId` and `Duration` for events across time zones, hold TTL, report timestamps | Built |
| Files | CSV event import, the daily `.jsonl` audit log, load-test reports, PDF generation | Built |
| Spring Boot, REST | All services | Built |
| MySQL, Hibernate, Flyway | JPA entities, `@Version` optimistic locking, `SKIP LOCKED` outbox query | Built |
| JWT | auth-service issues RS256 tokens; the gateway validates them against the JWKS | Built |
| Redis | Atomic holds (Lua), sold-seat sets, expiry sorted set, pub/sub for SSE, idempotency keys, rate limiting, token denylist | Built |
| Elasticsearch | Event search (fuzzy, filters, geo distance) | Built |
| Elasticsearch (logs, vector store) | Log storage and the RAG index | Planned |
| RabbitMQ | The `tickets` work queue with retries and a DLQ | Built |
| Kafka | `orders.v1` (consumed by catalog and by notification's audit log) and `catalog.v1` (catalog to booking seat inventory), with dead-letter topics | Built |
| S3 | AWS SDK v2 against an S3-compatible store locally | Built |
| Docker | Dockerfile and Compose files | Built |
| Microservices, API gateway, config server, Eureka, nginx | gateway, config-server, discovery, nginx | Built |
| Circuit breaker, rate limiting | Resilience4j on payment calls and gateway routes; Redis token buckets at the gateway and on holds | Built |
| Prometheus, Grafana, Micrometer, Logstash, Kibana, tracing | Section 10 | Planned |
| LLM, prompt engineering, agentic patterns, RAG, MCP, cost tracking | Section 8 | Planned |
| Kubernetes, Jenkins, AWS EC2 | Section 11 | Planned |

## 14. Testing

Built:
- **Unit:** JUnit 5, AssertJ and Mockito for pricing, hold validation, outbox relay logic and report math.
- **Integration:** Testcontainers (MySQL, Redis, Kafka, RabbitMQ, Elasticsearch, S3Mock, Mailpit) per service.
- **Concurrency:** 2,000 concurrent hold and checkout attempts on 100 seats produce exactly 100 orders and no duplicate seat (runs in CI).
- **Contract-ish:** shared message records in `common`, with JSON round-trip tests so producers and consumers agree.
- **Frontend:** Vitest and Testing Library for the seat map, countdown and checkout states.
- **End to end:** the smoke test in `tools/smoke`.

Planned for phase 4: LLM calls behind an interface with a stub for tests, so the tests cover tool-call loops, step limits, budget and cache behaviour without calling OpenRouter, plus one optional live smoke test that runs only when a key is present.

## 15. Risks

| Risk | Mitigation |
|---|---|
| Scope is large | Strict phases; each is independently shippable; documentation is written per phase |
| OpenRouter free models change or hit daily limits (phase 4) | Model IDs in configuration, a budget guard, a cache, stubbed tests |
| Memory use of the full stack on one machine | Compose profiles; Elasticsearch heap capped; run only the profiles needed |
| Version mismatches between Spring Boot, Spring Cloud and Spring AI (phase 4) | Pin one compatible set after checking Maven Central when the phase starts |
