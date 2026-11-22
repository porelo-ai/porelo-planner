import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, String state) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'blockedBy': <String>[],
  'worker': 'AGENT',
};

/// ST1 over PL1 in Review and PL2 in Backlog, ST2 without issues; [reasons] says whether the store keeps a reason.
Map<String, dynamic> _board({bool reasons = true}) => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
    {'name': 'Rejected', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Review', 'Finished', 'Rejected'],
    },
  ],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A story',
      'state': 'Backlog',
      'stateGiven': false,
      'misfits': <String>[],
      'issues': [_issue('PL1', 'Review'), _issue('PL2', 'Backlog')],
    },
    {
      'id': 'ST2',
      'title': 'An empty story',
      'state': 'Backlog',
      'stateGiven': true,
      'misfits': <String>[],
      'issues': <Map<String, dynamic>>[],
    },
  ],
  'repositories': ['repo'],
  'revision': 'r1',
  'reasons': reasons,
};

Future<(MockPlanningSource, PlanningBoard)> _shown(WidgetTester tester, Map<String, dynamic> board) async {
  tester.view
    ..physicalSize = const Size(1600, 1200)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = MockPlanningSource()..boards['demo'] = board;
  final client = MockPlanningClient(
    () => [
      PlanningProvider(name: 'local', connected: true, projects: ['demo'], source: backend),
    ],
    currentProvider: () => 'local',
  );
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(body: planningView(boards: boards)),
    ),
  );
  await settle(tester);
  return (backend, boards.selected!);
}

Map<String, dynamic> _sent(MockPlanningSource backend) =>
    (backend.moveCalls.last['moves'] as List).cast<Map<String, dynamic>>().single;

