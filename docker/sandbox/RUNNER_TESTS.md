# Sandbox Runner Tests

각 언어 러너의 단위 테스트 목록 및 검증 범위.
Docker 없이 실행 가능하며 CI/CD 파이프라인에서 러너 로직을 사전 검증하는 데 사용한다.

## 실행 방법

```bash
# Kotlin
/path/to/gradlew -p docker/sandbox/kotlin test

# Dart
cd docker/sandbox/dart && dart test

# Python
cd docker/sandbox/python && python3 -m unittest test_runner -v
```

---

## Kotlin (`kotlin/src/test/kotlin/KotlinRunnerTest.kt`)

**총 30개 테스트**

### `buildArgsLiteral` — args → Kotlin 리터럴 변환

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 | int, float, bool, null, string, string(특수문자) |
| 리스트 | int 리스트, float 리스트, bool 리스트, null 포함 리스트, 혼합 타입 리스트, 중첩 리스트, 빈 리스트, 리스트+스칼라 혼합 인자 |
| 맵 | int 값 맵, string 값 맵, 중첩 리스트 값 맵, 맵+스칼라 혼합 인자 |

### `judgeToJsonLiteral` — 반환값 → JSON 직렬화

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 | int, float, true, false, null, string, string(특수문자 `\n`) |
| 리스트 | int 리스트, 중첩 리스트, null 포함 리스트 |
| 맵 | int 값 맵, 리스트 값 맵 |

---

## Dart (`dart/test/runner_test.dart`)

**총 29개 테스트**

### `toArgsLiteral` — args → Dart 리터럴 변환

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 | int, float, bool, null, string, string(특수문자 `'`) |
| 리스트 | int 리스트, float 리스트, bool 리스트, null 포함 리스트, 혼합 타입 리스트, 중첩 리스트, 빈 리스트, 리스트+스칼라 혼합 인자 |
| 맵 | int 값 맵, string 값 맵, 중첩 리스트 값 맵, 맵+스칼라 혼합 인자 |

### `judgeNormalizeOutput` — 반환값 정규화

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 | int, float, true, false, null, string |
| 리스트 | int 리스트, 중첩 리스트, null 포함 리스트 |
| 맵 | int 값 맵, 리스트 값 맵 |

---

## Python (`python/test_runner.py`)

**총 36개 테스트**

### `normalize_json_value` — 반환값 정규화

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 | int, float, true, false, null, string |
| 리스트 | int 리스트, float 리스트, bool 리스트, null 포함 리스트, 혼합 타입 리스트, 중첩 리스트 |
| 딕셔너리 | int 값, string 값, 리스트 값, 중첩 딕셔너리 |

### `execute_solution` — 실행 및 반환값 검증

| 카테고리 | 케이스 |
|----------|--------|
| 스칼라 arg | int, float, bool, null, string |
| 리스트 arg | int 리스트, float 리스트, bool 리스트, null 포함 리스트, 중첩 리스트, 빈 리스트, 리스트+스칼라 혼합 인자 |
| 딕셔너리 arg | int 값 딕셔너리, string 값 딕셔너리, 중첩 리스트 값 딕셔너리, 딕셔너리+스칼라 혼합 인자 |
| 반환 타입 | None, string, 리스트, 딕셔너리 |

---

## 언어별 테스트 구조 차이

| 항목 | Python | Dart | Kotlin |
|------|--------|------|--------|
| Args 변환 | 불필요 (JSON 파싱 후 직접 전달) | `toArgsLiteral` 테스트 | `buildArgsLiteral` 테스트 |
| 반환값 | `normalize_json_value` (Python 객체 반환) | `judgeNormalizeOutput` (객체 반환) | `judgeToJsonLiteral` (JSON 문자열 반환) |
| 실행 검증 | `execute_solution` 통합 테스트 포함 | 없음 (Docker 필요) | 없음 (Docker 필요) |
