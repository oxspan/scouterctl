# 변경 기록

## [1.1.0](https://github.com/oxspan/scouterctl/compare/v1.0.0...v1.1.0) (2026-11-12)


### Features

* ✨ Group profile SQL steps by hash ([b1dd2fa](https://github.com/oxspan/scouterctl/commit/b1dd2fae9a0933e51984dffee9f6ddd5daaffce0))
* ✨ List objects by Collector storage date ([8a25b30](https://github.com/oxspan/scouterctl/commit/8a25b30ac581de685a09f8832856aca25b67ea26))
* ✨ Resolve XLog dictionary text on request ([08a59fd](https://github.com/oxspan/scouterctl/commit/08a59fd0e2ae4ad3f884a16483dba26ce051c3f0))
* ✨ Show approved Agent configuration values ([a7af022](https://github.com/oxspan/scouterctl/commit/a7af0229438e48060fa8061c580312c0a19cbb67))
* ✨ Show timing and network outcomes in live profiles ([672dacf](https://github.com/oxspan/scouterctl/commit/672dacf7ddb9d4d6c8aa6a8357ab0ec8db3931dd))
* 🔀 Promote Collector diagnostics and capture improvements ([#87](https://github.com/oxspan/scouterctl/issues/87)) ([30d6106](https://github.com/oxspan/scouterctl/commit/30d6106aee4ad83b39ce43496df9321315032a8f))


### Bug Fixes

* 🐛 Avoid duplicate profile capture resume scans ([defecb2](https://github.com/oxspan/scouterctl/commit/defecb25d3977a543d7276babe0e3cd2b7ef3fc0)), closes [#74](https://github.com/oxspan/scouterctl/issues/74)
* 🐛 Clarify sensitive values in profile error text ([2ad5f49](https://github.com/oxspan/scouterctl/commit/2ad5f496750a4b452d1f2847f7582e90fa97925d))
* 🐛 Diagnose Windows file ancestors before missing paths ([df26cd3](https://github.com/oxspan/scouterctl/commit/df26cd353b786d4e1123d13d9bd6e3e53cb01dd3)), closes [#75](https://github.com/oxspan/scouterctl/issues/75)
* 🐛 Explain invalid capture directory paths ([53d4c1b](https://github.com/oxspan/scouterctl/commit/53d4c1b5ea2adf9d3d403b552b9ff9f353ebd3da)), closes [#75](https://github.com/oxspan/scouterctl/issues/75)
* 🐛 Improve command help and option diagnostics ([22c81fa](https://github.com/oxspan/scouterctl/commit/22c81fa3f176a18d825567bfd40293b915052cc3)), closes [#65](https://github.com/oxspan/scouterctl/issues/65)
* 🐛 Report unverified XLog storage dates ([8d0cbf5](https://github.com/oxspan/scouterctl/commit/8d0cbf5c04ff218f463859847fdc3c86fccaed34))
* 🐛 Retry denied Windows file replacements ([da33d93](https://github.com/oxspan/scouterctl/commit/da33d93593db66fb2a43cb950c72b8179a3d0523)), refs [#58](https://github.com/oxspan/scouterctl/issues/58)
* 🐛 Validate reported Collector time zones ([10c2019](https://github.com/oxspan/scouterctl/commit/10c2019d70fe25a8aa7d6e721f85754435b5b6e0)), refs [#57](https://github.com/oxspan/scouterctl/issues/57)

### Compatibility and remaining limitations

* Persisted capture formats remain unchanged. Individual profile steps remain the default; live JSON includes additive diagnostics and step fields, so strict consumers need unknown-key tolerance.
* Time-zone checks use the reported Collector JVM property and client rules; they do not verify storage calendars. [#57](https://github.com/oxspan/scouterctl/issues/57) remains open.
* Windows retries handle bounded access denial; the original intermittent deployment failure has not been reproduced and its cause remains unconfirmed. [#58](https://github.com/oxspan/scouterctl/issues/58) remains open.
* Resume validation avoids duplicate page reads but retains cumulative scaling limits across repeated resumes.

## [1.0.0](https://github.com/oxspan/scouterctl/compare/v0.3.0...v1.0.0) (2026-11-07)


### Features

* ✨ Add command packages with native Windows Unicode support ([ac743da](https://github.com/oxspan/scouterctl/commit/ac743daf4292ae9df08a072178a67f1c0e7e99d6))
* ✨ Add real-time counter queries ([#42](https://github.com/oxspan/scouterctl/issues/42)) ([6e83199](https://github.com/oxspan/scouterctl/commit/6e8319932cbfbd4f0f837209263e54d4ec6f06ae))
* ✨ Add stored counter history and resumable exports ([9f3bc93](https://github.com/oxspan/scouterctl/commit/9f3bc93b8a601ec032c3803a9558178c5fcdff6f))
* ✨ Inspect Agent JVM and runtime settings ([#41](https://github.com/oxspan/scouterctl/issues/41)) ([6d5cea2](https://github.com/oxspan/scouterctl/commit/6d5cea215e5c53f1a2ed19c7730ba8f3e80078a5))
* ✨ Inspect Collector status and runtime settings ([#40](https://github.com/oxspan/scouterctl/issues/40)) ([9cd0cf5](https://github.com/oxspan/scouterctl/commit/9cd0cf5699170dd54a15c66df86d8414dc5cb7b8))
* ✨ Prepare Linux and Windows command releases ([ed964ae](https://github.com/oxspan/scouterctl/commit/ed964ae4fe058bd518e8eecce8334071cec36716))
* ✨ Report profile truncation in versioned captures ([#37](https://github.com/oxspan/scouterctl/issues/37)) ([67bb5e2](https://github.com/oxspan/scouterctl/commit/67bb5e2ff84ffc264196d7b940155a09bc0b1153))
* ✨ Support verified Collector releases on Java 8 ([#39](https://github.com/oxspan/scouterctl/issues/39)) ([b8fbda7](https://github.com/oxspan/scouterctl/commit/b8fbda796a0ee2d6e109406674980066f181d16e))


### Bug Fixes

* 🐛 Support Korean Windows usage with UTF-8 argument files ([3f11b77](https://github.com/oxspan/scouterctl/commit/3f11b77c34a38eebe76aeedc100b56fdf0333503))

## [0.3.0](https://github.com/oxspan/scouterctl/compare/v0.2.1...v0.3.0) (2026-11-04)


### Features

* 🔀 Include saved capture comparison in the next release ([db31d44](https://github.com/oxspan/scouterctl/commit/db31d44a83c489553259e612acf2fb60da8420e6))

## [0.2.1](https://github.com/oxspan/scouterctl/compare/v0.2.0...v0.2.1) (2026-11-04)


### Bug Fixes

* 🐛 Verify native terminal input and correct Windows cancellation hint ([#23](https://github.com/oxspan/scouterctl/issues/23)) ([f0d8edd](https://github.com/oxspan/scouterctl/commit/f0d8edd84e4fd520466dab049e6623ce5632cd88))

## [0.2.0](https://github.com/oxspan/scouterctl/compare/v0.1.0...v0.2.0) (2026-11-03)


### Features

* ✨ Add offline service error and latency summaries ([#16](https://github.com/oxspan/scouterctl/issues/16)) ([e7c771f](https://github.com/oxspan/scouterctl/commit/e7c771f9b83617b89510e3cef8010aa1a618acbf))
* ✨ Add saved connections and hidden terminal password input ([#15](https://github.com/oxspan/scouterctl/issues/15)) ([c6bc266](https://github.com/oxspan/scouterctl/commit/c6bc26645b356ff19f64ef57c62ee4c0ae798351))


### Bug Fixes

* 🐛 Improve initial Collector connection diagnostics ([#14](https://github.com/oxspan/scouterctl/issues/14)) ([1f7f6c1](https://github.com/oxspan/scouterctl/commit/1f7f6c13ffbbe9c3b1cf94a7aa67745c3fa40a58))
* 🐛 Preserve UTF-8 JSON output on Windows ([2a74318](https://github.com/oxspan/scouterctl/commit/2a743180643f0b1c309683fcb5984645fb215aa2))

## [0.1.0](https://github.com/oxspan/scouterctl/compare/v0.0.0...v0.1.0) (2026-11-02)


### Features

* ✨ Capture profiles and inspect evidence offline ([#6](https://github.com/oxspan/scouterctl/issues/6)) ([1be146b](https://github.com/oxspan/scouterctl/commit/1be146b32bf051e007d25f49f117e9d0e5a4dc95))
* ✨ Search and summarize captured requests offline ([#7](https://github.com/oxspan/scouterctl/issues/7)) ([7cc5097](https://github.com/oxspan/scouterctl/commit/7cc50978c184d85683f5811717a3aa2b18c4997b))
* resolve profile service SQL and error text ([#5](https://github.com/oxspan/scouterctl/issues/5)) ([53b59fe](https://github.com/oxspan/scouterctl/commit/53b59fe856e68f36ac0fe9d2f771dcdf08ff373f))


### Bug Fixes

* 🐛 Preserve branches when selecting a PR template ([#12](https://github.com/oxspan/scouterctl/issues/12)) ([4e1c481](https://github.com/oxspan/scouterctl/commit/4e1c4814ce9890d95bdbcb36daf53715ec7b854e))
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
