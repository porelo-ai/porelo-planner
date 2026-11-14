import 'dart:async';

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
  'parent': null,
  'link': 'https://example.org/issues/$id',
  'worker': 'AGENT',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
};

Map<String, dynamic> _story(String id, String state, List<Map<String, dynamic>> issues, String? derived) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'stateGiven': true,
  'misfits': <String>[],
  'derivedState': derived,
  'priority': 'High',
  'link': 'https://example.org/stories/$id',
  'issues': issues,
};

/// ST1 given Idea over an issue in Backlog; ST2 given Backlog over an issue at work; ST3 given Backlog with no issue.
Map<String, dynamic> _board() => {
  'states': [
    {'name': 'Idea', 'kind': 'WAITING'},
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Implementing', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Idea', 'Backlog', 'Implementing', 'Review'],
    },
  ],
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    _story('ST1', 'Idea', [_issue('PL1', 'Backlog')], 'Backlog'),
    _story('ST2', 'Backlog', [_issue('PL2', 'Implementing')], 'Implementing'),
    _story('ST3', 'Backlog', [], null),
  ],
  'revision': 'r1',
  'repositories': ['repo'],
};

Future<(MockPlanningSource, PlanningBoard)> _open(WidgetTester tester) async {
  final source = MockPlanningSource()..boards['demo'] = _board();
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  addTearDown(() async {
    board.dispose();
    await tester.runAsync(source.end);
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: ListenableBuilder(
          listenable: board,
          builder: (_, _) => Column(
            children: [
              for (final story in board.stories)
                Row(
                  children: [
                    Text(story.id),
                    ?planningGivenMark(story, onFollow: (to) => unawaited(board.followIssues(story.id, to))),
                  ],
                ),
            ],
          ),
        ),
      ),
    ),
  );
  await settle(tester);
  return (source, board);
}

Future<void> _follow(WidgetTester tester, String story) async {
  await tester.tap(find.byKey(Key('planning-given-menu $story')));
  await settle(tester);
  await tester.tap(find.byKey(Key('planning-follow $story')));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

void main() {
  // Harm: a story handed back to its issues that moves an issue, or that stays pinned, changes or hides the plan.
  testWidgets('the pin\'s entry hands a story back to its issues and moves none of them', (tester) async {
    final (source, board) = await _open(tester);
    for (final (id, to) in [('ST1', 'Backlog'), ('ST2', 'Implementing')]) {
      await _follow(tester, id);
      final story = board.stories.firstWhere((story) => story.id == id);
      expect(story.state, to, reason: '$id stands where its issues are');
      expect(story.stateGiven, isFalse, reason: '$id keeps no state of its own');
      expect(find.byKey(Key('planning-given $id')), findsNothing, reason: 'the pin goes');
    }
    expect(source.moveCalls.map((call) => call['moves']), [
      [
        {'story': 'ST1', 'to': 'Backlog', 'follow': true},
      ],
      [
        {'story': 'ST2', 'to': 'Implementing', 'follow': true},
      ],
    ], reason: 'each sent as a follow move');
    final issues = {
      for (final story in board.stories)
        for (final issue in story.issues) issue.id: issue.state,
    };
    expect(issues, {'PL1': 'Backlog', 'PL2': 'Implementing'}, reason: 'no issue moved');
  });

  // Harm: an entry read before the issues moved hands the story to a state the person was never offered.
  testWidgets('an entry offered before the issues moved sends the state it offered, which the planner refuses', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    await tester.tap(find.byKey(const Key('planning-given-menu ST1')));
    await settle(tester);
    expect(find.text('Follow the issues again (Backlog)'), findsOneWidget, reason: 'Backlog offered');
    final moved = _board();
    final story = (moved['stories'] as List).first as Map<String, dynamic>;
    ((story['issues'] as List).first as Map<String, dynamic>)['state'] = 'Review';
    story['derivedState'] = 'Review';
    moved['revision'] = 'r2';
    source.push('demo', moved);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.tap(find.byKey(const Key('planning-follow ST1')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);
    expect(source.previewCalls, isNotEmpty, reason: 'the entry was taken');
    expect(source.moveCalls, isEmpty, reason: 'nothing was saved');
    expect(
      source.previewCalls.map((call) => call['moves']),
      everyElement([
        {'story': 'ST1', 'to': 'Backlog', 'follow': true},
      ]),
      reason: 'only the offered state is sent',
    );
    final shown = board.stories.firstWhere((story) => story.id == 'ST1');
    expect((shown.state, shown.stateGiven), ('Idea', true), reason: 'the story keeps its given state');
  });

  // Harm: the mock board the window's tests run on lets a follow through that the planner refuses, so a window that
  // breaks on the planner passes its tests.
  test('the mock refuses a follow as the planner does', () {
    Map<String, dynamic> follow(Map<String, dynamic> board, List<Map<String, dynamic>> moves) =>
        applyPlanningMove(board, {'moves': moves, 'revision': 'r1', 'confirm': true});
    final batch = [
      {'issue': 'PL1', 'to': 'Review'},
      {'story': 'ST1', 'to': 'Backlog', 'follow': true},
    ];
    expect(() => follow(_board(), batch), throwsA(isA<PlanningRefusal>()), reason: 'a follow in a batch with others');
    final unknown = _board();
    final story = (unknown['stories'] as List).first as Map<String, dynamic>;
    (story['issues'] as List).add(_issue('PL9', 'Elsewhere'));
    expect(
      () => follow(unknown, [
        {'story': 'ST1', 'to': 'Backlog', 'follow': true},
      ]),
      throwsA(isA<PlanningRefusal>()),
      reason: 'an issue in a state outside the workflow leaves the state they give unknown',
    );
  });

  // Harm: a story without issues offered to follow them, which its head cannot hold without a state.
  testWidgets('a story without issues offers no entry', (tester) async {
    await _open(tester);
    expect(find.byKey(const Key('planning-given ST3')), findsOneWidget, reason: 'its pin stays');
    expect(find.byKey(const Key('planning-given-menu ST3')), findsNothing, reason: 'but opens no menu');
  });
}
