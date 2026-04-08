import 'package:test/test.dart';
import '../runner.dart';

// ── 반환값 정규화 헬퍼 (runner.dart 생성 코드의 __judgeNormalizeJsonValue 로직과 동일) ──
dynamic judgeNormalizeOutput(dynamic value) {
  if (value == null || value is num || value is bool || value is String) return value;
  if (value is List) return value.map(judgeNormalizeOutput).toList();
  if (value is Map) return {for (final e in value.entries) e.key.toString(): judgeNormalizeOutput(e.value)};
  return value.toString();
}

void main() {
  // ── toArgsLiteral: 스칼라 타입 ──────────────────────────────────────────
  group('toArgsLiteral - scalars', () {
    test('handles scalar int args', () {
      expect(toArgsLiteral([3, 5]), equals('3, 5'));
    });

    test('handles scalar float arg', () {
      expect(toArgsLiteral([1.5]), equals('1.5'));
    });

    test('handles scalar boolean arg', () {
      expect(toArgsLiteral([true]), equals('true'));
    });

    test('handles scalar null arg', () {
      expect(toArgsLiteral([null]), equals('null'));
    });

    test('handles scalar string arg', () {
      expect(toArgsLiteral(['hello']), equals("'hello'"));
    });

    test('handles string with special characters', () {
      expect(toArgsLiteral(["it's"]), equals(r"'it\'s'"));
    });
  });

  // ── toArgsLiteral: 리스트 타입 ──────────────────────────────────────────
  group('toArgsLiteral - lists', () {
    test('converts int list to valid Dart literal', () {
      expect(toArgsLiteral([[1, 2, 3]]), equals('[1, 2, 3]'));
    });

    test('converts float list to valid Dart literal', () {
      expect(toArgsLiteral([[1.5, 2.5]]), equals('[1.5, 2.5]'));
    });

    test('converts bool list to valid Dart literal', () {
      expect(toArgsLiteral([[true, false]]), equals('[true, false]'));
    });

    test('converts list with null to valid Dart literal', () {
      expect(toArgsLiteral([[null, 1]]), equals('[null, 1]'));
    });

    test('converts mixed type list to valid Dart literal', () {
      expect(toArgsLiteral([[1, 'hello', true]]), equals("[1, 'hello', true]"));
    });

    test('converts nested list to valid Dart literal', () {
      expect(toArgsLiteral([[[1, 2], [3, 4]]]), equals('[[1, 2], [3, 4]]'));
    });

    test('handles empty list arg', () {
      expect(toArgsLiteral([[]]), equals('[]'));
    });

    test('handles list and scalar mixed args', () {
      expect(toArgsLiteral([[1, 2, 3], 2]), equals('[1, 2, 3], 2'));
    });
  });

  // ── toArgsLiteral: 맵 타입 ──────────────────────────────────────────────
  group('toArgsLiteral - maps', () {
    test('converts map with int values to valid Dart literal', () {
      expect(toArgsLiteral([{'a': 1, 'b': 2}]), equals("{'a': 1, 'b': 2}"));
    });

    test('converts map with string values to valid Dart literal', () {
      expect(toArgsLiteral([{'key': 'value'}]), equals("{'key': 'value'}"));
    });

    test('converts map with nested list value to valid Dart literal', () {
      expect(toArgsLiteral([{'nums': [1, 2]}]), equals("{'nums': [1, 2]}"));
    });

    test('handles map and scalar mixed args', () {
      expect(toArgsLiteral([{'a': 1}, 42]), equals("{'a': 1}, 42"));
    });
  });

  // ── judgeNormalizeOutput: 반환값 정규화 ─────────────────────────────────
  group('judgeNormalizeOutput - return values', () {
    test('serializes int', () {
      expect(judgeNormalizeOutput(2), equals(2));
    });

    test('serializes float', () {
      expect(judgeNormalizeOutput(2.5), equals(2.5));
    });

    test('serializes true', () {
      expect(judgeNormalizeOutput(true), equals(true));
    });

    test('serializes false', () {
      expect(judgeNormalizeOutput(false), equals(false));
    });

    test('serializes null', () {
      expect(judgeNormalizeOutput(null), isNull);
    });

    test('serializes string', () {
      expect(judgeNormalizeOutput('hello'), equals('hello'));
    });

    test('serializes int list', () {
      expect(judgeNormalizeOutput([1, 2, 3]), equals([1, 2, 3]));
    });

    test('serializes nested list', () {
      expect(judgeNormalizeOutput([[1, 2], [3, 4]]), equals([[1, 2], [3, 4]]));
    });

    test('serializes list with null', () {
      expect(judgeNormalizeOutput([null, 1]), equals([null, 1]));
    });

    test('serializes map', () {
      expect(judgeNormalizeOutput({'a': 1}), equals({'a': 1}));
    });

    test('serializes map with list value', () {
      expect(judgeNormalizeOutput({'nums': [1, 2]}), equals({'nums': [1, 2]}));
    });
  });
}
