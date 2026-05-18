# BuildFlow 코드 리뷰 & 개선 추적 문서

> 점검일: 2026-04-30  
> 점검 범위: 프론트엔드 UI/코드, 백엔드 코드, MSA 아키텍처, API/보안

---

## 영역별 점수 요약

| 영역 | 점수 | 상태 |
|------|------|------|
| 백엔드 코드 컨벤션 | 9/10 | 양호 |
| 백엔드 서비스 로직 | 8/10 | 양호 |
| JPA / 쿼리 최적화 | 6/10 | 개선 필요 |
| 백엔드 테스트 | 0/10 | 미구현 |
| API / 보안 | 5/10 | 개선 필요 |
| MSA 인프라 | 6/10 | 개선 필요 |
| 프론트 UI/디자인 | 7/10 | 양호 |
| 프론트 코드 품질 | 6.5/10 | 개선 필요 |

---

## HIGH 우선순위 개선 체크리스트

### 보안

- [x] **H-SEC-1**: Gateway 토큰 블랙리스트 검증 추가
  - 파일: `gateway-server/.../filter/AuthorizationHeaderFilter.java`
  - 내용: Redis 블랙리스트 확인 로직 추가. 로그아웃된 토큰 무효화
  
- [x] **H-SEC-2**: SecurityConfig 경로별 인증 설정
  - 파일: 각 서비스 `SecurityConfig.java`
  - 내용: `anyRequest().permitAll()` → 경로별 명시적 인증으로 변경

- [x] **H-SEC-3**: Gateway CORS 설정 추가
  - 파일: `gateway-server/.../config/CorsConfig.java`
  - 내용: localhost:3000 허용, 프로덕션 도메인 환경변수화

- [x] **H-SEC-4**: 보안 응답 헤더 추가
  - 파일: `gateway-server/.../filter/SecurityHeaderFilter.java`
  - 내용: X-Content-Type-Options, X-Frame-Options, X-XSS-Protection 등

### 백엔드

- [x] **H-BE-1**: Site-Client N+1 쿼리 해결
  - 파일: `site-service/.../repository/SiteRepository.java`
  - 내용: @EntityGraph 추가하여 Client 즉시 로딩

- [x] **H-BE-2**: DashboardService 페이징/최적화
  - 파일: `site-service/.../service/DashboardService.java`
  - 내용: findAll() → 필요한 데이터만 조회하도록 최적화

### MSA 인프라

- [x] **H-INFRA-1**: Kafka 멱등성/신뢰성 설정
  - 파일: 각 서비스 `application.yml`
  - 내용: Producer: enable.idempotence, acks=all / Consumer: auto-commit=false

- [x] **H-INFRA-2**: 프로파일별 설정 분리
  - 파일: 각 서비스 `application.yml`, `application-docker.yml`
  - 내용: localhost 하드코딩 제거, docker 프로파일 분리

### 프론트엔드

- [x] **H-FE-1**: 에러 상태 UI 처리 추가
  - 파일: 각 페이지 컴포넌트, `axiosInstance.ts`
  - 내용: isError 체크, 에러 컴포넌트, axios 인터셉터 강화

- [x] **H-FE-2**: 색상 토큰 중앙화 + 다크 테마 오버라이드
  - 파일: `frontend/src/styles/colors.ts`, `frontend/src/index.css`
  - 내용: 하드코딩 색상 → 토큰 통합, Ant Design 다크 테마 보완

---

## MEDIUM 우선순위 개선 체크리스트

### 백엔드

- [ ] **M-BE-1**: GlobalExceptionHandler 통일
  - 파일: 각 서비스 `GlobalExceptionHandler.java`
  - 내용: BindException 처리 방식 통일 (getFieldErrors 사용)

- [ ] **M-BE-2**: 로그 레벨 조정
  - 파일: 각 서비스 `GlobalExceptionHandler.java`, `ProfitService.java`
  - 내용: BusinessException → warn, 상세 로그 → debug

- [ ] **M-BE-3**: Collectors.toList() → .toList() 통일
  - 파일: `DashboardService.java` 등
  - 내용: Java 17 기준 `.toList()` 통일

- [ ] **M-BE-4**: TaxInvoiceRepository 메서드명 → @Query
  - 파일: `TaxInvoiceRepository.java`
  - 내용: 긴 메서드명 쿼리를 @Query로 리팩토링

### MSA 인프라

- [ ] **M-INFRA-1**: 공통 라이브러리 모듈 추출
  - 내용: ApiResponse, BusinessException, KafkaEvent 등 공통 코드를 common 모듈로

- [ ] **M-INFRA-2**: Docker Compose 의존성 보완
  - 파일: `docker-compose.app.yml`
  - 내용: Redis depends_on 추가, Zipkin/Ollama 헬스체크

- [ ] **M-INFRA-3**: Zipkin 엔드포인트/샘플링 설정
  - 파일: 각 서비스 `application.yml`
  - 내용: tracing endpoint, sampling probability 설정

- [ ] **M-INFRA-4**: Feign Client 구현 및 fallback
  - 내용: 서비스 간 동기 통신을 위한 FeignClient 인터페이스 정의

- [ ] **M-INFRA-5**: Redis 캐싱 전략 수립
  - 내용: @Cacheable 적용, 키 네이밍 가이드, TTL 정책

### 프론트엔드

- [ ] **M-FE-1**: any 타입 제거 (3건)
  - 파일: `TaxListPage.tsx`, `WarrantyListPage.tsx`

- [ ] **M-FE-2**: 커스텀 훅 추출
  - 내용: useNumberFormat, useSearch 등

- [ ] **M-FE-3**: React.memo / useMemo / useCallback 적용
  - 내용: 리렌더링 최적화

- [ ] **M-FE-4**: 재사용 컴포넌트 추출
  - 내용: Badge, AlertBanner, EmptyState, LoadingSkeleton

- [ ] **M-FE-5**: 404 페이지 + 에러 바운더리 추가
  - 파일: `App.tsx`

- [ ] **M-FE-6**: ESLint + Prettier 설정
  - 내용: .eslintrc.json, .prettierrc 생성

- [ ] **M-FE-7**: 번들 최적화
  - 파일: `vite.config.ts`
  - 내용: manualChunks 설정, Ant Design 트리셰이킹

### UI/디자인

- [ ] **M-UI-1**: 반응형 레이아웃 기초
  - 파일: `index.css`, `MainLayout.tsx`
  - 내용: @media 쿼리, 모바일 사이드바 처리

- [ ] **M-UI-2**: 모달/폼 다크 테마 오버라이드 보완
  - 파일: `index.css`
  - 내용: InputNumber, DatePicker, Select 등 오버라이드

- [ ] **M-UI-3**: 아이콘 strokeWidth 일관성
  - 내용: 아이콘 설정 상수화

---

## 변경 이력

| 날짜 | 영역 | 작업 내용 | 커밋 |
|------|------|----------|------|
| 2026-04-30 | 문서 | REVIEW.md 초안 작성 | - |
| 2026-04-30 | 보안 | H-SEC-1~4 Gateway 보안 강화 | - |
| 2026-04-30 | 백엔드 | H-BE-1~2 JPA 최적화 | - |
| 2026-04-30 | 인프라 | H-INFRA-1~2 Kafka/프로파일 | - |
| 2026-04-30 | 프론트 | H-FE-1~2 에러 UI, 색상 토큰 | - |
