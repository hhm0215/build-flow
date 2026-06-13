# BuildFlow 진행 상황 (초기 계획 — 2026-04-04 스냅샷)

> **이 파일은 초기 Phase 계획 기록용입니다. 최신 상태는 아래 4개 문서 세트를 참고하세요.**
>
> - `.claude/BACKLOG.md` — 다음 작업 우선순위 (단일 진실원)
> - `.claude/PROGRESS.md` — 완료 이력 + 현재 git 상태
> - `.claude/RETROSPECTIVE.md` — 실패/사고 회고 + 재발 방지 규칙
> - `.claude/plans/` — 작업별 계획 문서 (`YYYY-MM-DD-{slug}.md`)
>
> 워크플로우 사이클은 `CLAUDE.md` "## 개발 워크플로우" 참고.

---

## 현재 상태: 기획/설계 단계

---

## Phase 0: 기획 & 설계

- [x] 프로젝트 개요 정의
- [x] 기능 목록 확정 (PLANNING.md)
- [x] 아키텍처 설계 (ARCHITECTURE.md)
- [x] ERD 설계 (ERD.md)
- [x] API 명세 (API_SPEC.md)
- [x] 기술 결정 기록 (DECISIONS.md)
- [x] CLAUDE.md (프로젝트 하네스)
- [ ] Docker Compose 작성
- [ ] GitHub 레포 생성 + 초기 폴더 구조

## Phase 1: 인프라 서비스

- [ ] eureka-server 생성 + 설정
- [ ] config-server 생성 + Git 설정 레포
- [ ] gateway-server 생성 + 라우팅 + JWT 필터
- [ ] Docker Compose (MySQL, Redis, Kafka, Zipkin)
- [ ] 인프라 서비스 통합 테스트

## Phase 2: auth-service

- [ ] Spring Security + JWT 발급/검증
- [ ] 회원가입/로그인 API
- [ ] Redis JWT 블랙리스트 (로그아웃)
- [ ] ADMIN/VIEWER 권한 분리

## Phase 3: estimate-service

- [ ] 공내역서 파일 업로드/다운로드/목록
- [ ] 견적서 파일 업로드/금액 입력/목록
- [ ] Kafka 이벤트 발행 (estimate.uploaded)
- [ ] Redis 분산락 (동시 수정 방지)

## Phase 4: site-service + purchase-service

- [ ] 현장 CRUD + 상태 관리
- [ ] 거래처(발주처) CRUD
- [ ] 매입 CRUD + Kafka 발행
- [ ] Kafka 소비 → 손익 재계산
- [ ] site_profit_cache 비정규화 테이블
- [ ] 전체 대시보드 API

## Phase 5: tax-service

- [ ] 세금계산서 CRUD (매출/매입)
- [ ] 입금 확인 등록
- [ ] 미수금 자동 계산
- [ ] Kafka 이벤트 발행

## Phase 6: notification-service

- [ ] Kafka 소비 → 알림 생성
- [ ] 하자보증보험 PDF 업로드 + OCR (Tesseract)
- [ ] 만료 임박 경고 로직
- [ ] 미수금 연체 알림

## Phase 7: chat-service (RAG 챗봇)

- [ ] LLM function calling 의도 파악
- [ ] OpenFeign으로 타 서비스 데이터 조회
- [ ] SSE 스트리밍 응답
- [ ] 대화 세션/메시지 저장

## Phase 8: frontend

- [ ] 프로젝트 세팅 (Vite + React + TS + Ant Design)
- [ ] 로그인 화면
- [ ] 대시보드 (전체 현장 종합)
- [ ] 현장 목록/상세 (문서 4종 탭)
- [ ] 플로팅 AI 챗봇
- [ ] 알림 벨

## Phase 9: 마감

- [ ] 통합 테스트
- [ ] JMeter 부하 테스트 + 결과 기록
- [ ] README.md 작성 (포트폴리오용)
- [ ] 아키텍처 다이어그램 이미지 생성

---

## 최근 작업 이력

| 날짜 | 작업 내용 |
|------|----------|
| 2026-04-04 | 기획서, 아키텍처, ERD, API 명세, 기술 결정 문서 작성 |

---

## 현재 이슈/블로커

(없음)

---

## 변경 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026-04-04 | 초안 |
