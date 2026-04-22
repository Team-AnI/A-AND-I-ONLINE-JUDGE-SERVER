#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import statistics
import subprocess
import sys
import tarfile
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT = ROOT / "SANDBOX_PERFORMANCE.md"


@dataclass(frozen=True)
class SandboxCase:
    name: str
    description: str
    args: list[Any]


@dataclass(frozen=True)
class SandboxLanguage:
    key: str
    context_dir: str
    image_base: str
    code: str
    cases: list[SandboxCase]


LANGUAGES: list[SandboxLanguage] = [
    SandboxLanguage(
        key="python",
        context_dir="docker/sandbox/python",
        image_base="judge-sandbox-python",
        code="""
def solution(n, rounds):
    total = 0
    for r in range(rounds):
        values = [((n - i) * (r + 3)) % 10007 for i in range(n)]
        values.sort()
        total = (total + values[n // 2]) % 1000003
    return total
""".strip(),
        cases=[
            SandboxCase("baseline_add", "부팅/러너 오버헤드 중심의 최소 연산", [123456, 1]),
            SandboxCase("sort_rounds_medium", "정렬 + 리스트 생성 중심의 중간 부하", [4000, 18]),
            SandboxCase("sort_rounds_heavy", "정렬 반복 중심의 높은 CPU 부하", [8000, 24]),
        ],
    ),
    SandboxLanguage(
        key="kotlin",
        context_dir="docker/sandbox/kotlin",
        image_base="judge-sandbox-kotlin",
        code="""
fun solution(n: Int, rounds: Int): Int {
    var total = 0
    repeat(rounds) { r ->
        val values = MutableList(n) { i -> (((n - i) * (r + 3)) % 10007) }
        values.sort()
        total = (total + values[n / 2]) % 1_000_003
    }
    return total
}
""".strip(),
        cases=[
            SandboxCase("baseline_add", "부팅/컴파일 오버헤드 중심의 최소 연산", [123456, 1]),
            SandboxCase("sort_rounds_medium", "정렬 + 리스트 생성 중심의 중간 부하", [4000, 18]),
            SandboxCase("sort_rounds_heavy", "정렬 반복 중심의 높은 CPU 부하", [8000, 24]),
        ],
    ),
    SandboxLanguage(
        key="dart",
        context_dir="docker/sandbox/dart",
        image_base="judge-sandbox-dart",
        code="""
int solution(int n, int rounds) {
  var total = 0;
  for (var r = 0; r < rounds; r++) {
    final values = List<int>.generate(n, (i) => (((n - i) * (r + 3)) % 10007));
    values.sort();
    total = (total + values[n ~/ 2]) % 1000003;
  }
  return total;
}
""".strip(),
        cases=[
            SandboxCase("baseline_add", "부팅/JIT snapshot 오버헤드 중심의 최소 연산", [123456, 1]),
            SandboxCase("sort_rounds_medium", "정렬 + 리스트 생성 중심의 중간 부하", [4000, 18]),
            SandboxCase("sort_rounds_heavy", "정렬 반복 중심의 높은 CPU 부하", [8000, 24]),
        ],
    ),
]


