# notification-service Phase 2 — PDF OCR 자동 채움

- **시작일**: 2026-06-18
- **BACKLOG 항목**: P0 (notification-service Phase 2 — PDF OCR 자동 채움)
- **예상 규모**: M~L
- **상태**: DONE (2026-06-18 완료, 통합 검증 다음 사이클)

## 목표

하자보증보험 PDF를 업로드하면 OCR로 보험사·증권번호·시작·만료일을 자동 추출해 `DefectWarranty` 엔티티를 채운다. 실패 시 PENDING/FAILED 상태로 표시하고 사용자가 수동 입력으로 보완.

## 배경 / 동기

- Phase 1(스케줄러)은 만료 알림을 자동화했지만, **등록 자체는 여전히 수동 입력**
- 한 현장당 보증보험 1~수 건. 손으로 보험사·증권번호·날짜 입력하는 게 가장 귀찮은 작업
- OCR 자동 채움 + 사용자 수정 가능한 패턴이 1인 환경에 최적

## 핵심 설계 결정

### 1. Tesseract는 notification-service만 필요 → 전용 Dockerfile 분리

**문제**: 현재 Dockerfile은 9개 서비스 공통(`alpine` 기반). Tess4J는 JNA 의존이라 Alpine `musl libc`에선 불안정. 또 다른 8개 서비스에 Tesseract 80MB 추가 낭비.

**결정**:
- `notification-service/Dockerfile` 신설 (debian-slim 기반, Tesseract 4 + `kor.traineddata` + `eng.traineddata`)
- 나머지 8개 서비스는 기존 공통 `Dockerfile` 그대로
- `docker-compose.app.yml`의 notification-service `build.dockerfile` 경로만 변경

### 2. PDF 텍스트 추출 — 하이브리드 (속도 우선)

```
PDF 입력
  ↓ 1차: PDFBox PDFTextStripper
  ↓
추출 텍스트 길이 ≥ 50자?
  ├ YES → 정규식 파싱 (텍스트 PDF, 빠름)
  └ NO  → 2차: PDFBox로 페이지를 BufferedImage 렌더링 → Tess4J OCR (스캔 PDF, 느림)
       → 정규식 파싱
```

스캔 PDF에 PDFBox 텍스트 추출을 시도해도 빈 결과만 나오므로 비용 거의 0. 텍스트 PDF엔 Tess4J 안 돌려서 속도 확보.

### 3. 정규식 파싱 — 한국 보증보험 양식 가정

`WarrantyOcrParser` 정적 유틸:
- **보험사**: "○○화재", "○○보증보험", "주식회사 ○○" 패턴 + 알려진 보험사 화이트리스트 (서울보증, 한화손해보험 등)
- **증권번호**: `보험번호|증권번호|보증번호` 라벨 다음 `[A-Z0-9가-힣\-]{6,30}` (한글 일부 포함)
- **날짜 두 개 추출**: `보증기간|유효기간|보험기간` 라벨 다음 두 날짜
  - 포맷: `YYYY-MM-DD` / `YYYY.MM.DD` / `YYYY년 MM월 DD일`
- **실패 처리**: 어느 필드든 못 찾으면 그 필드만 `null`로 두고 다른 필드는 채움. 사용자가 PUT으로 수정 가능

**한계**: 양식이 회사마다 달라 "best effort" 수준. 텍스트 PDF에서 70~90%, 스캔 PDF에서 30~70% 정도 추출 기대.

### 4. 비동기 처리 — @Async

- `POST /api/v1/warranties/upload` 호출 → 파일 저장 + DB에 `ocrStatus=PENDING` 레코드 즉시 생성 + WarrantyResponse 반환 (HTTP 202 Accepted)
- 백그라운드에서 `WarrantyOcrService.processAsync(warrantyId)` 실행
- 완료 시 entity 갱신 (`ocrStatus=SUCCESS/FAILED`, 추출 필드 채움)
- 프론트엔드는 polling 또는 새로고침으로 상태 갱신 (이번 Phase 외)

### 5. OCR 상태 필드

