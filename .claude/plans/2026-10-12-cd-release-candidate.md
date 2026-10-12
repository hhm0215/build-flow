# 검증된 main 릴리스 후보 증거

- **시작일**: 2026-10-12
- **BACKLOG 항목**: 단일 VPS 운영 릴리스 CI/CD
- **예상 규모**: M
- **상태**: IN_PROGRESS

## 목표
기존 CI 5개가 모두 통과한 병합 `main` SHA만 릴리스 후보 JSON artifact로 남긴다. 이번 단계는 VPS 배포·이미지 게시·실데이터 운영 승인이 아니다.

## 배경 / 동기
CI 통과와 VPS 적용을 구분하면서 배포 입력의 출처를 고정해야 한다. 현재 `main`의 required status checks가 비어 있고 실데이터 복구·마이그레이션·최소 권한 게이트도 미완료다.

## 접근법
CI의 5개 job에 의존하는 `main` push 전용 read-only job을 추가한다. 정확한 SHA checkout, 실행 맥락·job 결과·추적된 배포 설정 파일의 체크섬을 검증해 후보 JSON을 생성한다. 생성기는 비밀 파일·심볼릭 링크·경로 탈출을 거부한다. artifact는 단기 보존하고 배포 승인 필드는 false로 고정한다.

## 산출물 체크리스트
- [x] 릴리스 후보 생성기와 실패 경로 단위 테스트
- [x] `main` push CI job 및 artifact 정의
- [x] CI 결과와 배포 상태 구분 문서화
- [x] 보안·인프라 독립 사전·구현 검토
- [ ] PR/병합 SHA와 실제 `main` artifact 확인

## 리스크 / 모르는 것
후보 JSON은 배포 가능한 이미지가 아니다. Docker base 이미지와 Actions tag가 현재 변동 가능하고, VPS 실행 이미지·업로드/DB 복구는 별도 검증이 필요하다. 브랜치 보호 변경은 별도 사용자 답변 후 처리한다.

## 테스트 / 검증
정상 `main` 맥락 성공, PR/develop/실패·누락 job/잘못된 SHA·checkout 불일치·비밀 경로·심볼릭 링크 차단. CI에서 후보 artifact가 `main` push에만 생성되는지 확인한다.

## 결과 (작업 후 기록)
생성기 단위 테스트와 독립 보안·인프라 검토를 통과했다. 리뷰에서 PR 단계에 생성기 테스트가 없다는 지적을 받아 기존 `public-compose` job에도 동일 테스트를 추가했다. CI artifact 생성과 `main` 실행 결과는 PR 병합 후 확인한다. 이 단계에서 VPS를 변경하거나 실데이터 운영을 승인하지 않는다.
