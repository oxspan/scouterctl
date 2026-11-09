# scouterctl 기여 안내

[English](CONTRIBUTING.md) | 한국어

버그 제보, 문서 개선, Kotlin 코드 기여를 환영합니다. 취약점은 공개 이슈에 상세 내용을 올리지 말고 [보안 안내](SECURITY.ko.md)를 따라주세요.

## 개발 환경

`develop`을 기준으로 시작합니다. JDK 21과 저장소의 Gradle Wrapper를 사용하세요. Linux 검사에는 PATH에서 실행할 수 있는 Bash·`jq`·`sha256sum`·`unzip`도 필요합니다.

```sh
git clone --branch develop https://github.com/oxspan/scouterctl.git
cd scouterctl
./gradlew test
```

Windows PowerShell에서는 `./gradlew.bat test`를 사용합니다. 기본 테스트는 로컬 테스트 자료를 사용하므로 Scouter 계정이나 Collector 다운로드가 필요하지 않습니다. 개발 중에는 `--tests 'dev.scouterctl.CliOutputContractTest'`처럼 필요한 검사만 선택할 수 있습니다.

Linux 명령 ZIP의 빌드·검증:

```sh
./gradlew verifyReleaseInputs
```

개발용 설치는 `./gradlew installDist` 후 `build/install/scouterctl/bin/scouterctl`로 실행합니다. 네이티브 Windows 패키지는 별도 빌드 도구가 필요합니다. [릴리스 안내(영문)](docs/releasing.md)를 참고하세요. 실제 Collector·Agent·Spring 검사는 [테스트 안내(영문)](docs/testing.md)의 별도 작업이며, [로컬 CI(영문)](docs/local-ci.md)에서 act 실행 방법을 확인할 수 있습니다.

## 코드와 테스트

- CLI 기능은 Kotlin으로 작성합니다. Kotest `FunSpec`과 matcher를 사용하며 실행에는 JUnit Platform을 사용합니다. 네이티브 [Windows 실행기(영문)](https://github.com/oxspan/scouterctl/blob/master/native/windows/README.md)는 별도 플랫폼 구성 요소입니다.
- [구조 안내(영문)](docs/architecture.md)의 의존 방향을 지킵니다. CLI 인자 처리·JSON·파일·네트워크 처리는 core/application 밖에 둡니다.
- [출력 형식과 종료 코드(영문)](docs/cli-contract.md), [지원하는 캡처 파일 버전(영문)](docs/capture-compatibility.md)을 유지합니다. 기존 사용에 영향을 주면 변경 내용과 전환 방법을 설명하세요.
- 기존 테스트 자료와 별도 프로세스 실행 도구로 실제 동작을 확인합니다. 문서만 고쳤다면 새 실행 테스트를 만들 필요는 없습니다.
- 계정 정보·실제 캡처·로컬 설정·검증 기록은 커밋하지 않습니다. 예시는 민감한 정보가 없는 가상 자료로 작성하세요.
- 설치나 사용법을 바꾸면 관련 영어·한국어 안내도 함께 수정합니다.

## 브랜치와 PR

| 변경 | 시작 브랜치 | PR 대상 |
| --- | --- | --- |
| 기능·일반 수정·테스트·문서 (`feature/<이슈번호>[-<설명>]`) | `develop` | `develop` |
| 긴급 배포 수정 (`hotfix/<이슈번호>[-<설명>]`) | `master` | `master` |
| 배포할 변경 통합 | `develop` | `master` |

`feature/59`처럼 `#`을 뺀 이슈 번호를 사용합니다. 작업을 알아보기 쉽도록 `feature/59-object-list`처럼 소문자와 하이픈으로 짧은 설명을 붙이는 것을 권장하며, 설명은 생략할 수 있습니다. 연결할 이슈가 없는 소규모 유지보수는 `feature/<설명>`을 사용합니다. Dependabot과 release-please는 자동 생성된 브랜치명을 사용합니다.

`develop` 대상 PR에는 `Refs #59`로 이슈를 참조합니다. 해결분을 포함한 `master` 대상 PR에는 `Closes #59`를 적어 병합 시 이슈가 닫히도록 합니다.

`master`는 기본·배포 브랜치이고 `develop`은 다음 변경을 통합합니다. 별도 release 브랜치는 현재 사용하지 않습니다. PR에는 문제·변경 후 동작·실제로 수행한 검증·기존 사용자에게 미치는 영향을 적습니다.

비교할 브랜치를 고른 뒤 기본 PR 설명의 **Preview** 탭에서 **English** 또는 **한국어**를 선택하세요. 언어를 바꾸면 양식이 다시 열리므로 본문 작성 전에 선택합니다. GitHub가 작성 중인 내용을 복원하면 언어 링크를 새 탭에서 열고, 브랜치를 바꿨다면 언어를 다시 선택하세요. 양식은 기본 브랜치에서 불러옵니다. 로컬 템플릿도 사용할 수 있습니다.

```sh
gh pr create --base develop --template .github/PULL_REQUEST_TEMPLATE/korean.md
```

PR 한 개에는 한 언어만 사용합니다. [한국어](https://github.com/oxspan/scouterctl/blob/master/.github/PULL_REQUEST_TEMPLATE/korean.md)·[영어](https://github.com/oxspan/scouterctl/blob/master/.github/PULL_REQUEST_TEMPLATE/english.md) 양식이 있습니다.

`develop` 대상 PR은 Linux 테스트와 패키지 검사를, `master` 대상은 같은 패키지의 Windows 검사까지 받습니다. 최신 커밋의 결과를 확인하고 병합하세요. 로컬 검사 성공은 GitHub 검사 성공을 뜻하지 않습니다. [검사 실행 기준(영문)](docs/local-ci.md)을 참고하세요.

## 커밋과 병합

직접 작성하는 커밋은 의도에 맞는 Gitmoji 하나와 짧은 영어 제목을 사용합니다. 예: `✨ Add offline inspection`, `🐛 Preserve capture checkpoints`, `📝 Clarify installation`.

기능·hotfix PR은 보통 squash merge합니다. PR 제목은 `feat: ✨ Add offline inspection`처럼 Conventional Commit 형식으로 작성하고 squash 제목에도 사용합니다. `feat`는 부 버전, `fix`는 수정 버전, `!`는 현재 안정 버전 계열의 주 버전 변경을 요청합니다. `docs`·`test`·`ci`·`chore`만으로는 새 릴리스를 요청하지 않습니다.

`develop → master` 승격과 `master → develop` 동기화에는 merge commit을 사용합니다. 승격 제목은 `feat: 🔀 Promote capture comparison`처럼 릴리스 의도를 드러내고, 동기화는 `chore: 🔀 Sync master into develop`을 사용합니다. release-please와 Dependabot이 생성한 메시지는 유지합니다.

## 릴리스 관리

버전·릴리스 PR·플랫폼 검사·업로드 복구는 [릴리스 안내(영문)](docs/releasing.md)를 따릅니다. 빌드 버전은 `version.txt`에서 읽습니다. 일반 기여에 태그나 버전 정보 변경을 섞지 마세요.
