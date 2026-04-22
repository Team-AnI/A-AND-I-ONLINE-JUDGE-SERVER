import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

final _importDirectivePattern =
    RegExp(r"""^\s*import\s+(['"])([^'"]+)\1.*;\s*$""");

class UserCodeParts {
  final List<String> imports;
  final String body;
  final String? error;

  const UserCodeParts({
    required this.imports,
    required this.body,
    this.error,
  });
}

String _toLiteral(dynamic a) {
  if (a == null) return 'null';
  if (a is String)
    return "'${a.replaceAll(r'\\', r'\\\\').replaceAll("'", r"\'")}'";
  if (a is List) return '[${a.map(_toLiteral).join(', ')}]';
  if (a is Map) {
    return '{${a.entries.map((e) => "${_toLiteral(e.key.toString())}: ${_toLiteral(e.value)}").join(', ')}}';
  }
  return a.toString();
}

String toArgsLiteral(List<dynamic> args) => args.map(_toLiteral).join(', ');

UserCodeParts splitUserCode(String code) {
  final imports = <String>[];
  final body = <String>[];
  var inDirectiveSection = true;
  var inBlockComment = false;

  for (final line in const LineSplitter().convert(code)) {
    final trimmed = line.trim();

    if (inDirectiveSection) {
      if (inBlockComment) {
        body.add(line);
        if (trimmed.contains('*/')) {
          inBlockComment = false;
        }
        continue;
      }

      if (trimmed.isEmpty || trimmed.startsWith('//')) {
        body.add(line);
        continue;
      }

      if (trimmed.startsWith('/*')) {
        body.add(line);
        if (!trimmed.contains('*/')) {
          inBlockComment = true;
        }
        continue;
      }

      final importMatch = _importDirectivePattern.firstMatch(line);
      if (importMatch != null) {
        final uri = importMatch.group(2)!;
        if (!uri.startsWith('dart:')) {
          return UserCodeParts(
            imports: imports,
            body: code,
            error: 'only Dart SDK standard library imports are allowed: $uri',
          );
        }
        imports.add(line.trim());
        continue;
      }

      if (trimmed.startsWith('library ') ||
          trimmed.startsWith('part ') ||
          trimmed.startsWith('export ')) {
        return UserCodeParts(
          imports: imports,
          body: code,
          error: 'only import directives are supported in the header section',
        );
      }

      inDirectiveSection = false;
    }

    body.add(line);
  }

  return UserCodeParts(imports: imports, body: body.join('\n'));
}

List<String> buildImportDirectives(List<String> userImports) {
  final directives = <String>[];

  void addDirective(String directive) {
    if (!directives.contains(directive)) {
      directives.add(directive);
    }
  }

  for (final userImport in userImports) {
    addDirective(userImport);
  }

  addDirective("import 'dart:async';");
  addDirective("import 'dart:convert';");
  addDirective("import 'dart:io';");
  addDirective("import 'dart:math' as math;");
  return directives;
}

Map<String, dynamic> buildCaseResult({
  required String status,
  Object? output,
  String? error,
  double timeMs = 0.0,
  double memoryMb = 0.0,
  int? caseId,
}) {
  final payload = <String, dynamic>{
    'status': status,
    'output': output,
    'error': error,
    'timeMs': timeMs,
    'memoryMb': memoryMb,
  };
  if (caseId != null) {
    payload['caseId'] = caseId;
  }
  return payload;
}

