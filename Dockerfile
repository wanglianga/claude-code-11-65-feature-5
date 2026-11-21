# Build the application jar in a pinned Temurin JDK image, then run on a slim JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r agri && useradd -r -g agri agri
COPY --from=build /build/target/agri-pit.jar /app/agri-pit.jar
COPY scripts/verify.sh /app/scripts/verify.sh
RUN chmod +x /app/scripts/verify.sh && chown -R agri:agri /app
USER agri
EXPOSE 8080
ENV SERVER_PORT=8080 \
    APP_SEED_DEMO=true
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/actuator/health || exit 1"]
ENTRYPOINT ["java", "-jar", "/app/agri-pit.jar"]
