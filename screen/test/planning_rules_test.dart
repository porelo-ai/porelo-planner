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

/// ST1 ranked below its issue PL1, ST2 sound, and PL9 in Backlog in no story; the board names both breaches.
Map<String, dynamic> _board({bool rules = true}) => {
  'states': [
    {'name': 'New', 'kind': 'WAITING'},
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
    {'name': 'Rejected', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['New', 'Backlog', 'Review', 'Finished', 'Rejected'],
    },
  ],
  'stories': [
    {
      'id': 'ST1',
      'title': 'Low story',
      'state': 'Backlog',
      'priority': 'Low',
      'issues': [_issue('PL1', 'Backlog')],
    },
    {
      'id': 'ST2',
      'title': 'Sound story',
      'state': 'Backlog',
      'priority': 'High',
      'issues': [_issue('PL2', 'Backlog')],
    },
  ],
  'issues': [_issue('PL9', 'Backlog')],
  'repositories': ['repo'],
  'revision': 'r1',
  if (rules)
    'rules': [
      {'item': 'ST1', 'rule': 'StoryPriorityBelowIssue', 'words': 'ST1 has a lower priority than its open issue PL1.'},
      {'item': 'PL9', 'rule': 'IssueInNoStory', 'words': "PL9 stands past the workflow's first state in no story."},
    ],
};

Future<(MockPlanningSource, PlanningBoards)> _shown(WidgetTester tester, Map<String, dynamic> board) async {
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
  // The breaches come with the watched whole board, which follows the first read.
  for (var tries = 0; tries < 50 && boards.selected?.rules.isEmpty != false; tries++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    await settle(tester);
  }
  return (backend, boards);
}

void main() {
  // Harm: a story or an issue breaking the stories' rules is never seen in the window.
  testWidgets('the board lists each breach above the columns and marks the cards that hold one', (tester) async {
    await _shown(tester, _board());

    expect(find.text('2 notes'), findsOneWidget, reason: 'two breaches behind one marker');
    await tester.tap(find.byKey(const Key('planning-rules')));
    await settle(tester);
    expect(
      find.text('ST1 has a lower priority than its open issue PL1.'),
      findsOneWidget,
      reason: 'the story breach in the planner’s words',
    );
    expect(
      find.text("PL9 stands past the workflow's first state in no story."),
      findsOneWidget,
      reason: 'the issue breach in the planner’s words',
    );
    final mark = find.byKey(const Key('planning-rule ST1'));
    expect(mark, findsOneWidget, reason: 'the breaking story’s card is marked');
    expect(
      tester.widget<Tooltip>(find.ancestor(of: mark, matching: find.byType(Tooltip)).first).message,
      'ST1 has a lower priority than its open issue PL1.',
      reason: 'the mark says what it breaks',
    );
    expect(find.byKey(const Key('planning-rule ST2')), findsNothing, reason: 'a sound story has no mark');
    expect(
      find.byKey(const Key('planning-no-story-rule Backlog')),
      findsOneWidget,
      reason: 'the card of the issues in no story holding the breaking issue is marked',
    );
  });

  // Harm: the breaches vanish when the story list is read again on an unchanged board, so a breach looks mended.
  testWidgets('a read of the story list at the same revision keeps the breaches the whole board named', (tester) async {
    final (_, boards) = await _shown(tester, _board());
    final board = boards.selected!;

    await tester.runAsync(board.reload);
    await settle(tester);

    expect(board.snapshot?.revision, 'r1', reason: 'the board did not change');
    expect(board.rules.map((breach) => breach.item), ['ST1', 'PL9'], reason: 'both breaches still stand');
    expect(find.byKey(const Key('planning-rule ST1')), findsOneWidget, reason: 'the card stays marked');
  });

  // Harm: a breach held as a reason to refuse or warn, so the person cannot move the story as chosen.
  testWidgets('a story with a breach moves as any other, and the breaches it showed are dropped with the save', (
    tester,
  ) async {
    final (backend, boards) = await _shown(tester, _board());
    final board = boards.selected!;

    await tester.runAsync(() => board.move('ST1', 'Rejected'));
    await settle(tester);

    expect(board.question, isNull, reason: 'no warning was asked about');
    expect(
      (backend.boards['demo']!['stories'] as List).cast<Map<String, dynamic>>().first['state'],
      'Rejected',
      reason: 'the close was saved',
    );
    expect(board.rules, isEmpty, reason: 'no breach from before the save is shown as current');
    expect(find.byKey(const Key('planning-rules')), findsNothing, reason: 'the list is gone until a board names it');
  });

  // Harm: a new screen against an older planner showing marks from nothing, or failing on a missing list.
  test('a board that names no breaches, or malformed ones, shows none', () {
    expect(PlanningSnapshot.from(_board(rules: false)).rules, isEmpty, reason: 'an older planner names none');
    expect(
      PlanningSnapshot.from({
        ..._board(rules: false),
        'rules': [
          'ST1',
          {'item': 'ST1'},
          {
            'item': 'ST2',
            'rule': 'StoryPriorityBelowIssue',
            'words': 'ST2 has a lower priority than its open issue PL1.',
          },
        ],
      }).rules.map((breach) => breach.item),
      ['ST2'],
      reason: 'only a breach with an item and its words is shown',
    );
  });
}
