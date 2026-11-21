# Deploying Surge

This runbook puts Surge on one internet-facing Linux VM, the setup the security audit assumed: Docker Compose runs the whole platform, and nginx is the only container the internet can reach. Follow the steps in order. Each step says what to run and how to check it.

The examples use the domain `surge.example.com`, the checkout `/opt/surge` and a Debian or Ubuntu host. Replace them with your own.

**What runs where**

| File | Used for |
|---|---|
| `compose.yaml` | The platform: stores, brokers, services, gateway, nginx. Every service runs with `SURGE_ENV=prod`, and every password is a secret file. |
| `compose.prod.yaml` | Public deployment. nginx serves TLS on 443, and 80 only redirects. Real mail and S3. Every guard is on (`SURGE_PUBLIC_DEPLOYMENT=true`). |
| `compose.dev.yaml`, `compose.loadtest.yaml`, `compose.tls-local.yaml` | Your own machine only. **Never on the server.** |

## 1. The VM and its firewall

1. Create a VM with at least 4 vCPUs, 12 GB of RAM and 40 GB of disk. The stack idles at about 6.5 GB.
2. Install Docker Engine and the Compose plugin from Docker's own apt repository. You need Compose 2.24.4 or newer: the overlays use the `!override` tag. Check with `docker compose version`.
3. Install `ufw`, `fail2ban`, `certbot`, `age` (for backups), `unattended-upgrades`, `openssl` and a JDK 25 (`openjdk-25-jre-headless` or Temurin) from the distribution. `docker/gen-secrets.sh` makes the JWT and internal identity keys through `./mvnw`, so it needs `java` on `PATH` (or `JAVA_HOME`). Without one it stops before writing anything.
4. In the cloud security group, and in `ufw`, allow only:
   - port 22 from your own IP address;
   - ports 80 and 443 from anywhere.

   Deny everything else.

   ```bash
   sudo ufw default deny incoming && sudo ufw default allow outgoing
   sudo ufw allow from <your-ip> to any port 22 proto tcp
   sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
   sudo ufw enable
   ```

5. **Docker-published ports bypass `ufw`.** That is why `compose.prod.yaml` publishes nothing but 80 and 443. Never add a `ports:` entry on the server. To reach an internal UI, use `ssh -L` plus `docker compose exec`; do not publish a port.
6. Stop containers from reaching the cloud metadata endpoint, which can hand out the VM's cloud credentials:

   ```bash
   sudo iptables -I DOCKER-USER -d 169.254.169.254 -j DROP
   sudo apt install iptables-persistent && sudo netfilter-persistent save
   ```

   On AWS, also require IMDSv2 with a hop limit of 1.

## 2. SSH and the host

- In `/etc/ssh/sshd_config`, set `PasswordAuthentication no`, `PermitRootLogin no` and `KbdInteractiveAuthentication no`. Then run `sudo systemctl restart ssh`. Keep your current session open until a new key login works.
- Enable fail2ban's `sshd` jail: `sudo systemctl enable --now fail2ban`.
- Turn on automatic security updates with `sudo dpkg-reconfigure -plow unattended-upgrades`. Keep Docker Engine updated through apt.
- Write the Docker daemon settings to `/etc/docker/daemon.json`, then run `sudo systemctl restart docker`:

  ```json
  { "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" }, "live-restore": true }
  ```

- Membership in the `docker` group is equivalent to root. Give it only to the people who operate the server.

## 3. DNS

Create an `A` record for `surge.example.com` pointing at the VM's IPv4 address. Wait until `dig +short surge.example.com` returns the address.

**Do not publish an `AAAA` record.** The `public` network that nginx publishes from is IPv4 only. Docker's userland proxy (`docker-proxy`) would still accept IPv6 connections on 80 and 443 and relay them over IPv4. nginx would then see every IPv6 client as one `$remote_addr`, the proxy's, so all of them would share one per-IP budget: 4 seat streams, 1 sign-in per second and 50 connections.

If you need IPv6, three things are required:
- `"ipv6": true` and `"ip6tables": true` in `/etc/docker/daemon.json`;
- `enable_ipv6: true` with an IPv6 subnet on the `public` network in a compose overlay of your own;
- the `AAAA` record and the `ufw` rules for v6.

After that, check that nginx's access log shows real client addresses (`docker compose … logs web | grep remote_addr`) for an IPv6 and an IPv4 request.

