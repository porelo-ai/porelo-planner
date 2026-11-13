import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/http/windows_access.dart';

const _self = 'S-1-5-21-1000-2000-3000-1001';
const _other = 'S-1-5-21-1000-2000-3000-1002';
const _system = 'S-1-5-18';
const _admins = 'S-1-5-32-544';
const _users = 'S-1-5-32-545';
const _installer = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464';
const _base = r'C:\Users\someone\AppData\Local';
const _own = '$_base\\porelo-planner';
const _file = '$_own\\planner.json';
const _environment = {'LOCALAPPDATA': _base, 'SystemRoot': r'C:\Windows'};

/// What Windows answers for the lists a planner's account has by default, in the planner's form: the system's own
/// directories open to Users for reading, the profile the account's, the planner's own its alone; [differ] replaces
/// a path's lines.
String _answer(List<String> paths, {Map<String, List<String>> differ = const {}}) {
  List<String> lines(String path) {
    if (path == _file) return ['path {i} $_self 0 f', 'ace {i} A $_self 2032127 0 0'];
    if (path == _own) return ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0'];
    final system = path.length <= r'C:\Users'.length;
    return [
      'path {i} ${path == r'C:\'
          ? _installer
          : system
          ? _system
          : _self} 0 d',
      'ace {i} A $_system 2032127 3 0',
      'ace {i} A $_admins 2032127 3 0',
      if (system) 'ace {i} A $_users 1179817 3 0' else 'ace {i} A $_self 2032127 3 0',
    ];
  }

  return [
    'self $_self',
    for (var index = 0; index < paths.length; index++)
      for (final line in differ[paths[index]] ?? lines(paths[index])) line.replaceAll('{i}', '$index'),
  ].join('\r\n');
}

/// The refusal's name and words, or 'read' when the checks passed: the fake paths do not exist to be read then, which
/// is the one refusal that names the address file's read.
Future<String> _read({
  Map<String, List<String>> differ = const {},
  String? runtimeDirectory,
  Map<String, String> environment = _environment,
  String? Function(List<String> paths)? answer,
  List<List<String>>? asked,
  Future<String?> Function(String path)? realPath,
}) async {
  try {
    await PlannerAddress.read(
      runtimeDirectory: runtimeDirectory,
      windows: true,
      environment: environment,
      access: (paths, systemRoot) async {
        asked?.add([...paths, systemRoot]);
        return answer != null ? answer(paths) : _answer(paths, differ: differ);
      },
      realPath: realPath ?? (path) async => path,
    );
  } on PlanningRefusal catch (refusal) {
    if (refusal.name == 'NoAddress' && refusal.description == 'no planner runs: $_file cannot be read') return 'read';
    return '${refusal.name}: ${refusal.description}';
  }
  return 'read';
}

