# Build stage. The repo has no Maven wrapper and the shell default JDK here is Java 8, so
# the build runs against a pinned Maven + JDK 21 image rather than whatever the host has.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# pom.xml alone first: dependency resolution is the slow layer and only needs to re-run when
# the dependency list actually changes, not on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests are skipped on purpose. The integration test needs a Docker daemon to start a
# PostgreSQL container (contract §12), which is not available inside an image build. Run
# `mvn test` on a machine with Docker before building — see README.md.
RUN mvn -B -q -DskipTests package \
    && mv target/server-registry-*.jar /build/app.jar


# Runtime stage. JRE only — the JDK and the whole Maven repository stay behind in `build`.
FROM eclipse-temurin:21-jre

# curl is here for the container healthcheck. The application exposes no dedicated health
# endpoint (§1 fixes the dependency list, so no actuator); the check polls GET
# /api/servers/types, which exercises the web layer, JPA and a real database round trip.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs unprivileged. The image needs no write access anywhere: no logs to disk, no uploads,
# and the schema is created in PostgreSQL rather than locally.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser
WORKDIR /home/appuser

COPY --from=build --chown=appuser:appuser /build/app.jar app.jar

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM then sizes the heap from the container's memory limit,
# so `deploy.resources.limits` in compose is the single place memory is decided.
# JAVA_OPTS stays overridable for GC or debugging flags without rebuilding the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Shell form via `exec` so $JAVA_OPTS is expanded, while java still becomes PID 1 and
# receives SIGTERM directly — without exec, docker stop would wait out the full timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
