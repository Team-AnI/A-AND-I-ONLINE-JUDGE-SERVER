import sys
import unittest

sys.path.insert(0, ".")
from runner import normalize_json_value, execute_solution


class TestNormalizeJsonValue(unittest.TestCase):
    """반환값 정규화 (normalize_json_value) 전체 타입 검증"""

    def test_none(self):
        self.assertIsNone(normalize_json_value(None))

    def test_int(self):
        self.assertEqual(1, normalize_json_value(1))

    def test_float(self):
        self.assertEqual(1.5, normalize_json_value(1.5))

    def test_bool_true(self):
        self.assertEqual(True, normalize_json_value(True))

    def test_bool_false(self):
        self.assertEqual(False, normalize_json_value(False))

    def test_string(self):
        self.assertEqual("hello", normalize_json_value("hello"))

    def test_int_list(self):
        self.assertEqual([1, 2, 3], normalize_json_value([1, 2, 3]))

    def test_float_list(self):
        self.assertEqual([1.5, 2.5], normalize_json_value([1.5, 2.5]))

    def test_bool_list(self):
        self.assertEqual([True, False], normalize_json_value([True, False]))

    def test_list_with_null(self):
        self.assertEqual([None, 1], normalize_json_value([None, 1]))

    def test_mixed_list(self):
        self.assertEqual([1, "hello", True, None], normalize_json_value([1, "hello", True, None]))

    def test_nested_list(self):
        self.assertEqual([[1, 2], [3, 4]], normalize_json_value([[1, 2], [3, 4]]))

    def test_dict_int_values(self):
        self.assertEqual({"a": 1, "b": 2}, normalize_json_value({"a": 1, "b": 2}))

    def test_dict_string_values(self):
        self.assertEqual({"key": "value"}, normalize_json_value({"key": "value"}))

    def test_dict_with_list_value(self):
        self.assertEqual({"nums": [1, 2]}, normalize_json_value({"nums": [1, 2]}))

    def test_nested_dict(self):
        self.assertEqual({"a": {"b": 1}}, normalize_json_value({"a": {"b": 1}}))


class TestExecuteSolution(unittest.TestCase):
    """execute_solution 전체 arg 타입 및 반환 타입 검증"""

    def _make_namespace(self, code: str):
        ns = {}
        exec(compile(code, "<solution>", "exec"), ns)
        return ns

    # ── 스칼라 arg ──────────────────────────────────────────────────────────

    def test_int_arg(self):
        ns = self._make_namespace("def solution(n): return n * 2")
        result = execute_solution(ns, [5])
        self.assertIsNone(result["error"])
        self.assertEqual(10, result["output"])

    def test_float_arg(self):
        ns = self._make_namespace("def solution(x): return round(x * 2, 1)")
        result = execute_solution(ns, [1.5])
        self.assertIsNone(result["error"])
        self.assertEqual(3.0, result["output"])

    def test_bool_arg(self):
        ns = self._make_namespace("def solution(flag): return not flag")
        result = execute_solution(ns, [True])
        self.assertIsNone(result["error"])
        self.assertEqual(False, result["output"])

    def test_null_arg(self):
        ns = self._make_namespace("def solution(x): return x is None")
        result = execute_solution(ns, [None])
        self.assertIsNone(result["error"])
        self.assertEqual(True, result["output"])

    def test_string_arg(self):
        ns = self._make_namespace("def solution(s): return s.upper()")
        result = execute_solution(ns, ["hello"])
        self.assertIsNone(result["error"])
        self.assertEqual("HELLO", result["output"])

    # ── 리스트 arg ──────────────────────────────────────────────────────────

    def test_int_list_arg(self):
        ns = self._make_namespace("def solution(nums): return sum(nums)")
        result = execute_solution(ns, [[1, 2, 3]])
        self.assertIsNone(result["error"])
        self.assertEqual(6, result["output"])

    def test_float_list_arg(self):
        ns = self._make_namespace("def solution(nums): return sum(nums)")
        result = execute_solution(ns, [[1.5, 2.5]])
        self.assertIsNone(result["error"])
        self.assertEqual(4.0, result["output"])

    def test_bool_list_arg(self):
        ns = self._make_namespace("def solution(flags): return all(flags)")
        result = execute_solution(ns, [[True, True, False]])
        self.assertIsNone(result["error"])
        self.assertEqual(False, result["output"])

    def test_list_with_null_arg(self):
        ns = self._make_namespace(
            "def solution(nums): return [x for x in nums if x is not None]"
        )
        result = execute_solution(ns, [[None, 1, None, 2]])
        self.assertIsNone(result["error"])
        self.assertEqual([1, 2], result["output"])

    def test_nested_list_arg(self):
        ns = self._make_namespace(
            "def solution(matrix): return sum(x for row in matrix for x in row)"
        )
        result = execute_solution(ns, [[[1, 2], [3, 4]]])
        self.assertIsNone(result["error"])
        self.assertEqual(10, result["output"])

    def test_empty_list_arg(self):
        ns = self._make_namespace("def solution(nums): return sum(nums)")
        result = execute_solution(ns, [[]])
        self.assertIsNone(result["error"])
        self.assertEqual(0, result["output"])

    def test_list_and_scalar_mixed_args(self):
        ns = self._make_namespace("def solution(nums, target): return target in nums")
        result = execute_solution(ns, [[1, 2, 3], 2])
        self.assertIsNone(result["error"])
        self.assertEqual(True, result["output"])

    # ── 맵 arg ──────────────────────────────────────────────────────────────

    def test_dict_int_values_arg(self):
        ns = self._make_namespace('def solution(d): return d["a"] + d["b"]')
        result = execute_solution(ns, [{"a": 1, "b": 2}])
        self.assertIsNone(result["error"])
        self.assertEqual(3, result["output"])

    def test_dict_string_values_arg(self):
        ns = self._make_namespace('def solution(d): return d["key"].upper()')
        result = execute_solution(ns, [{"key": "hello"}])
        self.assertIsNone(result["error"])
        self.assertEqual("HELLO", result["output"])

    def test_dict_with_nested_list_arg(self):
        ns = self._make_namespace('def solution(d): return sum(d["nums"])')
        result = execute_solution(ns, [{"nums": [1, 2, 3]}])
        self.assertIsNone(result["error"])
        self.assertEqual(6, result["output"])

    def test_dict_and_scalar_mixed_args(self):
        ns = self._make_namespace('def solution(d, k): return d[k]')
        result = execute_solution(ns, [{"x": 42}, "x"])
        self.assertIsNone(result["error"])
        self.assertEqual(42, result["output"])

    # ── 반환 타입 ────────────────────────────────────────────────────────────

    def test_returns_none(self):
        ns = self._make_namespace("def solution(): return None")
        result = execute_solution(ns, [])
        self.assertIsNone(result["error"])
        self.assertIsNone(result["output"])

    def test_returns_string(self):
        ns = self._make_namespace("def solution(): return 'hello'")
        result = execute_solution(ns, [])
        self.assertIsNone(result["error"])
        self.assertEqual("hello", result["output"])

    def test_returns_list(self):
        ns = self._make_namespace("def solution(): return [1, 2, 3]")
        result = execute_solution(ns, [])
        self.assertIsNone(result["error"])
        self.assertEqual([1, 2, 3], result["output"])

    def test_returns_dict(self):
        ns = self._make_namespace("def solution(): return {'a': 1}")
        result = execute_solution(ns, [])
        self.assertIsNone(result["error"])
        self.assertEqual({"a": 1}, result["output"])


if __name__ == "__main__":
    unittest.main()
