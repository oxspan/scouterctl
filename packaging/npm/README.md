# scouterctl

Command-line access to Scouter Collectors and Agents. Requires Node.js 22 or newer
and Java 21 or newer. Supported platforms: Linux x64 and Windows x64.

```sh
npm install --global scouterctl
scouterctl --version
```

Pin a version globally with `npm install --global scouterctl@<version>`, or install
it in a project with `npm install --save-dev --save-exact scouterctl@<version>` and
run `npx --no-install scouterctl`. Installation works with `--ignore-scripts`;
all JARs are included, with no install-time or first-run downloads.

Java is selected from `JAVA_HOME`, then `PATH`. Windows uses the same native
launcher as the ZIP to preserve Unicode arguments independently of the system
code page. Use `JAVA_TOOL_OPTIONS` for JVM options on both platforms. The Windows
launcher also accepts `JAVA_OPTS` and `SCOUTERCTL_OPTS`; it rejects `JDK_JAVA_OPTIONS`.
Arguments,
standard input/output, the caller's directory and CLI exit codes are preserved.
On Windows with Java 21, avoid emoji in the installation path because of a JDK
class-loading limitation. Unicode command arguments and configuration paths are supported.

Update with `npm install --global scouterctl@<version>` and remove with
`npm uninstall --global scouterctl`. Configuration and capture files are stored
outside the package and are retained. Project installations use the corresponding
commands without `--global`.

[Installation and command documentation](https://github.com/oxspan/scouterctl/blob/master/docs/installation.md)
