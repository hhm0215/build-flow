# 매입 세금계산서 목록 → 현장 미배정 매입 가져오기

- **시작일**: 2026-11-04
- **BACKLOG 항목**: 로컬 실데이터 적재(견적·매출 세금계산서에 이은 매입) — 사용자 결정 2026-11-04
- **예상 규모**: M
- **상태**: DONE

## 목표
홈택스 '매입전자세금계산서목록' 엑셀을 현장 미배정 매입(PR #299: `siteId` 선택)으로 적재하는 도구.
미리보기 → `--emit-plan` → 브라우저 `buildflowApplyPurchasePlan` → `--record-result`(ledger kind `purchase`)가
견적·매출 세금계산서와 같은 안전장치로 동작하면 완료. 현장 배정은 가족이 화면에서 한다.

## 배경 / 동기
매입 세금계산서에는 현장 정보가 없어 자동 연결이 어렵다. 사용자 결정(2026-11-04)으로 미배정 매입으로 올리고
화면(PR #299 `PATCH /api/v1/purchases/{id}/site`)에서 배정한다.

## 접근법
- 파서: 매출과 같은 양식이라 `tax_sheet.py` 재사용. 머리글 오류 문구를 매출·매입 공통으로 바꾸고, 같은 열 이름이
  둘 이상 나오는 양식(공급자·공급받는자 '상호')을 `repeated`로 기록 — 매입 도구는 '상호' 중복이면 ERROR.
- `purchases.py`: `taxes.py`의 `FilePlan`·`mark_duplicates`(기간 겹친 파일 같은 행 제거, 같은 바이트 사본 제거)·
  `read_result`·저장소 밖 쓰기를 재사용. 음수(수정) 행은 기본 차단, `--skip-negative`로 제외.
- `PurchaseCreateRequest` 대응(본문에 이 6칸만, `siteId` 없음):
  | DTO 필드 | 값 | 경계 |
  |---|---|---|
  | `itemName` | 세금계산서 품목명(200자 자름, 넘치면 전체를 메모에 `품목 전체:`), 비면 `(품목명 없음)` | `@NotBlank @Size(200)` |
  | `quantity` | 1 | `@Positive` |
  | `unitPrice` | 공급가액 | `@PositiveOrZero @Digits(10,2)` → 정수 10자리 초과는 ERROR |
  | `supplier` | 상호(200자) | `@Size(200)` |
  | `purchaseDate` | 작성일자 | `LocalDate` |
  | `memo` | `[가져오기 매입 sha256:<16자> 행:<n>]` | `@Size(16000)` |
- 세액 필드가 없으므로 금액은 공급가액 기준. 세액·합계는 본문(품목명·메모)에 넣지 않고 미리보기·계획 정보·
  ledger(`amountBasis: supply`)에만 남긴다. tax-service 매입 세금계산서는 만들지 않는다(현장 필수 — 범위 밖).
- 비공사 추정(세무·전기요금·렌탈 등)은 막지 않고 미리보기 집계·계획·결과의 `hint`에만 표시.
- `browser_apply.js` `buildflowApplyPurchasePlan`: 로컬 호스트·계획 origin 일치·로그인 확인, `GET /api/v1/purchases`
  목록에서 메모 표식이 있으면 `linked`, 다른 목록 파일에서 가져온 매입(표식 있음)과 매입일·공급업체·단가·품목명이
  같으면 `같은 내용 이미 있음`으로 건너뜀(수동 입력 매입과는 비교 안 함). `POST`만 하고 배정 `PATCH`는 부르지 않음.
  계획 본문에 6칸 외(`siteId` 등)가 있으면 거부.
- `estimates.py --record-result`도 매입 결과를 같은 기록으로 받는다(매출과 같은 방식).

## 산출물 체크리스트
- [x] `scripts/import/tax_sheet.py` 공통화(`find_header_columns`, `repeated`)
- [x] `scripts/import/purchases.py`
- [x] `scripts/import/browser_apply.js` `buildflowApplyPurchasePlan`
- [x] `scripts/import/estimates.py` 결과 기록 분기
- [x] `scripts/import/test_purchases.py`(합성 fixture만)

## 리스크 / 모르는 것
- 미배정 매입 API는 PR #299 병합 후에만 동작(그 전 서버는 `siteId` 누락을 400으로 거부).
- 수정(음수) 세금계산서는 매입으로 표현 불가 → 원 건과 화면에서 상계해야 한다.
- 같은 날 같은 업체·같은 금액·같은 품목의 서로 다른 세금계산서가 다른 파일에 나뉘어 있으면 브라우저 같은 내용
  규칙이 하나를 건너뛸 수 있다(매출과 같은 한계, 결과 `skipped`에 남음).

## 테스트 / 검증
- `python -X utf8 -m unittest discover -s scripts/import -p "test_*.py"` 전부 통과.
- 파싱(새·구 양식, xls·xlsx), 요약 불일치 거부, DTO 경계, '상호' 중복 머리글, 기간 겹침·사본 중복 제거,
  음수 차단·`--skip-negative`, 계획 본문(`siteId`·세액 없음), ledger 기록 멱등·다른 kind와 섞이지 않음,
  node e2e(미배정 생성·재실행 연결·기록 후 재계획 0건·다른 파일 같은 내용·origin·토큰·`siteId` 본문 거부).
- 실파일은 미리보기만(적용·DB 쓰기 없음). 결과 숫자는 이 문서·PR에 적지 않고 작업 보고로만 전달.

## 결과 (작업 후 기록)
- 위 산출물 완료, 테스트 전부 통과. 실파일 미리보기에서 ERROR 0, 수정(음수) 행이 있는 파일은 `--skip-negative`
  확인 필요 상태로 확인됨(숫자는 보고로만).
- 적용(브라우저 실행)·현장 배정은 PR #299 반영된 로컬 풀 Docker에서 사용자가 진행.
