#!/usr/bin/env node
"use strict";

const { spawn } = require("node:child_process");
const { constants } = require("node:os");
const path = require("node:path");

const windows = process.platform === "win32";
const home = process.env["JAVA_HOME"]?.replace(/^"(.*)"$/, "$1");
const java = home ? path.join(home, "bin", "java") : "java";
const child = spawn(
  windows ? path.join(__dirname, "bin", "scouterctl.exe") : java,
  windows
    ? process.argv.slice(2)
    : [
        "-Dfile.encoding=UTF-8",
        "-cp",
        path.join(__dirname, "lib", "*"),
        "dev.scouterctl.MainKt",
        ...process.argv.slice(2),
      ],
  { stdio: "inherit", shell: false },
);

// Both processes receive console signals. Let Java finish its shutdown hooks.
process.on("SIGINT", () => {});
process.on("SIGTERM", () => child.kill("SIGTERM"));
child.on("error", (error) => {
  console.error(`scouterctl: could not start the command (${error.message}).`);
  console.error(
    windows
      ? "Reinstall scouterctl; its packaged Windows launcher could not start."
      : "Install Java 21 or newer and set JAVA_HOME or add java to PATH.",
  );
  process.exitCode = 1;
});
child.on("exit", (code, signal) => {
  process.exitCode = code ?? (signal ? 128 + constants.signals[signal] : 1);
});
