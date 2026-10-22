# 공개 스택 업무 서비스 포트 비게시 (Gateway 우회 차단)

- **시작일**: 2026-10-22
- **BACKLOG 항목**: P1 "보안·인프라 반복 점검 및 공개 전 하드닝" — 2026-10-22 발견(Gateway 우회 신뢰 경계)
- **예상 규모**: S
- **상태**: DONE

## 목표
공개 VPS 스택에서 업무 서비스·Eureka·Config 포트를 호스트에 게시하지 않아, 서버 안의 프로세스·SSH 터널 사용자가 Gateway 인증과 `X-User-Id` 감사 기록을 우회하지 못하게 한다.

## 배경 / 동기
업무 서비스는 Gateway가 넣은 `X-User-Id`를 토큰 재검증 없이 신뢰한다. 기존 구성은 `127.0.0.1:8081~8087`·8761·8888을 게시해 호스트 내부에서 무인증 생성·삭제가 가능했다(로컬 실측).

## 결정 (에이전트, 위임)
- 추천안 채택: 공개 override(`docker-compose.public.yml`)에서 9개 서비스 `ports: !reset []`. Gateway 8080·frontend 3000은 SSH 터널용 loopback 유지, MySQL/Redis/Kafka/Zipkin loopback은 이번 범위 밖.
- 대안: 서비스 측 JWT 재검증 — 6개 서비스 공통 필터·키 배포가 필요해 범위가 크다. 포트 비게시로 현재 위협(호스트 내 직접 호출)을 먼저 닫는다.
- 로컬 개발 `docker-compose.app.yml`은 그대로 둔다(개별 서비스 디버깅).

## 산출물
- `docker-compose.public.yml` 포트 리셋, `verify_public_compose.py` 허용 목록(frontend·gateway·mysql·redis·kafka·zipkin 외 게시 거부)과 회귀 2건
- `buildflow-release status`에 컨테이너 내부 health 10개 점검(설치본 갱신은 root 필요)
- `docs/SECURITY_OPERATIONS.md` 종료 점검 기준 수정

## 리스크
- `!reset`은 Compose 2.24.4+ 필요 → VPS `docker compose version` v5.5.1 실측.
- 설치된 `/usr/local/sbin/buildflow-release`는 root 복사본이라 status 내부 health는 root 재설치 전까지 적용되지 않는다. deploy 경로는 저장소의 검증기를 사용하므로 영향 없음.

## 결과
- 렌더 실측: 새 구성은 9개 서비스 게시 0, 기존 구성은 새 검증기가 `auth-service:8081`로 거부.
- `test_verify_*_compose.py` 21개 PASS.
- CI 러너 Compose v2.38.2는 앵커 별칭 경유 `!reset`을 무시해 첫 실행이 검증기에서 실패 → 서비스별 명시로 바꿔 통과(RETROSPECTIVE 2026-10-22). VPS 반영·실측은 PROGRESS 참조.
- 후속(같은 날): Redis·Kafka·Zipkin도 공개 스택에서 비게시, 검증기 허용 목록을 frontend·gateway·mysql로 축소(회귀 1건 추가). VPS 호스트에서 세 포트를 쓰는 스크립트 없음(predeploy 백업은 docker exec).
