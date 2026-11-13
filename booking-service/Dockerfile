# syntax=docker/dockerfile:1
# Build context: repo root.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common common
COPY booking-service booking-service
COPY tools/loadtest/pom.xml tools/loadtest/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -q -pl booking-service -am package -DskipTests \
    && cp booking-service/target/booking-service-*.jar /tmp/app.jar \
    && java -Djarmode=tools -jar /tmp/app.jar extract --layers --launcher --destination /tmp/extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home surge
WORKDIR /app
COPY --from=build /tmp/extracted/dependencies/ ./
COPY --from=build /tmp/extracted/spring-boot-loader/ ./
COPY --from=build /tmp/extracted/snapshot-dependencies/ ./
COPY --from=build /tmp/extracted/application/ ./
USER surge
EXPOSE 8081
# No HEALTHCHECK here: the JRE image has no curl/wget. compose.yaml defines a healthcheck instead.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
