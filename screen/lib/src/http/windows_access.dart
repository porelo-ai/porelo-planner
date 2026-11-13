import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// What Windows says about a path's owner and access control list, in the planner's own form, and the planner's rules
/// that keep its files to the account alone.
///
/// An answer is the lines one PowerShell call prints: `self <sid>`, then for each path
/// `path <index> <owner sid> <reparse 0|1> <d|f>` and per entry
/// `ace <index> <A|D> <sid> <rights> <inheritance> <propagation>`, the last two as .NET numbers of their flags. All by
/// SID, so no name in the system's language is compared.
class WindowsAccess {
  WindowsAccess._(this.self, this.paths);

  /// Reads the lines of one call asked about [asked] paths; throws a [FormatException] for any line out of its form,
  /// a second account, or a path missing.
  factory WindowsAccess.parse(String output, int asked) {
    String? self;
    final paths = <WindowsEntry>[];
    for (final line in output.split(RegExp(r'\r?\n'))) {
      if (line.isEmpty) continue;
      final field = line.split(' ');
      switch (field[0]) {
        case 'self':
          if (field.length != 2 || self != null) throw _unreadable(line);
          self = _sid(field[1], line);
        case 'path':
          if (field.length != 5 || _number(field[1], line) != paths.length) throw _unreadable(line);
          paths.add(
            WindowsEntry(
              owner: _sid(field[2], line),
              reparse: _flag(field[3], line),
              directory: _kind(field[4], line),
              aces: [],
            ),
          );
        case 'ace':
          if (field.length != 7 || paths.isEmpty || _number(field[1], line) != paths.length - 1) {
            throw _unreadable(line);
          }
          final allow = switch (field[2]) {
            'A' => true,
            'D' => false,
            _ => throw _unreadable(line),
          };
          // A .NET Int32, so a mask with its top bit set, as GENERIC_READ's, is negative.
          final rights = int.tryParse(field[4]);
          if (rights == null || rights < -0x80000000 || rights > 0x7fffffff || !_integer.hasMatch(field[4])) {
            throw _unreadable(line);
          }
          paths.last.aces.add(
            WindowsAce(
              allow: allow,
              sid: _sid(field[3], line),
              rights: rights & 0xffffffff,
              inheritance: _small(field[5], line),
              propagation: _small(field[6], line),
            ),
          );
        default:
          throw _unreadable(line);
      }
    }
    if (self == null || paths.length != asked) {
      throw FormatException(
        'Windows answered for ${paths.length} of $asked path(s)${self == null ? ', without the account' : ''}',
      );
    }
    return WindowsAccess._(self, List.unmodifiable(paths));
  }

  /// The account the call ran as.
  final String self;

  /// Each path asked about, in the order asked.
  final List<WindowsEntry> paths;

  /// SYSTEM, which can read everything anyway, as root can.
  static const system = 'S-1-5-18';

  /// The local Administrators group, which can read everything anyway, as root can.
  static const administrators = 'S-1-5-32-544';

  /// The owner of the system's own directories.
  static const trustedInstaller = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464';

  static const _delete = 0x10000;
  static const _writeDac = 0x40000;
  static const _writeOwner = 0x80000;
  static const _deleteChild = 0x40;
  static const _genericAll = 0x10000000;
  static const _genericWrite = 0x40000000;
  static const _fullControl = 0x1F01FF;
  static const _containerInherit = 1;
  static const _objectInherit = 2;
  static const _inheritOnly = 2;

  /// Any right that lets another account move a directory away or change who may reach it.
  static const _replaces = _delete | _writeDac | _writeOwner | _deleteChild | _genericAll | _genericWrite;

  /// The reason the path at [index] is not the account's alone, or null when it is: an owner other than the account,
  /// SYSTEM or Administrators, an allow entry for anyone else - one that applies only below included - a reparse
  /// point, or the wrong kind; with [inheriting], also one that does not pass the account's full control on.
  String? notOwnersAlone(String path, int index, {required bool directory, required bool inheriting}) {
    final entry = paths[index];
    if (entry.reparse || entry.directory != directory) {
      return '$path is not a ${directory ? 'directory' : 'file'}, or is a link or junction';
    }
    if (!_trusted(entry.owner)) return '$path belongs to ${entry.owner}, not to this account ($self)';
    for (final ace in entry.aces) {
      if (ace.allow && !_trusted(ace.sid)) return '$path lets ${ace.sid} in, not only this account';
    }
    if (inheriting && !entry.aces.any(_passesOn)) {
      return '$path does not pass this account\'s full control to what is made in it';
    }
    return null;
  }

  /// The reason the directory at [index], on the way to the planner's own, is not safe, or null when it is: a
  /// reparse point, not a directory, owned by neither the account nor the system, or letting another account rename,
  /// delete, re-permission or take it over.
  String? notSafeAncestor(String path, int index) {
    final entry = paths[index];
    if (entry.reparse || !entry.directory) return '$path is not a directory, or is a link or junction';
    if (!_trusted(entry.owner) && entry.owner != trustedInstaller) {
      return '$path belongs to ${entry.owner}, neither this account nor the system';
    }
    for (final ace in entry.aces) {
      if (ace.allow && ace.propagation & _inheritOnly == 0 && !_trusted(ace.sid) && ace.rights & _replaces != 0) {
        return '$path lets ${ace.sid} rename, delete or re-permission what lies below it';
      }
    }
    return null;
  }

  bool _passesOn(WindowsAce ace) =>
      ace.allow &&
      ace.sid == self &&
      ace.rights & _fullControl == _fullControl &&
      ace.inheritance & (_containerInherit | _objectInherit) == (_containerInherit | _objectInherit) &&
      ace.propagation == 0;

