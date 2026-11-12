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

/// ST1 given Review over PL1 in Backlog and PL2 in Review; ST2 in Backlog as its issue gives it.
Map<String, dynamic> _board() => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Review', 'Finished'],
    },
  ],
  'stories': [
    {
      'id': 'ST1',
      'title': 'Given story',
      'state': 'Review',
      'stateGiven': true,
      'misfits': ['PL1'],
      'priority': 'High',
      'issues': [_issue('PL1', 'Backlog'), _issue('PL2', 'Review')],
    },
    {
      'id': 'ST2',
      'title': 'Derived story',
      'state': 'Backlog',
      'stateGiven': false,
      'misfits': <String>[],
      'priority': 'High',
      'issues': [_issue('PL3', 'Backlog')],
    },
  ],
  'revision': 'r1',
};

String _tooltip(WidgetTester tester, String story) =>
    tester.widget<Tooltip>(find.byKey(Key('planning-given $story'))).message!;

void main() {
  // Harm: the person not seeing on the board that a story's state stands against its issues.
  testWidgets('a card marks a given state and names each misfit with its state; a derived one has no mark', (
    tester,
  ) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = MockPlanningSource()..boards['demo'] = _board();
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

    expect(find.byKey(const Key('planning-card ST1')), findsOneWidget, reason: 'the board is shown');
    expect(_tooltip(tester, 'ST1'), contains('PL1 Backlog'), reason: 'the misfit is named with its state');
    expect(_tooltip(tester, 'ST1'), isNot(contains('PL2')), reason: 'an issue that fits is not named');
    expect(find.byKey(const Key('planning-given ST2')), findsNothing, reason: 'a derived state has no mark');
  });

  // Harm: the story page showing a given state as one its issues give.
  testWidgets('the story page marks a given state and names its misfit with its state', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 2000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = MockPlanningSource()..boards['demo'] = _board();
    final board = PlanningBoard(source, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    final detail = PlanningDetail(board, 'ST1', null);
    addTearDown(() async {
      detail.dispose();
      board.dispose();
      await tester.runAsync(source.end);
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: ListenableBuilder(
              listenable: detail,
              builder: (_, _) => PlanningStoryView(
                detail: detail,
                onIssue: (_) {},
                onTask: (_) {},
                taskUnavailable: (_) => null,
                openLink: (_) async {},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);

    expect(_tooltip(tester, 'ST1'), contains('PL1 Backlog'), reason: 'the misfit is named with its state');
  });
}