String buildGeneratedProgram(UserCodeParts userCode) {
  final importBlock =
      '${buildImportDirectives(userCode.imports).join('\n')}\n\n';
  return importBlock +
      "Object? __judgeNormalizeJsonValue(Object? value) {\n" +
      "  if (value == null || value is num || value is bool || value is String) return value;\n" +
      "  if (value is List) return value.map((item) => __judgeNormalizeJsonValue(item)).toList();\n" +
      "  if (value is Map) return { for (final entry in value.entries) entry.key.toString(): __judgeNormalizeJsonValue(entry.value) };\n" +
      "  return value.toString();\n" +
      "}\n\n" +
      userCode.body +
      "\n\n" +
      "void main(List<String> args) {\n" +
      "  if (args.length < 2) {\n" +
      "    return;\n" +
      "  }\n" +
      "  final argsFile = File(args[0]);\n" +
      "  final resultFile = File(args[1]);\n" +
      "  final rawArgs = jsonDecode(argsFile.readAsStringSync()) as List<dynamic>;\n" +
      "  final sw = Stopwatch()..start();\n" +
      "  var peakBytes = ProcessInfo.currentRss;\n" +
      "  Timer? sampler;\n" +
      "  sampler = Timer.periodic(const Duration(milliseconds: 1), (_) {\n" +
      "    peakBytes = math.max(peakBytes, ProcessInfo.currentRss);\n" +
      "  });\n" +
      "  try {\n" +
      "    final result = Function.apply(solution, rawArgs);\n" +
      "    sw.stop();\n" +
      "    sampler.cancel();\n" +
      "    resultFile.writeAsStringSync(jsonEncode({'status': 'PASSED', 'output': __judgeNormalizeJsonValue(result), 'error': null, 'timeMs': sw.elapsedMicroseconds / 1000.0, 'memoryMb': peakBytes / (1024 * 1024)}));\n" +
      "  } catch (e) {\n" +
      "    sw.stop();\n" +
      "    sampler.cancel();\n" +
      "    resultFile.writeAsStringSync(jsonEncode({'status': 'RUNTIME_ERROR', 'output': null, 'error': 'RUNTIME_ERROR: ' + e.toString(), 'timeMs': sw.elapsedMicroseconds / 1000.0, 'memoryMb': peakBytes / (1024 * 1024)}));\n" +
      "  }\n" +
      "}\n";
}

Future<String?> compileSnapshot(String sourcePath, String snapshotPath) async {
  final result = await Process.run(
    Platform.resolvedExecutable,
    ['compile', 'jit-snapshot', '-o', snapshotPath, sourcePath],
  );
  if (result.exitCode == 0) {
    return null;
  }
  final stderr = ((result.stderr as String?) ?? '').trim();
  final stdout = ((result.stdout as String?) ?? '').trim();
  final message = stderr.isNotEmpty ? stderr : stdout;
  return 'COMPILE_ERROR: $message';
}

Future<double> _readRssMb(int pid) async {
  final statusFile = File('/proc/$pid/status');
  if (!await statusFile.exists()) {
    return 0.0;
  }
  final content = await statusFile.readAsString();
  final match =
      RegExp(r'^VmRSS:\s+(\d+)\s+kB', multiLine: true).firstMatch(content);
  if (match == null) {
    return 0.0;
  }
  final kb = int.tryParse(match.group(1) ?? '') ?? 0;
  return kb / 1024.0;
}

