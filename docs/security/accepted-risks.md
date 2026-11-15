# Accepted dependency risks

Findings from a local OSV.dev scan on 2026-11-15 (all Maven runtime dependencies that resolved offline,
plus `npm audit --omit=dev`, which found 0 vulnerabilities). Only HIGH findings are listed; MODERATE and LOW
ones are fixed by the same bumps. None of these is fixed in this task, because dependency versions are managed
in the module poms that other work is editing. They are accepted until the follow-up bumps land.

**Re-check date: 2026-11-29** (or the next weekly Dependabot / Security workflow run, whichever comes first).

The CI `dependency-scan` job fails on any finding with CVSS >= 7. It will stay red on main until these bumps
are made or an entry is added to an `osv-scanner.toml` ignore list with a reason and an expiry date.

| Package (resolved) | Advisory | Why accepted for now | Fix |
|---|---|---|---|
| `com.rabbitmq:amqp-client` 5.27.1 | GHSA-68mj-5wr7-6fgg, GHSA-93j5-89vc-pph4, GHSA-jh4v-gfqj-7rhx, GHSA-6g32-pxv4-2wfj | Broker-side frames and JSON-RPC are not attacker-controlled: the broker is internal and authenticated, and JSON-RPC is unused. Impact is availability only. | Override to >= 5.36.1 (`rabbitmq-client.version` / `amqp-client` in the Boot BOM) |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 | Transitive, only reachable by decompressing attacker-supplied Snappy data; no code path in Surge does that. | Override to >= 1.1.10.9 |
| `at.yawk.lz4:lz4-java` 1.10.1 | GHSA-mcr4-qmvw-px4g | Native library extraction to a shared temp dir needs local access to the host; containers run one process. | Override to >= 1.11.4 |
| `com.google.guava:guava` 33.0.0-jre | GHSA-xxph-c9ww-hj94 | Pulled by `discovery` (Eureka). Needs Java deserialisation of attacker data, which Surge never does with Guava collections. | Override to >= 33.7.2 in `discovery` |
| `org.apache.httpcomponents.core5:httpcore5`, `httpcore5-h2` 5.3.6 | GHSA-hf6x-8p5f-cgmf, GHSA-v3jc-474w-2wm6 | Used as an HTTP client to internal services only (memory exhaustion needs a malicious server). | Override to >= 5.4.3 |

## Follow-up bumps needed

Add these to the root `pom.xml` `dependencyManagement` (or as properties the Boot parent honours), then re-run
`./mvnw -B verify` and the Security workflow:

- `com.rabbitmq:amqp-client` -> 5.36.1 (moderate ones in the same artifact need >= 5.36.1)
- `org.xerial.snappy:snappy-java` -> 1.1.10.9
- `at.yawk.lz4:lz4-java` -> 1.11.4
- `com.google.guava:guava` -> 33.7.2 (or newer)
- `org.apache.httpcomponents.core5:httpcore5` and `httpcore5-h2` -> 5.4.3; `httpclient5` -> 5.6.3 (moderate)
- Lower priority (moderate/low): `netty-transport-native-epoll` -> 4.2.15.Final or newer (Boot already ships 4.2.19, so this was likely a false match on the linux classifier), `org.apache.httpcomponents:httpclient` 4.5.3 (XSS, moderate, Eureka client) -> 4.5.13+

Base images `nginx:1.27-alpine` and `redis:7-alpine` should also move to current stable versions (SEC-28 note).
