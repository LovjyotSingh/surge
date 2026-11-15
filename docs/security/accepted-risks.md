# Accepted dependency risks

Findings from a local OSV.dev scan on 2026-11-15 (all Maven runtime dependencies that resolved offline,
plus `npm audit --omit=dev`, which found 0 vulnerabilities). Only HIGH findings are listed; MODERATE and LOW
ones are fixed by the same bumps. None of these is fixed in this task, because dependency versions are managed
in the module poms that other work is editing. They are accepted until the follow-up bumps (task S9b) land.

**Coverage gap.** The local OSV query did NOT cover `booking-service`, nor the module-specific third-party
dependencies of the modules that depend on it (`common` and `outbox` could not be resolved offline, so the
modules that use them are partial), and it covered runtime scope only, not test scope. This list is therefore
incomplete until the first osv-scanner run in CI.

**Re-check date: 2026-11-29** (or the next weekly Dependabot / Security workflow run, whichever comes first).

The CI `dependency-scan` job fails on any finding with CVSS >= 7. Every advisory in the table below is
listed in the root `osv-scanner.toml` as an `[[IgnoredVulns]]` entry with `effectiveUntil = 2026-11-29`, so the
job passes with this ignore list while any NEW advisory still fails. Entries expire on their own and must be
deleted when the bump lands. The first CI run may surface more findings from the gap above; add them here and
to the toml, or bump them.

| Package (resolved) | Advisory | Why accepted for now | Fix |
|---|---|---|---|
| `com.rabbitmq:amqp-client` 5.27.1 | GHSA-68mj-5wr7-6fgg, GHSA-93j5-89vc-pph4, GHSA-jh4v-gfqj-7rhx, GHSA-6g32-pxv4-2wfj | Broker-side frames and JSON-RPC are not attacker-controlled: the broker is internal and authenticated, and JSON-RPC is unused. Impact is availability only. | Override to 5.37.1 (HIGH advisories are fixed at >= 5.34.0) |
| `org.xerial.snappy:snappy-java` 1.1.10.7 | GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 | Transitive. Surge enables no Snappy compression on any producer, so no attacker-supplied Snappy data is decompressed. | Override to >= 1.1.10.9 |
| `at.yawk.lz4:lz4-java` 1.10.1 | GHSA-mcr4-qmvw-px4g | Native library extraction to a shared temp dir needs local access to the host; containers run one process. | Override to >= 1.11.4 |
| `com.google.guava:guava` 33.0.0-jre | GHSA-xxph-c9ww-hj94 | Pulled by `discovery` (Eureka). Needs Java deserialisation of attacker data, which Surge never does with Guava collections. | Override to 33.7.2-jre in `discovery` |
| `org.apache.httpcomponents.core5:httpcore5`, `httpcore5-h2` 5.3.6 | GHSA-hf6x-8p5f-cgmf, GHSA-v3jc-474w-2wm6 | Used as an HTTP client to internal services only (memory exhaustion needs a malicious server). | Override to >= 5.4.3 |

## Follow-up bumps needed

Override via `dependencyManagement` in the root pom, or the Boot property if one exists (to verify in S9b), then re-run
`./mvnw -B verify` and the Security workflow, and delete the matching toml entries:

- `com.rabbitmq:amqp-client` -> 5.37.1
- `org.xerial.snappy:snappy-java` -> 1.1.10.9
- `at.yawk.lz4:lz4-java` -> 1.11.4
- `com.google.guava:guava` -> 33.7.2-jre (or newer)
- `org.apache.httpcomponents.core5:httpcore5` and `httpcore5-h2` -> 5.4.3; `httpclient5` -> 5.6.3 (moderate)
- Lower priority (moderate/low): `netty-transport-native-epoll` -> 4.2.15.Final or newer (Boot already ships 4.2.19, so this was likely a false match on the linux classifier), `org.apache.httpcomponents:httpclient` 4.5.3 (XSS, moderate, Eureka client) -> 4.5.13+

Base images `nginx:1.27-alpine` and `redis:7-alpine` should also move to current stable versions (SEC-28 note).
