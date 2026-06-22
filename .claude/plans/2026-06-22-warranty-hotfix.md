# warranty Phase 1.5 — 핫픽스 (코드 리뷰 발견 CRITICAL+HIGH 7건)

- **시작일**: 2026-06-22
- **BACKLOG 항목**: P0 신규 (코드 리뷰 결과 등록)
- **예상 규모**: M
- **상태**: DONE (2026-06-22)

## 배경

2026-06-22 /code-review xhigh 모드로 PR #22~#29 합산 diff(52 파일/2026 라인) 검토. CRITICAL 1 + HIGH 6건 발견. 모두 warranty Phase 1/2 백엔드 영역(빌드 검증 미수행 영역).

## 7건 수정 내역

### 1. (CRITICAL) `DefectWarrantyService.createFromOcr` @Async race
- **문제**: outer `@Transactional` 안에서 `ocrService.processAsync` 호출 → async 스레드의 `findById`가 outer commit 전 실행 → row 미존재 → `IllegalStateException` → catch fallback도 같은 이유 실패 → **PENDING 영구 고착**
- **해결**: outer `@Transactional` 제거. Spring Data JPA `SimpleJpaRepository.save`가 자체 짧은 tx로 commit한 후 `processAsync` 호출. 파일 검증/저장은 tx 밖

### 2. (HIGH) `application.yml` servlet.multipart 위치
- **문제**: `servlet.multipart`가 YAML 루트(`spring:` 트리 밖) → Spring 기본 1MB 한도 적용 → 20MB PDF 모두 거부
- **해결**: `spring.servlet.multipart.*`로 키 이동

### 3. (HIGH) `DefectWarranty.applyOcrResult` SUCCESS 무조건 마킹
- **문제**: 4 필드 모두 null이어도 `ocrStatus=SUCCESS` → "AI 추출됨" 초록 뱃지 거짓 표시
- **해결**: 최소 1 필드 추출 시 SUCCESS, 모두 null이면 FAILED

### 4. (HIGH) `Tesseract` bean 동시성
- **문제**: bean 싱글톤 + `@Async` 동시 호출 → native lib race / SIGSEGV
- **해결**: `@Scope("prototype")` + `ObjectProvider<Tesseract>` 주입 패턴 — 호출마다 새 인스턴스

### 5. (HIGH) Kafka send fire-and-forget
- **문제**: `KafkaTemplate.send()` 결과 await 안 함. broker 일시 장애 시 `markExpiringAlertSent` 커밋 → 7일 cooldown 동안 알림 누락
- **해결**: `sendWarrantyExpiring`이 `CompletableFuture` 반환 + scheduler에서 `.get(5초)` 동기 대기. 실패 시 markExpiringAlertSent 호출 안 함 → 다음 cron에서 재시도

### 6. (HIGH) `LocalDate.now()` + cron timezone
- **문제**: Docker UTC 컨테이너에서 `@Scheduled cron="0 0 9"` 가 18:00 KST 발사. 자정 경계 누락
- **해결**: `@Scheduled(zone="Asia/Seoul")` + `LocalDate.now(ZoneId.of("Asia/Seoul"))`

### 7. (HIGH) `ocrStatus` 마이그레이션 안전성
- **문제**: 신규 `@Column(nullable=false)` 컬럼 + `ddl-auto: update` → 기존 운영 데이터 있으면 ALTER ADD COLUMN NOT NULL이 DEFAULT 없어 실패
- **해결**: `@ColumnDefault("'MANUAL'")` 추가 → Hibernate가 DEFAULT 값 포함해 ALTER 생성. 기존 데이터는 MANUAL로 백필

## 산출물 체크리스트

- [ ] `DefectWarrantyService.createFromOcr` — `@Transactional` 제거, 호출 순서 안전화
- [ ] `application.yml` — `spring.servlet.multipart` 위치 수정
- [ ] `DefectWarranty.applyOcrResult` — SUCCESS/FAILED 분기
- [ ] `WarrantyOcrConfig` — Tesseract bean prototype + ObjectProvider 주입
- [ ] `WarrantyOcrService` — Tesseract 호출 시 provider.getObject() 사용
- [ ] `KafkaProducerService.sendWarrantyExpiring` — `CompletableFuture<SendResult>` 반환 + 로깅
- [ ] `WarrantyExpirationScheduler` — zone 명시 + ZoneId 사용 + future.get() 대기 + 실패 시 markExpiringAlertSent skip
- [ ] `DefectWarranty.ocrStatus` — `@ColumnDefault("'MANUAL'")` 추가
- [ ] BACKLOG / PROGRESS / RETROSPECTIVE 갱신

## 검증

- gradlew 부재로 컴파일 검증 못함 — 사이클 C(통합 검증)로 미룸
- /code-review 결과 MEDIUM 7건은 P1 신규 등록

## 결과 (2026-06-22 완료)

### 수정된 7건
1. ✅ `DefectWarrantyService.createFromOcr` — `@Transactional` 제거, save 자체 짧은 tx로 commit 후 processAsync 호출 (race 해소)
2. ✅ `application.yml` — `servlet:` 블록을 `spring:` 트리 아래로 이동 (20MB 한도 정상 적용)
3. ✅ `DefectWarranty.applyOcrResult` — 1개 이상 추출 시 SUCCESS, 모두 null이면 FAILED
4. ✅ `WarrantyOcrConfig` — Tesseract bean `@Scope(SCOPE_PROTOTYPE)`. Comment에 thread-safety 사유 명시
5. ✅ `WarrantyOcrService` — `ObjectProvider<Tesseract>` 주입, doOCR 호출마다 신규 인스턴스
6. ✅ `KafkaProducerService.sendWarrantyExpiring` — `CompletableFuture<SendResult>` 반환
7. ✅ `WarrantyExpirationScheduler` — `@Scheduled(zone="Asia/Seoul")` + `LocalDate.now(KST)` + `.get(5초)` 동기 대기 + 실패 시 markExpiringAlertSent skip
8. ✅ `DefectWarranty.ocrStatus` — `@ColumnDefault("'MANUAL'")` 추가 (기존 데이터 마이그레이션 안전)

### 검증
- gradlew 부재로 컴파일 검증 못함 — 사이클 C(통합 검증)에서 docker compose build로 검증 예정
- 정적 정합성 점검: import 정합, 메서드 시그니처 일치, ColumnDefault Hibernate annotation 정상

### 다음 후속
- `/code-review` MEDIUM 7건은 P1 신규 등록 (사이클 B에서 ADR-013 정식화 후 자동 사이클로 처리)
