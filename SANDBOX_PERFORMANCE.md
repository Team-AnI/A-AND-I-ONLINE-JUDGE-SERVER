# SANDBOX PERFORMANCE

각 태그/작업트리별 샌드박스 성능 측정 결과를 기록합니다.

## 측정 방법

- 스크립트: `python3 scripts/perf/benchmark_sandboxes.py --git-ref <tag> ...`
- 측정 단위: 각 언어 샌드박스 Docker `run`의 end-to-end wall time + 러너 내부 `timeMs`
- 부하 케이스: `baseline_add`, `sort_rounds_medium`, `sort_rounds_heavy`
- 해석 포인트: `avg wall ms`는 사용자가 체감하는 채점 소요시간, `avg overhead ms`는 러너/컴파일/컨테이너 오버헤드에 가깝습니다.

## Comparison: v2.0.15 -> working-tree

| language | case | baseline avg wall ms | latest avg wall ms | delta ms | improvement % |
| --- | --- | ---: | ---: | ---: | ---: |
| python | baseline_add | 317.35 | 373.35 | +56.00 | -17.64% |
| python | sort_rounds_medium | 279.14 | 313.85 | +34.71 | -12.44% |
| python | sort_rounds_heavy | 376.99 | 433.95 | +56.96 | -15.11% |
| kotlin | baseline_add | 2349.44 | 2515.70 | +166.26 | -7.08% |
| kotlin | sort_rounds_medium | 2408.28 | 2562.06 | +153.78 | -6.39% |
| kotlin | sort_rounds_heavy | 2385.10 | 2558.61 | +173.51 | -7.27% |
| dart | baseline_add | 475.77 | 523.06 | +47.29 | -9.94% |
| dart | sort_rounds_medium | 449.61 | 520.71 | +71.10 | -15.81% |
| dart | sort_rounds_heavy | 468.66 | 510.37 | +41.71 | -8.90% |

## v2.0.15

- 측정 시각(UTC): `2026-04-18T13:43:59.795645+00:00`
- iterations: `3` / warmups: `1`

### python

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/러너 오버헤드 중심의 최소 연산 | 317.35 | 323.16 | 117.19 | 200.16 | 5.10 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 279.14 | 284.98 | 60.53 | 218.61 | 0.30 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 376.99 | 380.22 | 161.86 | 215.13 | 0.60 |

### kotlin

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/컴파일 오버헤드 중심의 최소 연산 | 2349.44 | 2393.80 | 23.58 | 2325.85 | 0.00 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 2408.28 | 2501.55 | 15.51 | 2392.77 | 0.00 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 2385.10 | 2416.22 | 23.19 | 2361.92 | 0.00 |

### dart

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/JIT snapshot 오버헤드 중심의 최소 연산 | 475.77 | 506.55 | 30.58 | 445.19 | 0.00 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 449.61 | 483.08 | 16.57 | 433.04 | 0.00 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 468.66 | 480.69 | 34.56 | 434.10 | 0.00 |

## working-tree

- 측정 시각(UTC): `2026-04-18T13:44:41.345600+00:00`
- iterations: `3` / warmups: `1`

### python

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/러너 오버헤드 중심의 최소 연산 | 373.35 | 381.56 | 109.85 | 263.50 | 5.10 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 313.85 | 327.14 | 60.59 | 253.26 | 0.30 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 433.95 | 437.89 | 166.86 | 267.09 | 0.60 |

### kotlin

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/컴파일 오버헤드 중심의 최소 연산 | 2515.70 | 2583.42 | 20.28 | 2495.42 | 6.75 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 2562.06 | 2612.17 | 12.77 | 2549.29 | 5.16 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 2558.61 | 2634.77 | 21.98 | 2536.63 | 8.00 |

### dart

| case | description | avg wall ms | p95 wall ms | avg runner ms | avg overhead ms | avg memory mb |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| baseline_add | 부팅/JIT snapshot 오버헤드 중심의 최소 연산 | 523.06 | 523.73 | 31.41 | 491.64 | 28.54 |
| sort_rounds_medium | 정렬 + 리스트 생성 중심의 중간 부하 | 520.71 | 542.34 | 19.03 | 501.69 | 30.05 |
| sort_rounds_heavy | 정렬 반복 중심의 높은 CPU 부하 | 510.37 | 523.24 | 34.95 | 475.42 | 30.99 |
