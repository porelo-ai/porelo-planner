import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning_app/src/mock_address.dart';

void main() {
  // Harm: the app reads the real planner's address and token and works on the person's own projects.
  test('the account\'s own runtime directory is refused, however it is written', () {
    const environment = {'XDG_RUNTIME_DIR': '/srv/someone/run'};
    for (final entered in ['/srv/someone/run', '/srv/someone/run/', '/srv/someone/./run', ' /srv/someone/run ']) {
      expect(
        mockDirectoryRefusal(entered, environment: environment),
        isNotNull,
        reason: '"$entered" is the account\'s own runtime directory',
      );
    }
  });

  // Harm: as above, through the directory the planner falls back to when the variable is unset.
  test('a directory under /run/user is refused, set or not', () {
    for (final entered in ['/run/user/1000', '/run/user/1000/sub', '/run/user']) {
      expect(mockDirectoryRefusal(entered, environment: const {}), isNotNull, reason: '"$entered" is an account\'s');
    }
  });

  // Harm: a relative path read against the app's working directory, wherever that is.
  test('a relative directory is refused, and a directory of the mock\'s own is taken', () {
    expect(mockDirectoryRefusal('run', environment: const {}), isNotNull, reason: 'a relative path');
    expect(
      mockDirectoryRefusal('/tmp/porelo-mock', environment: const {'XDG_RUNTIME_DIR': '/run/user/1000'}),
      isNull,
      reason: 'a directory of the mock\'s own',
    );
  });
}
