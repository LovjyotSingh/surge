# syntax=docker/dockerfile:1
# Build context: repo root.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common common
COPY booking-service/pom.xml booking-service/pom.xml
COPY tools/loadtest tools/loadtest
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -q -pl tools/loadtest -am package -DskipTests \
    && cp tools/loadtest/target/loadtest.jar /tmp/loadtest.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home surge && mkdir -p /app/reports && chown surge /app/reports
WORKDIR /app
COPY --from=build /tmp/loadtest.jar /app/loadtest.jar
USER surge
ENTRYPOINT ["java", "-jar", "/app/loadtest.jar", "--out", "/app/reports"]