## 4. Code and secrets

```bash
sudo git clone https://github.com/<you>/surge.git /opt/surge
cd /opt/surge
sudo docker/gen-secrets.sh
```

`gen-secrets.sh` writes two things:

- `.env` (mode 600). It holds user names and settings only, never a password.
- `docker/secrets/` (mode 700). It holds one file per password or key: MySQL root and four per-service users, Redis, RabbitMQ, Kafka SCRAM, Elasticsearch, Eureka, the config server, the admin password, the ticket QR HMAC key, the S3 keys, the JWT RSA key and the internal ES256 keys.

Run it as root on the server, so each key file is owned by the uid of the container that reads it. Every value is random, and no value is printed.

**It never overwrites.** Without `--force` it writes only what is missing: if `.env` is lost, it rewrites only `.env`; if one file is deleted, it rewrites only that file.

**What `--force` replaces.** Every generated password and key, so read the rotation notes in step 11 first. It keeps:
- the provider secrets (`mail_password`, `s3_access_key`, `s3_secret_key`);
- every `.env` line it does not own (`MAIL_*`, `S3_*`, `PUBLIC_BASE_URL`, `SURGE_SERVER_NAME`);
- the user names and the admin email.

**Where the files go.** Compose mounts each file read-only into only the containers that use it, at `/run/secrets/env/<VARIABLE>`. No password is passed as an environment variable, so `docker inspect` shows none. Inside a container, any process can read the files mounted into it, as it could read its environment before. Two passwords still end up in a process environment inside their own container:
- MySQL's entrypoint exports the root password into `mysqld`'s environment;
- the Elasticsearch process gets `ELASTIC_PASSWORD`.

Never commit `.env` or `docker/secrets/`, and never copy either into `config-repo/`. Both are gitignored.

Then add the values that are not generated.

**Your settings, in `.env`** (edit it with `sudoedit .env`):

```bash
SURGE_SERVER_NAME=surge.example.com       # port 80 redirects only to https://<this name>
MAIL_HOST=smtp.your-provider.example
MAIL_PORT=587
MAIL_USERNAME=<provider user>
MAIL_FROM=Surge <tickets@surge.example.com>
S3_BUCKET=surge-example-tickets
S3_REGION=ap-south-1
# S3_ENDPOINT=                      # empty = AWS; another S3 store must be https://
PUBLIC_BASE_URL=https://surge.example.com # must be https:// (the ticket email's sign-in link)
SURGE_ADMIN_EMAIL=you@surge.example.com   # optional; gen-secrets wrote admin@surge.local
```

**Your provider's secrets, in their files.** Never type a secret on a command line: it would end up in `ps` and in your shell history. `docker/set-secret.sh` reads the value from the terminal without showing it, or from a pipe (for example from your password manager), and writes the file:

```bash
sudo docker/set-secret.sh mail_password        # your SMTP provider's password or API key
sudo docker/set-secret.sh s3_access_key        # the IAM user's access key id
sudo docker/set-secret.sh s3_secret_key        # the IAM user's secret key
# or: pass show surge/smtp | sudo docker/set-secret.sh mail_password
```

The admin sign-in password is in `docker/secrets/surge_admin_password`. Store a copy in your password manager.

## 5. S3 for the ticket PDFs

1. Create the bucket yourself. The service creates it only if it is missing, and the IAM user below has no right to do that.
2. Turn on Block Public Access (all four settings), default encryption (SSE-S3) and versioning.
3. Create an IAM user for Surge with only this policy, then put its keys in the two files from step 4:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::surge-example-tickets" },
    { "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::surge-example-tickets/tickets/*" }
  ]
}
```

`ListBucket` is needed only for the startup check (`HeadBucket`). In prod, notification-service refuses to start with the placeholder keys `surge-local`, with blank keys, or with a plain `http://` endpoint.

## 6. Email

- Use a provider that sends over SMTP with STARTTLS on port 587 (Amazon SES, Postmark, Brevo, Mailgun and others). The prod overlay sets `MAIL_SMTP_AUTH=true` and `MAIL_STARTTLS=true`. A public deployment refuses to start without SMTP AUTH, without required STARTTLS, or with a `@surge.local` sender. If the server ever drops its STARTTLS offer, the send fails rather than going out in clear text.
- Verify `surge.example.com` with the provider. Publish the SPF and DKIM records it gives you, and a DMARC record to start with:

  ```
  _dmarc.surge.example.com  TXT  "v=DMARC1; p=quarantine; rua=mailto:dmarc@surge.example.com"
  ```

