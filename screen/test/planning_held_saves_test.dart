import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart' show mockVersionedStories;
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

const _window = '11111111-1111-4111-8111-111111111111';
const _halted = '22222222-2222-4222-8222-222222222222';
const _elsewhere = '33333333-3333-4333-8333-333333333333';

Map<String, dynamic> _command(String save, int at, String target, String state, String reason, String subject) => {
  'id': '$save/$at',
  'kind': 'WRITE',
  'target': target,
  'state': state,
  'reason': reason,
  'subject': subject,
};

/// A window's refused save, a halted run's and one kept from another branch.
List<Map<String, dynamic>> _held() => [
  {
    'id': _window,
    'run': false,
    'earlier': false,
    'resendable': true,
    'commands': [
      _command(_window, 0, 'I1', 'DONE', 'NONE', ''),
      _command(_window, 1, 'I2', 'REFUSED', 'Changed', 'I2'),
    ],
  },
  {
    'id': _halted,
    'run': true,
    'earlier': false,
    'resendable': false,
    'commands': [_command(_halted, 0, 'S1', 'HELD', 'PartlySaved', 'S1')],
  },
  {
    'id': _elsewhere,
    'run': false,
    'earlier': true,
    'resendable': false,
    'repository': 'git@example.org:a/old.git',
    'branch': 'planning',
    'commands': [_command(_elsewhere, 0, 'S2', 'HELD', 'Changed', 'S2')],
  },
];

/// A source whose watch tells the whole board, its held saves among them, as the planner's stream does, and that
/// resends and drops a held save by taking it off the board.
class _Source extends FakePlanningSource {
  _Source(super.board);

  final resent = <String>[];
  final dropped = <String>[];
  final _whole = StreamController<PlanningSnapshot>.broadcast();

  PlanningSnapshot _snapshot() => PlanningSnapshot.from({...board, 'stories': mockVersionedStories(board)});

  void _without(String save, String revision) {
    board = {
      ...board,
      'revision': revision,
      'heldSaves': [
        for (final held in (board['heldSaves'] as List).cast<Map<String, dynamic>>())
          if (held['id'] != save) held,
      ],
    };
    _whole.add(_snapshot());
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    late final StreamController<PlanningSnapshot> replies;
    StreamSubscription<PlanningSnapshot>? changes;
    replies = StreamController<PlanningSnapshot>(
      onListen: () {
        replies.add(_snapshot());
        changes = _whole.stream.listen(replies.add);
      },
      onCancel: () => changes?.cancel(),
    );
    return replies.stream;
  }

  @override
  Future<String> resendSave(String project, String save) async {
    resent.add(save);
    _without(save, 'r2');
    return 'r2';
  }

  @override
  Future<void> dropSave(String project, String save) async {
    dropped.add(save);
    _without(save, board['revision'] as String);
  }

  @override
  Future<void> close() async {
    await _whole.close();
    await super.close();
  }
}

void main() {
  // Harm: the person cannot see a save the planner keeps for them, cannot act on it, or is offered to resend a save
  // the planner never writes here, a halted run's or one kept from another branch.
  testWidgets('held saves show with Resend and Drop', (tester) async {
    final source = _Source({...smallBoard(), 'heldSaves': _held()});
    final client = FakePlanningClient([provider('local', source)]);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await source.close();
    });
    final board = boards.sources.single;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: PlanningHeldSavesView(board: board)),
      ),
    );
    await settle(tester);

    expect(find.text('Held saves · demo'), findsOneWidget, reason: 'the section of Needs you');
    expect(
      tester.widget<Text>(find.byKey(const Key('held-save-command $_window/1'))).data,
      'I2 · refused · Changed: I2',
      reason: 'each target with its state and reason',
    );
    expect(
      tester.widget<Text>(find.byKey(const Key('held-save-command $_window/0'))).data,
      'I1 · saved',
      reason: 'a command written already, without a reason',
    );
    expect(
      find.text('Kept from git@example.org:a/old.git on planning; it is never written here.'),
      findsOneWidget,
      reason: 'where a save from elsewhere was to be written',
    );
    expect(find.byKey(const Key('held-save-resend $_window')), findsOneWidget, reason: 'a window\'s save is resent');
    expect(find.byKey(const Key('held-save-resend $_halted')), findsNothing, reason: 'a halted run\'s is not');
    expect(find.byKey(const Key('held-save-resend $_elsewhere')), findsNothing, reason: 'one from elsewhere is not');
    for (final save in [_window, _halted, _elsewhere]) {
      expect(find.byKey(Key('held-save-drop $save')), findsOneWidget, reason: 'every save can be dropped');
    }

    await tester.tap(find.byKey(const Key('held-save-resend $_window')));
    await settle(tester);
    expect(source.resent, [_window], reason: 'resent once');
    expect(find.byKey(const Key('held-save $_window')), findsNothing, reason: 'gone once the board no longer holds it');

    await tester.tap(find.byKey(const Key('held-save-drop $_halted')));
    await settle(tester);
    expect(source.dropped, [_halted], reason: 'dropped');
    expect(find.byKey(const Key('held-save $_halted')), findsNothing, reason: 'gone once dropped');
    expect(find.byKey(const Key('held-save $_elsewhere')), findsOneWidget, reason: 'the other stays');
  });

  // Harm: two machines hold the same project, and the person resends or drops a save without seeing which machine's
  // planner it reaches.
  testWidgets('held saves of a machine name it in their title', (tester) async {
    final source = _Source({...smallBoard(), 'heldSaves': _held()});
    final client = FakePlanningClient([provider('local', source)]);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await source.close();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlanningHeldSavesView(board: boards.sources.single, machine: 'laptop'),
        ),
      ),
    );
    await settle(tester);

    expect(find.text('Held saves · demo on laptop'), findsOneWidget, reason: 'the machine whose planner acts');
  });

  // Harm: a save kept from another repository or branch is offered for a resend the planner never writes here.
  test('a save kept from elsewhere is never resendable, whatever the board says', () {
    final save = PlanHeldSave.from({
      'id': _elsewhere,
      'run': false,
      'earlier': true,
      'resendable': true,
      'commands': <dynamic>[],
    });

    expect(save.resendable, isFalse, reason: 'only this source\'s saves are resent');
  });
}
