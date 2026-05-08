# Use Maven with OpenJDK 17 as base image for building
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
 
# Set working directory
WORKDIR /app
 
# Copy pom.xml first (for better Docker layer caching)
COPY pom.xml ./
 
# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B
 
# Copy source code
COPY src ./src
 
# Build the application
RUN mvn clean package -DskipTests -B
 
# Runtime stage - use JRE only for smaller image
FROM eclipse-temurin:17-jre-alpine
 
# Install wget for health checks (before switching to non-root user)
RUN apk add --no-cache wget
 
# Set working directory
WORKDIR /app
 
# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
 
# Copy the built JAR from builder stage (as root, before switching user)
COPY --from=builder /app/target/fsp-mcp-*.jar app.jar
 
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
