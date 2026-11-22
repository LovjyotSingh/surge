# syntax=docker/dockerfile:1
# Build context: repo root. Base images pinned by digest, like docker/service.Dockerfile (Dependabot bumps both).
FROM eclipse-temurin:26.0.2.1_1-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /src
ENV MAVEN_USER_HOME=/opt/maven-wrapper
# unzip: without it the wrapper silently fetches the .tar.gz, which fails the zip SHA-256 pinned in
# .mvn/wrapper/maven-wrapper.properties. Build stage only; the runtime image does not get it.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw ./
RUN ./mvnw -B -q --version
COPY . .
RUN --mount=type=cache,id=surge-m2-repository,target=/root/.m2/repository,sharing=shared \
    ./mvnw -B -q -DskipTests \
        -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav \
        -pl tools/loadtest -am package \
    && cp tools/loadtest/target/loadtest.jar /tmp/loadtest.jar

FROM eclipse-temurin:26.0.2.1_1-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
RUN groupadd --system --gid 10001 surge \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin surge \
    && mkdir -p /app/reports && chown 10001:10001 /app/reports
WORKDIR /app
COPY --from=build /tmp/loadtest.jar /app/loadtest.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/loadtest.jar", "--out", "/app/reports"]
