FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache nodejs npm \
    && addgroup -S -g 10001 jmindops \
    && adduser -S -D -H -u 10001 -G jmindops jmindops \
    && mkdir -p /app/data /tmp/jmindops \
    && chown -R jmindops:jmindops /app /tmp/jmindops
COPY --from=builder --chown=jmindops:jmindops /workspace/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp/jmindops -XX:MaxRAMPercentage=75.0"
ENV NPM_CONFIG_CACHE=/tmp/jmindops/npm-cache
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -q -O - http://127.0.0.1:8080/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
