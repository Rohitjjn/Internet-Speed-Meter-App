#!/usr/bin/env python3
"""
Condense a Gradle log (+ JUnit XML reports or a Lint XML report) into a short, readable
text and publish it as a GitHub *check run* on the commit, so the result is visible in
the Checks tab and retrievable through the REST API (the raw job log is not).

Usage (inside GitHub Actions):
  publish_check.py --name "Unit test log" --log gradle-test.log \
      --results app/build/test-results/testDebugUnitTest --outcome success|failure
  publish_check.py --name "Lint log" --log gradle-lint.log \
      --lint app/build/reports/lint-results-release.xml --outcome success|failure --neutral

  publish_check.py --self-test        # offline parser check, no token needed

Only the standard library is used. Never fails the job: publishing problems are printed
and swallowed so a broken token can never mask a real build result.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_TEXT = 60_000          # GitHub caps check-run output.text at 65 535 chars
MAX_SUMMARY = 6_000
MAX_ANNOTATIONS = 50       # per API call; the API accepts at most 50 per request

KOTLIN_DIAG = re.compile(r"^(e|w): (?:file://)?(\S+?):(\d+):(\d+) (.*)$")
TEST_FAILED = re.compile(r"^(\S+) > (.+) FAILED$")
TESTS_DONE = re.compile(r"^(\d+) tests? completed(?:, (\d+) failed)?(?:, (\d+) skipped)?")
TASK_FAILED = re.compile(r"^Execution failed for task '(.+)'\.$")


def read_text(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def strip_workspace(path):
    ws = os.environ.get("GITHUB_WORKSPACE", "")
    if ws and path.startswith(ws):
        path = path[len(ws):].lstrip("/")
    return path


def parse_gradle_log(log):
    errors, warnings, failed_tests, totals, task_failures, went_wrong = [], [], [], [], [], []
    capture = False
    for raw in log.splitlines():
        line = raw.rstrip()
        m = KOTLIN_DIAG.match(line)
        if m:
            kind, path, ln, col, msg = m.groups()
            item = (strip_workspace(path), int(ln), int(col), msg)
            (errors if kind == "e" else warnings).append(item)
            continue
        m = TEST_FAILED.match(line)
        if m:
            failed_tests.append(f"{m.group(1)} > {m.group(2)}")
            continue
        m = TESTS_DONE.match(line)
        if m:
            totals.append(line)
            continue
        m = TASK_FAILED.match(line)
        if m:
            task_failures.append(m.group(1))
        if line.startswith("* What went wrong:"):
            capture = True
            went_wrong.append(line)
            continue
        if capture:
            if line.startswith("* Try:") or line.startswith("* Exception is:"):
                capture = False
            elif len(went_wrong) < 60:
                went_wrong.append(line)
    return errors, warnings, failed_tests, totals, task_failures, went_wrong


def parse_junit(results_dir):
    """Returns (suite_rows, failures) from JUnit XML files."""
    suites, failures = [], []
    root = Path(results_dir)
    if not root.is_dir():
        return suites, failures
    for xml_file in sorted(root.glob("TEST-*.xml")):
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError:
            continue
        suite = tree.getroot()
        name = suite.get("name", xml_file.stem)
        tests = int(suite.get("tests", 0) or 0)
        fails = int(suite.get("failures", 0) or 0) + int(suite.get("errors", 0) or 0)
        skipped = int(suite.get("skipped", 0) or 0)
        suites.append((name, tests, fails, skipped))
        for case in suite.iter("testcase"):
            for node in list(case.findall("failure")) + list(case.findall("error")):
                message = (node.get("message") or "").strip()
                body = (node.text or "").strip().splitlines()
                # Keep the assertion + the first frames inside the project.
                keep = []
                for frame in body:
                    if len(keep) >= 14:
                        break
                    if frame.strip().startswith("at ") and "com.example" not in frame and len(keep) > 3:
                        continue
                    keep.append(frame)
                failures.append((case.get("classname", name), case.get("name", "?"), message, keep))
    return suites, failures


def parse_lint(lint_xml):
    issues = []
    text = read_text(lint_xml)
    if not text:
        return issues
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return issues
    for issue in root.iter("issue"):
        loc = issue.find("location")
        path = strip_workspace(loc.get("file", "")) if loc is not None else ""
        line = loc.get("line", "") if loc is not None else ""
        issues.append((issue.get("severity", ""), issue.get("id", ""), issue.get("message", ""), path, line))
    order = {"Fatal": 0, "Error": 1, "Warning": 2, "Informational": 3}
    issues.sort(key=lambda i: order.get(i[0], 9))
    return issues


def annotate(errors, failures):
    """Emit workflow commands so problems show up inline in the Files tab."""
    shown = 0
    for path, ln, col, msg in errors:
        if shown >= 10:
            break
        safe = msg.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
        print(f"::error file={path},line={ln},col={col}::{safe}")
        shown += 1
    for classname, name, message, _ in failures[:10]:
        safe = f"{classname}.{name}: {message}".replace("%", "%25").replace("\n", "%0A")
        print(f"::error::{safe}")


def build_report(args):
    log = read_text(args.log)
    errors, warnings, failed_tests, totals, task_failures, went_wrong = parse_gradle_log(log)
    suites, failures = parse_junit(args.results) if args.results else ([], [])
    lint_issues = parse_lint(args.lint) if args.lint else []

    lines = []
    title_bits = []
    if errors:
        title_bits.append(f"{len(errors)} compile error(s)")
    if failures or failed_tests:
        title_bits.append(f"{max(len(failures), len(failed_tests))} failed test(s)")
    if lint_issues:
        n_err = sum(1 for i in lint_issues if i[0] in ("Error", "Fatal"))
        title_bits.append(f"lint: {n_err} error(s), {len(lint_issues) - n_err} other")
    if not title_bits:
        title_bits.append("clean" if args.outcome == "success" else "failed — see log")
    title = f"{args.name}: " + ", ".join(title_bits)

    lines.append(f"# {args.name}")
    lines.append(f"Outcome: **{args.outcome}**  ·  commit `{os.environ.get('GITHUB_SHA', '')[:12]}`  ·  "
                 f"run {os.environ.get('GITHUB_RUN_ID', '')}")
    lines.append("")

    if errors:
        lines.append(f"## Kotlin compile errors ({len(errors)})")
        for path, ln, col, msg in errors[:80]:
            lines.append(f"- `{path}:{ln}:{col}` {msg}")
        if len(errors) > 80:
            lines.append(f"- … {len(errors) - 80} more")
        lines.append("")

    if suites:
        total = sum(s[1] for s in suites)
        failed = sum(s[2] for s in suites)
        skipped = sum(s[3] for s in suites)
        lines.append(f"## Unit tests: {total} run, {failed} failed, {skipped} skipped")
        for name, tests, fails, skip in suites:
            flag = "❌" if fails else "✅"
            lines.append(f"- {flag} `{name}` — {tests} tests, {fails} failed, {skip} skipped")
        lines.append("")
    elif totals:
        lines.append("## Unit tests")
        lines.extend(f"- {t}" for t in totals)
        lines.append("")

    if failures:
        lines.append("## Failed tests")
        for classname, name, message, frames in failures[:40]:
            lines.append(f"### `{classname}.{name}`")
            if message:
                lines.append(message[:800])
            if frames:
                lines.append("```")
                lines.extend(f[:300] for f in frames)
                lines.append("```")
        lines.append("")
    elif failed_tests:
        lines.append("## Failed tests (from console)")
        lines.extend(f"- {t}" for t in failed_tests[:60])
        lines.append("")

    if lint_issues:
        lines.append(f"## Lint issues ({len(lint_issues)})")
        for severity, issue_id, message, path, line in lint_issues[:120]:
            where = f"`{path}:{line}`" if path else ""
            lines.append(f"- **{severity}** {issue_id} — {message[:240]} {where}")
        if len(lint_issues) > 120:
            lines.append(f"- … {len(lint_issues) - 120} more")
        lines.append("")

    if task_failures or went_wrong:
        lines.append("## Gradle failure")
        for t in task_failures:
            lines.append(f"- task `{t}`")
        if went_wrong:
            lines.append("```")
            lines.extend(w[:300] for w in went_wrong)
            lines.append("```")
        lines.append("")

    if warnings:
        lines.append(f"## Kotlin warnings ({len(warnings)})")
        for path, ln, col, msg in warnings[:60]:
            lines.append(f"- `{path}:{ln}:{col}` {msg}")
        if len(warnings) > 60:
            lines.append(f"- … {len(warnings) - 60} more")
        lines.append("")

    if not log:
        lines.append("_No Gradle log was captured._")
    elif args.outcome != "success" and not (errors or failures or failed_tests or went_wrong):
        lines.append("## Log tail")
        lines.append("```")
        lines.extend(l[:300] for l in log.splitlines()[-120:])
        lines.append("```")

    text = "\n".join(lines)
    if len(text) > MAX_TEXT:
        text = text[:MAX_TEXT - 40] + "\n\n_… truncated …_"
    summary = "\n".join(lines[:40])
    if len(summary) > MAX_SUMMARY:
        summary = summary[:MAX_SUMMARY - 20] + "\n_…_"
    return title, summary, text, errors, failures


def post_check_run(name, title, summary, text, conclusion):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if not (token and repo and sha):
        print("publish_check: missing GH_TOKEN/GITHUB_REPOSITORY/GITHUB_SHA — skipped")
        return
    body = {
        "name": name,
        "head_sha": sha,
        "status": "completed",
        "conclusion": conclusion,
        "details_url": f"https://github.com/{repo}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}",
        "output": {"title": title[:250], "summary": summary, "text": text},
    }
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/check-runs",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"publish_check: created check run {data.get('id')} ({name}, {conclusion})")
    except urllib.error.HTTPError as e:
        print(f"publish_check: HTTP {e.code} — {e.read().decode('utf-8', 'replace')[:500]}")
    except Exception as e:  # noqa: BLE001 — never fail the job because of reporting
        print(f"publish_check: {e}")


def self_test():
    """
    Offline check of the three parsers against embedded samples (no token, no network).
    Run by the workflow before Gradle so a regression in this script is caught by the
    script itself instead of silently producing an empty check run.
    """
    import tempfile

    sample_log = "\n".join([
        "e: file:///home/runner/work/app/src/main/java/com/example/A.kt:12:5 Unresolved reference: foo",
        "w: file:///home/runner/work/app/src/main/java/com/example/B.kt:3:1 Unused variable",
        "com.example.SpeedEngineTest > downloadAndUploadAreNeverMirrored FAILED",
        "99 tests completed, 1 failed, 2 skipped",
        "Execution failed for task ':app:testDebugUnitTest'.",
        "* What went wrong:",
        "There were failing tests.",
        "* Try:",
    ])
    errors, warnings, failed_tests, totals, task_failures, went_wrong = parse_gradle_log(sample_log)
    assert len(errors) == 1 and errors[0][1] == 12 and errors[0][2] == 5, errors
    assert errors[0][0].endswith("app/src/main/java/com/example/A.kt"), errors
    assert len(warnings) == 1, warnings
    assert failed_tests == ["com.example.SpeedEngineTest > downloadAndUploadAreNeverMirrored"], failed_tests
    assert totals == ["99 tests completed, 1 failed, 2 skipped"], totals
    assert task_failures == [":app:testDebugUnitTest"], task_failures
    assert went_wrong[:2] == ["* What went wrong:", "There were failing tests."], went_wrong

    with tempfile.TemporaryDirectory() as tmp:
        junit = Path(tmp) / "TEST-com.example.SpeedEngineTest.xml"
        junit.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="com.example.SpeedEngineTest" tests="2" failures="1" errors="0" skipped="0">'
            '<testcase name="ok" classname="com.example.SpeedEngineTest"/>'
            '<testcase name="bad" classname="com.example.SpeedEngineTest">'
            '<failure message="expected:&lt;1&gt; but was:&lt;2&gt;">java.lang.AssertionError: expected:&lt;1&gt; but was:&lt;2&gt;\n'
            "\tat org.junit.Assert.fail(Assert.java:89)\n"
            "\tat com.example.SpeedEngineTest.bad(SpeedEngineTest.kt:42)\n</failure>"
            "</testcase></testsuite>",
            encoding="utf-8",
        )
        suites, failures = parse_junit(tmp)
        assert suites == [("com.example.SpeedEngineTest", 2, 1, 0)], suites
        assert len(failures) == 1 and failures[0][1] == "bad", failures
        assert "expected:<1> but was:<2>" in failures[0][2], failures
        assert any("SpeedEngineTest.kt:42" in frame for frame in failures[0][3]), failures

        lint = Path(tmp) / "lint-results-release.xml"
        lint.write_text(
            '<?xml version="1.0" encoding="UTF-8"?><issues format="6">'
            '<issue id="NewApi" severity="Error" message="Call requires API 26">'
            '<location file="/home/runner/work/app/src/main/java/com/example/C.kt" line="7"/></issue>'
            '<issue id="UnusedResources" severity="Warning" message="Unused">'
            '<location file="/home/runner/work/app/src/main/res/values/strings.xml" line="99"/></issue>'
            "</issues>",
            encoding="utf-8",
        )
        issues = parse_lint(str(lint))
        assert [i[0] for i in issues] == ["Error", "Warning"], issues
        assert issues[0][1] == "NewApi" and issues[0][4] == "7", issues

    print("publish_check self-test: OK")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true",
                        help="verify the log/JUnit/lint parsers against embedded samples and exit")
    parser.add_argument("--name", default="")
    parser.add_argument("--log", default="")
    parser.add_argument("--results", default="")
    parser.add_argument("--lint", default="")
    parser.add_argument("--outcome", default="failure")
    parser.add_argument("--neutral", action="store_true",
                        help="report 'neutral' instead of 'failure' (informational jobs)")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.name or not args.log:
        parser.error("--name and --log are required (or use --self-test)")

    title, summary, text, errors, failures = build_report(args)
    annotate(errors, failures)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        try:
            with open(step_summary, "a", encoding="utf-8") as fh:
                fh.write(text + "\n")
        except OSError:
            pass

    if args.outcome == "success":
        conclusion = "success"
    else:
        conclusion = "neutral" if args.neutral else "failure"
    post_check_run(args.name, title, summary, text, conclusion)
    return 0


if __name__ == "__main__":
    sys.exit(main())