void main() {
  // Harm: a window that cannot read the planner's own default lists finds no planner on Windows at all.
  test('the planner\'s own lists under LOCALAPPDATA pass, asked from the drive\'s root down to the file', () async {
    final asked = <List<String>>[];
    expect(await _read(asked: asked), 'read', reason: 'the lists the planner itself makes');
    expect(asked.single, [
      r'C:\',
      r'C:\Users',
      r'C:\Users\someone',
      r'C:\Users\someone\AppData',
      _base,
      _own,
      _file,
      r'C:\Windows',
    ], reason: 'every directory on the way, then the planner\'s own, then where PowerShell is taken from');
    expect(
      await _read(environment: {'localappdata': _base, 'SYSTEMROOT': r'C:\Windows'}),
      'read',
      reason: 'Windows reads a variable\'s name in any case',
    );
  });

  // Harm: an address file another account owns, may read or may swap hands the window its token or its planner.
  test('the address file, its directory and the base are refused when anyone else may reach them', () async {
    final refusals = {
      'planner.json of another': {
        _file: ['path {i} $_other 0 f', 'ace {i} A $_self 2032127 0 0'],
      },
      'planner.json others may read': {
        _file: ['path {i} $_self 0 f', 'ace {i} A $_self 2032127 0 0', 'ace {i} A $_users 1179817 0 0'],
      },
      'planner.json a link': {
        _file: ['path {i} $_self 1 f', 'ace {i} A $_self 2032127 0 0'],
      },
      'planner.json a directory': {
        _file: ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 0 0'],
      },
      'porelo-planner a junction': {
        _own: ['path {i} $_self 1 d', 'ace {i} A $_self 2032127 3 0'],
      },
      'porelo-planner with an entry for others that applies only below': {
        _own: ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0', 'ace {i} A $_other 1179817 3 2'],
      },
      'porelo-planner that passes no full control on': {
        _own: ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 0 0'],
      },
      'a base others may write': {
        _base: ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0', 'ace {i} A $_users 278 3 0'],
      },
      'an ancestor others may delete children of': {
        r'C:\Users\someone': ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0', 'ace {i} A $_other 64 0 0'],
      },
      for (final (right, mask) in [
        ('delete', 0x10000),
        ('re-permission', 0x40000),
        ('take over', 0x80000),
        ('do anything with', 0x10000000),
        ('write in the generic form', 0x40000000),
      ])
        'an ancestor others may $right': {
          r'C:\Users\someone': ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0', 'ace {i} A $_other $mask 0 0'],
        },
      'an ancestor of another account': {
        r'C:\Users': ['path {i} $_other 0 d', 'ace {i} A $_system 2032127 3 0'],
      },
      'an ancestor that is a junction': {
        r'C:\Users\someone\AppData': ['path {i} $_self 1 d', 'ace {i} A $_self 2032127 3 0'],
      },
    };
    for (final MapEntry(key: reason, value: differ) in refusals.entries) {
      expect(await _read(differ: differ), startsWith('BadAddress'), reason: reason);
    }
    expect(
      await _read(
        differ: {
          r'C:\Users\someone': ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0', 'ace {i} A $_other 64 3 2'],
        },
      ),
      'read',
      reason: 'an entry of an ancestor that applies only below it changes nothing of the way',
    );
    expect(
      await _read(
        differ: {
          _file: ['path {i} $_admins 0 f', 'ace {i} A $_self 2032127 0 0', 'ace {i} A $_system 2032127 0 0'],
        },
      ),
      'read',
      reason: 'SYSTEM and Administrators can read everything anyway; an elevated planner\'s file is theirs',
    );
  });

  // Harm: a base the window takes from a planted variable lets another account's directory, or a network share,
  // stand where the checks look.
  test('a base that is not a drive\'s plain absolute path is refused before Windows is asked', () async {
    for (final base in [
      r'\\server\share\Local',
      r'\\?\C:\Users\someone\AppData\Local',
      r'\\.\C:\Local',
      r'Users\someone\AppData\Local',
      r'C:Users\someone',
      r'C:\Users\someone\..\other\AppData\Local',
      r'C:\Users\someone\.\AppData\Local',
      r'C:\Users\someone\AppData\Local\',
      r'C:\Users\\someone\AppData\Local',
      'C:/Users/someone/AppData/Local',
      r'C:\Users\someone\AppData\Local:stream',
      'C:\\Users\\some\none',
      r'C:\',
    ]) {
      final asked = <List<String>>[];
      expect(
        await _read(environment: {'LOCALAPPDATA': base, 'SystemRoot': r'C:\Windows'}, asked: asked),
        startsWith('BadAddress'),
        reason: base,
      );
      expect(asked, isEmpty, reason: 'nothing asked for $base');
    }
    expect(
      await _read(environment: {'SystemRoot': r'C:\Windows'}),
      startsWith('NoAddress'),
      reason: 'no LOCALAPPDATA, no place to look',
    );
    expect(
      await _read(environment: {'LOCALAPPDATA': _base, 'SystemRoot': r'\\server\share\Windows'}),
      startsWith('BadAddress'),
      reason: 'PowerShell is never taken from a share',
    );
    expect(
      await _read(environment: {'LOCALAPPDATA': _base}),
      startsWith('BadAddress'),
      reason: 'no SystemRoot, no PowerShell of the system\'s own',
    );
    expect(
      await _read(
        runtimeDirectory: r'D:\Planner',
        environment: {'SystemRoot': r'C:\Windows'},
        answer: (paths) {
          return _answer(
            paths,
            differ: {
              for (final path in [r'D:\Planner', r'D:\Planner\porelo-planner'])
                path: ['path {i} $_self 0 d', 'ace {i} A $_self 2032127 3 0'],
              r'D:\Planner\porelo-planner\planner.json': ['path {i} $_self 0 f', 'ace {i} A $_self 2032127 0 0'],
              r'D:\': ['path {i} $_system 0 d', 'ace {i} A $_system 2032127 3 0'],
            },
          );
        },
      ),
      'NoAddress: no planner runs: D:\\Planner\\porelo-planner\\planner.json cannot be read',
      reason: 'the caller\'s base stands for LOCALAPPDATA, and passes its checks',
    );
  });

  // Harm: an answer read loosely lets a missing or misplaced line pass a path that was never checked.
  test('an answer out of the planner\'s form, or one that misses a path, is refused whole', () async {
    String drop(String text, String line) => text.split('\r\n').where((each) => each != line).join('\r\n');
    final answers = <String, String Function(List<String> paths)>{
      'no account': (paths) => drop(_answer(paths), 'self $_self'),
      'a path missing': (paths) => _answer(paths.take(paths.length - 1).toList()),
      'a second account, the first another': (paths) => 'self $_other\r\n${_answer(paths)}',
      'an entry before any path': (paths) => 'ace 0 A $_self 2032127 3 0\r\n${_answer(paths)}',
      'a path out of order': (paths) => _answer(paths).replaceFirst('path 1 ', 'path 2 '),
      'a name instead of a SID': (paths) => _answer(paths).replaceFirst(_self, 'someone'),
      'rights that are no number': (paths) => _answer(paths).replaceFirst('2032127', '0x1F01FF'),
      'a stray line': (paths) => '${_answer(paths)}\r\nwarning: something',
    };
    for (final MapEntry(key: reason, value: answer) in answers.entries) {
      expect(await _read(answer: answer), startsWith('BadAddress'), reason: reason);
    }
    expect(
      await _read(answer: (paths) => null),
      startsWith('NoAddress'),
      reason: 'a call that fails, as when planner.json is not there, is no planner',
    );
    expect(
      await _read(answer: (paths) => _answer(paths).replaceAll('1179817', '-1610612736')),
      'read',
      reason: 'a mask with its top bit set, as .NET prints GENERIC_READ, is read as the number it is',
    );
  });

  // Harm: a base on a substituted drive or a mapped share has a real place whose parents the checks never saw, which
  // another account may rename.
  test('a base whose real place is elsewhere is refused, and one that cannot be resolved is no planner', () async {
    final asked = <List<String>>[];
    expect(
      await _read(realPath: (path) async => r'D:\Profiles\someone\AppData\Local', asked: asked),
      startsWith('BadAddress'),
      reason: 'a substituted drive resolves elsewhere',
    );
    expect(
      await _read(realPath: (path) async => r'\\server\share\someone\AppData\Local'),
      startsWith('BadAddress'),
      reason: 'a mapped share resolves to the share',
    );
    expect(await _read(realPath: (path) async => null), startsWith('NoAddress'), reason: 'no such base, no planner');
    expect(asked, isEmpty, reason: 'nothing is asked of Windows for a base that is elsewhere');
  });

  // Harm: a PowerShell that hangs or prints without end holds the window's reach for ever or fills its memory.
  test('the call is ended past its time or its output bound, and answers only a clean end', () async {
    Future<String?> run(String command, {int bound = 1 << 20}) async {
      final process = await Process.start('/bin/sh', ['-c', 'exec $command']);
      // So a failing run of this test leaves nothing behind either.
      addTearDown(() => process.kill(ProcessSignal.sigkill));
      final answer = await WindowsAccess.answer(process, limit: const Duration(seconds: 2), bound: bound);
      // A call given up must not be left running.
      expect(
        await process.exitCode.timeout(const Duration(seconds: 2), onTimeout: () => -1000),
        isNot(-1000),
        reason: '$command has ended',
      );
      return answer;
    }

    final watch = Stopwatch()..start();
    expect(await run('sleep 30'), isNull, reason: 'a call that does not end');
    expect(watch.elapsed, lessThan(const Duration(seconds: 10)), reason: 'given up at its limit');
    watch.reset();
    expect(await run('yes', bound: 4096), isNull, reason: 'a call that prints without end');
    expect(await run('yes >&2', bound: 4096), isNull, reason: 'one that writes its errors without end');
    expect(watch.elapsed, lessThan(const Duration(seconds: 4)), reason: 'stopped at its bound, not its limit');
    expect(await run('sh -c "echo out; exit 3"'), isNull, reason: 'a call that fails');
    expect(await run('printf "self S-1-5-18\\n"'), 'self S-1-5-18\n', reason: 'what a clean end printed');
  });
}
