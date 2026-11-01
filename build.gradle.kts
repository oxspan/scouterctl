import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}
group = "dev.scouterctl"
version = "0.0.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.scouterctl.MainKt") }
dependencies {
    implementation(files("vendor/scouter-common-2.21.3.jar"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test-junit5"))
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
    useJUnitPlatform()
    dependsOn("fatJar")
    systemProperty("cli.jar", layout.buildDirectory.file("libs/scouterctl-${project.version}-all.jar").get().asFile.absolutePath)
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

val pocDist = tasks.register<Zip>("pocDist") {
    dependsOn(tasks.test)
    archiveFileName.set("scouterctl-${project.version}-poc.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("scouterctl-${project.version}") {
        from(tasks.named("fatJar"))
        from("QUICKSTART.md") { rename { "README.md" } }
        from("CHANGELOG.md")
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