`DefectWarranty.ocrStatus` ENUM:
- `PENDING` — 업로드 직후, OCR 진행 중
- `SUCCESS` — OCR 완료, 추출 필드 채워짐
- `FAILED` — OCR 자체 실패 (파일 손상, Tesseract 에러 등)
- `MANUAL` — 사용자가 직접 입력한 케이스 (기존 JSON CRUD 경로)

### 6. 파일 저장 — 로컬 파일시스템

- 디렉토리: `${app.upload-dir:./uploads/warranties}` (외부화)
- 파일명: `{UUID}.pdf`로 저장 (원본명 충돌 방지)
- `DefectWarranty.filePath`에 상대 경로 저장
- `.gitignore`에 `uploads/` 추가
- S3 전환 시 `WarrantyStorageService` 인터페이스로 추상화 (이번 Phase 외)

### 7. 의존성

`notification-service/build.gradle`에 추가:
```gradle
implementation 'net.sourceforge.tess4j:tess4j:5.13.0'
implementation 'org.apache.pdfbox:pdfbox:3.0.3'
```

## 산출물 체크리스트

### 빌드/인프라
- [ ] `notification-service/build.gradle`: Tess4J + PDFBox 의존성
- [ ] `notification-service/Dockerfile` 신설: debian-slim + Tesseract + kor·eng traineddata
- [ ] `docker-compose.app.yml`: notification-service `build.dockerfile: notification-service/Dockerfile`
- [ ] `.gitignore`: `uploads/` 추가
- [ ] `application.yml`: `app.upload-dir`, `app.ocr.{tessdata-path,min-text-length}` 외부화

### 도메인
- [ ] `DefectWarranty`: `ocrStatus` 필드 (`OcrStatus` ENUM) + `applyOcrResult(...)` 메서드
- [ ] `OcrStatus` enum 신설
- [ ] `DefectWarrantyService.createFromOcr(...)` — 업로드 + PENDING 생성
- [ ] `DefectWarrantyController.upload(MultipartFile, Long siteId)` — `POST /upload` 엔드포인트 (202 Accepted)

### OCR 처리
- [ ] `WarrantyOcrService` — async 메서드 `processAsync(Long warrantyId)`, 하이브리드 파이프라인
- [ ] `WarrantyOcrParser` — 정규식 정적 유틸 (보험사/증권번호/시작·만료일)
- [ ] `WarrantyOcrConfig` — `@EnableAsync`, Tesseract 인스턴스 빈 등록
- [ ] `WarrantyOcrParserTest` — 샘플 텍스트 input으로 정규식 검증 (단위 테스트)

### 결과 반영
- [ ] BACKLOG.md: P0 Phase 2 항목 제거, "프론트 OCR 상태 표시 + 폴링" 신규 P1 등록
- [ ] PROGRESS.md "완료된 작업" 한 줄 + "다음 세션 진입점" 갱신
- [ ] `docs/ERD.md`: warranty 테이블에 `ocr_status` 컬럼 반영
- [ ] `docs/API_SPEC.md`: `POST /api/v1/warranties/upload` 추가

## 리스크 / 모르는 것

1. **Tess4J + JVM 메모리**: native libraries 로딩으로 시작 시 메모리 사용 ↑. notification-service docker 메모리 제한 검토. 현재 무제한이라 일단 OK
2. **한국어 OCR 정확도**: 실제 보증보험 PDF 샘플이 없어 정규식 튜닝이 부정확할 수 있음. 최초 사용 후 실패 케이스 수집 → 정규식 보강 (Phase 3 후속)
3. **Tess4J 5.13 + macOS Apple Silicon**: native lib 호환 — Docker 안에서만 동작하면 OK. 로컬 개발 시엔 컨테이너 실행 권장
4. **PDFBox 3.x API**: 2.x와 비호환 변경 있음. 최신 안정판 3.0.3 사용
5. **동시 업로드**: `@Async` 기본 ThreadPoolExecutor 코어 풀 8 — 1인 환경엔 충분

## 테스트 / 검증

