import json
import math
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def normalize_json_value(value):
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else str(value)
    if isinstance(value, dict):
        return {str(key): normalize_json_value(val) for key, val in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize_json_value(item) for item in value]
    return str(value)


def build_case_result(*, status, output=None, error=None, time_ms=0.0, memory_mb=0.0, case_id=None):
    payload = {
        "status": status,
        "output": output,
        "error": error,
        "timeMs": time_ms,
        "memoryMb": memory_mb,
    }
    if case_id is not None:
        payload["caseId"] = case_id
    return payload


def validate_solution_code(code):
    namespace = {}
    try:
        compiled = compile(code, "<solution>", "exec")
    except SyntaxError as e:
        return None, f"COMPILE_ERROR: {e}"
    except Exception as e:
        return None, f"INTERNAL_ERROR: failed to compile input: {e}"

    try:
        exec(compiled, namespace)
    except Exception as e:
        return None, f"RUNTIME_ERROR: {e}"

    if "solution" not in namespace or not callable(namespace["solution"]):
        return None, "RUNTIME_ERROR: 'solution' function not defined"

    return compiled, None


def execute_solution(namespace, args, case_id=None):
    """
    Measure only the user function call window.
    Excludes parse/compile/bootstrap overhead from memory/time measurement.
    """
    tracemalloc = __import__("tracemalloc")
    tracemalloc.start()
    start_ns = time.perf_counter_ns()
    try:
        result = namespace["solution"](*args)
        output = normalize_json_value(result)
        error = None
        status = "PASSED"
    except Exception as e:
        output = None
        error = f"RUNTIME_ERROR: {e}"
        status = "RUNTIME_ERROR"
    finally:
        elapsed_ms = (time.perf_counter_ns() - start_ns) / 1_000_000.0
        _, peak_bytes = tracemalloc.get_traced_memory()
        tracemalloc.stop()

    return build_case_result(
        case_id=case_id,
        status=status,
        output=output,
        error=error,
        time_ms=elapsed_ms,
        memory_mb=peak_bytes / (1024 * 1024),
    )


def execute_single(code, args):
    namespace = {}
    try:
        compiled = compile(code, "<solution>", "exec")
        exec(compiled, namespace)
    except SyntaxError as e:
        return build_case_result(status="COMPILE_ERROR", error=f"COMPILE_ERROR: {e}")
    except Exception as e:
        return build_case_result(status="RUNTIME_ERROR", error=f"RUNTIME_ERROR: {e}")

    if "solution" not in namespace or not callable(namespace["solution"]):
        return build_case_result(status="RUNTIME_ERROR", error="RUNTIME_ERROR: 'solution' function not defined")

    return execute_solution(namespace, args)


def run_case_subprocess(code_path, args, case_id):
    payload = json.dumps({"codePath": str(code_path), "args": args})
    proc = subprocess.run(
        [sys.executable, __file__, "--child"],
        input=payload,
        capture_output=True,
        text=True,
    )

    stdout = (proc.stdout or "").strip()
    stderr = (proc.stderr or "").strip()
    if not stdout:
        return build_case_result(
            case_id=case_id,
            status="RUNTIME_ERROR",
            output=None,
            error=f"RUNTIME_ERROR: {stderr or 'child produced no output'}",
            time_ms=0.0,
            memory_mb=0.0,
        )

    try:
        result = json.loads(stdout)
    except Exception as e:
        return build_case_result(
            case_id=case_id,
            status="RUNTIME_ERROR",
            output=None,
            error=f"RUNTIME_ERROR: failed to parse child output: {e}",
            time_ms=0.0,
            memory_mb=0.0,
        )

    result["caseId"] = case_id
    result.setdefault("status", "PASSED" if result.get("error") is None else "RUNTIME_ERROR")
    result["timeMs"] = float(result.get("timeMs", 0.0) or 0.0)
    result["memoryMb"] = float(result.get("memoryMb", 0.0) or 0.0)
    return result


def execute_bulk(code, cases):
    _, validation_error = validate_solution_code(code)
    if validation_error is not None:
        return {
            "results": [
                build_case_result(
                    case_id=case.get("caseId"),
                    status="COMPILE_ERROR" if validation_error.startswith("COMPILE_ERROR") else "RUNTIME_ERROR",
                    output=None,
                    error=validation_error,
                    time_ms=0.0,
                    memory_mb=0.0,
                )
                for case in cases
            ]
        }

    with tempfile.TemporaryDirectory(prefix="judge_") as tmp_dir:
        code_path = Path(tmp_dir) / "solution.py"
        code_path.write_text(code, encoding="utf-8")
        results = [
            run_case_subprocess(
                code_path=code_path,
                args=case.get("args", []),
                case_id=case.get("caseId"),
            )
            for case in cases
        ]
    return {"results": results}


def child_main():
    try:
        payload = json.loads(sys.stdin.read())
    except Exception as e:
        print(json.dumps(build_case_result(status="INTERNAL_ERROR", error=f"INTERNAL_ERROR: failed to parse input: {e}")))
        return

    code_path = payload.get("codePath")
    args = payload.get("args", [])
    if not code_path:
        print(json.dumps(build_case_result(status="INTERNAL_ERROR", error="INTERNAL_ERROR: codePath is required")))
        return

    try:
        code = Path(code_path).read_text(encoding="utf-8")
    except Exception as e:
        print(json.dumps(build_case_result(status="INTERNAL_ERROR", error=f"INTERNAL_ERROR: failed to read code: {e}")))
        return

    print(json.dumps(execute_single(code, args)))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--child":
        child_main()
        return

    try:
        payload = json.loads(sys.stdin.read())
    except Exception as e:
        print(json.dumps(build_case_result(status="INTERNAL_ERROR", error=f"INTERNAL_ERROR: failed to parse input: {e}")))
        sys.exit(1)

    code = payload.get("code", "")
    if "cases" in payload:
        cases = payload.get("cases") or []
        print(json.dumps(execute_bulk(code, cases)))
        return

    args = payload.get("args", [])
    print(json.dumps(execute_single(code, args)))


if __name__ == "__main__":
    main()
