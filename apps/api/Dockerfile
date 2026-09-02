# ---- build stage -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer so source edits don't refetch them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: the RLS suite needs a live PostgreSQL,
# which does not exist during an image build. CI runs them against a service
# container, and `make test` runs them locally. The image is not the place.
RUN mvn -B -q clean package -DskipTests

# ---- runtime stage -----------------------------------------------------
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# SECURITY: never run the API as root. If the process is compromised, an
# unprivileged user limits what the attacker inherits.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system aea \
 && useradd --system --gid aea --no-create-home aea

COPY --from=build /build/target/aea-api-*.jar app.jar
RUN chown aea:aea /app/app.jar
USER aea

EXPOSE 8080

# Container-aware heap sizing; the default cgroup detection in JDK 21 is good.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
