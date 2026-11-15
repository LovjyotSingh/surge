# Accepted dependency risks

There are currently no accepted dependency risks. `osv-scanner.toml` has no `[[IgnoredVulns]]` entries.

## Resolved in S9b

The HIGH advisories found by the S9 local OSV scan (2026-11-15) were fixed by overriding versions in the root
`pom.xml`, and their ignore entries were removed from `osv-scanner.toml`:

| Package | Was | Now | Advisories fixed | Mechanism |
|---|---|---|---|---|
| `com.rabbitmq:amqp-client` | 5.27.1 | 5.37.1 | GHSA-68mj-5wr7-6fgg, GHSA-93j5-89vc-pph4, GHSA-jh4v-gfqj-7rhx, GHSA-6g32-pxv4-2wfj | Boot property `rabbit-amqp-client.version` |
| `org.xerial.snappy:snappy-java` | 1.1.10.7 | 1.1.10.9 | GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 | `dependencyManagement` |
| `at.yawk.lz4:lz4-java` | 1.10.1 | 1.11.4 | GHSA-mcr4-qmvw-px4g | `dependencyManagement` |
| `com.google.guava:guava` | 33.0.0-jre | 33.7.2-jre | GHSA-xxph-c9ww-hj94 | `dependencyManagement` |
| `org.apache.httpcomponents.core5:httpcore5`, `httpcore5-h2` | 5.3.6 | 5.4.3 | GHSA-hf6x-8p5f-cgmf, GHSA-v3jc-474w-2wm6 | Boot property `httpcore5.version` |
| `org.apache.httpcomponents.client5:httpclient5` | 5.5.2 | 5.6.3 | moderate | Boot property `httpclient5.version` |
| `org.apache.httpcomponents:httpclient` (Eureka) | 4.5.3 | 4.5.14 | moderate (XSS) | `dependencyManagement` |

OSV re-query of the new versions (`/v1/querybatch`) returned no advisories for any of them.

**Remove these overrides** when the Spring Boot / Spring Cloud BOMs ship versions at or above them, so the
BOM is the single source of truth again.

## Still open (not HIGH, not verified)

- `netty-transport-native-epoll`: Boot already ships 4.2.19, so the earlier note was likely a false match on the
  linux classifier.
- Base images `nginx:1.27-alpine` and `redis:7-alpine` should move to current stable versions (SEC-28 note).
- The original scan did not cover `booking-service` or test scope; the first CI osv-scanner run is the real check.
