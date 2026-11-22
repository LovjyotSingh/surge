# Accepted risks

Two kinds: [design and operational risks](#design-and-operational-risks), which come from how Surge is built and are
accepted for a demo that runs on one VM, and [dependency risks](#accepted-dependency-risks). Each design risk says what
it is, why it is accepted, what limits it today, and what would fix it. [SECURITY.md](../../SECURITY.md) has a
one-line summary of each.

## Design and operational risks

### N2 (Medium): one Eureka credential for every service, and any client may register under any name

**Risk.** All Spring services (and discovery itself) share one Eureka user. Eureka does not restrict what an
authenticated client may register, so a client with that credential can register an instance under any application
name (for example `PAYMENT-SERVICE`) and receive the traffic the gateway or booking sends there, including the
signed internal identity tokens in it (valid 60 s, addressed to that one service).

**Why it is accepted.** Exploiting it needs the Eureka password, which only the Spring containers mount, so it
needs a compromised container that is already on the internal network. That is a stronger position than the
threat model's starting point (an attacker who can reach nginx), and each service still verifies tokens against its own
audience and allow-listed issuers, so a rogue instance cannot mint a token for another service.

**Mitigation today.** The credential is a generated secret file, never an environment variable. Discovery is on an
internal network no published port reaches. Internal tokens are addressed to one service and live 60 s. The
gateway signs the identity token per request and addresses it to one service, so a rogue instance sees only the
requests routed to it.

**What would fix it.** On a single VM: drop Eureka lookups for static Docker DNS URIs (`http://payment-service:8082`),
since Compose already gives every service a stable name. Or one Eureka user per service, with registration limited to
that service's own application name (a small filter in front of `/eureka/apps/*`). On Kubernetes (a later phase)
service DNS replaces Eureka altogether.

### N5 (Low): search, list and detail share catalog's 300-call bulkhead

**Risk.** The gateway caps concurrent calls to catalog-service with one bulkhead of 300
(`surge.gateway.bulkhead.catalog`, `BulkheadConfig`). Search, event list and event detail all draw from it, so a
flood of slow searches can use it up and make the cheap detail calls answer 503 `service-busy` too.

**Why it is accepted.** The gateway's per-IP limits and nginx's request limits already apply first, so one client
cannot fill 300 slots; many clients at once is a volumetric attack, which is out of scope. The impact is a degraded
catalog for the length of the flood. Booking and checkout do not use catalog, so buying seats keeps working.

**Mitigation today.** nginx and gateway rate limits per client, and a bulkhead that refuses at once (503 with
`Retry-After: 1`) instead of queueing.

**What would fix it.** A separate route and bulkhead for search (a small one) and for detail and list (a larger one), so
the expensive path cannot starve the cheap ones.

### TLS private key readable by group 101

**Risk.** In the production overlay nginx runs as uid/gid 101 and cannot read a root-only key, so the deploy hook
and `docker/gen-selfsigned-cert.sh` install the key as mode 640, owner root, group 101 (see [DEPLOY.md](../DEPLOY.md)).
On the host, gid 101 is whichever group has that number: on some distributions a system group, which would be able to
read the key. Where the local script cannot `chgrp` (a non-Linux host) the throwaway key is mode 644.

**Why it is accepted.** The server is a single-purpose VM with no other users, and a process in a system group
that could read the key would already be code running on the host. The local self-signed key protects nothing.

**Mitigation today.** The directory is mode 750 (or 755 only for the local throwaway key), the certificate is public
anyway, and the deploy hook sets the group explicitly each renewal. Certificates are outside the checkout and out of
git and the build context (`docker/tls/` is in `.gitignore` and `.dockerignore`).

**What would fix it.** Check `getent group 101` on the VM and, if it is taken, create a dedicated group with an
unused gid and set `user: "101:<that gid>"` for nginx; or serve the key to nginx as a Docker secret instead of a
bind mount.

### 60-second replay window for internal identity tokens

**Risk.** A captured `X-Surge-Identity` token can be replayed until it expires (60 s).

**Why it is accepted.** Capturing one needs access to the internal network or to a service's memory, and the token
only works against the single service it is addressed to (`aud`), as the user it names. Tracking used `jti`s
would need a shared store on the hot path of every request.

**Mitigation today.** Short lifetime, one audience, one issuer per key (`kid` must equal `iss`), idempotency keys on
every state-changing call, orders checked against their owner.

**What would fix it.** A per-service replay cache of recent `jti`s (a small in-memory set is enough per instance),
or mutual TLS between services.

### Single-instance in-memory stores

**Risk.** The login throttle (auth-service) and payment-service's store live in memory. They are correct only with
one instance each: a second auth instance would give each account twice the attempts, and a restart clears the
counters and payment records.

**Why it is accepted.** Surge runs on one VM, one instance per service. Scaling out is not a goal of the demo.

**Mitigation today.** Compose runs one instance of each; the per-IP limits in the gateway use Redis and are
shared. The gateway's per-IP limit and nginx's limits stay in force across an auth restart. Verification and
per-address email budgets are in MySQL, not in memory.

**What would fix it.** Keep the throttle and the payment store in Redis or MySQL before running a second instance.

### SEC-22: the access token is kept in sessionStorage

**Risk.** The web app keeps the JWT in `sessionStorage`, so script running in the page could read it.

**Why it is accepted.** There is no script to run: the Content-Security-Policy allows no inline script or style
and only same-origin scripts, and the image build fails if Vite ever emits an inline one. The token lives 30 minutes
and is revoked by logout (Redis denylist). A cookie would need CSRF protection and a different CORS story for the
SSE seat stream.

**Mitigation today.** The strict CSP and the other headers in `web/nginx/security-headers.conf`, short token
lifetime, denylist on logout, no user content rendered as HTML (React escapes it; the display name is limited to plain
words).

**What would fix it.** An `HttpOnly; Secure; SameSite=Strict` cookie set by the gateway, plus a CSRF token on
state-changing calls.

### SEC-23: signup answers 409 for a taken email

**Risk.** `POST /api/auth/signup` answers 409 `email-taken` for an address that has a verified account, an
unverified one younger than 24 hours, or is the admin's. Anyone can therefore test whether an address is registered.

**Why it is accepted.** A generic answer ("check your email") would need the account to be created or not
behind the scenes and an email to be sent either way, which for a ticket shop with an immediate session is a worse
experience. Sign-in already gives one answer for unknown emails and wrong passwords, so the login form leaks nothing.

**Mitigation today.** The signup route is rate limited per IP at the gateway and nginx, and the 409 sends no email.
Signing up for an address you do not own never lets you into the real owner's account: the new account must verify the
address with a code that only the owner receives. Verification emails to one address are capped at 10 per 24 hours.

**What would fix it.** Answer 202 to every valid signup and email the address either "here is your code" or "someone
tried to sign up with your address"; sign in only after the code is entered.

### Late seat broadcasts

**Risk.** Seat updates travel over Redis pub/sub to the SSE stream with no ordering guarantee between publishers, so
an `AVAILABLE` or `HELD` update can arrive after `SOLD`. The seat then looks free in that browser.

**Why it is accepted.** It is cosmetic. Booking decides, in MySQL and Redis, whether a seat can be held: clicking
the stale seat gets 409 `seat-taken`, and the web app reloads the seat map. Nobody can buy a seat twice.

**Mitigation today.** Booking is the only authority on a seat's state; the 409 and the reload correct the view.

**What would fix it.** A per-event sequence number on each broadcast, so the browser ignores an update older than
the one it has already applied.

## Accepted dependency risks

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
- The original scan did not cover `booking-service` or test scope; the first CI osv-scanner run is the real check.
