import 'dart:io';
import 'dart:convert';

String _toLiteral(dynamic a) {
  if (a == null) return 'null';
  if (a is String) return "'${a.replaceAll(r'\', r'\\').replaceAll("'", r"\'")}'" ;
  if (a is List) return '[${a.map(_toLiteral).join(', ')}]';
  if (a is Map) return '{${a.entries.map((e) => "'${e.key}': ${_toLiteral(e.value)}").join(', ')}}';
  return a.toString();
}

String toArgsLiteral(List<dynamic> args) => args.map(_toLiteral).join(', ');

void main() async {
  String raw;
  try {
    raw = await stdin.transform(utf8.decoder).join();
  } catch (e) {
    print(jsonEncode({'output': null, 'error': 'INTERNAL_ERROR: failed to read stdin: $e', 'timeMs': 0.0, 'memoryMb': 0.0}));
    exit(1);
  }

  Map<String, dynamic> payload;
  try {
    payload = jsonDecode(raw) as Map<String, dynamic>;
  } catch (e) {
    print(jsonEncode({'output': null, 'error': 'INTERNAL_ERROR: failed to parse input: $e', 'timeMs': 0.0, 'memoryMb': 0.0}));
    exit(1);
  }

  final code = payload['code'] as String? ?? '';
  final args = (payload['args'] as List<dynamic>?) ?? [];

  final tmpDir = await Directory('/tmp').createTemp('judge_');
  final sourceFile = File('${tmpDir.path}/solution.dart');
  final resultFile = File('${tmpDir.path}/result.txt');

  final argsLiteral = toArgsLiteral(args);

  // Escape single quotes in the result file path for embedding in Dart string literal
  final safeResultPath = resultFile.path.replaceAll(r'\', r'\\').replaceAll("'", r"\'");

  // Generate solution.dart: user code + main() that writes result to a file
  // Uses string concatenation to avoid Dart template interpolation conflicts
  await sourceFile.writeAsString(
    "import 'dart:convert';\n" +
    "import 'dart:io';\n\n" +
    "Object? __judgeNormalizeJsonValue(Object? value) {\n" +
    "  if (value == null || value is num || value is bool || value is String) return value;\n" +
    "  if (value is List) return value.map((item) => __judgeNormalizeJsonValue(item)).toList();\n" +
    "  if (value is Map) return { for (final entry in value.entries) entry.key.toString(): __judgeNormalizeJsonValue(entry.value) };\n" +
    "  return value.toString();\n" +
    "}\n\n" +
    code + "\n\n" +
    "void main() {\n" +
    "  final resultFile = File('$safeResultPath');\n" +
    "  final sw = Stopwatch()..start();\n" +
    "  try {\n" +
    "    final result = solution($argsLiteral);\n" +
    "    sw.stop();\n" +
    "    resultFile.writeAsStringSync(jsonEncode({'status': 'OK', 'output': __judgeNormalizeJsonValue(result), 'timeMs': sw.elapsedMicroseconds / 1000.0}));\n" +
    "  } catch (e) {\n" +
    "    sw.stop();\n" +
    "    resultFile.writeAsStringSync(jsonEncode({'status': 'RUNTIME_ERROR', 'error': e.toString(), 'timeMs': sw.elapsedMicroseconds / 1000.0}));\n" +
    "  }\n" +
    "}\n"
  );

  // Run with `dart run` (JIT — no native binary written, safe with noexec tmpfs)
  final result = await Process.run('dart', ['run', sourceFile.path]);

  String? errorMsg;
  Object? outputVal;
  double timeMs = 0.0;

  if (resultFile.existsSync()) {
    final resultPayload = jsonDecode(resultFile.readAsStringSync()) as Map<String, dynamic>;
    final status = resultPayload['status'] as String? ?? 'RUNTIME_ERROR';
    timeMs = (resultPayload['timeMs'] as num?)?.toDouble() ?? 0.0;

    if (status == 'OK') {
      outputVal = resultPayload['output'];
    } else {
      errorMsg = '$status: ${resultPayload['error'] ?? ''}';
    }
  } else {
    // result file not written → compile/syntax error
    final stderr = (result.stderr as String).replaceAll('\n', ' ').trim();
    errorMsg = 'COMPILE_ERROR: $stderr';
  }

  await tmpDir.delete(recursive: true);

  if (errorMsg != null) {
    print(jsonEncode({'output': null, 'error': errorMsg, 'timeMs': timeMs, 'memoryMb': 0.0}));
  } else {
    print(jsonEncode({'output': outputVal, 'error': null, 'timeMs': timeMs, 'memoryMb': 0.0}));
  }
}
