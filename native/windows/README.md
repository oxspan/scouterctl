# Windows launcher

This bootstrap passes Windows UTF-16 arguments directly to the Kotlin entry point through the public JNI Invocation API. It hosts Java in the same console process, preserves the current directory and streams, and uses a process-local UTF-8 manifest. It does not alter the system locale or console code page.

Build with Zig 0.15.2 and Windows JDK 21 headers using `windowsDistZip`; see [installation](../../docs/installation.md). The executable targets Windows x64, Windows 10 version 1903 or newer, and HotSpot Java 21 or newer. `JAVA_HOME` takes priority over a `java.exe` found on PATH.

`JAVA_OPTS` and `SCOUTERCTL_OPTS` accept JVM options using Windows command-line quoting. The JVM also reads `JAVA_TOOL_OPTIONS`. The java.exe-specific `JDK_JAVA_OPTIONS` is rejected with guidance rather than silently ignored. Java launcher options such as `-jar` or `--class-path` are not JVM options.

The distribution copies the Zig runtime, LLVM C++/ABI/unwind and MinGW notices from the pinned toolchain. The JDK is not bundled. Scouterctl's own source remains Apache-2.0.

JDK 21 still cannot load application JARs through a class path containing supplementary characters such as emoji. Korean installation paths and Unicode argument/file values are supported; emoji in the installation path remains outside this launcher's supported scope. No custom class loader or JDK-private API is used to hide that limitation.

For a Java `ProcessBuilder` caller, use `-Djdk.lang.Process.allowAmbiguousCommands=false` in the calling JVM when passing literal quotes and backslashes. The legacy Windows quoting mode can discard characters before the executable receives them. The native Windows tests use the strict mode and assert exact stored values.
