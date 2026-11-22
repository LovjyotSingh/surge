# Security

Surge is a personal portfolio project, maintained by one person. It is built to be safe to deploy on a single public server, and this page says how to report a problem, what counts, and how the security design works.

## Reporting a vulnerability

Please report vulnerabilities privately through **GitHub's private vulnerability reporting**: open the repository's **Security** tab and click **Report a vulnerability**. Do not open a public issue, pull request or discussion for a security problem.

A good report says:

- what is affected (file, endpoint, container or workflow) and the commit you looked at;
- how to reproduce it, ideally against the local stack from the [README quick start](README.md#quick-start);
- what an attacker gains (for example, acting as another user, reading someone's ticket, buying a seat twice).

Reports are handled on a best-effort basis. Only the latest commit on `main` is supported; there are no released versions.

## Scope

The threat model is the one the design was audited against: one server running Docker Compose with only nginx reachable from the internet. The attacker can reach nginx, create accounts and send any HTTP request, and may get code execution in one container.

**In scope**

- Any way to act as another user or as an admin, or to make a service accept a request the gateway would refuse.
- Overselling, double charging, or reading or changing someone else's holds, orders or tickets.
- Bypassing rate limits, the login throttle, email verification or logout.
- Moving from one compromised container to data or brokers it should not reach (networks, credentials, ACLs).
- Secrets in the repository, in logs or in images, and weaknesses in the CI workflows.
- Anything in this repository's code, Compose files, nginx configuration, Dockerfiles and scripts.

**Out of scope**

- Running `compose.dev.yaml` or `compose.loadtest.yaml` on a public host. Both are documented as local only: the dev overlay exposes Mailpit (every email) and S3Mock, and the load-test overlay turns on test-token minting.
- Vulnerabilities in third-party images or libraries that Surge's configuration does not make reachable; report those upstream. (Do tell me if a pinned version is affected and reachable.)
- Volumetric denial of service. Surge limits each client, but one server can always be flooded.
- payment-service's chaos settings: they are an admin-only demo feature, and the admin is trusted.
- The accepted risks listed at the end of this page.

## Security design

The full explanation, with interview questions, is in [LEARN.md section 5.31](LEARN.md#531-security). In short:

### Edge: nginx

nginx is the only container with a published port, and it forwards `/api/` only to the gateway.

- It blanks any `X-User-*`, `X-Forwarded-*` and `X-Real-IP` header the client sends and sets `X-Forwarded-For` to the real peer address ([web/nginx/api.conf](web/nginx/api.conf)).
- Security headers on every page: `X-Frame-Options DENY`, `nosniff`, `no-referrer`, a Permissions-Policy, COOP and a strict Content-Security-Policy with no inline script or style (the image build fails if Vite ever emits one) ([web/nginx/security-headers.conf](web/nginx/security-headers.conf), [web/Dockerfile](web/Dockerfile)). API responses get `Cache-Control: no-store` ([web/nginx/api-response-headers.conf](web/nginx/api-response-headers.conf)).
- Body limits (64 KB by default, 16 KB for `/api/auth/`, 6 MB only for the CSV import), client and proxy timeouts, `server_tokens off`, and per-IP connection and request limits, with a separate limit on sign-in and on seat streams ([web/nginx.conf](web/nginx.conf)). Responses are never buffered to disk (`proxy_max_temp_file_size 0`; `/tmp` is a 64 MB `tmpfs`).
- With the production overlay, nginx runs [web/nginx-tls.conf](web/nginx-tls.conf): TLS 1.2 and 1.3 on 443 with HSTS, and port 80 serves only the Let's Encrypt challenge and redirects the rest to the one configured host name. See [docs/DEPLOY.md](docs/DEPLOY.md).
- It runs as an unprivileged user (uid 101) on the official nginx image.

### Gateway: the only reader of user tokens

- User JWTs are RS256 only, checked against auth-service's JWKS (refetched at most once per 30 s), with issuer `surge-auth`, audience `surge-api`, a required `exp`, `jti` and a well-formed `sub` ([SecurityConfig](gateway/src/main/java/dev/surge/gateway/security/SecurityConfig.java), [JwksCache](gateway/src/main/java/dev/surge/gateway/security/JwksCache.java)).
- Logout puts the token's `jti` on a Redis denylist until it expires. Every signed-in request checks it, and if Redis cannot answer within 3 s (the one command deadline of the gateway's Redis client) the request gets `503 auth-unavailable` with `Retry-After: 1`, as does a request whose key set cannot be fetched, rather than letting a possibly revoked token through ([TokenDenylist](gateway/src/main/java/dev/surge/gateway/security/TokenDenylist.java), [LogoutConfig](gateway/src/main/java/dev/surge/gateway/security/LogoutConfig.java)).
- Rules are deny by default; admin paths need the `ADMIN` role; `/api/payments/**` (other than admin chaos) answers 404. Paths that a servlet container could normalise differently (`..`, encoded slashes, `;`) are refused before any rule runs ([PathGuardFilter](gateway/src/main/java/dev/surge/gateway/security/PathGuardFilter.java)).
- Every public route has Redis token buckets, per user or per client IP. `X-Forwarded-For` counts only from nginx's address, IPv6 clients are keyed per /64, and the buckets that guard passwords, codes and email sending fail closed ([RouteConfig](gateway/src/main/java/dev/surge/gateway/config/RouteConfig.java), [RateLimitFilter](gateway/src/main/java/dev/surge/gateway/ratelimit/RateLimitFilter.java), [ClientAddress](gateway/src/main/java/dev/surge/gateway/ratelimit/ClientAddress.java)).
- A public deployment refuses to start unless `SURGE_TRUSTED_PROXIES` names nginx and nothing wider ([TrustedProxiesGuard](gateway/src/main/java/dev/surge/gateway/config/TrustedProxiesGuard.java)), and refuses `SURGE_ALLOW_LOADTEST_SUBJECTS=true`, which would let load-test tokens skip the per-IP limits ([LoadtestSubjectsGuard](gateway/src/main/java/dev/surge/gateway/config/LoadtestSubjectsGuard.java)).

### Zero-trust internal identity

- For every routed request the gateway signs a 60-second ES256 token, `X-Surge-Identity`, addressed to exactly one service (`aud`), carrying the user id, roles and whether the email is verified ([InternalIdentityFilter](gateway/src/main/java/dev/surge/gateway/security/InternalIdentityFilter.java)).
- Every service verifies it and trusts nothing else: ES256 only, `kid` must equal `iss`, the issuer must be on the service's allow-list, the audience must be the service's own name, at most 60 s old ([InternalTokenVerifier](common/src/main/java/dev/surge/common/security/InternalTokenVerifier.java)). `X-User-*` headers are never read.
- Services sign their own calls (booking to payment; notification to booking, auth and catalog) with their own key. A service token can speak only for that service, never for a user or a role ([InternalTokenSigningInterceptor](common/src/main/java/dev/surge/common/security/InternalTokenSigningInterceptor.java)).
- Each service declares its own rules on top of a deny-by-default chain, and checks the role again in the controller ([InternalSecurityAutoConfiguration](common/src/main/java/dev/surge/common/security/InternalSecurityAutoConfiguration.java), [SurgeAccessRules](common/src/main/java/dev/surge/common/security/SurgeAccessRules.java), [InternalAccess](common/src/main/java/dev/surge/common/security/InternalAccess.java), for example booking's [AccessRulesConfig](booking-service/src/main/java/dev/surge/booking/config/AccessRulesConfig.java)).

### Accounts

- Passwords: BCrypt, 8 to 72 bytes, not on a common-password list, not containing the email's local part ([PasswordPolicy](auth-service/src/main/java/dev/surge/auth/api/PasswordPolicy.java)). Login gives the same answer and does the same BCrypt work for an unknown email.
- A per-account login throttle on top of the gateway's per-IP limit: after 5 failures a lock of 30 s that doubles up to 15 minutes ([LoginThrottle](auth-service/src/main/java/dev/surge/auth/api/LoginThrottle.java)).
- Email verification with a 6-digit code: stored as a hash, single use, 15 minutes, 5 attempts, limited resends, at most 10 emails per address per 24 hours. The code email contains nothing the sender typed, and the display name is plain words (letters, digits, spaces, `.'-`, at most 60). An account that stays unverified for 24 hours gives its address up to the next signup; a verified account is never replaced. Holding seats needs a verified email ([EmailVerificationService](auth-service/src/main/java/dev/surge/auth/verify/EmailVerificationService.java)).
- User tokens live 30 minutes and are signed with a key loaded from a file, so they survive a restart ([TokenService](auth-service/src/main/java/dev/surge/auth/jwt/TokenService.java), [KeyConfig](auth-service/src/main/java/dev/surge/auth/jwt/KeyConfig.java)).
- With `SURGE_ENV=prod`, auth refuses to start with a short or default admin password or with test tokens enabled ([StartupGuards](auth-service/src/main/java/dev/surge/auth/config/StartupGuards.java)), a public deployment refuses the default `@surge.local` admin email, and the admin password is re-hashed whenever it changes ([AdminSeeder](auth-service/src/main/java/dev/surge/auth/user/AdminSeeder.java)). Every service runs with `SURGE_ENV=prod` in Compose, with one exception: the load-test overlay ([compose.loadtest.yaml](compose.loadtest.yaml)) runs auth with `SURGE_ENV=dev`, because auth refuses to mint test tokens in prod, so under that overlay these guards are off. It is for private machines only.

### Business integrity

- Orders, holds and tickets are checked against their owner; someone else's looks like a 404. Prices are set by the server and locked on the hold. Idempotency keys are per user. A user may hold seats in at most 3 events at once.
- booking dead-letters a `catalog.v1` message that would move the sale of an event that already sold seats, or that has more than 100 rows ([EventScheduledHandler](booking-service/src/main/java/dev/surge/booking/messaging/EventScheduledHandler.java)).
- Ticket QR codes carry an HMAC-SHA256 over order, user and event, so they cannot be forged ([TicketQr](notification-service/src/main/java/dev/surge/notification/ticket/TicketQr.java)).
- Every admin action is logged as one `ADMIN_ACTION actor=… action=… target=… requestId=…` line ([AdminAudit](common/src/main/java/dev/surge/common/security/AdminAudit.java)).

### Secrets and the platform

- [docker/gen-secrets.sh](docker/gen-secrets.sh) generates every credential and key; nothing has a default. Each password and key is its own file in `docker/secrets/` (directory mode 700); `.env` holds only user names and settings. Compose mounts each file read-only into only the containers that use it, at `/run/secrets/env/<VARIABLE>`, and [SecretFiles](common/src/main/java/dev/surge/common/security/SecretFiles.java) reads them at start-up, so no password is an environment variable and `docker inspect` shows none. [docker/set-secret.sh](docker/set-secret.sh) writes a provider's secret from standard input, so it never appears in `ps` or shell history. With `SURGE_ENV=prod` every service refuses development keys and missing or development store credentials ([StoreCredentials](common/src/main/java/dev/surge/common/security/StoreCredentials.java), [InternalKeys](common/src/main/java/dev/surge/common/security/InternalKeys.java)). Rotation and backups: [docs/DEPLOY.md](docs/DEPLOY.md).
- Public deployment guards. [compose.prod.yaml](compose.prod.yaml) sets `SURGE_PUBLIC_DEPLOYMENT=true` on every Spring service, and each of these refuses to start instead of running unsafely: `SURGE_ENV=dev`; a blank or too-wide trusted-proxy list (gateway); mail without SMTP AUTH, without required STARTTLS or with an `@surge.local` sender ([MailSettings](common/src/main/java/dev/surge/common/security/MailSettings.java), auth and notification); the development S3 keys or a plain-HTTP S3 endpoint, and a `PUBLIC_BASE_URL` that is not `https://` ([PublicBaseUrlGuard](notification-service/src/main/java/dev/surge/notification/config/PublicBaseUrlGuard.java), notification); load-test subjects (gateway); the `@surge.local` admin email (auth). The steps to set this up are in [docs/DEPLOY.md](docs/DEPLOY.md).
- Networks ([compose.yaml](compose.yaml)): `edge` (nginx and the gateway) and `app` (the services) are internal, and each store has its own internal network that only its users join, so booking cannot reach Elasticsearch. In the base Compose file only web (through `public`, the non-internal network that carries its published port) and auth and notification (through `egress`, for SMTP and S3) have a route out. Checked live: the gateway, booking, catalog and payment cannot reach the internet, auth and web can; web cannot reach booking or MySQL; the gateway cannot reach booking's Redis. The development overlay ([compose.dev.yaml](compose.dev.yaml)) puts every container with a debug port on its own non-internal `devhost-*` network, so the dev stack is **not** egress-isolated.
- Every store and broker has its own users with the least rights: one MySQL user per database ([docker/mysql-init/](docker/mysql-init/)), Redis ACL users limited to their commands, keys and channels ([docker/redis/](docker/redis/)), RabbitMQ with no guest user and per-service permissions ([docker/rabbitmq/](docker/rabbitmq/)), Kafka with SASL/SCRAM, ACLs per topic and group and no topic auto-creation ([docker/kafka/init.sh](docker/kafka/init.sh)), Elasticsearch with security on and a `catalog` user that cannot delete the index ([docker/elasticsearch/init.sh](docker/elasticsearch/init.sh)). Eureka and the config server need HTTP Basic.
- Every container drops all Linux capabilities, sets `no-new-privileges`, runs read-only as a non-root user, and has pid and memory limits. Base and third-party images are pinned by digest.

### Supply chain

- Every GitHub Action is pinned to a commit SHA, and workflows have read-only permissions ([ci.yml](.github/workflows/ci.yml)).
- Dependabot proposes Maven, npm, Actions, Docker and Compose updates ([dependabot.yml](.github/dependabot.yml)).
- CodeQL for Java and TypeScript ([codeql.yml](.github/workflows/codeql.yml)); OSV and `npm audit` dependency scans and Trivy secret scanning ([security.yml](.github/workflows/security.yml), [osv-scanner.toml](osv-scanner.toml)).
- CI builds every image and fails on any HIGH or CRITICAL vulnerability that has a fix (Trivy), checks that Compose publishes only nginx's port, and rejects secrets in `config-repo/`.

## Accepted risks

- **Dependencies:** [docs/security/accepted-risks.md](docs/security/accepted-risks.md) lists dependency advisories that are accepted rather than fixed. Today it has none: the HIGH advisories found so far were fixed by upgrading. When nginx 1.30.6 had six CVEs and no unprivileged 1.30.7 image existed yet, the web image moved to the official 1.30.7 image run unprivileged instead of adding the CVEs to an ignore list.
- **Design** (each one, with why it is accepted, what limits it and what would fix it, is in [docs/security/accepted-risks.md](docs/security/accepted-risks.md#design-and-operational-risks)):
  - A captured internal identity token can be replayed, but only within its 60 seconds and only against the one service it is addressed to.
  - The login throttle and payment-service's store live in memory, so those services run as one instance.
  - **N2 (Medium):** every Spring service shares one Eureka credential, and any authenticated client may register under any application name. Exploiting it needs a compromised container. The planned fix is static Docker DNS URIs on a single VM, or one Eureka user per service limited to its own name; on Kubernetes, service DNS replaces Eureka.
  - **N5 (Low):** search, list and detail share the gateway's 300-call bulkhead for catalog, so a flood of searches can make the others answer 503 (booking is unaffected). A bulkhead per operation would fix it.
  - The TLS private key is mode 640 with group 101 (nginx's gid) so the unprivileged nginx can read it; check that gid 101 is not a host system group.
  - **SEC-22:** the token is kept in `sessionStorage`; the strict CSP (no inline script) is what protects it. An `HttpOnly` cookie with CSRF protection would be the fix.
  - **SEC-23:** signup answers 409 for a taken email, so anyone can test whether an address is registered. A uniform "check your email" answer would fix it.
  - A seat update (`AVAILABLE` or `HELD`) can arrive after `SOLD`. Clicking that seat gets 409 `seat-taken` and the web app reloads the seat map; booking never sells it twice. A per-event sequence number would fix it.
  - Ticket email is at-least-once: a failed commit after sending causes a second, identical email.
  - Trivy's misconfiguration scan is advisory, not a gate.
  - TLS exists only in the production overlay (`compose.prod.yaml`, see [docs/DEPLOY.md](docs/DEPLOY.md)); the local stack is plain HTTP on `127.0.0.1`.
