# 연결과 비밀번호

[English](../connections.md) | 한국어

Collector 연결을 이름으로 저장하거나 이미 저장한 연결을 선택합니다. 예시의 주소와 계정은 관리자가 알려준 값으로 바꾸세요. `local`이 있으면 다른 이름을 사용합니다.

```sh
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user observer
scouterctl context list
scouterctl context use --name local
scouterctl auth login
scouterctl object list --interactive
```

`context add`는 주소·포트·계정만 저장하며 연결하거나 현재 연결로 선택하지 않습니다. 같은 이름은 덮어쓰지 않습니다. `context use`로 이후 명령에서 사용할 연결을 고릅니다. 이 로컬 명령은 필요한 인자를 명시해야 하며 입력을 묻지 않습니다.

`auth login`은 로그인 가능 여부를 확인한 뒤 연결을 끊습니다. **비밀번호나 로그인 세션은 저장하지 않습니다.** 이후 온라인 명령마다 `SCOUTERCTL_PASSWORD`를 제공하거나 `--interactive`로 입력해야 합니다.

연결 선택은 `--context NAME`, 저장된 현재 연결 순입니다. `--host`·`--port`·`--user`를 명시하면 저장된 항목보다 우선합니다. 주소·포트 기본값은 `127.0.0.1:6100`이며 계정은 필요합니다. `--context` 없이 세 접속 옵션을 모두 지정하면 설정 파일을 읽지 않습니다. 연결 이름 오타나 잘못된 설정은 오류로 처리하며 임의의 다른 Collector로 접속하지 않습니다. 오프라인 `inspect`는 이 설정을 읽지 않습니다.

## 설정 파일

기본 위치는 `$XDG_CONFIG_HOME/scouterctl/config.json` 또는 `$HOME/.config/scouterctl/config.json`입니다. `SCOUTERCTL_CONFIG`로 별도 파일을 지정할 수 있습니다. 연결 설정과 현재 선택만 담고 비밀번호·비밀번호 해시·세션은 저장하지 않습니다.

쓰기에는 파일 잠금과 원자적 교체를 사용합니다. 다른 쓰기 작업이 있으면 종료 코드 `10`을 반환합니다. POSIX 파일시스템에서 새 설정 파일은 소유자만 접근하도록 만듭니다. 직접 수정하기 전에는 백업하고, 선택 중인 항목을 지울 때는 `current`도 해제하세요.

## 터미널 입력

일반 명령은 기본적으로 비대화형입니다. `auth login`이나 `--interactive`에서 Java가 실제 콘솔을 사용할 수 있을 때만 숨김 입력을 허용합니다. `--no-input`은 항상 입력을 막습니다. `SCOUTERCTL_PASSWORD`가 있으면 먼저 사용하며 화면에 출력하지 않습니다.

입출력을 리다이렉트하면 비밀번호를 묻거나 stdin에서 읽지 않습니다. 비밀번호가 없으면 JSON 오류와 종료 코드 `2`를 반환합니다. 입력 안내는 stderr, 결과는 stdout으로 분리됩니다. 빈 입력이나 터미널 EOF(Linux의 Ctrl+D, Windows의 Ctrl+Z 후 Enter)는 종료 코드 `2`로 취소합니다. Ctrl+C는 자격 증명을 저장하지 않고 종료합니다. 자동 재시도나 비밀번호 캐시는 없습니다.

## 스크립트에서 비밀번호 사용

연결을 선택하거나 `--host`·`--port`·`--user`를 지정한 뒤 현재 셸에 비밀번호를 설정합니다. 명령 인자나 인자 파일에 비밀번호를 넣지 마세요.

### Bash

```bash
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
printf '\n'
export SCOUTERCTL_PASSWORD
scouterctl doctor --no-input
# 작업이 끝나면 지웁니다.
unset SCOUTERCTL_PASSWORD
```

### PowerShell

```powershell
$password = Read-Host 'Collector password' -AsSecureString
$env:SCOUTERCTL_PASSWORD = [System.Net.NetworkCredential]::new('', $password).Password
scouterctl doctor --no-input
# 작업이 끝나면 지웁니다.
Remove-Item Env:SCOUTERCTL_PASSWORD
```

한 번만 실행할 때는 환경 변수 대신 `--interactive`를 쓸 수 있습니다. 자동화 작업에서는 사용하는 비밀 관리 도구로 환경 변수를 전달하고 `--no-input`을 지정하세요. 연결 설정은 접속 편의 기능이며 권한을 제한하는 수단은 아닙니다. Collector TCP에는 TLS가 없으므로 신뢰할 수 있는 네트워크나 별도로 보안 처리한 터널을 사용합니다.

## 연결 문제 해결

| 결과 | 확인할 내용 |
| --- | --- |
| 종료 `2`, `INVALID_ARGUMENT` | 계정과 비밀번호를 제공했는지 확인합니다. 호스트는 이름/IP, 포트는 1~65535, `--timeout-ms`는 1~60000 범위입니다. |
| 종료 `3`, `AUTH_FAILED` | 관리자에게 계정·비밀번호를 확인하고 숨김 입력으로 다시 입력합니다. |
| 종료 `5`, `CONNECTION_FAILED` | 이름 해석·TCP 포트·Collector 상태·네트워크/터널을 확인합니다. 느린 연결이라면 `--timeout-ms`를 조정합니다. 기본값은 5000입니다. |
| 종료 `9`, `VERSION_MISMATCH` | [지원 버전(영문)](../supported-versions.md)을 확인합니다. 버전 검사를 건너뛰는 옵션은 없습니다. |
| 종료 `0`, 빈 대상 목록 | 로그인은 됐지만 대상이 없습니다. Agent의 Collector 주소와 시작 로그를 확인합니다. |

오류 안내는 다음 확인 항목을 제안하며 네트워크 원인을 확정하지는 않습니다. 연결된 뒤에도 시간 초과가 날 수 있습니다. 문제를 고친 뒤 `doctor`, `object list` 순으로 다시 확인하세요.
