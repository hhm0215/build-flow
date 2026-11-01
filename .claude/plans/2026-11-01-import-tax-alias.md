# 매출 세금계산서 가져오기 — 거래처 별칭·현장 지정

- **시작일**: 2026-11-01
- **BACKLOG 항목**: 로컬 실데이터 적재(USB) 후속 — 매출 세금계산서 현장 연결률 개선
- **예상 규모**: S
- **상태**: DONE

## 목표
`taxes.py` → `buildflowApplyTaxPlan` 흐름에서 연결 못 한 매출 세금계산서 행을, 추측 매칭이 아니라 사람이(또는 주
에이전트가) 확정한 별칭·현장 지정으로 결정적으로 연결한다. 연결 못 한 행은 사람이 지정 파일을 채울 수 있게 CSV로 내보낸다.

## 배경 / 동기
실데이터 첫 적용에서 연결된 행이 소수였다. 세금계산서 상호는 법인 정식명(예: 합성 `(주)가나합성디자인건설`)인데 DB 거래처는
견적서 표기(`가나 합성`)이고, 품목명은 현장명을 줄이거나 순서를 바꿔 쓰고 `잔금`·`계약금` 같은 말을 붙인다.

## 접근법
- `taxes.py --client-alias <CSV>`: `세금계산서 상호,DB 거래처명`. 상호는 `company_key`(browser_apply.js `companyKey`와
  같은 규칙)로 비교. 계획 entry에 `clientName`(별칭 적용 결과, 없으면 상호)·`clientAlias`. 세금계산서 본문 상호는 원본 유지.
- `taxes.py --site-map <CSV>`: `세금계산서 상호(또는 *),품목명,DB 현장명`. 품목명은 정규화 키가 **같을 때만**(포함 아님),
  상호 지정 줄이 `*` 줄보다 우선. 계획 entry에 `siteName`. 같은 키에 다른 값이면 거부.
- 두 파일 모두 저장소 밖 경로만(`outside_repo`), UTF-8 CSV, 첫 줄 머리글·`#` 주석 허용(기존 `load_site_aliases` 관례).
- `browser_apply.js matchSite`: 거래처는 `clientName`으로 찾음(예전 계획은 상호로). `siteName`이 있으면 그 이름(정규화 키)이
  그 거래처 활성 현장으로 정확히 1개일 때만 연결, 아니면 `지정 현장 없음`. 없으면 기존 포함 규칙 → 0개일 때만 보조 규칙:
  품목명 키에서 일반어(금속공사·인테리어·계약금·중도금·선급금·기성금·잔금·공사, 긴 말 먼저)를 뺀 뒤 `현장명 ⊂ 품목명` 또는
  `품목명(4글자 이상) ⊂ 현장명`이 정확히 1개. 포함 규칙 여럿이면 보조 규칙으로 넘어가지 않는다.
- 결과 entry에 `matchRule`(site-map > contains > alias > exact)과 `clientRule`(exact/alias), 적재 기록에도 `matchRule`.
  skipped에는 상호·품목명·clientName·siteName과 후보 이름(`candidateNames`)을 더한다.
- `taxes.py --unmatched-csv <CSV>`를 `--from-result <결과>`(기록 없음) 또는 `--record-result`와 함께: 건너뛴 행을
  `상호,품목명,작성일자,공급가액,사유,후보`로 저장소 밖에 UTF-8 BOM CSV. `같은 내용 이미 있음`은 제외. 수식으로 읽힐 글자는 `'` 접두.

## 산출물 체크리스트
- [x] taxes.py 별칭·지정·미연결 CSV
- [x] browser_apply.js 지정 현장·보조 규칙·matchRule
- [x] 합성 fixture 테스트(파일 로드 0/1/충돌, 우선순위, 보조 규칙 0/1/여럿·4글자 미만, 일반어 제거, 미연결 CSV)

## 리스크 / 모르는 것
- 보조 규칙은 거래처 범위 안에서만, 정확히 1개일 때만 연결하므로 오연결 위험은 낮지만, 품목명이 다른 현장 이름의 일부(4글자
  이상)와 우연히 같으면 연결될 수 있다 — 결과의 `matchRule: contains`로 사후 확인 가능.
- 순서가 바뀐 현장명(품목명이 현장명의 부분 문자열이 아님)은 site-map으로만 연결된다.

## 테스트 / 검증
- `python -X utf8 -m unittest discover -s scripts/import -p "test_*.py"` 97개 통과(신규 9개, node e2e 포함, skip 0).

## 결과 (작업 후 기록)
- 구현·테스트 완료. 실데이터 적용·로컬 DB 쓰기는 하지 않았다. 다음: 주 에이전트가 실데이터로 `--from-result
  --unmatched-csv`를 뽑아 별칭·지정 파일(저장소 밖)을 확정한 뒤 다시 계획·적용.
