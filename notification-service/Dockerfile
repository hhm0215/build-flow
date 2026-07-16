# notification-service 전용 Dockerfile — Tess4J(JNA) 안정성을 위해 debian-slim 베이스 + Tesseract 4 + kor·eng traineddata
# 다른 8개 서비스는 루트 Dockerfile(alpine) 그대로 사용

# alpine 태그는 arm64(Apple Silicon) 매니페스트가 없어 non-alpine 사용
FROM gradle:8.10-jdk17 AS builder
WORKDIR /workspace

ARG SERVICE_NAME=notification-service

# 멀티 모듈 의존성 캐시 레이어
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
RUN gradle :${SERVICE_NAME}:dependencies --no-daemon 2>/dev/null || true

COPY ${SERVICE_NAME}/src ${SERVICE_NAME}/src
RUN gradle :${SERVICE_NAME}:bootJar --no-daemon -x test

# 런타임 — debian jammy + Tesseract + 한글 모델
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Tesseract 4 + kor·eng traineddata + libtesseract (JNA가 로드)
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-kor \
        tesseract-ocr-eng \
        libtesseract-dev \
    && rm -rf /var/lib/apt/lists/*

# /usr/share/tessdata 표준 위치로 심볼릭 링크 (Ubuntu 패키지는 /usr/share/tesseract-ocr/4.00/tessdata에 설치)
# application.yml의 app.ocr.tessdata-path default와 일치
RUN ls /usr/share/tesseract-ocr/4.00/tessdata/kor.traineddata && echo "Tesseract 한글 모델 OK"

RUN groupadd -r spring && useradd -r -g spring spring

# 업로드 디렉토리 미리 생성 + 소유자 부여
RUN mkdir -p /app/uploads/warranties && chown -R spring:spring /app/uploads

USER spring:spring

COPY --from=builder /workspace/notification-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
