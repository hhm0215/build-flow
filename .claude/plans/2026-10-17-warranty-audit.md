# 보증보험 확인 감사 이력

- **시작일**: 2026-10-17
- **BACKLOG 항목**: P0 "공개 파일럿 CRUD 실동선 검증·결함 수리" — 실데이터 전 확인 근거(누가·언제 어떤 OCR 값을 확인했는지)
- **예상 규모**: M
- **상태**: DONE

## 목표

보증 행의 값이 바뀌는 모든 경로(직접 등록, OCR 결과 반영, 수동 수정·확정, 삭제)를 추가 전용(append-only) 이력으로 남기고 조회 API를 둔다. 원본 PDF·현재 행은 바꾸지 않는다.

## 접근법 (에이전트 결정, 사용자 위임)

- notification-service에 `warranty_audit_events` 테이블(엔티티): warrantyId, action(CREATED/OCR_APPLIED/OCR_FAILED/MANUAL_UPDATED/DELETED), actor(게이트웨이 `X-User-Id`, 없으면 `system`), 변경 후 값 스냅샷(보험사·증권번호·기간·금액·ocrStatus·expiryConfirmedAt), occurredAt(KST). 보증 삭제 후에도 이력은 남긴다(FK 없음).
- 같은 트랜잭션에서 기록해 값 변경과 이력이 함께 커밋/롤백된다.
- `GET /api/v1/warranties/{id}/history` (시간순). 업데이트·삭제 API 수정 없이 기록만 추가.
- 공유 관리자 계정이라 actor는 계정 단위이며 실제 사람을 증명하지 못한다는 한계를 문서화.

## 리스크

- 신규 테이블은 `ddl-auto: update`로 생성(기존 테이블 무변경). 운영 반영은 마이그레이션 게이트 이후.
- 이력에 민감정보(파일 경로)는 넣지 않는다.

## 검증

단위/JPA 테스트(롤백 시 이력 미기록, 삭제 후 이력 유지), 로컬 Docker 실측.

## 결과 (작업 후 기록)

- 백엔드: `WarrantyAuditEvent`·기록기·`GET /warranties/{id}/history`, 6개 변경 경로 연결. JPA 테스트 4건(시간순·삭제 후 유지·롤백 시 미기록·헤더 검증) 포함 notification 테스트 PASS.
- 프론트: 타입·`useWarrantyHistory`·MSW 이력·보증 수정 모달의 "변경·확인 이력" 목록. lint·144건·빌드 PASS.
- 로컬 Docker: 테이블 자동 생성, 등록/수정/삭제 `metal`, 업로드 `metal`→OCR 반영 `system`, 헤더 없는 삭제 `unknown`, 삭제 후 이력 조회 PASS. Gateway 경유 실제 로그인 토큰 경로는 자격 증명이 필요해 미실측.
