#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-judge-sandbox-kotlin:latest}"
CONTAINER_MEMORY_MB="${CONTAINER_MEMORY_MB:-768}"
KOTLIN_COMPILE_XMS_MB="${KOTLIN_COMPILE_XMS_MB:-64}"
KOTLIN_COMPILE_XMX_MB="${KOTLIN_COMPILE_XMX_MB:-512}"
BUILD_IMAGE=0
KEEP_PAYLOAD=0
PAYLOAD_FILE=""
CODE_FILE=""
CASES_FILE=""

usage() {
  cat <<'EOF'
Usage:
  scripts/local/run-kotlin-sandbox.sh [options]

Options:
  --build                 Build judge-sandbox-kotlin image before running
  --payload-file <file>   Full runner payload JSON file
  --code-file <file>      Kotlin source file containing fun solution(...)
  --cases-file <file>     JSON array file for "cases" (required with --code-file)
  --image <name>          Docker image name (default: judge-sandbox-kotlin:latest)
  --memory-mb <mb>        Docker container memory limit in MB (default: 768)
  --xms-mb <mb>           Kotlin compiler JVM Xms in MB (default: 64)
  --xmx-mb <mb>           Kotlin compiler JVM Xmx in MB (default: 512)
  --keep-payload          Keep generated temporary payload file
  -h, --help              Show this help

Examples:
  # 1) Full payload JSON already prepared
  scripts/local/run-kotlin-sandbox.sh --build --payload-file /tmp/payload.json

  # 2) Build payload from code + cases JSON
  scripts/local/run-kotlin-sandbox.sh \
    --code-file /tmp/solution.kt \
    --cases-file /tmp/cases.json

cases.json example:
[
  {
    "caseId": 1,
    "args": [2, 60, ["[2024-01-01T00:00:00][진성이][VIP][LOGIN]"]]
  }
]
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)
      BUILD_IMAGE=1
      shift
      ;;
    --payload-file)
      PAYLOAD_FILE="${2:-}"
      shift 2
      ;;
    --code-file)
      CODE_FILE="${2:-}"
      shift 2
      ;;
    --cases-file)
      CASES_FILE="${2:-}"
      shift 2
      ;;
    --image)
      IMAGE_NAME="${2:-}"
      shift 2
      ;;
    --memory-mb)
      CONTAINER_MEMORY_MB="${2:-}"
      shift 2
      ;;
    --xms-mb)
      KOTLIN_COMPILE_XMS_MB="${2:-}"
      shift 2
      ;;
    --xmx-mb)
      KOTLIN_COMPILE_XMX_MB="${2:-}"
      shift 2
      ;;
    --keep-payload)
      KEEP_PAYLOAD=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

require_cmd docker
require_cmd python3

if [[ "$BUILD_IMAGE" -eq 1 ]]; then
  echo "[1/3] Building image: $IMAGE_NAME"
  docker build -t "$IMAGE_NAME" "$ROOT_DIR/docker/sandbox/kotlin"
fi

if ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
  die "docker image not found: $IMAGE_NAME (run with --build first)"
fi

TMP_PAYLOAD=""
if [[ -n "$PAYLOAD_FILE" ]]; then
  [[ -f "$PAYLOAD_FILE" ]] || die "payload file not found: $PAYLOAD_FILE"
elif [[ -n "$CODE_FILE" || -n "$CASES_FILE" ]]; then
  [[ -n "$CODE_FILE" ]] || die "--code-file is required when --cases-file is used"
  [[ -n "$CASES_FILE" ]] || die "--cases-file is required when --code-file is used"
  [[ -f "$CODE_FILE" ]] || die "code file not found: $CODE_FILE"
  [[ -f "$CASES_FILE" ]] || die "cases file not found: $CASES_FILE"

  TMP_PAYLOAD="$(mktemp "${TMPDIR:-/tmp}/kotlin-runner-payload.XXXXXX.json")"
  python3 - "$CODE_FILE" "$CASES_FILE" "$TMP_PAYLOAD" <<'PY'
import json
import pathlib
import sys

code_path = pathlib.Path(sys.argv[1])
cases_path = pathlib.Path(sys.argv[2])
output_path = pathlib.Path(sys.argv[3])

code = code_path.read_text(encoding="utf-8")
cases = json.loads(cases_path.read_text(encoding="utf-8"))

if not isinstance(cases, list):
    raise SystemExit("cases file must be a JSON array")

payload = {
    "code": code,
    "cases": cases,
}

output_path.write_text(
    json.dumps(payload, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
PY
  PAYLOAD_FILE="$TMP_PAYLOAD"
else
  die "provide either --payload-file or both --code-file and --cases-file"
fi

cleanup() {
  if [[ -n "$TMP_PAYLOAD" && "$KEEP_PAYLOAD" -ne 1 ]]; then
    rm -f "$TMP_PAYLOAD"
  fi
}
trap cleanup EXIT

echo "[2/3] Running Kotlin sandbox locally"
echo "       image:              $IMAGE_NAME"
echo "       container memory:   ${CONTAINER_MEMORY_MB} MB"
echo "       compile JVM Xms/Xmx ${KOTLIN_COMPILE_XMS_MB} / ${KOTLIN_COMPILE_XMX_MB} MB"
echo "       payload:            $PAYLOAD_FILE"

echo "[3/3] Sandbox output"
docker run --rm -i \
  --network none \
  --memory "${CONTAINER_MEMORY_MB}m" \
  --read-only \
  --security-opt no-new-privileges \
  --pids-limit 50 \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  -e "KOTLIN_COMPILE_XMS_MB=${KOTLIN_COMPILE_XMS_MB}" \
  -e "KOTLIN_COMPILE_XMX_MB=${KOTLIN_COMPILE_XMX_MB}" \
  "$IMAGE_NAME" < "$PAYLOAD_FILE"
