# alpine 태그는 arm64(Apple Silicon) 매니페스트가 없어 non-alpine 사용
FROM gradle:8.10-jdk17 AS builder
WORKDIR /workspace

ARG SERVICE_NAME

# 의존성 캐시 레이어
COPY build.gradle settings.gradle ./
COPY eureka-server/build.gradle eureka-server/
COPY config-server/build.gradle config-server/
COPY gateway-server/build.gradle gateway-server/
COPY auth-service/build.gradle auth-service/
COPY estimate-service/build.gradle estimate-service/
COPY site-service/build.gradle site-service/
COPY purchase-service/build.gradle purchase-service/
COPY tax-service/build.gradle tax-service/
COPY notification-service/build.gradle notification-service/
COPY chat-service/build.gradle chat-service/
RUN gradle :${SERVICE_NAME}:dependencies --no-daemon 2>/dev/null || true

COPY ${SERVICE_NAME}/src ${SERVICE_NAME}/src
RUN gradle :${SERVICE_NAME}:bootJar --no-daemon -x test

# 17-jre-alpine은 arm64(Apple Silicon) 매니페스트가 없어 jammy 사용
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

ARG SERVICE_NAME
COPY --from=builder /workspace/${SERVICE_NAME}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
