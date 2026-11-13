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
  'size': 'S',
  'blockedBy': <String>[],
  'link': 'https://example.org/issues/$id',
  'worker': 'MANUAL',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
};

/// One story of three issues, one in Review and one Finished.
Map<String, dynamic> _board() => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Implementing', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Implementing', 'Review', 'Finished'],
    },
  ],
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A story',
      'state': 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'issues': [_issue('PL1', 'Review'), _issue('PL2', 'Backlog'), _issue('PL3', 'Finished')],
    },
  ],
  'revision': 'r1',
  'repositories': ['repo'],
};

Future<void> _show(WidgetTester tester, PlanningBoard board) async {
  tester.view
    ..physicalSize = const Size(1280, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final detail = PlanningDetail(board, 'ST1', null);
  addTearDown(detail.dispose);
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
}

void main() {
  // Harm: a story whose issues a search hides is read as empty, so its issues are filed twice; and a move of a hidden
  // issue is saved unseen.
  testWidgets('a search that matches no issue says so, and the progress still counts all', (tester) async {
    final source = MockPlanningSource()..boards['demo'] = _board();
    final board = PlanningBoard(source, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    addTearDown(() async {
      board.dispose();
      await tester.runAsync(source.end);
    });
    await _show(tester, board);

    await tester.tap(find.byKey(const Key('planning-issue-search-open')));
    await settle(tester);
    await tester.enterText(find.byKey(const Key('planning-issue-search')), 'no such issue');
    await settle(tester);

    expect(find.byKey(const Key('planning-issue PL2')), findsNothing, reason: 'the search hides every row');
    expect(find.text('Nothing matches.'), findsOneWidget, reason: 'the list says the search hides them');
    expect(find.text('No issues yet.'), findsNothing, reason: 'the story is never read as empty');
    expect(find.textContaining(' of 3 in Review or Finished'), findsOneWidget, reason: 'progress counts every issue');

    await tester.tap(find.byKey(const Key('planning-issue-search-clear')));
    await settle(tester);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget.key is ValueKey<String> && (widget.key! as ValueKey<String>).value.startsWith('planning-issue PL'),
      ),
      findsNWidgets(3),
      reason: 'clearing the filter shows every issue again',
    );
  });

  // Harm: the planner refuses a move of an issue the search hides, and the refusal is shown nowhere.
  testWidgets('a refused move of an issue the search hides is still shown', (tester) async {
    final (source, board) = await _open(tester);
    await _showDetail(tester, board);
    await _search(tester, 'no such issue');
    source.moveFailure = mockRefusal('org.fuin.porelo.Tasks1.MoveNotAllowed', {
      'item': 'PL2',
      'from': 'Backlog',
      'to': 'Review',
      'reason': 'The story is frozen.',
    });

    await tester.runAsync(() => board.moveIssue('PL2', 'Review'));
    await settle(tester);

    expect(source.moveCalls, hasLength(1), reason: 'the move was sent at once');
    expect(find.byKey(const Key('planning-issue PL2')), findsNothing, reason: 'its row stays hidden');
    expect(
      find.descendant(
        of: find.byKey(const Key('planning-move-error')),
        matching: find.textContaining('PL2'),
        matchRoot: true,
      ),
      findsOneWidget,
      reason: 'the refusal is shown outside the table',
    );
  });

  // Harm: a move chosen on a row the search left is staged for another issue.
  testWidgets('a move chosen on the one row a search leaves moves that issue alone', (tester) async {
    final (source, board) = await _open(tester);
    await _showDetail(tester, board);
    await _search(tester, 'PL2');

    await tester.tap(
      find.descendant(
        of: find.byType(DataTable),
        matching: find.byType(PopupMenuButton<(String, String, bool, String)>),
      ),
    );
    await settle(tester);
    await tester.tap(find.text('Finished').last);
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);

    expect(
      [for (final call in source.moveCalls) call['moves']],
      [
        [
          {'issue': 'PL2', 'to': 'Finished'},
        ],
      ],
      reason: 'the row shown is PL2, so PL2 alone is moved, to the state chosen',
    );
  });
}

Future<(MockPlanningSource, PlanningBoard)> _open(WidgetTester tester) async {
  final source = MockPlanningSource()..boards['demo'] = _board();
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  addTearDown(() async {
    board.dispose();
    await tester.runAsync(source.end);
  });
  return (source, board);
}

Future<PlanningDetail> _showDetail(WidgetTester tester, PlanningBoard board) async {
  await _show(tester, board);
  return tester.state<State<PlanningStoryView>>(find.byType(PlanningStoryView)).widget.detail;
}

Future<void> _search(WidgetTester tester, String text) async {
  await tester.tap(find.byKey(const Key('planning-issue-search-open')));
  await settle(tester);
  await tester.enterText(find.byKey(const Key('planning-issue-search')), text);
  await settle(tester);
}
