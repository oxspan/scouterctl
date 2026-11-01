# 변경 기록

## [0.0.1](https://github.com/oxspan/scouterctl/compare/v0.0.0...v0.0.1) (2026-11-01)


### Bug Fixes

* **build:** synchronize versions and automate POC releases ([a5c32d2](https://github.com/oxspan/scouterctl/commit/a5c32d2f77d133c612590bdda2a4032308b6cf0b))

## 0.0.0 — 2026-11-02

첫 로컬 평가용 POC 패키지입니다. 이전 개발 중 표기 `0.1.0-poc`는 공개 릴리스가 아니며, 이번 첫 버전을 `0.0.0`으로 정리했습니다.

### 포함

- Collector 2.21.3 TCP 로그인, object 발견, XLog 페이지 조회, 구조형 프로파일 조회.
- SQL hash·elapsedMs·errorHash·updated 숫자 필드. 자유 텍스트·바인드 값 제외.
- JSON/JSONL 출력, 명시적 종료 코드, 64-bit ID 문자열 보존.
- 페이지 checksum·manifest·중단 후 재개를 지원하는 capture.
- JDK 21 실행 fat JAR, 빠른 시작 문서, ZIP 및 SHA-256 checksum.
- 빌드 버전에서 CLI `--version`, Collector에 전달하는 client version, JAR 이름을 일관되게 생성.

### 검증 범위

- CLI 계약 테스트 6개.
- 실제 Collector 저장·조회와 독립 Java reference probe 대조.
- 실제 Java Agent HTTP 요청 36건, XLog 36건, 오류 6건, METHOD/SQL3 및 8페이지 capture 재개.
- Spring Boot 3.5.16/Tomcat 10.1.55/H2 API 12건, `/error` dispatch를 포함한 XLog·프로파일 18건, SQL 프로파일 9건.
- 애플리케이션 예외와 중복 키 SQL 실행 오류 구분, 기대 SQL 해시 대조, 민감정보 marker 제외.

### 알려진 제약

검증된 Collector 2.21.3과 고정 codec만 지원합니다. 운영용 배포, Native Image, 월 단위 부하, 다른 DB/Collector 버전, SQL·예외 원문 해석과 API 안정성을 보장하는 버전이 아닙니다. `executionComplete`는 요청 실행 완료를 뜻하며 전체 트래픽 포함을 보장하지 않습니다.
