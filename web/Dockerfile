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

# nginx stable branch, unprivileged (uid 101, listens on 8080; 8443 comes with TLS in S10). The slim variant has
# no curl, xslt or other modules nginx.conf does not use; apk upgrade applies Alpine security fixes released after
# the base image was built (the images job in CI fails on any fixable HIGH or CRITICAL).
FROM nginxinc/nginx-unprivileged:1.30.6-alpine-slim@sha256:5d8bb9437d74a600ec5dbf90c035451c724dc33eac8d3a71078f6f8612c345f6
USER root
# The stock server block (conf.d/default.conf) would answer on 8080 next to ours.
RUN apk upgrade --no-cache && rm -f /etc/nginx/conf.d/default.conf
COPY --chown=root:root nginx.conf /etc/nginx/nginx.conf
COPY --chown=root:root nginx/ /etc/nginx/surge/
COPY --chown=root:root --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/healthz"]