- Tell your users to check Spam for the verification code and the ticket email. In the Gmail app, that is ☰ (the menu) → Spam.

## 7. TLS certificate (Let's Encrypt)

nginx needs a certificate before it can start, and certbot needs nginx running to answer its challenge. So you start with a placeholder.

1. **Placeholder.** A self-signed certificate for the first start (valid for 30 days):

   ```bash
   sudo docker/gen-selfsigned-cert.sh /etc/surge/tls surge.example.com
   sudo install -d -m 755 /var/www/surge-acme
   ```

2. **Start the platform** (step 8). Check that `curl -sI http://surge.example.com/` answers `301` to `https://surge.example.com/` (the `SURGE_SERVER_NAME` from `.env`, whatever `Host` the request sent).
3. **Deploy hook.** It copies the certificate to where nginx reads it, then reloads nginx. nginx runs as uid/gid 101 and cannot read certbot's root-only key, so the hook copies the key with group 101.
   - Save it as `/etc/surge/certbot-deploy-hook.sh` and make it executable with `sudo chmod 755`.
   - Do not put it in `/etc/letsencrypt/renewal-hooks/deploy/`. certbot runs that directory only on `renew`, not on the first `certonly`, and with step 4's `--deploy-hook` it would also run twice.

   ```sh
   #!/bin/sh
   set -eu
   [ "$RENEWED_LINEAGE" = /etc/letsencrypt/live/surge ] || exit 0
   install -d -m 750 -o root -g 101 /etc/surge/tls
   install -m 644 -o root -g 101 "$RENEWED_LINEAGE/fullchain.pem" /etc/surge/tls/fullchain.pem.new
   install -m 640 -o root -g 101 "$RENEWED_LINEAGE/privkey.pem" /etc/surge/tls/privkey.pem.new
   mv -f /etc/surge/tls/fullchain.pem.new /etc/surge/tls/fullchain.pem
   mv -f /etc/surge/tls/privkey.pem.new /etc/surge/tls/privkey.pem
   cd /opt/surge
   docker compose -f compose.yaml -f compose.prod.yaml --profile core --profile platform exec -T web nginx -s reload
   ```

4. **Certificate.** nginx serves `/.well-known/acme-challenge/` on port 80 from `/var/www/surge-acme`. Pass the hook with `--deploy-hook`. certbot then runs it right after this first issue, and stores it in the renewal configuration for every renewal:

   ```bash
   sudo certbot certonly --webroot -w /var/www/surge-acme -d surge.example.com --cert-name surge \
       --deploy-hook /etc/surge/certbot-deploy-hook.sh \
       --email you@surge.example.com --agree-tos --no-eff-email
   sudo certbot renew --dry-run
   ```

   - **Check the copy.** `sudo openssl x509 -in /etc/surge/tls/fullchain.pem -noout -issuer` must name Let's Encrypt, not `CN=surge.example.com`. Otherwise nginx is still serving the 30-day self-signed placeholder, and the site breaks when it expires.
   - **If the certificate was issued without the hook**, run the hook once by hand:

     ```bash
     sudo RENEWED_LINEAGE=/etc/letsencrypt/live/surge /etc/surge/certbot-deploy-hook.sh
     ```

   certbot's systemd timer renews the certificate automatically and runs the hook each time.
5. **Check:** `curl -sI https://surge.example.com/` must answer without `-k` and show `Strict-Transport-Security`.
6. **`includeSubDomains`.** HSTS is one year with `includeSubDomains`. Every browser that has visited will then refuse plain HTTP on every subdomain of the name you serve, for a year. That is fine for a dedicated name such as `surge.example.com`. On an apex domain (`example.com`), first make sure no subdomain is served over HTTP only, for example an intranet host or a printer. If one is, remove `includeSubDomains` from `web/nginx-templates/surge/tls-site.conf.template` before the first start.
7. **HSTS preload.** Add `preload` (in the same template) and submit the domain to hstspreload.org only after a few weeks of clean HTTPS. Preloading is hard to undo.

## 8. Start

```bash
cd /opt/surge
sudo docker compose -f compose.yaml -f compose.prod.yaml --profile core --profile platform up -d --build
sudo docker compose -f compose.yaml -f compose.prod.yaml --profile core --profile platform ps
```

