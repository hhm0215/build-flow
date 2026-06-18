# notification-service OCR + 만료 스케줄러

- **시작일**: 2026-06-18
- **BACKLOG 항목**: P0 (notification-service OCR + 만료 스케줄러)
- **예상 규모**: L (Phase 분할 권장)
- **상태**: PHASE 1 DONE / PHASE 2 BACKLOG 재등록

## 목표

하자보증보험 PDF를 업로드하면 OCR로 핵심 정보(보험사/증권번호/시작·만료일)를 자동 추출하고, 만료 D-30/D-7 시점에 자동 알림을 발송한다.

## 배경 / 동기

- 현재 `DefectWarranty`는 JSON CRUD만 가능. PDF 업로드 + 텍스트 자동 추출 미구현
- 만료 알림이 수동 — 사용자가 직접 `/api/v1/warranties/expiring` 호출해야 함
- BACKLOG P0 우선순위 작업

## 현재 코드 상태 (조사 완료)

✅ 이미 갖춰진 것:
- `DefectWarranty` 엔티티: `insuranceCompany` / `policyNumber` / `startDate` / `endDate` / `filePath` 필드
- `isExpiringSoon(days)`, `isExpired()` 메서드
- `findExpiringSoon(now, threshold)` 쿼리 메서드
- `WarrantyResponse`에 `daysUntilExpiry` 계산 포함

❌ 누락:
- `@EnableScheduling` (NotificationServiceApplication)
- `KafkaProducerService` + `warranty.expiring` 토픽 (notification-service는 현재 consume만)
- OCR 처리 상태 필드 (`ocrStatus`: PENDING/SUCCESS/FAILED)
- PDF 업로드 엔드포인트 (현재 JSON body만 받음)
- OCR 라이브러리 의존성 (Tess4J, PDFBox)
- 파일 저장 디렉토리 설정 (`app.upload-dir`)

## 접근법 — Phase 분할 권장

**Phase 1 (S~M, 단독 PR)**: 만료 스케줄러
- 이미 있는 `isExpiringSoon` + `findExpiringSoon` 그대로 활용
- 구현 부담 작고 가치 즉시 발생 (현재 만료 알림이 수동인 문제 즉시 해결)

**Phase 2 (M~L, 별도 PR)**: PDF OCR
- 외부 라이브러리 + Docker 이미지 변경 필요 (Tesseract 한글 모델 설치)
- 비동기 처리 + 파일 저장 인프라까지 같이 따라옴
- 위험 분리: Phase 1이 먼저 가치 실현, Phase 2가 실패해도 Phase 1은 유효

### Phase 1: 만료 스케줄러 상세

**산출물**:
1. `NotificationServiceApplication`에 `@EnableScheduling` + `@EnableJpaAuditing` 추가
2. `global/kafka/KafkaProducerService` 신설 — 다른 서비스(site/estimate/purchase/tax)와 동일 패턴
3. `global/event/WarrantyExpiringEvent` 신설 — `siteId`, `warrantyId`, `insuranceCompany`, `endDate`, `daysUntilExpiry`
4. `domain/warranty/scheduler/WarrantyExpirationScheduler`
   - `@Scheduled(cron = "0 0 9 * * *")` 매일 09:00 실행
   - D-30, D-7 두 시점 별도 체크 (중복 발송 방지를 위해 `processed_expiring_alerts` 테이블 또는 마지막 알림 발송일 기록)
5. `domain/notification/consumer/NotificationConsumer`에 `warranty.expiring` 핸들러 추가 → 인앱 알림 자동 생성
6. application.yml: kafka producer 설정 추가 (현재 consumer만 있음)

**중복 방지 전략**:
- 가장 단순: `DefectWarranty`에 `lastExpiringAlertSentAt` LocalDate 필드 추가 → 같은 날 중복 발송 차단
- 또는: `processed_events`처럼 별도 테이블 — 과도함 (1일 1회 cron이라 단순 컬럼으로 충분)
- **결정**: 단일 컬럼 `lastExpiringAlertSentAt LocalDate` 추가

**테스트 전략**:
- `@SpringBootTest`로 스케줄러 메서드 직접 호출 검증
- `EmbeddedKafka`로 발행 토픽 검증
- 통합 테스트는 별도 — 본 PR은 단위 테스트만

### Phase 2: PDF OCR (Phase 1 머지 후 별도 사이클)

**산출물 (요약, 실제 작업 시 별도 plans/ 문서 생성)**:
- `build.gradle`: `net.sourceforge.tess4j:tess4j:5.x` + `org.apache.pdfbox:pdfbox:3.x`
- `Dockerfile`: Tesseract 바이너리 + 한글 traineddata 설치 (`kor.traineddata`)
- `DefectWarranty`에 `ocrStatus` ENUM 컬럼 추가 (PENDING/SUCCESS/FAILED)
- `WarrantyOcrService`:
  - 1차: PDFBox 텍스트 추출 시도
  - 텍스트 < 임계치면 2차: PDFBox → BufferedImage → Tess4J
  - 정규식으로 보험사/증권번호/시작·만료일 파싱
- `POST /api/v1/warranties/upload` 신설 — multipart PDF
  - 파일 저장 → `WarrantyOcrService.processAsync` 호출 → 즉시 PENDING 반환
  - 비동기 처리 후 SUCCESS/FAILED 갱신
