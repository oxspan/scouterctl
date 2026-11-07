import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}
group = "dev.scouterctl"
version = providers.gradleProperty("candidateVersion").orElse(
    providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map(String::trim)).get()
require(version.toString().matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
    "version.txt must contain a release version (major.minor.patch)"
}
val packageVersionFile = layout.buildDirectory.file("generated/package-version/version.txt")
val packageVersion = tasks.register("packageVersion") {
    inputs.property("version", project.version.toString())
    outputs.file(packageVersionFile)
    doLast {
        packageVersionFile.get().asFile.apply { parentFile.mkdirs(); writeText("${project.version}\n") }
    }
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application {
    mainClass.set("dev.scouterctl.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
tasks.startScripts {
    doLast {
        // Stop prerequisite failures before Gradle's batch template falls through to execution.
        val script = windowsScript.readText()
        val failureExit = "\"%COMSPEC%\" /c exit 1"
        check(script.split(failureExit).size == 3) { "Review the Windows launcher failure paths" }
        windowsScript.writeText(script.replace(failureExit, "exit /b 1"))
    }
}
distributions {
    main {
        contents {
            from("README.md", "QUICKSTART.md", "CONTRIBUTING.md", "CHANGELOG.md", "LICENSE", "SECURITY.md")
            from(packageVersion)
            from("docs") { into("docs") }
            from("vendor") {
                include("README.md", "SCOUTER-LICENSE.txt")
                into("vendor")
            }
        }
    }
}
dependencies {
    implementation(files("vendor/scouter-common-2.21.3.jar"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.6")
    testImplementation("io.kotest:kotest-assertions-core:6.2.6")
    testImplementation("org.jetbrains.pty4j:pty4j:0.13.12")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation(kotlin("reflect"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val verifyCodec = tasks.register("verifyCodec") {
    val codec = layout.projectDirectory.file("vendor/scouter-common-2.21.3.jar")
    inputs.file(codec)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256").digest(codec.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == "89aa7f9343843be682fec6c166f12f2a84636b7756680a4fb9972dd9a9ee9591") {
            "Pinned scouter-common checksum mismatch"
        }
    }
}
tasks.compileKotlin { dependsOn(verifyCodec) }
tasks.processResources {
    val releaseVersion = project.version.toString()
    inputs.property("version", releaseVersion)
    filesMatching("scouterctl-version.properties") {
        expand("version" to releaseVersion)
    }
}
tasks.test {
    useJUnitPlatform { includeEngines("kotest") }
    dependsOn(tasks.distZip)
    inputs.file(tasks.distZip.flatMap { it.archiveFile })
    systemProperty("cli.distribution", tasks.distZip.get().archiveFile.get().asFile.absolutePath)
    systemProperty("architecture.main.classes", sourceSets.main.get().output.classesDirs.asPath)
    val externalJar = providers.gradleProperty("cliJar")
    if (!externalJar.isPresent) dependsOn("fatJar")
    val cliJar = externalJar.map { file(it) }
        .orElse(layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").map { it.asFile })
    inputs.file(cliJar)
    systemProperty("cli.jar", cliJar.get().absolutePath)
}
tasks.register<Jar>("fatJar") {
    dependsOn(tasks.classes)
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Version"] = project.version.toString()
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.register<JavaExec>("realSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.RealSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"))
    systemProperty("cli.jar", layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath)
}
tasks.register<JavaExec>("agentSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.AgentSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"))
    systemProperty("cli.jar", layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath)
}
tasks.register<JavaExec>("springSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.SpringSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"))
    systemProperty("cli.jar", layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath)
}

tasks.register<JavaExec>("recoverySmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.SpringSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--recovery")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}

val pocDist = tasks.register<Zip>("pocDist") {
    dependsOn(tasks.test)
    archiveFileName.set("scouterctl-${project.version}-poc.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("scouterctl-${project.version}") {
        from(tasks.named("fatJar"))
        from("QUICKSTART.md") { rename { "README.md" } }
        from("CHANGELOG.md", "LICENSE", "SECURITY.md")
        from(packageVersion)
        from("vendor") {
            include("README.md", "SCOUTER-LICENSE.txt")
            into("vendor")
        }
    }
}
tasks.register("pocRelease") {
    dependsOn(pocDist)
    val archive = pocDist.flatMap { it.archiveFile }
    val checksums = layout.buildDirectory.file("distributions/SHA256SUMS")
    inputs.file(archive)
    outputs.file(checksums)
    doLast {
        val file = archive.get().asFile
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        checksums.get().asFile.writeText("$hash  ${file.name}\n")
    }
}

listOf("scaleFixtures" to "generate", "scaleCheck" to "run").forEach { (taskName, mode) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        val externalJar = providers.gradleProperty("cliJar")
        if (!externalJar.isPresent) dependsOn("fatJar")
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.scouterctl.ScaleCheckKt")
        args(mode, providers.gradleProperty("scaleInputs").getOrElse("build/scale-inputs"))
        systemProperty("cli.jar", externalJar.getOrElse(layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
    }
}

// Opt-in fixture for matching legacy Collector/Agent releases on Java 8.
val legacyFixture = sourceSets.create("legacyFixture")
configurations.named(legacyFixture.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}
dependencies { add(legacyFixture.implementationConfigurationName, "com.h2database:h2:1.4.200") }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileLegacyFixtureKotlin") {
    setSource(files("src/test/kotlin/dev/scouterctl/fixture/TestService.kt"))
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=8")
}
tasks.named<JavaCompile>(legacyFixture.compileJavaTaskName) { options.release.set(8) }
tasks.register("legacyFixtureClasspath") {
    dependsOn(legacyFixture.classesTaskName)
    doLast { println(legacyFixture.runtimeClasspath.asPath) }
}

tasks.register<JavaExec>("collectorInspectionSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.SpringSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--inspection-only")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}

