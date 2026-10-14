# 보증보험 만료일 KST 경계 일치

## 범위

- 만료 목록 조회·응답 표시·알림 스케줄러가 동일한 `Asia/Seoul` 업무일을 사용한다.
- KST 자정 전후의 고정 시각 회귀 테스트를 추가한다.
- DB 스키마·공개 API·운영 데이터는 변경하지 않는다.

## 검증

- notification-service 테스트와 전체 백엔드 테스트를 실행한다.
- 변경 diff에서 원본·비밀값·다른 도메인 영향을 검토한다.
- CI/PR·운영 반영은 별도 검증 결과로 기록하며 로컬 통과와 혼동하지 않는다.

## 현재 결과

- `notification-service:test`와 전체 `gradlew test` PASS. UTC 15:00의 KST 자정 전후, 만료 당일/다음날 표시 테스트 추가.
- 독립 코드 리뷰의 다건 목록 자정 교차 지적을 반영해 목록도 요청당 업무일을 한 번만 계산하며 회귀를 추가했다. 수정 후 notification-service 테스트 재통과.
- `docs/DELIVERY_PLAN.md`에 회사 PC용 진행 상황 확인 경로와 목표일의 의미를 명시했다.
- PR/CI·VPS 반영은 아직 PENDING. 사용자 소유 `EstimateService.java`와 `output/`은 변경 범위에서 제외한다.
