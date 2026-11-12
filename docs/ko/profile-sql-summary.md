# 프로파일 SQL 집계

[English](../profile-sql-summary.md) | 한국어

SQL이 반복되어 프로파일 출력이 커지면 `--group-sql`로 SQL 해시별 집계를 조회할 수 있습니다.

```sh
scouterctl profile get --date YYYYMMDD --txid TXID --group-sql
scouterctl inspect --directory CAPTURE --txid TXID --group-sql
```

이 옵션은 `profile get`과 `inspect`에서만 사용할 수 있습니다. SQL 문장을 보려면 `--include-text`도 지정하세요. 오프라인 조회에서는 텍스트를 포함해 저장한 캡처가 필요합니다. 옵션을 생략하면 개별 스텝을 반환합니다. 집계 조회는 캡처 파일이나 저장 형식을 바꾸지 않습니다.

온라인 결과의 `data`, 오프라인 결과의 `data.record.profile`이 `scouterctl.profile-sql-summary/v1` 집계 객체가 됩니다. 바깥쪽 `scouterctl.result/v1` 응답과 오프라인 캡처·XLog·누락 정보는 유지합니다. 사용할 수 없는 오프라인 프로파일은 `null`입니다.

| 필드 | 의미 |
| --- | --- |
| `txid` | 부호를 포함한 10진 문자열 트랜잭션 ID |
| `totalSteps`, `sqlSteps`, `nonSqlSteps` | 디코딩된 전체·SQL·나머지 스텝 수 |
| `groups[].hash`, `count` | SQL 해시와 해당 SQL 스텝 수 |
| `elapsedTotalMs`, `maxElapsedMs` | 그룹의 경과 시간 합계와 최댓값, 밀리초 |
| `errors` | 오류 해시가 0이 아닌 SQL 스텝 수 |
| `textIncluded`, `completeness` | 요청에 따른 텍스트 포함 여부와 원래 프로파일의 잘림 상태 |
| `basis` | `decoded-profile-steps`: 디코딩된 스텝을 기준으로 집계 |

서로 다른 SQL 스텝 종류도 해시가 같으면 한 그룹에 포함합니다. 경과 시간 합계 내림차순, 같은 합계에서는 부호 있는 해시 오름차순으로 정렬합니다. 합계는 64비트 정수이며 원래 음수 값도 유지합니다. 실행 수는 영향받은 행 수가 아니며, 스텝 시간이 겹칠 수 있으므로 합계가 트랜잭션 전체 시간과 같다고 해석하면 안 됩니다.

`--include-text`를 지정하면 `statement`에 사전의 `hash`, `text`, `status`를 반환합니다. 텍스트가 없거나 다른 해시의 사전 항목만 있으면 미해결 상태입니다. 같은 해시에 서로 다른 문장이 확인되면 `statementConflict: true`와 미해결 텍스트를 반환합니다. 텍스트 옵션을 생략하면 `statement: null`이며 충돌 정보도 공개하지 않습니다. 집계는 오류 텍스트·바인드 값·스텝 순서·SQL 외 스텝 상세를 포함하지 않습니다. 상세 분석에는 기본 스텝 출력을 사용하세요.

출력 크기는 서로 다른 SQL 해시 수에 비례합니다. 반복 SQL에 효과가 크지만 Collector 전송량이나 디코딩 작업을 줄이지 않으며, 스텝·그룹 수에 새 제한을 두지 않습니다. 누락된 스텝을 복원하지도 않습니다.

[기존 프로파일 완전성·종료 코드](../profile-completeness.md)를 유지합니다. 명시적인 잘림은 가능한 집계와 함께 `completeness: "truncated"`, `executionComplete: false`, 종료 코드 8을 반환합니다. `unknown`은 완전함을 보장하지 않습니다. 불완전한 캡처와 사용할 수 없는 프로파일도 기존의 종료 코드 8을 유지합니다. 오프라인 집계 역시 선택한 트랜잭션 뒤의 페이지까지 캡처 전체 무결성을 검사하며, 손상되면 결과 대신 오류를 반환합니다. [영문 가이드](../profile-sql-summary.md)에서 전체 JSON 예시를 볼 수 있습니다.