Wait until every service is `healthy` and `kafka-init` and `es-init` have exited with 0. A service that refuses to start names the setting it wants in its log, never the value. Read it with `docker compose … logs <service>`.

**Never** add `compose.dev.yaml`, `compose.loadtest.yaml` or `compose.tls-local.yaml` on the server, and never start the `smoke` or `loadtest` profiles there. Leave `SURGE_AUTH_TEST_TOKENS` unset.

## 9. After the first deploy

Run these from your own machine unless a line says otherwise.

1. **Ports.** `nmap -Pn -p- surge.example.com` shows only 22, 80 and 443 open. Port 22 shows only from your own IP.
2. **Redirect and headers.**
   - `curl -sI http://surge.example.com/x` gives `301` with `Location: https://surge.example.com/x`.
   - `curl -sI https://surge.example.com/` shows `Strict-Transport-Security`, `Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` and `Cross-Origin-Opener-Policy`.
   - `Server` shows `nginx` with no version.
3. **TLS.** `testssl.sh surge.example.com` must report no HIGH or CRITICAL finding, and only TLS 1.2 and 1.3 offered.
4. **Headers grade.** Run Mozilla Observatory (`https://developer.mozilla.org/en-US/observatory`) on the domain. Aim for grade A or better.
5. **Admin.** Sign in at `/login` with `SURGE_ADMIN_EMAIL` and the password from `docker/secrets/surge_admin_password`. Confirm that `admin@surge.local` / `surge-admin` gets `401`.
6. **A real purchase.** Sign up with a real address, enter the emailed code (check Spam), hold and pay for a seat, and get the ticket email with its PDF. The smoke CLI needs Mailpit, so it does not run here.
7. **Nothing internal is reachable from outside.** `curl -m 5 http://surge.example.com:8761/`, and the same for ports 15672, 9200, 8025, 8085 and 3307, must all fail. `https://surge.example.com/api/payments/charge` answers 404.
8. **No password in the environment** (on the server). This must print nothing:

   ```bash
   for c in $(sudo docker ps -q); do
     sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$c" | sed "s|^|$c |"
   done | grep -iE '(password|secret|_key)=[^/]'
   ```

   - Variables that only name a file are fine, so the pattern skips values that start with `/`. Examples: `MYSQL_ROOT_PASSWORD_FILE=/run/…`, `SURGE_ADMIN_PASSWORD_FILE=/run/…`, `SURGE_INTERNAL_SIGNING_KEY=/run/secrets/…`.
   - Any other `…PASSWORD=`, `…SECRET…=` or `…_KEY=` line is a leak.
9. **Guards.** Optional, on a staging copy:
   1. Replace one secret file with a known default, for example `printf surge-local | sudo docker/set-secret.sh s3_access_key`, or `surge-admin` for `surge_admin_password`.
   2. Recreate that service with `docker compose … up -d --force-recreate notification-service`. It must fail to start.
   3. Restore the file and recreate the service again.

## 10. Backups

**Nightly.** Run this from cron as root, and copy the output off the VM (to another region, another provider, or your own machine). `age` encrypts it to a public key whose private half is not on the server.

```bash
cd /opt/surge
ts=$(date -u +%Y%m%dT%H%M%SZ)
sudo docker compose -f compose.yaml -f compose.prod.yaml --profile core --profile platform exec -T mysql sh -c \
  'MYSQL_PWD="$(cat /run/secrets/env/MYSQL_ROOT_PASSWORD)" mysqldump -uroot --single-transaction --routines \
   --databases auth booking catalog notification' | age -r <age-public-key> > /var/backups/surge-db-$ts.sql.age
sudo tar -C /opt/surge -c .env docker/secrets | age -r <age-public-key> > /var/backups/surge-secrets-$ts.tar.age
sudo docker run --rm -v surge_audit-data:/audit:ro alpine tar -C /audit -c . \
  | age -r <age-public-key> > /var/backups/surge-audit-$ts.tar.age
```

- The volume names start with the Compose project name, which is the checkout's directory name (`surge` here).
- **The secrets.** Without `docker/secrets/` a restored database is still usable: reset the MySQL users with `ALTER USER`. But the JWT key is gone, so everyone has to sign in again.
- **S3.** Versioning on the bucket keeps every ticket PDF.
- **Not backed up.** Redis holds only short-lived holds and rate limits. Elasticsearch is rebuilt from MySQL when catalog starts. Neither needs a backup.
- **Test a restore once a month.** Load the dump into a scratch MySQL container, and check row counts and the latest orders.