tasks.register<JavaExec>("agentInspectionSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.AgentSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--inspection-only")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}


tasks.register<JavaExec>("counterSmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.AgentSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--counter-only")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}


tasks.register<JavaExec>("hostCounterSmoke") {
    dependsOn(tasks.testClasses)
    val externalJar = providers.gradleProperty("cliJar")
    if (!externalJar.isPresent) dependsOn("fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.AgentSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--host-counters")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}

tasks.register<JavaExec>("counterHistorySmoke") {
    dependsOn(tasks.testClasses, "fatJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.AgentSmokeKt")
    args(providers.gradleProperty("scouterRoot").getOrElse("../scouter"), "--counter-history-only")
    systemProperty("cli.jar", providers.gradleProperty("cliJar").getOrElse(
        layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath))
}

// Opt-in Windows x64 packaging; ordinary Kotlin builds do not need a native toolchain.
val zigHome = providers.gradleProperty("zigHome")
val windowsJdkHome = providers.gradleProperty("windowsJdkHome").orElse(
    if (System.getProperty("os.name").startsWith("Windows")) providers.systemProperty("java.home")
    else providers.environmentVariable("WINDOWS_JAVA_HOME")
)
val windowsLauncherFile = layout.buildDirectory.file("windows-launcher/scouterctl.exe")
val compileWindowsLauncher = tasks.register<Exec>("compileWindowsLauncher") {
    inputs.dir("native/windows")
    inputs.property("compilerVersion", "0.15.2")
    inputs.property("target", "x86_64-windows-gnu")
    inputs.files(windowsJdkHome.map { listOf(file("$it/include/jni.h"), file("$it/include/win32/jni_md.h")) })
    outputs.file(windowsLauncherFile)
    workingDir("native/windows")
    doFirst {
        require(zigHome.isPresent) { "Set -PzigHome to an extracted Zig 0.15.2 toolchain" }
        require(windowsJdkHome.isPresent) { "Set -PwindowsJdkHome to a Windows JDK 21 installation" }
        val zig = file("${zigHome.get()}/zig" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val actual = providers.exec { commandLine(zig, "version") }.standardOutput.asText.get().trim()
        check(actual == "0.15.2") { "Windows launcher build requires Zig 0.15.2" }
        val include = file("${windowsJdkHome.get()}/include")
        check(include.resolve("win32/jni_md.h").isFile) { "Windows JDK headers are required" }
        val output = windowsLauncherFile.get().asFile
        output.parentFile.mkdirs()
        commandLine(zig, "c++", "-target", "x86_64-windows-gnu", "-std=c++17", "-O2", "-static", "-municode",
            "-Wall", "-Wextra", "-Werror", "-I$include", "-I${include.resolve("win32")}",
            "launcher.cpp", "launcher.rc", "-lshell32", "-o", output)
    }
}
val releaseInputDirectory = providers.gradleProperty("releaseInputDirectory").map { file(it) }
val commandDistribution = releaseInputDirectory.map { it.resolve("scouterctl-${project.version}.zip") }
    .orElse(tasks.distZip.flatMap { it.archiveFile }.map { it.asFile })
val windowsDistZip = tasks.register<Zip>("windowsDistZip") {
    dependsOn(compileWindowsLauncher)
    if (!releaseInputDirectory.isPresent) dependsOn(tasks.distZip)
    archiveFileName.set("scouterctl-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(commandDistribution.map { zipTree(it) }) { exclude("*/bin/*") }
    into("scouterctl-${project.version}/bin") { from(windowsLauncherFile) }
    into("scouterctl-${project.version}/vendor/windows-launcher") {
        from("native/windows/README.md")
        from(zigHome.map { file("$it/LICENSE") }) { rename { "Zig-LICENSE.txt" } }
        listOf("libcxx", "libcxxabi", "libunwind").forEach { library ->
            from(zigHome.map { file("$it/lib/$library/LICENSE.TXT") }) { rename { "$library-LICENSE.txt" } }
        }
        from(zigHome.map { file("$it/lib/libc/mingw/COPYING") }) { rename { "MinGW-COPYING.txt" } }
    }
}
tasks.test {
    val windowsDistribution = providers.gradleProperty("windowsDistribution")
    if (windowsDistribution.isPresent && System.getProperty("os.name").startsWith("Windows")) {
        inputs.file(windowsDistribution.map { file(it) })
        // Java's legacy Windows child-process quoting can discard backslashes before quotes.
        systemProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
        systemProperty("cli.windows.distribution", file(windowsDistribution.get()).absolutePath)
    } else exclude("**/WindowsLauncherTest*")
}

val linuxDistZip = tasks.register<Zip>("linuxDistZip") {
    dependsOn(tasks.distZip)
    archiveFileName.set("scouterctl-${project.version}-linux-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.distZip.map { zipTree(it.archiveFile) }) {
        exclude("*/bin/scouterctl.bat")
        eachFile { if (path.endsWith("/bin/scouterctl")) permissions { unix("rwxr-xr-x") } }
    }
}
val releaseInputs = tasks.register<Sync>("releaseInputs") {
    dependsOn(linuxDistZip, tasks.named("fatJar"), tasks.distZip)
    into(layout.buildDirectory.dir("release-inputs"))
    from(linuxDistZip, tasks.named("fatJar"), tasks.distZip)
    doLast {
        val directory = layout.buildDirectory.dir("release-inputs").get().asFile
        val names = listOf("scouterctl-${project.version}.zip", "scouterctl-${project.version}-linux-x64.zip", "scouterctl-${project.version}-all.jar").sorted()
        directory.resolve("SHA256SUMS").writeText(names.joinToString("") { name ->
            val hash = MessageDigest.getInstance("SHA-256").digest(directory.resolve(name).readBytes()).joinToString("") { "%02x".format(it) }
            "$hash  $name\n"
        })
    }
}
val releasePackages = tasks.register<Sync>("releasePackages") {
    dependsOn(windowsDistZip)
    into(layout.buildDirectory.dir("release"))
    if (releaseInputDirectory.isPresent) {
        from(releaseInputDirectory.map { it.resolve("scouterctl-${project.version}-linux-x64.zip") })
    } else {
        dependsOn(linuxDistZip)
        from(linuxDistZip)
    }
    from(windowsDistZip)
    doLast {
        val directory = layout.buildDirectory.dir("release").get().asFile
        val names = listOf("scouterctl-${project.version}-linux-x64.zip", "scouterctl-${project.version}-windows-x64.zip")
        directory.resolve("SHA256SUMS").writeText(names.joinToString("") { name ->
            val hash = MessageDigest.getInstance("SHA-256").digest(directory.resolve(name).readBytes()).joinToString("") { "%02x".format(it) }
            "$hash  $name\n"
        })
    }
}
listOf("verifyReleaseInputs" to "inputs", "verifyCommandRelease" to "bundle").forEach { (taskName, mode) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        if (mode == "inputs" && !releaseInputDirectory.isPresent) dependsOn(releaseInputs)
        if (mode == "bundle") dependsOn(releasePackages)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.scouterctl.CommandPackageCheckKt")
        systemProperty("release.libraries", (listOf(tasks.jar.get().archiveFile.get().asFile.name) + configurations.runtimeClasspath.get().files.map { it.name }).joinToString("|"))
        systemProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
        args(mode, project.version.toString(), if (mode == "inputs")
            releaseInputDirectory.orElse(layout.buildDirectory.dir("release-inputs").map { it.asFile }).get().absolutePath
            else layout.buildDirectory.dir("release").get().asFile.absolutePath)
    }
}

tasks.register<JavaExec>("installLifecycleSmoke") {
    dependsOn(tasks.testClasses)
    val current = providers.gradleProperty("currentPackage")
    val previous = providers.gradleProperty("previousPackage")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.scouterctl.CommandPackageCheckKt")
    systemProperty("release.libraries", (listOf(tasks.jar.get().archiveFile.get().asFile.name) + configurations.runtimeClasspath.get().files.map { it.name }).joinToString("|"))
    systemProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
    doFirst {
        require(current.isPresent && previous.isPresent) { "Set currentPackage and previousPackage to two actual command ZIPs" }
        args("lifecycle", project.version.toString(), file(current.get()).absolutePath, file(previous.get()).absolutePath)
    }
}
