# syntax=docker/dockerfile:1
# One image recipe for every Spring service (plan D7). Build context: the repo root.
#
#   docker build -f docker/service.Dockerfile --build-arg MODULE=booking-service --build-arg PORT=8081 .
#
# MODULE is the Maven module directory (booking-service, auth-service, catalog-service, payment-service,
# notification-service, gateway, discovery, config-server); PORT is its server.port, used by the health check.
#
# Base images are pinned by digest (SEC-17); Dependabot (docker ecosystem, directory /docker) bumps tag and digest.

FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
WORKDIR /src

# The Maven distribution the wrapper downloads lives in this layer (not in the cache mount), so the parallel
# builds of the eight images never race on unpacking it.
ENV MAVEN_USER_HOME=/opt/maven-wrapper
COPY .mvn .mvn
COPY mvnw ./
RUN ./mvnw -B -q --version

# Every module's pom must be present for the reactor (-pl … -am); .dockerignore keeps target/, web/, docs and
# secrets out of the context. This layer is identical for all eight images.
COPY . .

ARG MODULE
# -DskipTests, never -Dmaven.test.skip: the services' test classpath needs common's test-jar, which is only built
# when test sources are compiled. The local repository is a BuildKit cache shared by the parallel builds; the
# resolver's file locks keep concurrent downloads of the same artifact safe.
RUN --mount=type=cache,id=surge-m2-repository,target=/root/.m2/repository,sharing=shared \
    set -eu; \
    if [ -z "${MODULE:-}" ]; then echo "MODULE build arg is required" >&2; exit 1; fi; \
    ./mvnw -B -q -DskipTests \
        -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav \
        -pl "${MODULE}" -am package; \
    jars=$(find "${MODULE}/target" -maxdepth 1 -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-tests.jar' ! -name '*-javadoc.jar'); \
    if [ "$(printf '%s\n' "$jars" | grep -c .)" -ne 1 ]; then \
        echo "expected exactly one jar in ${MODULE}/target, found: $jars" >&2; exit 1; \
    fi; \
    cp "$jars" /tmp/app.jar; \
    java -Djarmode=tools -jar /tmp/app.jar extract --layers --launcher --destination /tmp/extracted

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c
ARG PORT
RUN if [ -z "${PORT:-}" ]; then echo "PORT build arg is required" >&2; exit 1; fi; \
    groupadd --system --gid 10001 surge \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin surge \
    # The only place the app may write besides /tmp (compose mounts a named volume here, e.g. notification's audit
    # log); a new volume copies this owner, so uid 10001 can write it with a read-only root file system.
    && mkdir -p /var/lib/surge/audit && chown -R 10001:10001 /var/lib/surge
WORKDIR /app
# Owned by root and not writable by the app user: a compromised process cannot rewrite its own code.
COPY --from=build /tmp/extracted/dependencies/ ./
COPY --from=build /tmp/extracted/spring-boot-loader/ ./
COPY --from=build /tmp/extracted/snapshot-dependencies/ ./
COPY --from=build /tmp/extracted/application/ ./
ENV HEALTH_PORT=${PORT}
EXPOSE ${PORT}
USER 10001:10001
# The JRE image has no curl or wget: bash's /dev/tcp asks /actuator/health (open without a token, status only).
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --start-interval=3s --retries=6 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${HEALTH_PORT} && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
# Replaces the base image's /__cacert_entrypoint.sh (it may write to the JDK trust store; nothing here needs it).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
