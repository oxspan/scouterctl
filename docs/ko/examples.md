# 명령과 실행 결과 예시

[English](../examples.md) | 한국어

Scouter 2.8.1·2.21.3 테스트 환경의 실제 출력입니다. 대상·시각·값은 환경마다 달라집니다. “일부 필드”라고 적은 예시는 실제 응답에서 필드를 생략했으며, JSONL 예시는 모든 이벤트를 담았습니다.

온라인 명령은 [연결을 선택하고 비밀번호를 제공](connections.md)한 뒤 실행합니다. 대상 해시·날짜·트랜잭션 ID는 자신의 값으로 바꾸세요. `inspect`에는 저장한 캡처만 필요합니다.

## 연결 확인

```sh
scouterctl doctor --interactive
```

`doctor` 성공 응답 전체입니다. 종료 코드는 `0`입니다.

```json
{
  "schemaVersion": "scouterctl.result/v1",
  "ok": true,
  "data": {
    "serverId": "command-fixture",
    "version": "2.8.1 2020-05-14 23:36 GMT",
    "timezoneDisplayName": "Coordinated Universal Time",
    "group": "guest",
    "source": "collector-tcp",
    "effectivePermission": "unknown",
    "nativeTls": false
  },
  "executionComplete": true
}
```

`effectivePermission: "unknown"`은 관리자 권한을 확인했다는 뜻이 아닙니다. 로그인과 Collector 버전을 확인한 결과이며 Agent의 정상 동작까지 확인하지는 않습니다.

## 현재 힙 사용량

```sh
scouterctl counter get --object-hash 970227877 --counter HeapUsed --no-input
```

성공 응답의 일부 필드입니다. 종료 코드는 `0`입니다.

```json
{
  "ok": true,
  "data": {
    "counters": [
      {
        "name": "HeapUsed",
        "displayName": "Heap Used",
        "unit": "MB",
        "value": "9.86953",
        "availability": "available"
      }
    ]
  },
  "executionComplete": true
}
```

값은 문자열이고 단위는 `MB`입니다. 실제 0도 `available`이며, 값이 없으면 null로 표시합니다. Collector에 캐시된 현재 값이므로 측정 시각이 최신임을 보장하지 않습니다.

## SQL과 오류 프로파일

```sh
scouterctl inspect --directory captures/incident-001 --txid -8863176071656033104 --include-text
```

`--include-profiles --include-text`로 프로파일과 원문을 저장한 캡처가 필요합니다. 아래 예시에 사용한 이전 v2 캡처도 현재 CLI에서 읽을 수 있습니다.

결과의 일부 필드이며 종료 코드는 `0`입니다. 오류 원문은 짧게 줄이고 바인딩 값과 스택 트레이스는 생략했습니다.

```json
{
  "ok": true,
  "data": {
    "record": {
      "xlog": {
        "txid": "-8863176071656033104"
      },
      "profileStatus": "available",
      "profile": {
        "steps": [
          {
            "type": "SQL3",
            "sql": {
              "elapsedMs": 1,
              "statement": {
                "hash": -886850054,
                "text": "insert into fixture_rows(id, marker) values (?, ?)",
                "status": "resolved"
              },
              "error": {
                "status": "resolved",
                "text": "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation [details omitted]"
              }
            }
          }
        ],
        "completeness": "unknown"
      }
    }
  },
  "executionComplete": true
}
```

SQL에 물음표가 보여도 오류 원문에는 바인딩 값이 들어갈 수 있습니다. 필요한 경우에만 원문을 포함하세요. `executionComplete`와 프로파일의 누락 여부는 별개이며, 이 이전 캡처는 `unknown`입니다. [프로파일 누락·잘림 안내(영문)](../profile-completeness.md)를 참고하세요.

## 과거 지표 조회

```sh
scouterctl counter history --object-hash 1533469885 --counter HeapUsed --date 20261107 --from 2026-11-07T00:00:00.000Z --to 2026-11-07T05:30:13.438Z --timezone UTC --no-input
```

종료 코드 `0`인 JSONL 응답 전체입니다. 각 줄은 독립된 JSON 객체입니다.

```jsonl
{"type":"start","schemaVersion":"scouterctl.counter-stream/v1","source":{"serverId":"command-fixture","version":"2.8.1 2020-05-14 23:36 GMT","timezoneDisplayName":"Coordinated Universal Time","group":"guest","source":"collector-tcp","effectivePermission":"unknown","nativeTls":false},"query":{"date":"20261107","from":1794009600000,"to":1794029413438,"timezone":"UTC","objHash":1533469885,"counter":"HeapUsed"},"target":{"objHash":1533469885,"objName":"/scouterctl-test/command-fixture","objType":"java"},"counter":{"name":"HeapUsed","displayName":"Heap Used","unit":"MB"},"resolution":"stored-realtime","intervalMs":null,"missingValues":"omitted","metadataScope":"current-collector-definition"}
{"type":"record","data":{"time":1794029400034,"value":"8.846268","availability":"available"}}
{"type":"record","data":{"time":1794029402033,"value":"9.687073","availability":"available"}}
{"type":"record","data":{"time":1794029404034,"value":"14.435959","availability":"available"}}
{"type":"record","data":{"time":1794029406035,"value":"15.197998","availability":"available"}}
{"type":"record","data":{"time":1794029408036,"value":"15.575096","availability":"available"}}
{"type":"record","data":{"time":1794029410037,"value":"16.062645","availability":"available"}}
{"type":"record","data":{"time":1794029412038,"value":"16.062645","availability":"available"}}
{"type":"complete","executionComplete":true,"records":7,"upstreamCoverage":"unknown","sampling":"unknown"}
```

마지막 `complete` 이벤트와 종료 코드 `0`을 함께 확인하세요. 7개는 이 조회에서 반환된 표본 수이며 요청 수나 수집 누락이 없다는 뜻이 아닙니다. 시각은 epoch 밀리초이고 빠진 표본은 0으로 채우지 않습니다.

## 비밀번호 누락

```sh
scouterctl doctor --host 127.0.0.1 --port 6100 --user observer --no-input
```

`SCOUTERCTL_PASSWORD`가 없고 터미널 입력도 허용하지 않으면 Collector에 연결하기 전에 종료 코드 `2`를 반환합니다.

```json
{
  "schemaVersion": "scouterctl.error/v1",
  "type": "error",
  "ok": false,
  "error": {
    "code": "INVALID_ARGUMENT",
    "message": "Set SCOUTERCTL_PASSWORD, or use --interactive on a terminal for hidden input"
  }
}
```

사용하는 비밀 관리 도구로 비밀번호를 전달하거나 실제 터미널에서 `--interactive`를 사용하세요. 명령 오류도 stdout의 JSON으로 나오므로 종료 코드를 함께 읽어야 합니다.

[출력 형식과 종료 코드(영문)](../cli-contract.md), [빠른 시작](../../QUICKSTART.ko.md), [지원하는 Scouter 2.x 버전(영문)](../supported-versions.md)을 확인할 수 있습니다.
