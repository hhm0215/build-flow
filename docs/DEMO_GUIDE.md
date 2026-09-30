# BuildFlow 실사용 시현·스모크 가이드

## 목적

실사용 DB를 오염시키지 않으면서 현재 구현된 기능을 반복 검증한다. 자동 검증, 되돌릴 수 있는 UI 시현, 별도 샌드박스가 필요한 시현을 구분한다.

## 시작 전 점검

```powershell
.\scripts\buildflow.ps1 check
.\scripts\buildflow.ps1 status
```

- `http://localhost:3000` 로그인 화면
- API 8081~8087 actuator health `UP`
- Ollama `qwen2.5:7b`
- 관리자 계정 1명

## 자동으로 반복 가능한 검증

### 로그인·현장 API 스모크

관리자 비밀번호를 파일이나 명령행에 남기지 않고 대화형으로 입력한다. 임시 현장을 생성·조회한 뒤 자동 삭제한다.

```powershell
.\scripts\verify-admin.ps1
```

### DB 백업·격리 복원

백업 도구 회귀는 Docker와 실제 업무 DB를 사용하지 않는다.

```powershell
.\scripts\db\test-backup-tools.ps1
```

실제 복원 검증은 무작위 Compose project와 임시 volume만 사용하고 검증 후 자동 제거한다.

```powershell
.\scripts\db\restore-test.ps1 -BackupDirectory .\backups\db\<UTC timestamp>
```

복원된 관리자 계정의 실제 로그인·로그아웃까지 확인할 때만 다음 대화형 옵션을 사용한다.

```powershell
.\scripts\db\restore-test.ps1 -BackupDirectory .\backups\db\<UTC timestamp> -ValidateLogin
```

### 코드 회귀

```powershell
.\gradlew.bat test --no-daemon
Set-Location frontend
bun run lint
bun run test
bun run build
```

## 현재 실환경에서 되돌릴 수 있는 UI 시현

모든 임시 이름 앞에 `[시현] YYYYMMDD-HHmm` 접두어를 붙여 실제 자료와 구분한다.

1. 로그인 후 대시보드의 빈 기준선을 확인한다.
2. 현장을 하나 만들고 목록·상세·수정·검색을 확인한다.
3. 해당 현장에 **작성 중(DRAFT)** 견적을 만들고 수정·삭제를 확인한다.
4. 매입을 만들고 수정·삭제 및 현장 손익 반영을 확인한다.
5. **미입금** 세금계산서를 만들고 수정·삭제 및 미수금 표시를 확인한다.
6. AI 현장 비서에 현장 현황·손익·미수금을 질문하고 실제 빈 값 또는 임시 데이터에 맞는 답변을 확인한다.
7. `견적 → 매입 → 세금계산서 → 현장` 순서로 임시 데이터를 삭제하고 대시보드가 다시 기준선으로 돌아왔는지 확인한다.

## 별도 샌드박스에서만 시현할 항목

다음 동작은 정책상 되돌릴 수 없거나 파일이 남을 수 있으므로 현재 실환경에서 임시 테스트로 실행하지 않는다.

- 견적 **확정**: 확정 후 수정·삭제가 차단된다.
- 세금계산서 **입금 확인**: 확정 후 수정·삭제가 차단된다.
- 하자보증보험 PDF 업로드·OCR: 원본 파일과 DB 레코드 보존 정책을 함께 확인해야 한다.
- 실제 USB 견적서 AI 파싱: 원본 불변·복사 체크섬·버전 관계 검토 기능이 구현된 뒤 진행한다.

이 항목들은 시현 전용 DB/파일 volume을 함께 만드는 후속 샌드박스에서 검증한다.

## 시현 종료 확인

- 현장·견적·매입·세금계산서 목록에 `[시현]` 데이터가 남지 않았다.
- 대시보드 합계와 미수금이 시현 전 기준선으로 돌아왔다.
- 임시 복원 project의 container·network·volume이 남지 않았다.
- 테스트 과정에서 실제 파일 이름을 변경하거나 원본 파일을 덮어쓰지 않았다.
