import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
    'saves': [_window],
    'reason': 'I2 was changed elsewhere since it was read.',
    'changes': [
      {'target': 'I2', 'field': 'state', 'now': 'Ready', 'nowAt': '2026-05-06T07:08:09Z', 'saved': 'Building'},
    ],
    'tries': [
      {'at': '2026-05-06T07:10:00Z', 'result': 'REFUSED', 'reason': 'I2 was changed elsewhere since it was read.'},
    ],
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
    'saves': [_halted],
    'reason': 'Only part of the save may have been written to S1.',
    'changes': [
      {'target': 'S1', 'field': 'state', 'saved': 'Done'},
    ],
    'tries': [
      {
        'at': '2026-05-06T07:10:00Z',
        'result': 'REFUSED',
        'reason': 'Only part of the save may have been written to S1.',
      },
    ],
    'run': true,
    'earlier': false,
    'resendable': false,
    'commands': [_command(_halted, 0, 'S1', 'HELD', 'PartlySaved', 'S1')],
  },
  {
    'id': _elsewhere,
    'saves': [_elsewhere],
    'reason': 'S2 was changed elsewhere since it was read.',
    'changes': [
      {'target': 'S2', 'field': 'state', 'saved': 'Done'},
    ],
    'tries': [
      {'at': '2026-05-06T07:10:00Z', 'result': 'REFUSED', 'reason': 'S2 was changed elsewhere since it was read.'},
    ],
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

  /// The planner's board with [held] as its held saves, as its stream tells a new one.
  void hold(List<Map<String, dynamic>> held) {
    board = {...board, 'heldSaves': held};
    _whole.add(_snapshot());
  }

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
    await tester.tap(find.byKey(const Key('held-save-open $_window')));
    await settle(tester);
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

  // Harm: a save the person opens shows neither what it would change from the value last read, nor GitHub's own answer
  // and request id, nor its tries, so the person cannot judge it or quote it to GitHub; or Copy gives less than shown.
  testWidgets('a held save opens in place with its change, answer, request id and tries, and copies them', (
    tester,
  ) async {
    final copied = _copies(tester);
    final held = {
      ..._held().first,
      'changes': [
        {'target': 'I2', 'field': 'state', 'now': 'Ready', 'nowAt': '2026-05-06T07:08:09Z', 'saved': 'Building'},
        {'target': 'I2', 'field': 'plan', 'saved': 'The plan.'},
      ],
      'answer': {'status': 502, 'text': 'Server Error', 'requestId': 'C0DE:1234'},
      'tries': [
        {'at': '2026-05-06T07:00:00Z', 'result': 'REFUSED', 'reason': 'The server could not be reached.'},
        {
          'at': '2026-05-06T07:10:00Z',
          'result': 'HELD',
          'reason': 'The server could not be reached.',
          'answer': {'status': 502, 'text': 'Server Error', 'requestId': 'C0DE:1234'},
        },
      ],
    };
    final source = await _shown(tester, [held]);

    expect(find.byKey(const Key('held-save-details $_window')), findsNothing, reason: 'closed at first');
    expect(find.text('I2 was changed elsewhere since it was read.'), findsOneWidget, reason: 'why, on the line');
    expect(find.text('2 tries'), findsOneWidget, reason: 'how often, on the line');
    expect(find.byKey(const Key('held-save-resend $_window')), findsOneWidget, reason: 'Resend on the closed line');
    expect(find.byKey(const Key('held-save-drop $_window')), findsOneWidget, reason: 'Drop on the closed line');

    await tester.tap(find.byKey(const Key('held-save-open $_window')));
    await settle(tester);

    final read = tester.widget<Text>(find.byKey(const Key('held-save-change $_window 0'))).data!;
    expect(read, startsWith('I2 state: Ready → Building · last read on GitHub '), reason: 'from the value last read');
    expect(
      tester.widget<Text>(find.byKey(const Key('held-save-change $_window 1'))).data,
      'I2 plan: not read → The plan.',
      reason: 'a value never read names no time',
    );
    expect(
      tester.widget<SelectableText>(find.byKey(const Key('held-save-answer $_window'))).data,
      'HTTP 502 · Server Error',
      reason: 'GitHub\'s answer word for word',
    );
    expect(find.text('Request id C0DE:1234'), findsOneWidget);
    expect(
      tester.widget<SelectableText>(find.byKey(const Key('held-save-try $_window 1'))).data,
      endsWith(' · held · The server could not be reached. · HTTP 502 · Server Error'),
      reason: 'every try',
    );

    await tester.tap(find.byKey(const Key('held-save-copy-request $_window')));
    await tester.tap(find.byKey(const Key('held-save-copy $_window')));
    await settle(tester);
    expect(copied.first, 'C0DE:1234', reason: 'the request id alone');
    expect(
      copied.last.split('\n'),
      containsAll([
        'Held saves · demo',
        'A save made on the board.',
        'Why: I2 was changed elsewhere since it was read.',
        'Saves: $_window',
        'Change: I2 state: Ready → Building (last read on GitHub 2026-05-06T07:08:09.000Z)',
        'Change: I2 plan: not read → The plan.',
        'Answer: HTTP 502 · Server Error',
        'Request id: C0DE:1234',
        'Try: 2026-05-06T07:00:00.000Z · REFUSED · The server could not be reached.',
        'Try: 2026-05-06T07:10:00.000Z · HELD · The server could not be reached. · HTTP 502 · Server Error',
        'Command: I2 · refused · Changed: I2',
      ]),
      reason: 'the whole record',
    );

    await tester.tap(find.byKey(const Key('held-save-open $_window')));
    await settle(tester);
    expect(find.byKey(const Key('held-save-details $_window')), findsNothing, reason: 'closed again');
    expect(source.resent, isEmpty);
  });

  // Harm: a folded line that hides how many saves and tries it holds, resends each save, or closes under the person
  // when a newer save folds in.
  testWidgets('a folded line names its saves and tries, stays open as a save folds in, and resends once', (
    tester,
  ) async {
    const older = '44444444-4444-4444-8444-444444444444';
    const newest = '55555555-5555-4555-8555-555555555555';
    Map<String, dynamic> line(String id, List<String> saves, int tries) => {
      ..._held().first,
      'id': id,
      'saves': saves,
      'tries': [
        for (var at = 0; at < tries; at++)
          {'at': '2026-05-06T07:0$at:00Z', 'result': 'REFUSED', 'reason': 'The server could not be reached.'},
      ],
    };
    final source = await _shown(tester, [
      line(_window, [older, _window], 3),
    ]);

    expect(find.text('2 saves of one change · 3 tries'), findsOneWidget);
    expect(find.text('Resend once'), findsOneWidget);
    expect(find.text('Drop all'), findsOneWidget);
    await tester.tap(find.byKey(const Key('held-save-open $_window')));
    await settle(tester);

    source.hold([
      line(newest, [older, _window, newest], 4),
    ]);
    await settle(tester);
    expect(find.byKey(const Key('held-save-details $newest')), findsOneWidget, reason: 'still open');
    expect(find.text('3 saves of one change · 4 tries'), findsOneWidget);

    await tester.tap(find.byKey(const Key('held-save-resend $newest')));
    await settle(tester);
    expect(source.resent, [newest], reason: 'the newest, once');
  });

  // Harm: a line whose folded saves are not known is dropped or resent, leaving older saves held unseen; or a line
  // tried more often than the planner sends tries for claims an exact count.
  testWidgets('a line with an unreadable list of saves offers no Resend or Drop, and a long one no exact count', (
    tester,
  ) async {
    await _shown(tester, [
      {..._held().first, 'saves': <dynamic>[]},
      {
        ..._held()[1],
        'tries': [
          for (var at = 0; at < 1000; at++) {'at': '2026-05-06T07:00:00Z', 'result': 'REFUSED', 'reason': 'x'},
        ],
      },
    ]);

    expect(find.byKey(const Key('held-save-unreadable $_window')), findsOneWidget, reason: 'it says so');
    expect(find.byKey(const Key('held-save-resend $_window')), findsNothing);
    expect(find.byKey(const Key('held-save-drop $_window')), findsNothing);
    expect(find.text('1000 or more tries'), findsOneWidget);
  });
}

Future<_Source> _shown(WidgetTester tester, List<Map<String, dynamic>> held) async {
  final source = _Source({...smallBoard(), 'heldSaves': held});
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
        body: SingleChildScrollView(child: PlanningHeldSavesView(board: boards.sources.single)),
      ),
    ),
  );
  await settle(tester);
  return source;
}

List<String> _copies(WidgetTester tester) {
  final copied = <String>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == 'Clipboard.setData') copied.add((call.arguments as Map)['text'] as String);
    return null;
  });
  addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
  return copied;
}
