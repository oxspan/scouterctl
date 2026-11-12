# 문서 목록

[English](../README.md) | 한국어

[설치](installation.md) 후 [빠른 시작](../../QUICKSTART.ko.md)을 따라 실행할 수 있습니다.

[명령과 실행 결과 예시](examples.md)에서 실제 출력과 읽는 방법을 확인할 수 있습니다.

## 한국어 안내

- [프로젝트 소개](../../README.ko.md)
- [설치·업데이트·이전 버전으로 전환·제거](installation.md)
- [빠른 시작](../../QUICKSTART.ko.md)
- [연결과 비밀번호](connections.md)
- [Collector 시간대와 조회 검사](collector-timezone.md)
- [프로파일 SQL 집계](profile-sql-summary.md)
- [기여 안내](../../CONTRIBUTING.ko.md) · [보안 안내](../../SECURITY.ko.md)

## 상세 사용법

아래 상세 문서는 영문입니다. 명령어·옵션·JSON 필드 이름은 언어에 관계없이 같습니다.

| 문서 | 내용 |
| --- | --- |
| [Collector·Agent·실시간 지표](../inspection.md) | 상태·실행 중 설정·현재 지표 |
| [트랜잭션 캡처와 분석](../captures.md) | XLog·프로파일·오프라인 검색·요약·비교 |
| [과거 지표 조회](../counter-history.md) | 하루 동안 저장된 표본의 JSONL 출력 |
| [여러 날 지표 추출](../counter-export.md) | 파일 저장·진행 상태·재개 |
| [지원 버전](../supported-versions.md) | Collector·Agent·JVM·운영체제별 확인 범위 |
| [출력 형식과 종료 코드](../cli-contract.md) | 스크립트에서 결과를 읽는 방법 |
| [지원하는 캡처 파일 버전](../capture-compatibility.md) | 이전 파일 읽기와 재개 가능 범위 |
| [중단 후 캡처 재개](../capture-recovery.md) | Collector 중단·연결 실패 후 처리 |
| [프로파일 누락·잘림](../profile-completeness.md) | Collector가 반환한 프로파일의 한계 |

## 개발·유지보수 참고 자료

다음 문서도 영문입니다.

- [구조와 구성 요소](../architecture.md) · [테스트](../testing.md)
- [로컬 CI](../local-ci.md) · [CI 문제 해결](../ci-runner-recovery.md) · [규모별 측정](../capture-scale-validation.md)
- [릴리스 관리](../releasing.md) · [변경 기록](../../CHANGELOG.md)
- [외부 라이브러리 출처](../../vendor/README.md) · [라이선스](../../LICENSE)
- [0.x JAR 설치](../legacy-jar.md) · [1.0.0 릴리스 안내](../releases/1.0.0.md)
