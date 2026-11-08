# scouterctl 설치

[English](../installation.md) | 한국어

같은 [GitHub 릴리스](https://github.com/oxspan/scouterctl/releases)에서 운영체제에 맞는 ZIP과 `SHA256SUMS`를 받습니다.

| 운영체제 | 파일 | 실행 환경 |
| --- | --- | --- |
| Linux x64 | `scouterctl-<version>-linux-x64.zip` | JDK 21 |
| Windows x64 | `scouterctl-<version>-windows-x64.zip` | x64 HotSpot JDK 21, Windows 10 1903 이상 |

Java·Collector·Agent는 포함하지 않습니다. Collector·Agent의 JVM은 CLI와 별개입니다. [지원 버전(영문)](../supported-versions.md)을 확인하세요. 현재 macOS·ARM은 지원 대상이 아닙니다. 0.x JAR 파일은 [이전 설치 안내(영문)](../legacy-jar.md)를 따릅니다.

## Linux

압축을 풀기 전에 체크섬을 확인합니다. 아래는 1.0.0 설치 예시입니다.

```sh
sha256sum --check --ignore-missing SHA256SUMS
mkdir -p "$HOME/.local/opt"
unzip scouterctl-1.0.0-linux-x64.zip -d "$HOME/.local/opt"
export PATH="$HOME/.local/opt/scouterctl-1.0.0/bin:$PATH"
scouterctl --version
scouterctl --help
```

다음 터미널에서도 사용하려면 셸 시작 파일에 PATH 설정을 추가하세요. `JAVA_HOME`을 JDK 21로 지정하거나 PATH에서 Java 21을 찾을 수 있어야 합니다. 압축 해제 도구가 실행 권한을 보존하지 않으면 `chmod +x <설치 경로>/bin/scouterctl`이 필요할 수 있습니다.

## Windows

체크섬이 맞는지 확인한 뒤 압축을 풉니다.

```powershell
$archive = Get-Item ./scouterctl-1.0.0-windows-x64.zip
$actual = (Get-FileHash $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ("$actual  $($archive.Name)" -cnotin (Get-Content ./SHA256SUMS)) { throw 'Checksum mismatch' }
Expand-Archive -LiteralPath $archive.FullName -DestinationPath "$HOME/Tools"
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21'
$env:PATH = "$HOME/Tools/scouterctl-1.0.0/bin;$env:PATH"
scouterctl --version
scouterctl --help
```

`JAVA_HOME`은 실제 JDK 설치 경로로 바꿉니다. 다음 터미널에서도 사용하려면 설치 폴더의 `bin`을 **사용자 PATH**에 추가하세요. ZIP은 PATH·레지스트리·시스템 언어·콘솔 코드 페이지·실행 정책을 바꾸지 않습니다. `JAVA_HOME`이 PATH의 Java보다 우선하며 PowerShell과 명령 프롬프트에서 `scouterctl.exe`가 실행됩니다.

한글 설치 경로와 직접 입력한 Unicode 인자를 지원합니다. JDK 21의 클래스 로딩 제한으로 **설치 경로**에는 이모지 등 보조 평면 문자를 넣지 마세요. 인자·호출한 디렉터리·캡처 및 설정 경로에는 이모지를 사용할 수 있습니다. Java `ProcessBuilder`에서 따옴표와 역슬래시를 전달하려면 호출하는 JVM에 `-Djdk.lang.Process.allowAmbiguousCommands=false`를 설정하세요. 복잡한 따옴표를 다룰 때는 PowerShell 7을 권장합니다.

`JAVA_OPTS`와 `SCOUTERCTL_OPTS`에는 JVM 옵션을 지정할 수 있고 JVM은 `JAVA_TOOL_OPTIONS`도 읽습니다. java.exe 전용 `JDK_JAVA_OPTIONS`는 안내와 함께 거부합니다. Java를 찾지 못하는 등 CLI 시작 전 오류는 stderr와 종료 코드 1로 전달되며 JSON 결과가 나오지 않을 수 있습니다.

## 연결

```sh
scouterctl doctor --host 127.0.0.1 --port 6100 --user guest --interactive
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user guest
scouterctl context use --name local
```

주소·계정은 실제 Collector 정보로 바꾸세요. 비밀번호는 저장하지 않습니다. 자동화에서는 `SCOUTERCTL_PASSWORD`와 `--no-input`을 사용합니다. [연결과 비밀번호](connections.md), [빠른 시작](../../QUICKSTART.ko.md)으로 이어집니다.

## 업데이트·이전 버전으로 전환·제거

버전마다 다른 디렉터리에 압축을 풀고 PATH로 사용할 버전을 선택합니다. `scouterctl --version`으로 확인하세요. 필요하면 새 터미널을 열거나 셸의 명령 캐시를 비웁니다. 기존 설치 폴더 위에 새 버전을 덮어쓰지 마세요. 이전 버전의 PATH로 바꾸면 되돌릴 수 있습니다.

제거할 때는 해당 버전의 PATH 항목과 설치 폴더만 지웁니다. 설정과 저장 자료는 설치 폴더 밖에 두세요. 설정 파일은 `SCOUTERCTL_CONFIG`, 또는 `~/.config/scouterctl/config.json`에 있으며 `XDG_CONFIG_HOME`으로 기본 위치를 바꿀 수 있습니다. 설치 폴더 밖의 자료는 업데이트·제거 후에도 유지됩니다. 이전 CLI가 새 파일 형식을 읽지 못할 수 있으므로 [캡처 파일 버전(영문)](../capture-compatibility.md)과 [지표 추출 파일(영문)](../counter-export.md)을 확인하세요.

## 소스에서 빌드

[기여 안내](../../CONTRIBUTING.ko.md)에서 개발용 실행 방법을, [릴리스 안내(영문)](../releasing.md)에서 Windows 실행기와 배포 파일의 빌드 도구를 확인할 수 있습니다.
