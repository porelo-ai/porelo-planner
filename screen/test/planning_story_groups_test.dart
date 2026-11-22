import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/story_groups.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, String state, {List<String> blockedBy = const []}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': blockedBy,
  'link': 'https://example.org/issues/$id',
  'worker': 'MANUAL',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
};

final _count = {
  'states': [
    {'name': 'Backlog', 'issues': 0},
    {'name': 'Implementing', 'issues': 0},
    {'name': 'Review', 'issues': 1},
    {'name': 'Finished', 'issues': 0},
  ],
  'blocked': 1,
  'sizes': [
    {'name': 'S', 'issues': 1},
  ],
  'unsized': 0,
  'left': 1,
  'minutes': 30,
  'unestimated': 0,
  'review': 1,
};

/// Story ST1 with one issue blocked by an issue of story ST2 and one in review; [open] is ST1's count, if any.
Map<String, dynamic> _board({Map<String, dynamic>? open}) => {
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
  'work': [
    {
      'state': 'Implementing',
      'from': ['Backlog'],
      'done': 'Review',
    },
  ],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A story',
      'state': 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'issues': [
        _issue('PL1', 'Backlog', blockedBy: ['PL9']),
        _issue('PL2', 'Review'),
      ],
      'open': ?open,
    },
    {
      'id': 'ST2',
      'title': 'Another story',
      'state': 'Implementing',
      'priority': 'High',
      'link': 'https://example.org/stories/ST2',
      'issues': [_issue('PL9', 'Implementing')],
    },
  ],
  'revision': 'r1',
  'repositories': ['repo'],
};

Future<(MockPlanningSource, PlanningBoard)> _open(WidgetTester tester, Map<String, dynamic> data) async {
  final source = MockPlanningSource()..boards['demo'] = data;
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  addTearDown(() async {
    board.dispose();
    await tester.runAsync(source.end);
  });
  return (source, board);
}

Future<void> _show(WidgetTester tester, PlanningBoard board, {ValueChanged<String>? onIssue}) async {
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
              onIssue: onIssue ?? (_) {},
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

String? _figure(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(Key(key))).data;

const _figures = ['planning-story-left', 'planning-story-minutes', 'planning-story-sizes'];

void main() {
  // Harm: a story the planner could not count reads as having nothing left, and one whose count a move made stale
  // shows the old figures beside the moved issues.
  testWidgets('the head reads unknown without a count and while a move is saved, and the count once known', (
    tester,
  ) async {
    final (_, unknown) = await _open(tester, _board());
    await _show(tester, unknown);
    expect(
      [for (final key in _figures) _figure(tester, key)],
      everyElement(storyFigureUnknown),
      reason: 'no count was sent',
    );

    final (source, board) = await _open(tester, _board(open: _count));
    await _show(tester, board);
    expect(_figure(tester, 'planning-story-left'), '1 of 2', reason: 'the planner\'s count');

    source.moveHeld = Completer<void>();
    unawaited(board.moveIssue('PL2', 'Backlog'));
    await settle(tester);
    expect(
      [for (final key in _figures) _figure(tester, key)],
      everyElement(storyFigureUnknown),
      reason: 'the count no longer matches the issues while the move is saved',
    );
    source.moveHeld!.complete();
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    await settle(tester);
  });

  // Harm: an issue waiting on another story's open work is offered as ready to start, or its row no longer opens it.
  testWidgets('an issue blocked from another story stands under Blocked, and its row still opens it', (tester) async {
    final (_, board) = await _open(tester, _board(open: _count));
    final opened = <String>[];
    await _show(tester, board, onIssue: opened.add);

    expect(find.byKey(const Key('planning-issue-group blocked Blocked')), findsOneWidget, reason: 'PL9 is open');
    expect(find.byKey(const Key('planning-issue-group waiting Backlog')), findsNothing, reason: 'PL1 is blocked');
    expect(find.byKey(const Key('planning-issue-group review Review')), findsOneWidget, reason: 'PL2 waits for review');
    expect(find.textContaining('PL9'), findsOneWidget, reason: 'the row names what it waits on');

    await tester.tap(find.byKey(const Key('planning-issue PL1')));
    expect(opened, ['PL1'], reason: 'the row opens its issue');
  });

  // Harm: a board that stopped answering shows its last count as what is open now.
  testWidgets('the head reads unknown while the board is not live', (tester) async {
    final (source, board) = await _open(tester, _board(open: _count));
    await _show(tester, board);
    expect(_figure(tester, 'planning-story-left'), '1 of 2', reason: 'the count while live');

    source.fail(StateError('gone'));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);

    expect(_figure(tester, 'planning-story-left'), storyFigureUnknown, reason: 'the board is not live');
  });
}