Future<double> collectPeakRssMb(int pid, Future<int> exitCodeFuture) async {
  var exited = false;
  exitCodeFuture.whenComplete(() => exited = true);
  var peak = 0.0;
  while (!exited) {
    peak = math.max(peak, await _readRssMb(pid));
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
  peak = math.max(peak, await _readRssMb(pid));
  return peak;
}

Future<Map<String, dynamic>> runCompiledCase({
  required String snapshotPath,
  required List<dynamic> args,
  required int caseId,
}) async {
  final tmpDir = await Directory('/tmp').createTemp('judge_case_');
  try {
    final argsFile = File('${tmpDir.path}/args.json');
    final resultFile = File('${tmpDir.path}/result.json');
    await argsFile.writeAsString(jsonEncode(args));

    final process = await Process.start(
      Platform.resolvedExecutable,
      ['run', snapshotPath, argsFile.path, resultFile.path],
    );
    final exitCodeFuture = process.exitCode;
    final peakFuture = collectPeakRssMb(process.pid, exitCodeFuture);

    await exitCodeFuture;

    final stderr = await process.stderr.transform(utf8.decoder).join();
    final peakMb = await peakFuture;
    if (!resultFile.existsSync()) {
      final message =
          stderr.trim().isNotEmpty ? stderr.trim() : 'child produced no result';
      return buildCaseResult(
        caseId: caseId,
        status: 'RUNTIME_ERROR',
        output: null,
        error: 'RUNTIME_ERROR: $message',
        timeMs: 0.0,
        memoryMb: peakMb,
      );
    }

    final payload =
        jsonDecode(await resultFile.readAsString()) as Map<String, dynamic>;
    payload['caseId'] = caseId;
    payload['memoryMb'] =
        math.max((payload['memoryMb'] as num?)?.toDouble() ?? 0.0, peakMb);
    return payload;
  } finally {
    await tmpDir.delete(recursive: true);
  }
}

Future<Map<String, dynamic>> executeBulk(
    String code, List<dynamic> cases) async {
  final userCode = splitUserCode(code);
  if (userCode.error != null) {
    return {
      'results': cases
          .map((caseItem) => buildCaseResult(
                caseId: (caseItem as Map<String, dynamic>)['caseId'] as int?,
                status: 'COMPILE_ERROR',
                output: null,
                error: 'COMPILE_ERROR: ${userCode.error}',
              ))
          .toList(),
    };
  }

  final tmpDir = await Directory('/tmp').createTemp('judge_batch_');
  try {
    final sourceFile = File('${tmpDir.path}/solution.dart');
    final snapshotFile = File('${tmpDir.path}/solution.jit');
    await sourceFile.writeAsString(buildGeneratedProgram(userCode));

    final compileError =
        await compileSnapshot(sourceFile.path, snapshotFile.path);
    if (compileError != null) {
      return {
        'results': cases
            .map((caseItem) => buildCaseResult(
                  caseId: (caseItem as Map<String, dynamic>)['caseId'] as int?,
                  status: 'COMPILE_ERROR',
                  output: null,
                  error: compileError,
                ))
            .toList(),
      };
    }

    final results = <Map<String, dynamic>>[];
    for (final caseItem in cases.cast<Map<String, dynamic>>()) {
      results.add(
        await runCompiledCase(
          snapshotPath: snapshotFile.path,
          args: (caseItem['args'] as List<dynamic>? ?? const <dynamic>[]),
          caseId: caseItem['caseId'] as int,
        ),
      );
    }
    return {'results': results};
  } finally {
    await tmpDir.delete(recursive: true);
  }
}

Future<void> main() async {
  String raw;
  try {
    raw = await stdin.transform(utf8.decoder).join();
  } catch (e) {
    print(jsonEncode(buildCaseResult(
        status: 'INTERNAL_ERROR',
        error: 'INTERNAL_ERROR: failed to read stdin: $e')));
    exit(1);
  }

  Map<String, dynamic> payload;
  try {
    payload = jsonDecode(raw) as Map<String, dynamic>;
  } catch (e) {
    print(jsonEncode(buildCaseResult(
        status: 'INTERNAL_ERROR',
        error: 'INTERNAL_ERROR: failed to parse input: $e')));
    exit(1);
  }

  final code = payload['code'] as String? ?? '';
  if (payload.containsKey('cases')) {
    final results = await executeBulk(
      code,
      (payload['cases'] as List<dynamic>? ?? const <dynamic>[]),
    );
    print(jsonEncode(results));
    return;
  }

  final singleResult = await executeBulk(
    code,
    [
      {
        'caseId': 1,
        'args': (payload['args'] as List<dynamic>? ?? const <dynamic>[]),
      }
    ],
  );
  final first = (singleResult['results'] as List).first as Map<String, dynamic>;
  first.remove('caseId');
  print(jsonEncode(first));
}
