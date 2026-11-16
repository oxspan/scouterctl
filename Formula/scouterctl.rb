class Scouterctl < Formula
  desc "Command-line access to Scouter Collectors and Agents"
  homepage "https://github.com/oxspan/scouterctl"
  url "https://github.com/oxspan/scouterctl/releases/download/v1.1.0/scouterctl-1.1.0-linux-x64.zip"
  version "1.1.0"
  sha256 "0939fa4fcfc3c563f2c6fe92433b2a5788a36bb3a8e43f6a0f1c735640c0d7e1"
  license "Apache-2.0"

  depends_on :linux
  depends_on arch: :x86_64
  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"scouterctl").write_env_script libexec/"bin/scouterctl",
      Language::Java.overridable_java_home_env("21")
  end

  test do
    assert_equal "scouterctl 1.1.0\n", shell_output("#{bin}/scouterctl --version")
    ENV["SCOUTERCTL_CONFIG"] = testpath/"config.json"
    system bin/"scouterctl", "context", "add", "--name", "test", "--user", "guest"
    assert_match '"test"', shell_output("#{bin}/scouterctl context list")
  end
end
