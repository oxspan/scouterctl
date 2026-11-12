# Collector 시간대

[English](../collector-timezone.md) | 한국어

`doctor`와 `collector status`에 기존 `timezoneDisplayName` 외에 `timezone` 객체를 추가합니다. 상태 조회에서는 `data.collector.timezone`에 있습니다.

```json
{"id":"Asia/Seoul","utcOffsetSeconds":32400,"referenceTimeEpochMs":1793491200000,"idSource":"jvm-property","offsetSource":"client-zone-rules"}
```

ID는 인증된 기존 `SERVER_ENV` 요청으로 받은 JVM `user.timezone` 속성입니다. 인식 가능한 Java 단축 ID는 Java 매핑으로 확장합니다. 오프셋은 Collector의 `SERVER_TIME` 시각을 기준으로 **CLI의 시간대 규칙으로 계산한 값**이며, 서버에서 직접 측정한 오프셋은 아닙니다. 지역화된 표시명으로 ID를 추정하지 않으며 다른 JVM 환경 값은 출력하지 않습니다. 속성이 없거나 해석할 수 없으면 `timezone: null`입니다. 잘못된 프로토콜 타입은 값을 공개하지 않고 오류로 처리합니다.

`xlog search`, `capture`, `counter history`, `counter export`는 요청 구간의 오프셋을 보고된 Collector 시간대와 비교합니다. ID가 달라도 구간 전체의 오프셋이 같으면 허용합니다. 과거 조회를 현재 오프셋과 비교하지 않으며 구간 내부의 DST 전환과 종료 시각 미포함 조건도 검사합니다. 불일치하면 `--timezone`과 Collector 시간대를 명시한 `INVALID_ARGUMENT`(종료 코드 2)을 스트림 시작·캡처 파일 생성 전에 반환합니다. 우회 옵션은 없습니다. 메타데이터가 없으면 기존의 미검증 조회 동작을 유지하며, 통신·응답 오류를 미확인 상태로 숨기지 않습니다.

예를 들어 `Asia/Seoul`을 보고한 Collector에서는 같은 시각을 `America/Los_Angeles`와 그 지역 날짜로 선언한 요청을 거부합니다. `--from`·`--to`를 UTC 문자열로 주면서 `--timezone Asia/Seoul`을 지정하는 방식은 계속 사용할 수 있습니다.

이 검사는 보고된 JVM 시간대와의 비교입니다. 실행 중 변경된 기본 시간대, 서버와 CLI의 시간대 데이터 차이, 실제 저장 파티션까지 보장하지 않습니다. XLog의 `storageDateVerification: "unverified"`와 캡처의 `timezoneVerification: "operator-declared"`는 유지합니다. 새 진단 필드는 실시간 출력에만 추가하며 캡처 형식은 바꾸지 않습니다.

Scouter 2.8.1·2.21.3의 `DateTimeHelper`는 해당 지역의 `20000101`을 기준으로 하루를 86,400,000ms씩 증가시킵니다. 이 저장 달력은 DST가 반영된 실제 지역 날짜와 다를 수 있습니다. `America/Chicago`에서 `2026-07-01T05:30:00Z`는 지역 시간상 7월 1일이지만 저장 달력에서는 6월 30일입니다. 시간대가 일치해도 빈 결과를 트래픽 부재로 단정하지 말고, 예상과 다르면 운영자와 저장 날짜를 확인하세요. 이번 변경은 이 Scouter 저장 달력 동작을 수정하지 않습니다. 자세한 예시는 [영문 가이드](../collector-timezone.md)를 참고하세요.
