import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.file.RelativePath
import java.util.zip.ZipFile

val releaseVersion = project.version.toString()
val sourceSets = extensions.getByType<SourceSetContainer>()
val npmStage = layout.buildDirectory.dir("npm-stage/$releaseVersion")
val externalDistribution = providers.gradleProperty("npmDistribution")
val externalWindows = providers.gradleProperty("npmWindowsDistribution")
val jvmArchive = externalDistribution.map { file(it) }
    .orElse(tasks.named<Zip>("distZip").flatMap { it.archiveFile }.map { it.asFile })
val windowsArchive = externalWindows.map { file(it) }
    .orElse(tasks.named<Zip>("windowsDistZip").flatMap { it.archiveFile }.map { it.asFile })
val stageNpmPackage = tasks.register<Sync>("stageNpmPackage") {
    if (!externalDistribution.isPresent) dependsOn("distZip")
    if (!externalWindows.isPresent) dependsOn("windowsDistZip")
    into(npmStage)
    from("packaging/npm")
    from("LICENSE")
    from(jvmArchive.map { zipTree(it) }) {
        include("*/lib/**", "*/vendor/SCOUTER-LICENSE.txt")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    }
    from(windowsArchive.map { zipTree(it) }) {
        include("*/bin/scouterctl.exe", "*/vendor/windows-launcher/**")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    }
    includeEmptyDirs = false
    inputs.property("version", releaseVersion)
    doFirst {
        ZipFile(jvmArchive.get()).use { zip ->
            val entry = checkNotNull(zip.getEntry("scouterctl-$releaseVersion/version.txt")) { "Wrong npm JVM distribution version" }
            check(zip.getInputStream(entry).bufferedReader().use { it.readText().trim() } == releaseVersion)
        }
        ZipFile(windowsArchive.get()).use { zip ->
            check(zip.entries().asSequence().any { it.name.endsWith("/bin/scouterctl.exe") }) { "Missing Windows npm launcher" }
        }
    }
    doLast {
        npmStage.get().file("package.json").asFile.writeText("""
            {
              "name": "scouterctl",
              "version": "$releaseVersion",
              "description": "Command-line access to Scouter Collectors and Agents",
              "license": "Apache-2.0",
              "repository": { "type": "git", "url": "git+https://github.com/oxspan/scouterctl.git" },
              "homepage": "https://github.com/oxspan/scouterctl",
              "bin": { "scouterctl": "scouterctl.cjs" },
              "engines": { "node": ">=22" },
              "os": ["linux", "win32"],
              "cpu": ["x64"],
              "files": ["scouterctl.cjs", "bin/", "lib/", "vendor/", "LICENSE", "README.md"],
              "publishConfig": { "access": "public" }
            }
        """.trimIndent() + "\n")
    }
}
tasks.register<Exec>("npmPackage") {
    description = "Pack the versioned, offline-installable npm distribution (Node.js/npm required)."
    dependsOn(stageNpmPackage)
    workingDir(npmStage)
    val output = layout.buildDirectory.dir("npm")
    inputs.dir(npmStage)
    outputs.file(output.map { it.file("scouterctl-$releaseVersion.tgz") })
    doFirst {
        output.get().asFile.mkdirs()
        val command = listOf("npm", "pack", "--ignore-scripts", "--pack-destination", output.get().asFile.absolutePath)
        commandLine(if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd.exe", "/d", "/c") + command else command)
    }
}
tasks.register<JavaExec>("verifyNpmPackage") {
    description = "Install, upgrade, downgrade and remove two actual npm archives."
    dependsOn("testClasses")
    classpath = sourceSets.named("test").get().runtimeClasspath
    mainClass.set("dev.scouterctl.NpmPackageCheckKt")
    systemProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
    args(releaseVersion,
        providers.gradleProperty("npmArchive").getOrElse("build/npm/scouterctl-$releaseVersion.tgz"),
        providers.gradleProperty("previousNpmArchive").getOrElse("build/npm/scouterctl-0.0.0.tgz"))
}
