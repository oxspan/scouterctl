# 보안 안내

[English](SECURITY.md) | 한국어

## 취약점 신고

저장소의 Security 탭에서 **Report a vulnerability**를 사용할 수 있으면 [비공개 취약점 신고](https://github.com/oxspan/scouterctl/security/advisories/new)로 알려주세요. 영향을 받는 scouterctl·Collector 버전, 운영체제·Java 버전, 재현 방법과 예상 영향을 포함해 주세요.

신고 버튼이 보이지 않으면 취약점 내용을 적지 말고 기능 활성화를 요청하는 이슈만 올려주세요. 공격 방법·계정 정보·운영 캡처·SQL 파라미터·개인정보는 공개 이슈나 PR에 올리지 않습니다. 민감한 내용은 비공개 연락 경로가 마련된 뒤 전달하세요.

## 수정 대상 버전

보안 수정은 최신 배포 버전을 대상으로 합니다. 이전 버전의 별도 유지보수는 보장하지 않습니다. 재현할 수 있도록 정확한 버전과 알고 있는 커밋을 알려주세요.

## 저장소 보호 정책

- Secret scanning과 푸시 보호로 지원되는 자격증명 패턴을 탐지합니다. 캡처·SQL 원문 등 민감정보는 공개 전에 별도로 검토해야 합니다.
- `master`와 `develop` 변경은 PR을 거치며, 리뷰 대화 해결, 최신 기준 브랜치 반영, GitHub Actions의 `Build and test` 및 `Windows command package` 체크를 요구합니다. Windows 검사는 `master` 대상 PR에서 실행하며 `develop` 대상 PR에서는 의도적으로 건너뜁니다. 규칙 우회는 허용하지 않습니다. 운영자가 한 명인 동안 필수 승인 수는 0명입니다.
- Dependabot 버전 업데이트는 `develop`의 GitHub Actions와 Gradle을 대상으로 합니다. 보안 업데이트 대상은 기본 브랜치인 `master`입니다. 의존성 제출 workflow는 `master` 푸시 후, 매주, 또는 `master`에서 수동 실행할 때 Gradle 의존성을 해석해 제출합니다. 저장소에 포함된 Scouter JAR은 교체할 때 출처·체크섬·취약점을 별도로 검토해야 합니다.
- CodeQL 기본 설정으로 GitHub Actions, Java/Kotlin, C/C++을 검사하며 로컬·원격 입력을 모두 분석 대상으로 삼습니다. 발견된 항목은 운영자가 검토합니다.
- Actions는 전체 커밋 SHA로 고정합니다. GitHub 소유 Actions, `gradle/actions/*`, `googleapis/release-please-action`을 허용합니다. 기본 workflow 토큰은 읽기 전용이며 필요한 작업에 추가 권한을 지정합니다.

## 사용할 때 주의할 점

- Collector TCP는 신뢰할 수 있는 네트워크나 별도로 보안 처리한 터널에서 사용합니다. CLI가 TLS를 추가하지는 않습니다.
- 조회 전용 요청도 Collector의 권한 검사를 보장하지 않습니다. 적절한 계정을 사용하고 네트워크 접근을 제한하세요.
- 캡처와 선택적으로 저장한 서비스명·SQL·오류 원문에는 민감한 애플리케이션 정보가 포함될 수 있습니다. 파일 접근 권한을 제한하고 공유 전 민감한 내용을 지우세요.
- ZIP은 같은 릴리스의 체크섬과 대조합니다. 파일 내용이 맞는지 확인하는 검사이며 GitHub와 별개로 배포자의 신원을 증명하지는 않습니다.
