# Stage 1: build the jar with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Stage 2: run it on a slim JRE, as a non-root user
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S recoverpro && adduser -S recoverpro -G recoverpro
COPY --from=build /app/target/*.jar app.jar
# UPLOAD_DIR/REPORTS_DIR default to ./uploads and ./reports (relative to WORKDIR /app) when
# unset -- pre-create both, owned by the app user, so local-disk storage (AWS_S3_ENABLED=false)
# works without a permission error on first write. Mount a volume at /app/uploads (and set
# UPLOAD_DIR=/app/uploads explicitly, or to wherever the volume is mounted) in production --
# without one, everything written here is lost on the container's next redeploy.
RUN mkdir -p /app/uploads /app/reports && chown -R recoverpro:recoverpro /app/uploads /app/reports
VOLUME ["/app/uploads"]
USER recoverpro:recoverpro
EXPOSE 8080
# SYSTEM 05 TASK 5.2: readiness (not the aggregate health endpoint) is the right check here --
# it reflects whether the app should currently receive traffic (DB/Redis/Flyway all healthy),
# not just whether the process is alive.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
