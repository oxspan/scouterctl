import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}
group = "dev.scouterctl"
version = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.get().trim()
require(version.toString().matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
    "version.txt must contain a release version (major.minor.patch)"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.scouterctl.MainKt") }
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
        from("CHANGELOG.md", "version.txt", "LICENSE", "SECURITY.md")
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
