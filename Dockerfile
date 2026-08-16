# ============================================================
# Stage 1: Build the Java application
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM first for layer caching
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
RUN chmod +x mvnw

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime image
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# Install git (needed for checkout/diff operations in the Action)
RUN apk add --no-cache git

WORKDIR /app

# Copy the shaded uber-JAR from the build stage
COPY --from=builder /build/target/ai-code-review-assistant-*.jar app.jar

# Copy entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# GitHub Actions runs containers as root by default
ENTRYPOINT ["/entrypoint.sh"]