## 11. Ongoing

**Updates.**
- Merge Dependabot's pull requests weekly.
- Then update and rebuild: `git pull && docker compose … up -d --build --pull always`.
- CI's Trivy job fails on any fixable HIGH or CRITICAL finding in the images.

**Rotation.** Rotate at least yearly, and at once after any suspected compromise.

Recreate every container that reads a changed file. Compose does not notice that a secret file's content changed, so a plain `up -d` keeps the old container and the old password keeps working. Below, `dc` stands for `sudo docker compose -f compose.yaml -f compose.prod.yaml --profile core --profile platform`.

The simplest way to rotate one generated password is to delete its file and run `docker/gen-secrets.sh` again without `--force`. It writes only the missing file, renders the Redis ACL or RabbitMQ definitions that depend on it again, and keeps everything else.

| Secret | How to rotate |
|---|---|
| Admin password | Delete `surge_admin_password` and rerun `gen-secrets.sh` (or write your own with `set-secret.sh`), then `dc up -d --force-recreate auth-service`. The new password works and the old one gets `401`. |
| JWT key | `docker/gen-jwt-key.sh --force docker/secrets/jwt.jwk`, then `dc up -d --force-recreate auth-service`. Everyone signs in again. auth can also serve the old public key for one token lifetime (`SURGE_JWT_PREVIOUS_KEY_FILE`, see auth's `jwt/JwtKeys`), but compose does not mount one yet. |
| Eureka, config | Delete `eureka_password` or `config_password` and rerun `gen-secrets.sh`, then `dc up -d --force-recreate`. Every Spring service, discovery and config-server read them, so recreate them all. |
| Kafka users | Delete the `kafka_*_password` file and rerun `gen-secrets.sh`, then `dc up -d --force-recreate kafka kafka-init` and the service that uses that user. |
| Elasticsearch `catalog` | Delete `elasticsearch_catalog_password` and rerun `gen-secrets.sh`, then `dc up -d --force-recreate es-init catalog-service`. |
| Redis, RabbitMQ | Delete the `redis_*` or `rabbit_*` password file and rerun `gen-secrets.sh` (it renders the ACL or definitions again). Then `dc up -d --force-recreate` the store (`redis`, `redis-edge` or `rabbitmq`) and its services. |
| S3 keys, mail password | Create the new key at the provider, `set-secret.sh s3_access_key` / `s3_secret_key` / `mail_password`, then `dc up -d --force-recreate notification-service` (and `auth-service` for mail). Only then revoke the old key. |
| MySQL users, the `elastic` password | Change them in the store first (`ALTER USER …`, `elasticsearch-reset-password`), then `set-secret.sh` the same value and recreate the services that read it. These files are read only when their volume is created. |
| Everything at once | `docker/gen-secrets.sh --force` replaces every generated password and key. It keeps `mail_password`, the S3 keys and your `.env` settings. Because of MySQL and `elastic`, use it only with fresh volumes: `dc down -v` deletes all data. |

**Watching.**
- Disk, memory and container restarts (`docker compose ps`, `docker stats`).
- Repeated admin sign-in failures: grep auth's log for `Failed sign-in for the admin account` and `is locked`.
- Every admin action is logged as an `ADMIN_ACTION` line.
- Keep logs for 30 to 90 days. Ship them off the VM if you can.

## Trying TLS locally first

`compose.tls-local.yaml` runs this same TLS edge with a self-signed certificate on `127.0.0.1:80` and `:443`, on top of the development stack (Mailpit and S3Mock):

```bash
docker/gen-selfsigned-cert.sh
docker compose -f compose.yaml -f compose.dev.yaml -f compose.tls-local.yaml --profile core --profile platform up -d --build
curl -kI https://localhost/     # 200 with HSTS (max-age=300) and every security header
curl -I http://localhost/       # 301 to https://localhost/
```

What to expect:
- Browsers warn about the certificate.
- A browser stores no HSTS from a certificate it does not trust. If you make it trust this one (mkcert, a keychain entry), it pins `https://localhost`, on every port, for the overlay's short `max-age` of 300 seconds, not a year.
- The smoke and load tests talk plain HTTP to `web:8080`, which now only redirects. Run them without this overlay.
