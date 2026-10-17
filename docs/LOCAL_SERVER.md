# 로컬 서버(LAN) 운영 런북

> 사무실·가정 내부망의 다른 PC/휴대폰에서 이 PC의 BuildFlow를 쓰기 위한 절차. 인터넷 공개는 [VPS 공개 HTTPS 테스트 파일럿](VPS_PUBLIC_PILOT.md)을 따른다. 실데이터 입력 게이트(`docs/SECURITY_OPERATIONS.md`, ADR-018)는 LAN에서도 동일하게 적용한다.

## 노출 정책

| 대상 | 바인딩 | 비고 |
|---|---|---|
| frontend(Nginx) | `127.0.0.1:3000` + `<LAN IP>:3000` | 유일한 LAN 진입점. `/api`는 Nginx가 내부 Gateway로 프록시, 로그인 6회/분 제한 |
| Gateway·8081~8087·Eureka·Config | `127.0.0.1` | LAN 직접 접근 불가 |
| MySQL·Redis·Kafka·Zipkin·Ollama | `127.0.0.1` | LAN 직접 접근 불가 |

`0.0.0.0`(모든 인터페이스)·공인 IP·링크 로컬 주소 바인딩은 금지다. `scripts/vps/verify_lan_compose.py`가 이를 검사하고 CI도 같은 검사를 실행한다.

## 시작

1. 이 PC의 사설 IPv4를 확인한다(`Get-NetIPAddress -AddressFamily IPv4`). 공유기에서 이 PC에 **고정 IP(DHCP 예약)**를 지정해 주소가 바뀌지 않게 한다.
2. 저장소 `.env`에 `LAN_BIND_ADDRESS=<그 IP>`를 추가한다(예: `192.168.0.20`). Git에 커밋하지 않는다.
3. 설정을 먼저 검증한다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.lan.yml config --format json | python scripts/vps/verify_lan_compose.py
```

4. 기동 순서(Eureka → Config → Gateway → 나머지)는 기존과 같다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.lan.yml up -d
```

5. 다른 기기에서 `http://<LAN IP>:3000`으로 접속한다. Gateway CORS는 `http://<LAN IP>:3000`과 `http://localhost:3000`만 허용한다.

## Windows 방화벽 (사용자 실행)

네트워크 프로필이 **개인(Private)**인지 확인하고, 같은 서브넷에서만 3000번을 허용한다. 관리자 PowerShell에서 실행한다.

```powershell
New-NetFirewallRule -DisplayName "BuildFlow LAN 3000" -Direction Inbound -Protocol TCP -LocalPort 3000 -RemoteAddress LocalSubnet -Profile Private -Action Allow
```

공용(Public) 프로필이나 `Any` 원격 주소로 열지 않는다. 사용을 마치면 `Remove-NetFirewallRule -DisplayName "BuildFlow LAN 3000"`으로 제거한다. 공유기 포트포워딩은 설정하지 않는다(인터넷 노출이 된다).

## 보안 한계

- LAN 구간은 **HTTP 평문**이다. 같은 네트워크의 다른 기기가 로그인 정보를 볼 수 있으므로 신뢰하는 내부망(가족·사무실 전용 Wi-Fi)에서만 쓴다. 손님용·공용 Wi-Fi에서는 쓰지 않는다. 내부망이라도 실데이터 운영 전에는 HTTPS(사설 인증서 또는 VPS 공개 경로)를 검토한다.
- Redis는 비밀번호가 없지만 `127.0.0.1`과 내부 Docker 네트워크에만 있다. 앱이 DB root 계정을 공유하는 문제는 [DB 운영 가이드](DATABASE_OPERATIONS.md)의 최소 권한 분리 단계에서 다룬다.
- 백업·복구는 [DB 운영 가이드](DATABASE_OPERATIONS.md)를 따른다. LAN 공개가 백업 게이트를 대체하지 않는다.

## 실측 기록

- 2026-10-17 로컬 Docker: `LAN_BIND_ADDRESS`를 이 PC의 사설 IP로 지정해 기동. LAN 주소에서 프론트 200, Gateway·8081·8083·MySQL·Redis·Kafka·Eureka·Config 연결 거부, LAN 출처 로그인 요청 CORS 통과(잘못된 테스트 자격 401)·외부 출처 403·무인증 API 401. 다른 물리 기기 접속과 방화벽 규칙은 사용자 환경에서 PENDING.
