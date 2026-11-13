import 'dart:io';

/// The entered directory cannot be a `--mock` planner's, with the words why.
class MockDirectoryRefused implements Exception {
  /// Takes the words why.
  const MockDirectoryRefused(this.words);

  /// Why the directory is refused.
  final String words;

  @override
  String toString() => words;
}

/// The directory to read a `--mock` planner's address from: [entered] with every link resolved, so the address is
/// read from the very path checked. Refused, as [mockDirectoryRefusal] says, for the path as entered and as resolved,
/// and refused when the path, the account's own runtime directory or [runUser] cannot be resolved: a link anywhere
/// above it, such as `/var/run` to `/run`, would otherwise lead the app to the real planner's address and token.
Future<String> mockDirectory(String entered, {Map<String, String>? environment, String runUser = '/run/user'}) async {
  final env = environment ?? Platform.environment;
  if (mockDirectoryRefusal(entered, environment: env, runUser: runUser) case final refused?) {
    throw MockDirectoryRefused(refused);
  }
  final resolved = await _resolved(entered.trim(), 'The directory');
  final own = env['XDG_RUNTIME_DIR']?.trim() ?? '';
  final ownResolved = own.isEmpty ? null : await _resolved(own, 'This account\'s runtime directory');
  final users = await Directory(runUser).exists() ? await _resolved(runUser, runUser) : runUser;
  if (mockDirectoryRefusal(resolved, environment: {'XDG_RUNTIME_DIR': ?ownResolved}, runUser: users)
      case final refused?) {
    throw MockDirectoryRefused('$entered leads to $resolved. $refused');
  }
  return resolved;
}

Future<String> _resolved(String path, String what) async {
  try {
    return await Directory(path).resolveSymbolicLinks();
  } on FileSystemException {
    throw MockDirectoryRefused('$what $path cannot be resolved, so where it leads is not known.');
  }
}

/// Why [entered] cannot be the runtime directory of a planner started with `--mock`, or null when it can.
///
/// The account's own runtime directory holds the address and token of the person's real planner, which the mock
/// mode refuses to use; reading it here would point the app at the person's projects. So it is refused, as are the
/// directories under [runUser], and a directory that is not absolute.
String? mockDirectoryRefusal(String entered, {Map<String, String>? environment, String runUser = '/run/user'}) {
  final directory = _plain(entered.trim());
  if (!directory.startsWith('/')) return 'Enter the absolute runtime directory the --mock planner was started with.';
  final own = (environment ?? Platform.environment)['XDG_RUNTIME_DIR']?.trim() ?? '';
  if (own.isNotEmpty && _plain(own) == directory) {
    return 'That is this account\'s own runtime directory, where the real planner writes its address.';
  }
  final users = _plain(runUser);
  if (directory == users || directory.startsWith('$users/')) {
    return 'Directories under $users hold the accounts\' real planners; start the mock with a directory of its own.';
  }
  return null;
}

// Without `.` or `..` and without a trailing slash, so one directory written two ways is seen as one.
String _plain(String path) {
  final steps = <String>[];
  for (final step in path.split('/')) {
    if (step.isEmpty || step == '.') continue;
    if (step == '..') {
      if (steps.isNotEmpty) steps.removeLast();
      continue;
    }
    steps.add(step);
  }
  return path.startsWith('/') ? '/${steps.join('/')}' : steps.join('/');
}