void main() {
  // Harm: the person cannot say why an item is closed, or the sentence typed never reaches the planner.
  testWidgets('a move into a closed state asks first and sends the reason typed, trimmed, confirming no warning', (
    tester,
  ) async {
    final (backend, board) = await _shown(tester, _board());

    await tester.runAsync(() => board.moveIssue('PL1', 'Finished'));
    await settle(tester);

    expect(board.question?.asksReason, isTrue, reason: 'asked first, though the preview warned of nothing');
    expect(backend.moveCalls, isEmpty, reason: 'nothing saved before Move');
    await tester.enterText(find.byKey(const Key('planning-move-reason')), '  Built as the story asked.  ');
    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await settle(tester);

    expect(_sent(backend)['reason'], 'Built as the story asked.', reason: 'the reason, trimmed');
    expect(backend.moveCalls.last['confirm'], isFalse, reason: 'no warning was confirmed');
    expect(board.question, isNull, reason: 'saved, not asked about again');
  });

  // Harm: an empty field sent as a blank reason, or a story's close losing the field.
  testWidgets('a reason left empty is not sent, and a story moved to Rejected is asked too', (tester) async {
    final (backend, board) = await _shown(tester, _board());

    await tester.runAsync(() => board.move('ST2', 'Rejected'));
    await settle(tester);
    expect(find.byKey(const Key('planning-move-reason')), findsOneWidget, reason: 'the story is asked as well');
    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await settle(tester);

    expect(_sent(backend).containsKey('reason'), isFalse, reason: 'no reason sent');
    expect(_sent(backend)['story'], 'ST2');
  });

  // Harm: every move held up by a question, or one offered where the store drops the sentence.
  testWidgets('a waiting move, and a closed one on a store that keeps no reason, save at once', (tester) async {
    final (backend, board) = await _shown(tester, _board());

    await tester.runAsync(() => board.moveIssue('PL2', 'Review'));
    await settle(tester);
    expect(board.question, isNull, reason: 'a waiting state asks nothing');
    expect(backend.moveCalls, hasLength(1));

    final (keepsNone, plain) = await _shown(tester, _board(reasons: false));
    await tester.runAsync(() => plain.moveIssue('PL1', 'Finished'));
    await settle(tester);
    expect(plain.question, isNull, reason: 'the store keeps no reason');
    expect(keepsNone.moveCalls, hasLength(1), reason: 'saved at once, as before');
  });

  // Harm: a long reason refused only by the planner, or a short one in another script refused by its encoding.
  testWidgets('Move stands disabled while the reason holds more than 280 characters counted as code points', (
    tester,
  ) async {
    final (_, board) = await _shown(tester, _board());
    await tester.runAsync(() => board.moveIssue('PL1', 'Finished'));
    await settle(tester);

    Finder move() => find.byKey(const Key('planning-move-confirm'));
    await tester.enterText(find.byKey(const Key('planning-move-reason')), '\u{1F600}' * 280);
    await tester.pump();
    expect(tester.widget<FilledButton>(move()).onPressed, isNotNull, reason: '280 characters, 560 code units');
    await tester.enterText(find.byKey(const Key('planning-move-reason')), 'a' * 281);
    await tester.pump();
    expect(tester.widget<FilledButton>(move()).onPressed, isNull, reason: '281 characters');
    expect(find.text('A reason holds at most 280 characters.'), findsOneWidget);
  });

  // Harm: a sentence that hides or turns what a reader sees sent, to be refused only by the planner after Move.
  testWidgets('Move stands disabled while the reason holds a character the planner refuses', (tester) async {
    final (_, board) = await _shown(tester, _board());
    await tester.runAsync(() => board.moveIssue('PL1', 'Finished'));
    await settle(tester);

    Finder move() => find.byKey(const Key('planning-move-confirm'));
    for (final bad in [
      'soft\u{00AD}hyphen',
      'tag\u{E0041}x',
      'private\u{E000}x',
      'braille\u{2800}blank',
      'hangul\u{3164}filler',
      'choseong\u{115F}filler',
      'turn\u{202E}it',
    ]) {
      await tester.enterText(find.byKey(const Key('planning-move-reason')), bad);
      await tester.pump();
      expect(tester.widget<FilledButton>(move()).onPressed, isNull, reason: 'refused: ${bad.runes.toList()}');
    }
    await tester.enterText(find.byKey(const Key('planning-move-reason')), 'Plain words, ä and 中文.');
    await tester.pump();
    expect(tester.widget<FilledButton>(move()).onPressed, isNotNull, reason: 'letters of any script pass');
  });

  // Harm: the sentence typed lost when planning changed under the save and the move is asked about again.
  testWidgets('a move asked about again after planning changed keeps the reason typed', (tester) async {
    final (backend, board) = await _shown(tester, _board());
    await tester.runAsync(() => board.moveIssue('PL1', 'Finished'));
    await settle(tester);
    await tester.enterText(find.byKey(const Key('planning-move-reason')), 'Built.');
    backend.moveFailure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'revision': 'r1'});

    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await settle(tester);
    backend.moveFailure = null;

    expect(board.question?.again, isTrue, reason: 'asked about anew');
    expect(board.question?.reason.value, 'Built.', reason: 'the reason typed stays');
    expect(
      tester.widget<TextField>(find.byKey(const Key('planning-move-reason'))).controller?.text,
      'Built.',
      reason: 'shown in the field again',
    );
  });

  // Harm: the window's tests pass against a stand-in that takes what the planner refuses.
  test('the stand-in refuses a reason as the planner does and takes a blank one as none', () {
    Map<String, dynamic> save(Map<String, dynamic> move) => applyPlanningMove(_board(), {
      'moves': [move],
      'revision': 'r1',
      'confirm': true,
    });
    String refused(Map<String, dynamic> move) {
      try {
        save(move);
      } on PlanningRefusal catch (refusal) {
        expect(refusal.name, 'MoveNotAllowed');
        return refusal.parameters['reason'] as String;
      }
      fail('not refused: $move');
    }

    expect(refused({'issue': 'PL2', 'to': 'Review', 'reason': 'Why.'}), contains('closed state'));
    expect(refused({'issue': 'PL1', 'to': 'Finished', 'reason': 'a' * 281}), contains('at most 280'));
    expect(refused({'issue': 'PL1', 'to': 'Finished', 'reason': 'one\ntwo'}), contains('no control'));
    expect(refused({'issue': 'PL1', 'to': 'Finished', 'reason': 'turn\u202Eit'}), contains('no control'));
    for (final bad in ['soft\u{00AD}x', 'tag\u{E0041}x', 'lone\uD800x', 'braille\u{2800}x']) {
      expect(refused({'issue': 'PL1', 'to': 'Finished', 'reason': bad}), contains('no control'));
    }
    expect(refused({'issue': 'PL1', 'to': 'Review', 'reason': 'Why.'}), contains('closed state'));
    expect(refused({'story': 'ST2', 'to': 'Backlog', 'follow': true, 'reason': 'Why.'}), contains('closes nothing'));
    final closed = save({'issue': 'PL1', 'to': 'Finished'});
    expect(
      () => applyPlanningMove(closed, {
        'moves': [
          {'issue': 'PL1', 'to': 'Finished', 'reason': 'Again.'},
        ],
        'revision': closed['revision'],
        'confirm': true,
      }),
      throwsA(isA<PlanningRefusal>().having((r) => r.parameters['reason'], 'reason', contains('already'))),
    );
    expect(save({'issue': 'PL1', 'to': 'Finished', 'reason': '   '})['moved'], ['PL1'], reason: 'blank is none');
  });

  // Harm: a new screen against an older planner offering a reason its store drops.
  test('a workflow without the flag keeps no reasons, and a story list read beside it keeps the workflow’s', () {
    expect(PlanningSnapshot.from(_board()..remove('reasons')).reasons, isFalse);
    final workflow = PlanningSnapshot.from(_board());
    final stories = PlanningSnapshot.from({'stories': _board()['stories'], 'revision': 'r2'});
    expect(PlanningSnapshot.fromParts(workflow, stories).reasons, isTrue);
  });
}