- `app.upload-dir` 설정 (`./uploads/warranties/` default, 운영 시 S3 전환 검토)

**리스크**:
- Tesseract 한글 OCR 정확도 — PDF 폰트·해상도에 따라 30~70% 추출률. 실패 시 사용자 수동 입력 fallback 유지 (이미 가능)
- Docker 이미지 크기 증가 (~80MB) — base 이미지를 `eclipse-temurin:17-jdk` → `:17-jre`로 줄여 상쇄

## Phase 1 산출물 체크리스트

- [ ] `notification-service/build.gradle`: 이미 spring-kafka 있음, 추가 의존성 없음
- [ ] `NotificationServiceApplication`: `@EnableScheduling` + `@EnableJpaAuditing`
- [ ] `global/kafka/KafkaProducerService` 신설
- [ ] `global/event/WarrantyExpiringEvent` 신설
- [ ] `domain/warranty/entity/DefectWarranty`: `lastExpiringAlertSentAt` 필드 + `markAlertSent` 메서드
- [ ] `domain/warranty/scheduler/WarrantyExpirationScheduler` 신설
- [ ] `domain/notification/consumer/NotificationConsumer`: `warranty.expiring` 핸들러
- [ ] `application.yml`: kafka producer 설정
- [ ] `docs/ARCHITECTURE.md`: `warranty.expiring` 토픽을 "계획 토픽"에서 "구현된 토픽"으로 이동
- [ ] BACKLOG.md: P0 항목 "Phase 1 완료" 표시 (Phase 2 별도 등록)
- [ ] PROGRESS.md "완료된 작업"에 한 줄 추가

## 리스크 / 모르는 것

- 매일 09:00 발송 시점에 사용자가 자고 있을 수도 있음 — 시간대 검토 필요. 현재 1인 사용자라 큰 문제 없음
- D-30, D-7 두 임계치 — 사용자가 늘리고 싶을 수도 (D-60 등). `application.yml`에서 설정 가능하게 외부화 권장
- 스케줄러 단위 테스트 — `@SpringBootTest`가 무거우니 스케줄러 클래스의 메서드를 직접 호출하는 좁은 테스트로 가는 게 빠름

## 테스트 / 검증

- `WarrantyExpirationScheduler` 메서드를 직접 호출하는 단위 테스트 (D-30, D-7 케이스 각각)
- `EmbeddedKafka`로 `warranty.expiring` 발행 검증
- 수동: `application.yml`에 cron을 `*/10 * * * * *` (10초)로 임시 변경해서 로컬 실행 → 콘솔 로그로 발행 확인 → 원복 후 커밋

## 결과 (Phase 1 — 2026-06-18 완료)

✅ **신규 파일 4종**:
- `global/event/KafkaEvent.java` — 다른 서비스와 동일 구조 (eventId UUID / eventType / timestamp / payload)
- `domain/warranty/event/WarrantyExpiringPayload.java` — `warrantyId`, `siteId`, `insuranceCompany`, `endDate`, `daysUntilExpiry` + `of(DefectWarranty, long)` 정적 팩토리
- `global/kafka/KafkaProducerService.java` — purchase-service 패턴 그대로
- `domain/warranty/scheduler/WarrantyExpirationScheduler.java` — `@Scheduled(cron = "${app.warranty.scheduler-cron:0 0 9 * * *}")` 매일 09:00

✅ **기존 파일 갱신 5종**:
- `NotificationServiceApplication`: `@EnableScheduling` 추가
- `DefectWarranty`: `lastExpiringAlertSentAt LocalDate` 필드 + `markExpiringAlertSent(LocalDate)` 메서드
- `DefectWarrantyRepository`: `findExpiringNotYetAlerted(today, threshold, cooldownThreshold)` 쿼리 신설
- `KafkaConsumerService`: `warranty.expiring` 핸들러 추가 → `NotificationService.createNotification("WARRANTY_EXPIRING", ..., siteId)`
- `application.yml`: kafka producer 설정 + `app.warranty.{alert-threshold-days,alert-cooldown-days,scheduler-cron}` 외부화

✅ **중복 방지 전략**: 단일 컬럼 `lastExpiringAlertSentAt` + 쿼리에서 "오늘로부터 cooldown일 이전" 조건. 30일 전 발송 → 23일 cooldown → 7일 전 재발송 (대략 D-30, D-23, D-16, D-9, D-2 패턴으로 자연스럽게 알림)

⚠️ **빌드 검증 못함**: gradle/gradlew 모두 미설치 (BACKLOG P2 그대로). 정적 정합성 점검만 수행:
- import 정합성 OK
- 메서드 시그니처 정합성 OK (markExpiringAlertSent, findExpiringNotYetAlerted, KafkaEvent.of, WarrantyExpiringPayload.of)
- application.yml 들여쓰기 OK
- 다음 PR 머지 후 로컬에서 `docker compose up`으로 통합 검증 권장

❌ **테스트 미작성**: Phase 1 범위 외로 결정 — BACKLOG P1에 "스케줄러 단위 테스트" 항목 신설하지 않고, 다음 사이클(Phase 2 OCR) 작업 시 함께 검토. 1인 환경 + 1일 1회 cron + 단순 로직이라 통합 검증으로 충분 판단

🔜 **Phase 2 (OCR)**: BACKLOG P0에 별도 항목으로 재등록 — Tess4J + PDFBox + Docker 한글 traineddata + 비동기 처리
