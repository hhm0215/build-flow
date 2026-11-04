# 견적 가져오기 오독 방어 + 수신처 거래처 별칭·회사 키 재사용

- **시작일**: 2026-11-04
- **BACKLOG 항목**: 로컬 실데이터 적재(견적) 후속 — 사용자 지시 2026-11-04
- **예상 규모**: S~M
- **상태**: DONE

## 목표
견적 엑셀 미리보기가 양식 글자·숫자를 현장명/수신처로 읽은 파일을 OK로 통과시키지 않고, 견적서 수신처 표기
변형(㈜·띄어쓰기 등) 때문에 거래처가 새로 생기거나 보관 거래처 이름 충돌로 적용이 멈추지 않게 한다.

## 배경 / 동기
실데이터 미리보기(세부 내용은 저장소에 남기지 않음)에서 다음이 OK로 통과했다. 합성 예로 바꾸면:
- 현장명 셀이 `견적`(양식 제목 글자)인 파일
- 열이 밀린 양식에서 수신처 `EA`·현장명 `20000`(단위·숫자)
- 날짜 폴더(`3월 31일`)의 상위가 연도 모음 폴더이고 수신처도 비어 현장명이 날짜만 남은 파일
- 수신처 표기가 DB 정식 거래처와 조금씩 달라(`합성디자인`/`합성 디자인`/`합성디자인(주)`) 새 거래처가 생기거나,
  보관된 같은 이름 거래처 때문에 적용이 중단됨

## 접근법
- `estimate_sheet.form_text_reason`: 공백 제거 후 숫자·금액 모양, 공백·기호 제거·대문자로 단위 낱말 집합 또는
  양식 제목 낱말 집합과 정확히 같으면 사유 반환. `_finish`에서 현장명·수신처에 적용해 ERROR
  `현장명/수신처 칸이 양식 글자·숫자로 읽힘 — 양식 확인`. 부분 포함('합성견적센터', 'M동 합성')은 통과.
- `estimates.build_plan`: `--site-from-folder`로 만든 현장명도 같은 검사, 그리고 `FOLDER_DATE`만 남으면
  `--site-alias`가 없을 때 ERROR. 양식 글자로 읽힌 수신처는 날짜 폴더 접두어로 쓰지 않는다.
- `--client-alias <CSV>`: `taxes.load_client_aliases`·`company_key` 재사용(머리글 `견적서 수신처` 추가).
  계획 entry `clientName`(+`clientAliased`)과 `--apply`의 거래처 이름에 정식명 사용. 회사 키가 같은데 적재할
  표기가 다른 수신처 묶음은 미리보기 경고. "같은 현장명에 거래처 여럿" 판정도 회사 키로 비교.
- 거래처 찾기(`browser_apply.js clientIdOf`와 `Resolver.client_id` 같은 규칙):
  정확한 이름 활성 1곳(`exact`) → 회사 키 같은 활성 정확히 1곳(`key`) → 키 같은 활성 둘 이상이면 멈춤 →
  키 같은 보관 거래처만 있으면 멈춤 → 키 일치 없음일 때만 생성(`created`). 결과·적재 기록에 `clientRule`.

## 산출물 체크리스트
- [x] `scripts/import/estimate_sheet.py` 오독 방어
- [x] `scripts/import/estimates.py` 폴더 날짜 현장명 ERROR, `--client-alias`, 표기 혼재 경고, Resolver 회사 키
- [x] `scripts/import/browser_apply.js` `clientIdOf` 회사 키 재사용·`clientRule`
- [x] `scripts/import/taxes.py` 별칭 머리글 공유
- [x] `scripts/import/test_estimate_guards.py`(합성 fixture만)
- [x] `docs/LOCAL_DATA_IMPORT.md` 규칙·옵션 표

## 리스크
- 단위·제목 낱말 집합이 실제 현장명과 겹치면(예: 현장명이 정확히 `본`) 막힌다 → 정확 일치만 보므로 드묾,
  막히면 원본 사본 수정. 별칭으로 우회하지 않는다(오독 신호이므로).
- 회사 키가 같은 다른 회사(표기만 같은 동명 회사)는 같은 거래처로 붙는다 → 활성 정확히 1곳일 때만, 결과에
  `clientRule=key`로 남겨 사람이 확인 가능.

## 결과
- 단위 테스트 16건 추가, `python -X utf8 -m unittest discover -s scripts/import -p "test_*.py"` 128건 통과
  (node e2e 포함).
- 실데이터 재검증(USB 미리보기)은 이 PR 범위 밖 — 로컬에서 별도로 수행.