def run(cmd: list[str], *, cwd: Path | None = None, stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise SystemExit(f"required tool not found: {name}")


def git_label(ref: str | None, source_dir: Path) -> str:
    if ref and ref.upper() != "WORKTREE":
        return ref
    describe = run(["git", "describe", "--tags", "--always", "--dirty"], cwd=source_dir)
    if describe.returncode == 0:
        return describe.stdout.decode().strip()
    return "working-tree"


def sanitized_token(value: str) -> str:
    token = re.sub(r"[^a-zA-Z0-9_.-]+", "-", value).strip("-").lower()
    return token or "unknown"


def extract_git_ref(repo_root: Path, git_ref: str) -> Path:
    temp_dir = Path(tempfile.mkdtemp(prefix=f"sandbox-bench-{sanitized_token(git_ref)}-"))
    archive = run(["git", "archive", "--format=tar", git_ref], cwd=repo_root)
    if archive.returncode != 0:
        raise RuntimeError(archive.stderr.decode().strip() or f"failed to archive ref {git_ref}")
    with tempfile.NamedTemporaryFile(suffix=".tar", delete=False) as handle:
        handle.write(archive.stdout)
        tar_path = Path(handle.name)
    try:
        with tarfile.open(tar_path) as tar:
            tar.extractall(temp_dir)
    finally:
        tar_path.unlink(missing_ok=True)
    return temp_dir


def build_image(source_dir: Path, language: SandboxLanguage, image_tag: str) -> None:
    context_dir = source_dir / language.context_dir
    if not context_dir.exists():
        raise RuntimeError(f"missing sandbox context for {language.key}: {context_dir}")
    result = run(["docker", "build", "-t", image_tag, "."], cwd=context_dir)
    if result.returncode != 0:
        raise RuntimeError(
            f"docker build failed for {language.key}\nstdout:\n{result.stdout.decode()}\nstderr:\n{result.stderr.decode()}"
        )


def run_case(image_tag: str, language: SandboxLanguage, case: SandboxCase) -> dict[str, Any]:
    payload = {
        "code": language.code,
        "args": case.args,
    }
    raw = json.dumps(payload).encode()
    started = time.perf_counter()
    result = run(["docker", "run", "--rm", "-i", "--network", "none", image_tag], stdin=raw)
    wall_ms = (time.perf_counter() - started) * 1000.0
    stdout = result.stdout.decode().strip()
    stderr = result.stderr.decode().strip()
    if result.returncode != 0:
        raise RuntimeError(
            f"docker run failed for {language.key}/{case.name}\nstdout:\n{stdout}\nstderr:\n{stderr}"
        )
    if not stdout:
        raise RuntimeError(f"blank stdout for {language.key}/{case.name}; stderr={stderr}")
    parsed = json.loads(stdout)
    if isinstance(parsed, dict) and isinstance((parsed.get("results") or [None])[0], dict):
        case_result = parsed["results"][0]
    elif isinstance(parsed, dict):
        case_result = parsed
    else:
        raise RuntimeError(f"unexpected runner output for {language.key}/{case.name}: {stdout}")
    status = case_result.get("status") or ("PASSED" if case_result.get("error") in (None, "") else "RUNTIME_ERROR")
    if status != "PASSED":
        raise RuntimeError(f"runner status for {language.key}/{case.name} was {status}: {stdout}")
    runner_ms = float(case_result.get("timeMs") or 0.0)
    memory_mb = float(case_result.get("memoryMb") or 0.0)
    return {
        "wallMs": wall_ms,
        "runnerMs": runner_ms,
        "overheadMs": max(0.0, wall_ms - runner_ms),
        "memoryMb": memory_mb,
    }


def stats(values: list[float]) -> dict[str, float]:
    if not values:
        return {"avg": 0.0, "p50": 0.0, "p95": 0.0, "min": 0.0, "max": 0.0}
    ordered = sorted(values)
    return {
        "avg": statistics.fmean(values),
        "p50": percentile(ordered, 0.50),
        "p95": percentile(ordered, 0.95),
        "min": ordered[0],
        "max": ordered[-1],
    }


def percentile(sorted_values: list[float], q: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    pos = (len(sorted_values) - 1) * q
    lower = math.floor(pos)
    upper = math.ceil(pos)
    if lower == upper:
        return sorted_values[lower]
    weight = pos - lower
    return sorted_values[lower] * (1 - weight) + sorted_values[upper] * weight


def benchmark_source(
    *,
    source_dir: Path,
    label: str,
    iterations: int,
    warmups: int,
    languages: list[SandboxLanguage],
    skip_build: bool,
) -> dict[str, Any]:
    token = sanitized_token(label)
    env_results: dict[str, Any] = {
        "label": label,
        "measuredAt": datetime.now(timezone.utc).isoformat(),
        "iterations": iterations,
        "warmups": warmups,
        "languages": {},
    }

    for language in languages:
        image_tag = f"{language.image_base}:bench-{token}"
        lang_result: dict[str, Any] = {"cases": {}}
        try:
            if skip_build:
                inspect = run(["docker", "image", "inspect", image_tag])
                if inspect.returncode != 0:
                    raise RuntimeError(f"--skip-build was set but image is missing: {image_tag}")
            else:
                build_image(source_dir, language, image_tag)
            for case in language.cases:
                try:
                    for _ in range(warmups):
                        run_case(image_tag, language, case)
                    samples = [run_case(image_tag, language, case) for _ in range(iterations)]
                    lang_result["cases"][case.name] = {
                        "description": case.description,
                        "args": case.args,
                        "samples": samples,
                        "wallMs": stats([sample["wallMs"] for sample in samples]),
                        "runnerMs": stats([sample["runnerMs"] for sample in samples]),
                        "overheadMs": stats([sample["overheadMs"] for sample in samples]),
                        "memoryMb": stats([sample["memoryMb"] for sample in samples]),
                    }
                except Exception as exc:  # noqa: BLE001
                    lang_result["cases"][case.name] = {
                        "description": case.description,
                        "args": case.args,
                        "error": str(exc),
                    }
        except Exception as exc:  # noqa: BLE001
            lang_result["error"] = str(exc)
        env_results["languages"][language.key] = lang_result
    return env_results


def render_report_block(results: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    if len(results) > 1:
        lines.extend(render_comparison(results))
        lines.append("")
    for result in results:
        label = result["label"]
        lines.append(f"## {label}")
        lines.append("")
        lines.append(f"- 측정 시각(UTC): `{result['measuredAt']}`")
        lines.append(f"- iterations: `{result['iterations']}` / warmups: `{result['warmups']}`")
        lines.append("")
        for language_key, language_result in result["languages"].items():
            lines.append(f"### {language_key}")
            lines.append("")
            lines.append("| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |")
            lines.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
            if language_result.get("error"):
                lines.append(f"| *(language setup failed)* | {language_result['error']} | - | - | - | - | - |")
            for case_name, case_result in language_result["cases"].items():
                if case_result.get("error"):
                    lines.append(
                        f"| {case_name} | {case_result['description']} | FAIL | FAIL | FAIL | FAIL | FAIL |"
                    )
                    lines.append(f"| ↳ error | `{case_result['error']}` |  |  |  |  |  |")
                    continue
                lines.append(
                    "| {case} | {desc} | {wall_avg:.2f} | {wall_p95:.2f} | {runner_avg:.2f} | {overhead_avg:.2f} | {memory_avg:.2f} |".format(
                        case=case_name,
                        desc=case_result["description"],
                        wall_avg=case_result["wallMs"]["avg"],
                        wall_p95=case_result["wallMs"]["p95"],
                        runner_avg=case_result["runnerMs"]["avg"],
                        overhead_avg=case_result["overheadMs"]["avg"],
                        memory_avg=case_result["memoryMb"]["avg"],
                    )
                )
            lines.append("")
    return "\n".join(lines).strip() + "\n"


def render_comparison(results: list[dict[str, Any]]) -> list[str]:
    baseline = results[0]
    latest = results[-1]
    lines = [
        f"## Comparison: {baseline['label']} -> {latest['label']}",
        "",
        "| language | case | baseline avg wall ms | latest avg wall ms | delta ms | improvement % |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for language_key in baseline["languages"].keys():
        base_cases = baseline["languages"][language_key]["cases"]
        latest_cases = latest["languages"].get(language_key, {}).get("cases", {})
        for case_name in base_cases.keys():
            if case_name not in latest_cases:
                continue
            if base_cases[case_name].get("error") or latest_cases[case_name].get("error"):
                continue
            base_avg = base_cases[case_name]["wallMs"]["avg"]
            latest_avg = latest_cases[case_name]["wallMs"]["avg"]
            delta = latest_avg - base_avg
            improvement = ((base_avg - latest_avg) / base_avg * 100.0) if base_avg else 0.0
            lines.append(
                f"| {language_key} | {case_name} | {base_avg:.2f} | {latest_avg:.2f} | {delta:+.2f} | {improvement:+.2f}% |"
            )
    return lines


def ensure_header(md_path: Path) -> None:
    if md_path.exists():
        return
    md_path.write_text(
        "\n".join(
            [
                "# SANDBOX PERFORMANCE",
                "",
                "각 태그/작업트리별 샌드박스 성능 측정 결과를 기록합니다.",
                "",
                "## 측정 방법",
                "",
                "- 스크립트: `python3 scripts/perf/benchmark_sandboxes.py --git-ref <tag> ...`",
                "- 측정 단위: 각 언어 샌드박스 Docker `run`의 end-to-end wall time + 러너 내부 `timeMs`",
                "- 부하 케이스: `baseline_add`, `sort_rounds_medium`, `sort_rounds_heavy`",
                "- 해석 포인트: `avg wall ms`는 사용자가 체감하는 채점 소요시간, `avg overhead ms`는 러너/컴파일/컨테이너 오버헤드에 가깝습니다.",
                "",
            ]
        )
        + "\n"
    )


def upsert_section(md_path: Path, section_markdown: str, heading: str) -> None:
    ensure_header(md_path)
    content = md_path.read_text()
    pattern = re.compile(rf"(?ms)^## {re.escape(heading)}\n.*?(?=^## |\Z)")
    replacement = section_markdown.strip() + "\n\n"
    if pattern.search(content):
        content = pattern.sub(replacement, content)
    else:
        if not content.endswith("\n"):
            content += "\n"
        content += replacement
    md_path.write_text(content)


def append_block(md_path: Path, block: str) -> None:
    ensure_header(md_path)
    content = md_path.read_text()
    if not content.endswith("\n"):
        content += "\n"
    content += block if block.endswith("\n") else block + "\n"
    md_path.write_text(content)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark sandbox Docker runners across worktree/tag refs.")
    parser.add_argument("--git-ref", action="append", dest="git_refs", help="git tag/ref to benchmark. repeatable.")
    parser.add_argument("--label", action="append", dest="labels", help="custom label matching --git-ref order.")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--report", default=str(DEFAULT_REPORT))
    parser.add_argument("--json-out", help="optional path to write raw JSON results")
    parser.add_argument("--replace", action="store_true", help="replace per-label sections instead of appending a combined block")
    parser.add_argument("--skip-build", action="store_true", help="reuse existing bench images if already built")
    parser.add_argument("--languages", nargs="*", choices=[lang.key for lang in LANGUAGES], help="subset of languages to benchmark")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    require_tool("python3")
    require_tool("git")
    require_tool("docker")

    selected_languages = [lang for lang in LANGUAGES if not args.languages or lang.key in args.languages]
    git_refs = args.git_refs or [None]
    labels = args.labels or []
    if labels and len(labels) != len(git_refs):
        raise SystemExit("--label count must match --git-ref count")

    temp_dirs: list[Path] = []
    results: list[dict[str, Any]] = []
    try:
        for index, git_ref in enumerate(git_refs):
            if git_ref is None or str(git_ref).upper() == "WORKTREE":
                source_dir = ROOT
            else:
                source_dir = extract_git_ref(ROOT, git_ref)
                temp_dirs.append(source_dir)
            label = labels[index] if index < len(labels) else git_label(git_ref, source_dir)
            result = benchmark_source(
                source_dir=source_dir,
                label=label,
                iterations=args.iterations,
                warmups=args.warmups,
                languages=selected_languages,
                skip_build=args.skip_build,
            )
            results.append(result)

        report_path = Path(args.report).resolve()
        block = render_report_block(results)
        if args.replace:
            if len(results) == 1:
                upsert_section(report_path, block, results[0]["label"])
            else:
                append_block(report_path, block)
        else:
            append_block(report_path, block)

        if args.json_out:
            Path(args.json_out).write_text(json.dumps(results, indent=2, ensure_ascii=False))

        print(block, end="")
        print(f"\n[ok] report updated: {report_path}")
        return 0
    finally:
        for temp_dir in temp_dirs:
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
