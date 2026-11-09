# 빠른 시작

[English](QUICKSTART.md) | 한국어

JDK 21과 [운영체제에 맞는 ZIP](docs/ko/installation.md)을 설치한 뒤 실행을 확인합니다.

```sh
scouterctl --version
scouterctl --help
```

여러 줄로 적은 명령은 Bash 예시입니다. Windows에서는 각 명령을 한 줄로 입력하면 같은 옵션을 사용할 수 있습니다.

## 1. Collector 연결

관리자에게 Collector의 TCP 주소와 계정을 받으세요. 아래의 `127.0.0.1:6100`과 `observer`는 실제 접속 정보로 바꿉니다. Web API 주소는 Collector TCP 주소가 아닙니다.

```sh
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user observer
scouterctl context use --name local
scouterctl doctor --interactive
scouterctl object list --interactive
```

`local`이 이미 있으면 다른 이름을 쓰거나 기존 연결을 선택하세요. `object list`에서 조회할 애플리케이션의 `objHash`를 복사합니다. `doctor`는 로그인과 Collector 버전을 확인하며, 대상 목록이 비어 있으면 Collector가 대상을 반환하지 않은 상태입니다.

비밀번호는 저장하지 않습니다. 다음 온라인 명령을 실행하기 전에 [Bash·PowerShell 안내](docs/ko/connections.md#스크립트에서-비밀번호-사용)에 따라 현재 셸의 `SCOUTERCTL_PASSWORD`를 설정하세요. Collector TCP는 신뢰할 수 있는 네트워크나 별도로 보안 처리한 터널에서 사용합니다.

## 2. 현재 상태 조회

`123456`을 선택한 대상의 해시로 바꿉니다.

```sh
scouterctl collector status --no-input
scouterctl agent status --object-hash 123456 --no-input
scouterctl counter get --object-hash 123456 --counter HeapUsed --no-input
```

반환 필드와 값이 없는 경우의 처리는 [상태·설정·실시간 지표 안내(영문)](docs/inspection.md)를 확인하세요.

## 3. 트랜잭션과 프로파일 저장

조회할 자료가 저장된 날짜와 Collector의 시간대를 사용합니다. 아래는 UTC 예시입니다. `captures`가 없으면 먼저 만들고, 캡처마다 새 하위 디렉터리를 지정하세요.

```sh
mkdir captures
scouterctl capture --object-hash 123456 --date 20261107 \
  --from 2026-11-07T09:00:00Z --to 2026-11-07T09:10:00Z --timezone UTC \
  --include-profiles --directory captures/incident-001 --no-input
```

캡처 결과의 `storageDateVerification: "unverified"`는 입력한 저장 날짜를 Collector에서 확인하지 않았다는 뜻입니다. 조회가 완료됐는데 결과가 비어 있으면 저장 날짜를 확인하세요.

중단되거나 일부만 저장됐으면 같은 명령에 `--resume`을 붙여 재개합니다. 서비스명·SQL·오류 원문을 저장하려는 경우에만 캡처할 때 `--include-text`를 추가하세요. 민감한 애플리케이션 정보가 포함될 수 있습니다. 자세한 사용법은 [캡처와 오프라인 분석(영문)](docs/captures.md)에 있습니다.

## 4. Collector 없이 분석

```sh
scouterctl inspect summary --directory captures/incident-001
scouterctl inspect search --directory captures/incident-001 --errors-only
scouterctl inspect --directory captures/incident-001 --txid '<TXID>'
```

마지막 명령의 `<TXID>`는 검색 결과의 트랜잭션 ID로 바꿉니다. 오프라인 명령에는 저장한 파일과 JDK 21만 필요하며 Collector에 연결하거나 접속 정보를 읽지 않습니다.

## 5. 과거 지표 추출

```sh
scouterctl counter export --object-hash 123456 --counter HeapUsed \
  --from 2026-11-06T00:00:00Z --to 2026-11-08T00:00:00Z --timezone UTC \
  --directory captures/heap-export --max-chunks 24 --no-input
```

한 번에 처리할 구간 수에 도달해 종료 코드 `8`이 나오면 같은 조회 조건에 `--resume`을 붙여 이어서 받습니다. 빠진 표본은 0으로 채우지 않습니다. [하루 지표 조회(영문)](docs/counter-history.md)와 [여러 날 지표 추출·재개(영문)](docs/counter-export.md)를 참고하세요.

## 결과 확인

실제 로그인·지표·SQL/오류·JSONL·인증 오류 출력은 [명령과 실행 결과 예시](docs/ko/examples.md)에서 볼 수 있습니다.

온라인 결과는 JSON 또는 JSONL입니다. 종료 코드와 완료 여부를 함께 확인하세요. 추출 완료는 Collector가 모든 트래픽을 보관했다는 뜻이 아닙니다. 트랜잭션 ID는 문자열이며 부동소수점 숫자로 바꾸면 안 됩니다.

작업이 끝나면 `SCOUTERCTL_PASSWORD`를 지우세요. [연결 문제 해결](docs/ko/connections.md#연결-문제-해결), [출력 형식과 종료 코드(영문)](docs/cli-contract.md), [지원 버전(영문)](docs/supported-versions.md), [문서 목록](docs/ko/README.md)에서 자세한 내용을 확인할 수 있습니다.