- `WarrantyOcrParserTest`: 텍스트 input → 추출 결과 검증 (3~5 케이스)
- `WarrantyOcrService` 통합 테스트는 실제 PDF 샘플 필요 → 이번 범위 외 (수동 통합 검증)
- 정적 검증: gradle 빌드 (gradlew 부재라 머지 후 `docker compose up`)

## 결과 (2026-06-18 완료)

### 신규 파일 6종
- `domain/warranty/entity/OcrStatus.java` — PENDING/SUCCESS/FAILED/MANUAL enum
- `domain/warranty/service/WarrantyOcrParser.java` — 정규식 유틸 (보험사 화이트리스트 12종, 증권번호, 날짜 두 개)
- `domain/warranty/service/WarrantyOcrService.java` — `@Async @Transactional` 하이브리드 파이프라인
- `global/config/WarrantyOcrConfig.java` — `@EnableAsync` + Tesseract 빈
- `test/.../WarrantyOcrParserTest.java` — 6 케이스 (빈 텍스트 / 보험사 / 증권번호 / dash 날짜 / 한국어 날짜 / 4필드 통합)
- `notification-service/Dockerfile` — debian jammy + Tesseract 4 + kor·eng traineddata (다른 8 서비스는 alpine 그대로)

### 기존 파일 갱신 9종
- `notification-service/build.gradle` — `pdfbox:3.0.3` + `tess4j:5.13.0`
- `docker-compose.app.yml` — notification-service build.dockerfile 경로 변경
- `.gitignore` — `uploads/` + `**/uploads/` 추가
- `application.yml` — `app.upload-dir`, `app.ocr.{min-text-length,scan-dpi,tessdata-path,languages}`, multipart 20MB 한도
- `DefectWarranty` — `ocrStatus` ENUM 필드 + `createPending`, `applyOcrResult`, `markOcrFailed` 메서드 / 기존 빌더는 default `MANUAL` / nullable 필드 변경 (insuranceCompany/startDate/endDate)
- `WarrantyResponse` — `ocrStatus` 필드 + endDate null 가드
- `DefectWarrantyService` — `createFromOcr(siteId, file)` + 파일 저장 + OCR 비동기 트리거
- `DefectWarrantyController` — `POST /api/v1/warranties/upload` (multipart, 202 Accepted)
- `ErrorCode` — `WARRANTY_FILE_EMPTY`, `WARRANTY_FILE_STORAGE_FAILED` 추가

### 핵심 흐름
```
POST /api/v1/warranties/upload (multipart)
  → DefectWarrantyService.createFromOcr
      → 파일 저장 (UUID.pdf)
      → DefectWarranty.createPending → DB 저장 (ocrStatus=PENDING)
      → WarrantyOcrService.processAsync (@Async, 즉시 반환)
  → 202 Accepted + PENDING WarrantyResponse

[비동기]
WarrantyOcrService.processAsync
  → PDFBox 텍스트 추출 시도 → ≥ 50자면 정규식 파싱
  → 부족 시 Tess4J 페이지별 OCR (kor+eng, 300 DPI)
  → WarrantyOcrParser.parse → Result(insurer/policyNumber/start/end)
  → warranty.applyOcrResult(...) → SUCCESS
  → 실패 시 warranty.markOcrFailed → FAILED
```

### ⚠️ 한계
- 빌드 검증 안 함 (gradlew 부재, BACKLOG P2). 통합 검증은 머지 후 `docker compose build notification-service && up -d`
- 실제 보증보험 PDF 샘플로 정규식 튜닝 안 됨 — 첫 사용 후 실패 케이스 수집해서 보강 (Phase 3 후속, BACKLOG에 신규 등록)
- Tesseract 한글 traineddata 경로(`/usr/share/tesseract-ocr/4.00/tessdata`)가 Ubuntu jammy 기준 — 다른 버전이면 application.yml로 override
- 프론트엔드 폴링·OCR 상태 표시 미구현 — BACKLOG P1 등록

### 다음 후속 후보
- BACKLOG P1 신설: 프론트 `WarrantyListPage`에 OCR 상태 뱃지 + 업로드 모달 + 폴링
- BACKLOG P3 후보: 정규식 튜닝 (실제 PDF 샘플 수집 후)
