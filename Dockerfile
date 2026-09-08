# The JAR is built by the CI workflow's own `mvn clean package` step, on the
# GitHub Actions runner — which has working Google Cloud auth (Workload
# Identity), so it can resolve the private com.forgecrux:forge-auth-lib /
# com.forge.libs:forge-logging-lib dependencies from Artifact Registry. This
# Dockerfile used to ALSO run its own `mvn dependency:go-offline` + `mvn
# clean package` inside a separate Maven builder stage — but that stage is a
# fully isolated Docker build container with NO knowledge of the runner's
# gcloud session, so it could never authenticate to Artifact Registry and
# every build failed trying to resolve forge-auth-lib / forge-logging-lib.
# Just packaging the already-built JAR avoids re-resolving anything here.
FROM eclipse-temurin:17-jre-alpine

# Install wget for health checks (before switching to non-root user)
RUN apk add --no-cache wget

# Set working directory
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the JAR built by the workflow's `mvn clean package` step (must run
# before this Docker build step — see .github/workflows/deploy_prod.yml)
COPY target/fsp-mcp-generation-svc-*.jar app.jar

# Change ownership to spring user
RUN chown spring:spring app.jar

# Create writable log directory in /tmp (works with read-only root filesystem)
RUN mkdir -p /tmp/logs

# Switch to non-root user
USER spring:spring

# Cloud Run will pass the PORT environment variable
ENV PORT=8080

# Expose the port
EXPOSE ${PORT}

# Health check (using wget which is available in alpine)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/mcp-generate/v1/api/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java -jar -Dserver.port=${PORT} app.jar"]
