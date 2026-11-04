# 배포 후 옛 청크 요청 실패 복구 (캐시 정책 + 1회 자동 새로고침)

## 목표

프론트를 새로 배포해도 이미 열려 있던(또는 캐시된) 페이지가 "문제가 생겼습니다"에 갇히지 않게 한다.

## 배경 / 동기

2026-11-04 로컬 실측: 재배포 뒤 옛 `index.html`/`index-*.js`가 `assets/DashboardPage-<옛해시>.js`를 요청 →
Nginx SPA fallback이 없는 자산에 `index.html`(text/html)을 200으로 반환 → "Failed to load module script ... MIME type text/html"
→ 페이지 ErrorBoundary. `index.html` 응답에 Cache-Control이 없어(ETag만) 일반 새로고침으로도 복구되지 않았다.

## 접근법

1. `frontend/nginx.conf`
   - `map $uri $static_cache_control`로 경로별 값 선택 후 server 수준 `add_header Cache-Control` 1개만 추가.
     location 안 add_header는 server 보안 헤더(CSP Report-Only·X-Frame-Options 등) 상속을 끊으므로 쓰지 않는다.
   - `/assets/` = `public, max-age=31536000, immutable`, 그 외(index.html·SPA fallback) = `no-cache`, `/api/` = 빈 값(업스트림 유지).
   - `location /assets/ { try_files $uri =404; }` + 명명 location에서 `text/plain` 404 본문.
   - Caddy는 Cache-Control을 건드리지 않으므로 변경 없음.
2. 앱
   - `utils/chunkReload.ts`: 청크 오류 판별, sessionStorage 시각 기록으로 60초 창 안 1회만 새로고침, `vite:preloadError` 핸들러.
   - `ErrorBoundary`: 청크 오류면 1회 자동 새로고침 시도, 화면은 "새 버전이 배포되어 새로고침이 필요합니다." + 새로고침 버튼.

## 산출물 체크리스트

- [x] nginx 캐시 정책·자산 404
- [x] chunkReload 유틸 + main.tsx 핸들러 설치
- [x] ErrorBoundary 청크 오류 안내
- [x] Vitest (유틸 + ErrorBoundary)
- [x] 별도 컨테이너로 Nginx 실측

## 리스크 / 모르는 것

- 저장소 접근이 막힌 브라우저(사생활 모드 일부)는 반복 방지를 보장할 수 없어 자동 새로고침을 하지 않고 안내만 보인다.
- `no-cache`라 index.html은 매 진입 시 재검증(304) 요청 1회가 추가된다 — 682B 수준이라 무시 가능.

## 테스트 / 검증

- `bun run lint && bun run test && bun run build`
- frontend 이미지를 별도 태그로 빌드, 별도 컨테이너(`--add-host gateway-server:127.0.0.1`, 127.0.0.1:18089)에서 curl 확인 후 컨테이너·이미지 삭제. 로컬 실사용 스택은 건드리지 않음.

## 결과 (작업 후 기록)

- lint 통과, Vitest 68파일 341테스트 → 신규 포함 전체 통과, build 통과.
- curl 실측:
  - `/` 200 text/html, `Cache-Control: no-cache`, 보안 헤더 4종(CSP-RO·XFO·XCTO·Referrer) 유지, ETag 재검증 304
  - `/dashboard` 200 text/html, `no-cache`
  - `/assets/DashboardPage-<실해시>.js` 200 application/javascript, `public, max-age=31536000, immutable`
  - `/assets/DashboardPage-oldhash.js` 404 text/plain(`Not Found`), Cache-Control 없음, 보안 헤더 유지
  - `nginx -t` 성공
- 참고(범위 밖): `public/`에 없는 루트 정적 파일(예: `/favicon.svg`)은 여전히 SPA fallback으로 200 text/html.
