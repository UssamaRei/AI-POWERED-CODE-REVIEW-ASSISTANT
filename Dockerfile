# ============================================================
# Stage 1: Build the Java application
# ============================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy POM first for layer caching
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime image
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# Install git (needed for checkout/diff operations in the Action)
RUN apk add --no-cache git

WORKDIR /app

# Copy the shaded uber-JAR from the build stage
COPY --from=builder /build/target/ai-code-review-assistant-*.jar app.jar

# Copy entrypoint script and ensure Unix line endings
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh

# GitHub Actions runs containers as root by default
ENTRYPOINT ["/entrypoint.sh"]

