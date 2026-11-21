# syntax=docker/dockerfile:1
# Build context: web/. Base images pinned by digest (Dependabot, docker ecosystem, directory /web).
FROM node:24.22.3-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build \
    # The CSP (nginx.conf) allows no inline script or style: fail the build if Vite ever emits one.
    && if grep -Eq '<script>|<script [^>]*>[^<]|<style|style=' dist/index.html; then \
         echo "dist/index.html has inline script or style; the CSP would block it" >&2; exit 1; fi

# nginx stable branch, official image, run unprivileged as its nginx user (uid 101). This is everything the
# nginxinc/nginx-unprivileged variant adds, done here so a security release is usable the day it ships (1.30.7
# fixed six CVEs before an unprivileged 1.30.7 existed): nginx.conf already puts the pid file and every temp path
# in /tmp and listens on 8080 (and 8443 with nginx-tls.conf), and the cache directory is handed to uid 101. The slim
# variant has no curl, xslt or other modules nginx.conf does not use; apk upgrade applies Alpine security fixes
# released after the base image was built (the images job in CI fails on any fixable HIGH or CRITICAL).
FROM nginx:1.30.7-alpine-slim@sha256:8c950211a641ec5b23f265450517001f6bcca533666120ebe36cb4cff3d00a55
USER root
# The stock server block (conf.d/default.conf) would answer on port 80 next to ours.
RUN apk upgrade --no-cache && rm -f /etc/nginx/conf.d/default.conf \
    && chown -R 101:101 /var/cache/nginx
COPY --chown=root:root nginx.conf /etc/nginx/nginx.conf
# TLS (S10): compose.prod.yaml and compose.tls-local.yaml run `nginx -c /etc/nginx/nginx-tls.conf`, mount the
# certificate at /etc/nginx/tls/, and point the entrypoint's envsubst step at /etc/nginx/surge-templates (rendered
# into /tmp). Not the default /etc/nginx/templates: the plain configuration renders nothing.
COPY --chown=root:root nginx-tls.conf /etc/nginx/nginx-tls.conf
COPY --chown=root:root nginx-templates/ /etc/nginx/surge-templates/
COPY --chown=root:root nginx/ /etc/nginx/surge/
COPY --chown=root:root --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080 8443
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/healthz"]
