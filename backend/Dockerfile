# syntax=docker/dockerfile:1
# Writeproof API. Build: docker build -t writeproof-backend backend/

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
# Tests run in CI; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/writeproof-backend-*.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system writeproof && useradd --system --gid writeproof --no-create-home writeproof
WORKDIR /app
# Layers from least to most often changed, so upgrades re-pull only the application layer.
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER writeproof
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
