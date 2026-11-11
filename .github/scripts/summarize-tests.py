#!/usr/bin/env python3
"""Render Gradle test counts and the coverage summary without publishing logs."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(tests_directory: Path, coverage_summary: Path) -> str:
    lines = ["## Test results (Linux)", ""]
    reports = sorted(tests_directory.glob("TEST-*.xml"))
    try:
        if not reports:
            raise ValueError("No test reports")
        totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
        for report in reports:
            suite = ET.parse(report).getroot()
            if suite.tag != "testsuite":
                raise ValueError("Unexpected report root")
            counts = {name: int(suite.attrib[name]) for name in totals}
            if min(counts.values()) < 0 or counts["tests"] < sum(counts[name] for name in ("failures", "errors", "skipped")):
                raise ValueError("Invalid report counts")
            for name, value in counts.items():
                totals[name] += value
        passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
        lines += [
            "| Total | Passed | Failed | Errors | Skipped |",
            "| ---: | ---: | ---: | ---: | ---: |",
            f"| {totals['tests']} | {passed} | {totals['failures']} | {totals['errors']} | {totals['skipped']} |",
        ]
        if not totals["tests"]:
            lines += ["", "No tests were executed."]
    except (ET.ParseError, OSError, KeyError, ValueError):
        lines += ["Test results are unavailable or invalid. Check the build logs; this is not a passing test result."]
    lines += [""]
    try:
        coverage = coverage_summary.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        coverage = ""
    if coverage:
        lines += [coverage]
    else:
        lines += ["## Test coverage", "", "Coverage is unavailable. The build or tests may have stopped before a report was generated."]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tests-directory", type=Path, default=Path("build/test-results/test"))
    parser.add_argument("--coverage-summary", type=Path, default=Path("build/reports/jacoco/summary.md"))
    parser.add_argument("--output", type=Path, default=Path("build/reports/ci/summary.md"))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(summarize(args.tests_directory, args.coverage_summary), encoding="utf-8")


if __name__ == "__main__":
    main()