  bool _trusted(String sid) => sid == self || sid == system || sid == administrators;

  static final _sidForm = RegExp(r'^S-1-[0-9]+(-[0-9]+){1,14}$');
  static final _integer = RegExp(r'^-?[0-9]+$');
  static final _index = RegExp(r'^[0-9]+$');

  static String _sid(String text, String line) => _sidForm.hasMatch(text) ? text : throw _unreadable(line);

  static int _number(String text, String line) =>
      _index.hasMatch(text) && text.length < 6 ? int.parse(text) : throw _unreadable(line);

  static int _small(String text, String line) {
    final value = _number(text, line);
    return value <= 3 ? value : throw _unreadable(line);
  }

  static bool _flag(String text, String line) => switch (text) {
    '0' => false,
    '1' => true,
    _ => throw _unreadable(line),
  };

  static bool _kind(String text, String line) => switch (text) {
    'd' => true,
    'f' => false,
    _ => throw _unreadable(line),
  };

  static FormatException _unreadable(String line) =>
      FormatException('Windows answered a line out of its form: \'$line\'');

  /// The read half of the planner's own script: the account, then each path of `PORELO_PATHS` in this form. The paths
  /// come through the environment, so none is ever read as code.
  static const script = r'''
$ErrorActionPreference = 'Stop'
$sidType = [System.Security.Principal.SecurityIdentifier]
$self = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
function Lines($text) { @($text -split "`n" | Where-Object { $_ -ne '' }) }
"self $($self.Value)"
$i = 0
foreach ($p in (Lines $env:PORELO_PATHS)) {
  $item = Get-Item -LiteralPath $p -Force
  $reparse = [int](($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
  $kind = if ($item.PSIsContainer) { 'd' } else { 'f' }
  $acl = Get-Acl -LiteralPath $p
  "path $i $($acl.GetOwner($sidType).Value) $reparse $kind"
  foreach ($r in $acl.GetAccessRules($true, $true, $sidType)) {
    $type = if ($r.AccessControlType -eq 'Allow') { 'A' } else { 'D' }
    "ace $i $type $($r.IdentityReference.Value) $([int]$r.FileSystemRights) $([int]$r.InheritanceFlags) $([int]$r.PropagationFlags)"
  }
  $i++
}
''';

  /// Runs [script] for [paths] with Windows PowerShell from [systemRoot]'s own directory, and answers what it printed,
  /// or null when it cannot be run, fails, or does not end within [limit], when it is killed.
  static Future<String?> ask(
    List<String> paths, {
    required String systemRoot,
    Duration limit = const Duration(seconds: 30),
  }) async {
    final program = '$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe';
    final Process process;
    try {
      process = await Process.start(
        program,
        [
          '-NoLogo',
          '-NoProfile',
          '-NonInteractive',
          '-ExecutionPolicy',
          'Bypass',
          '-EncodedCommand',
          base64.encode(_utf16le(script)),
        ],
        environment: {'PORELO_PATHS': paths.map((path) => '$path\n').join()},
      );
    } on ProcessException {
      return null;
    }
    return answer(process, limit: limit);
  }

  /// What [process] printed once it ended with 0, or null when it failed, printed more than [bound] bytes on either
  /// output, or did not end within [limit]; it is killed in each such case, and nothing waits on its outputs past
  /// [limit].
  static Future<String?> answer(Process process, {required Duration limit, int bound = 1 << 20}) async {
    unawaited(process.stdin.close().catchError((Object _) {}));
    final over = Completer<void>();
    // Both outputs drained at once, so a full pipe never holds the program, and counted, so neither fills memory.
    Future<List<int>> collect(Stream<List<int>> stream) async {
      final bytes = <int>[];
      await for (final chunk in stream) {
        if (bytes.length + chunk.length > bound) {
          if (!over.isCompleted) over.complete();
          break;
        }
        bytes.addAll(chunk);
      }
      return bytes;
    }

    final done = (process.exitCode, collect(process.stdout), collect(process.stderr)).wait;
    try {
      final ended = await Future.any([done.then((result) => result), over.future.then((_) => null)]).timeout(limit);
      if (ended == null || over.isCompleted) {
        process.kill(ProcessSignal.sigkill);
        return null;
      }
      final (code, out, _) = ended;
      return code == 0 ? utf8.decode(out) : null;
    } on TimeoutException {
      process.kill(ProcessSignal.sigkill);
      return null;
    } on ParallelWaitError<Object?, Object?> {
      process.kill(ProcessSignal.sigkill);
      return null;
    } on FormatException {
      return null;
    }
  }

  static List<int> _utf16le(String text) => [
    for (final unit in text.codeUnits) ...[unit & 0xff, unit >> 8],
  ];
}

/// One path's owner and access control list.
class WindowsEntry {
  /// Takes what Windows said of the path.
  WindowsEntry({required this.owner, required this.reparse, required this.directory, required this.aces});

  /// The owner's SID.
  final String owner;

  /// Whether the path is a reparse point: a symbolic link, a junction or a mount.
  final bool reparse;

  /// Whether it is a directory.
  final bool directory;

  /// Its entries.
  final List<WindowsAce> aces;
}

/// One entry of an access control list.
class WindowsAce {
  /// Takes the entry's fields as Windows gave them.
  const WindowsAce({
    required this.allow,
    required this.sid,
    required this.rights,
    required this.inheritance,
    required this.propagation,
  });

  /// Whether it allows, not denies.
  final bool allow;

  /// Whom it names.
  final String sid;

  /// Its access mask, as an unsigned 32-bit number.
  final int rights;

  /// Who below inherits it: 1 directories, 2 files.
  final int inheritance;

  /// How: 1 not past the next level, 2 only below, not the path itself.
  final int propagation;
}
