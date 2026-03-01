FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Wayrecall Team"
LABEL service="notification-service"
LABEL version="0.1.0"

WORKDIR /app

# Копируем fat jar
COPY target/scala-3.4.0/notification-service-assembly-0.1.0.jar app.jar

# Переменные окружения (defaults)
ENV HTTP_PORT=8094
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092
ENV DATABASE_URL=jdbc:postgresql://postgres:5432/tracker_notifications
ENV DATABASE_USER=postgres
ENV DATABASE_PASSWORD=postgres
ENV REDIS_HOST=redis
ENV REDIS_PORT=6379

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8094/health || exit 1

EXPOSE 8094

ENTRYPOINT ["java", "-jar", "app.jar"]
