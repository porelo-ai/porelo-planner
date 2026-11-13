import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning_app/src/mock_address.dart';

void main() {
  late Directory temp;
  late Directory runUser;
  late Directory own;

  setUp(() {
    temp = Directory.systemTemp.createTempSync('porelo-app-links');
    runUser = Directory('${temp.path}/run-user')..createSync();
    Directory('${runUser.path}/1000').createSync();
    own = Directory('${temp.path}/own-runtime')..createSync();
    Link('${temp.path}/to-users').createSync(runUser.path);
    Link('${temp.path}/to-own').createSync(own.path);
  });

  tearDown(() => temp.deleteSync(recursive: true));

  // Harm: a link that leads into the accounts' runtime directories lets the app read the real planner's address and
  // token, as /var/run does on a host where it links to /run.
  test('a directory reached through a link to the accounts\' runtime directories is refused', () async {
    await expectLater(
      mockDirectory('${temp.path}/to-users/1000', environment: const {}, runUser: runUser.path),
      throwsA(isA<MockDirectoryRefused>()),
      reason: 'the entered path leads under the stand-in for /run/user',
    );
  });

  // Harm: as above, through a link to the account's own runtime directory.
  test('a directory reached through a link to the account\'s own runtime directory is refused', () async {
    await expectLater(
      mockDirectory('${temp.path}/to-own', environment: {'XDG_RUNTIME_DIR': own.path}, runUser: runUser.path),
      throwsA(isA<MockDirectoryRefused>()),
      reason: 'the entered path leads to the stand-in for XDG_RUNTIME_DIR',
    );
  });

  // Harm: a path that cannot be resolved is checked only as text, and may still lead where it must not.
  test('a directory that cannot be resolved is refused', () async {
    await expectLater(
      mockDirectory('${temp.path}/missing', environment: const {}, runUser: runUser.path),
      throwsA(isA<MockDirectoryRefused>()),
      reason: 'nothing can be said of where it leads',
    );
  });

  // Harm: the app reads the address from a path other than the one checked.
  test('a directory of the mock\'s own is answered resolved', () async {
    final mock = Directory('${temp.path}/mock')..createSync();
    Link('${temp.path}/to-mock').createSync(mock.path);
    expect(
      await mockDirectory('${temp.path}/to-mock', environment: {'XDG_RUNTIME_DIR': own.path}, runUser: runUser.path),
      mock.resolveSymbolicLinksSync(),
      reason: 'the directory read is the one checked',
    );
  });
}
