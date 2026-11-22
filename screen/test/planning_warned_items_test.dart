import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, String state, {String repository = 'repo', List<Object>? warnings}) => {
  'id': id,
  'repository': repository,
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': <String>[],
  'warnings': ?warnings,
};

Map<String, dynamic> _warning(String kind, String text) => {'kind': kind, 'text': text};

const _outside = 'Its state is none of the workflow\'s states, so no work starts on it.';
const _twice = 'Its id names two issues; it can be neither worked nor moved until one id is changed.';

/// Shows a board of [stories] in the states Ready, Building and Finished, as a planner serves it.
Future<void> _show(WidgetTester tester, List<Map<String, dynamic>> stories) async {
  tester.view
    ..physicalSize = const Size(2400, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = MockPlanningSource()
    ..boards['demo'] = {
      'states': [
        {'name': 'Ready', 'kind': 'WAITING'},
        {'name': 'Building', 'kind': 'WORKING'},
        {'name': 'Finished', 'kind': 'CLOSED'},
      ],
      'phases': [
        {
          'name': 'Work',
          'states': ['Ready', 'Building', 'Finished'],
        },
      ],
      'stories': stories,
      'revision': 'r1',
    };
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
}

String _tooltip(WidgetTester tester, String key) =>
    tester.widget<Tooltip>(find.ancestor(of: find.byKey(Key(key)), matching: find.byType(Tooltip)).first).message!;

void main() {
  // Harm: a planner that serves a story whose state cannot be told, or an issue in a state the workflow lacks,
  // takes the whole board away from a screen built before those were served.
  testWidgets('a story without a state and an issue outside the workflow leave the board standing', (tester) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'No state told',
        'state': '',
        'stateGiven': false,
        'priority': 'High',
        'issues': [_issue('I1', 'Parked')],
      },
      {
        'id': 'S2',
        'title': 'One issue parked by hand',
        'state': 'Ready',
        'priority': 'High',
        'issues': [_issue('I2', 'Parked'), _issue('I3', 'Ready')],
      },
    ]);

    expect(tester.takeException(), isNull, reason: 'the board is built');
    expect(find.text('One issue parked by hand'), findsWidgets, reason: 'the story in a known state is shown');
  });

  // Harm: a card the store could not fully read looks like any other, so the person cannot tell why it holds back
  // its work, or what to set right in its file.
  testWidgets('a warned story and a warned issue carry a mark whose tooltip says what is wrong', (tester) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'Warned',
        'state': 'Parked',
        'stateGiven': true,
        'priority': 'High',
        'issues': [
          _issue('I1', 'Parked', warnings: [_warning('StateOutsideWorkflow', _outside)]),
          _issue('I2', 'Ready'),
        ],
        'warnings': [_warning('StateOutsideWorkflow', _outside), _warning('IdTwice', _twice)],
      },
      {
        'id': 'S2',
        'title': 'Sound',
        'state': 'Ready',
        'priority': 'High',
        'issues': <Object>[],
        'warnings': <Object>[],
      },
    ]);
    await tester.tap(find.byKey(const Key('planning-expand S1')));
    await settle(tester);

    expect(
      _tooltip(tester, 'planning-warned S1'),
      '$_outside\n$_twice',
      reason: 'every warning of the story, in order',
    );
    expect(_tooltip(tester, 'planning-warned-issue I1'), _outside, reason: 'the issue row says its own');
    expect(find.byKey(const Key('planning-warned-issue I2')), findsNothing, reason: 'its sound sibling has none');
    expect(find.byKey(const Key('planning-warned S2')), findsNothing, reason: 'a sound story has none');
  });

  // Harm: an older planner names no warnings, and a mark on such an item tells the person something is wrong
  // where nothing is.
  testWidgets('an answer without warnings shows no mark', (tester) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'Older',
        'state': 'Ready',
        'priority': 'High',
        'issues': [_issue('I1', 'Ready')],
      },
    ]);
    await tester.tap(find.byKey(const Key('planning-expand S1')));
    await settle(tester);

    expect(find.byKey(const Key('planning-warned S1')), findsNothing);
    expect(find.byKey(const Key('planning-warned-issue I1')), findsNothing);
  });

  // Harm: stories in different states the workflow lacks, and those without a state, stand in one column, so the
  // person cannot tell which value each holds or which to set right.
  testWidgets('each state outside the workflow, and no state, has a column of its own at the board\'s end', (
    tester,
  ) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'Parked one',
        'state': 'Parked',
        'stateGiven': true,
        'priority': 'High',
        'issues': <Object>[],
      },
      {'id': 'S2', 'title': 'Held one', 'state': 'Held', 'stateGiven': true, 'priority': 'High', 'issues': <Object>[]},
      {
        'id': 'S3',
        'title': 'Parked two',
        'state': 'Parked',
        'stateGiven': true,
        'priority': 'High',
        'issues': <Object>[],
      },
      {'id': 'S4', 'title': 'Untold', 'state': '', 'stateGiven': false, 'priority': 'High', 'issues': <Object>[]},
      {'id': 'S5', 'title': 'Ready one', 'state': 'Ready', 'priority': 'High', 'issues': <Object>[]},
    ]);

    Finder card(String column, String story) =>
        find.descendant(of: find.byKey(Key(column)), matching: find.byKey(Key('planning-card $story')));
    expect(card('planning-unknown-state Parked', 'S1'), findsOneWidget);
    expect(card('planning-unknown-state Parked', 'S3'), findsOneWidget);
    expect(card('planning-unknown-state Held', 'S2'), findsOneWidget);
    expect(card('planning-no-state', 'S4'), findsOneWidget);
    expect(card('planning-unknown-state Parked', 'S2'), findsNothing, reason: 'each value in its own column');
    expect(
      find.descendant(of: find.byKey(const Key('planning-unknown-state Parked')), matching: find.text('Parked')),
      findsOneWidget,
      reason: 'the column is named after the value',
    );
    expect(
      find.descendant(of: find.byKey(const Key('planning-no-state')), matching: find.text('No state')),
      findsOneWidget,
    );
    final ready = tester.getRect(find.byKey(const Key('planning-card S5')));
    for (final column in ['planning-unknown-state Parked', 'planning-unknown-state Held', 'planning-no-state']) {
      expect(tester.getRect(find.byKey(Key(column))).left, greaterThan(ready.right), reason: '$column at the end');
    }
    expect(
      tester.getRect(find.byKey(const Key('planning-no-state'))).left,
      greaterThan(tester.getRect(find.byKey(const Key('planning-unknown-state Parked'))).left),
      reason: 'No state stands last',
    );
  });

  // Harm: two issues of one id in one story's list, one in each repository, take the board away with a key clash.
  testWidgets('two issues of one id in one list are both shown, each with its repository', (tester) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'Twice',
        'state': 'Ready',
        'priority': 'High',
        'issues': [
          _issue('I1', 'Ready', repository: 'one', warnings: [_warning('IdTwice', _twice)]),
          _issue('I1', 'Ready', repository: 'two', warnings: [_warning('IdTwice', _twice)]),
        ],
      },
    ]);
    await tester.tap(find.byKey(const Key('planning-expand S1')));
    await settle(tester);

    expect(tester.takeException(), isNull, reason: 'the list is built');
    expect(find.text('I1 · one'), findsOneWidget);
    expect(find.text('I1 · two'), findsOneWidget);
  });

  // Harm: two stories of one id show the first copy's card twice, so the second copy's own content is hidden.
  testWidgets('two stories of one id each show their own card', (tester) async {
    await _show(tester, [
      for (final title in ['First copy', 'Second copy'])
        {
          'id': 'S1',
          'title': title,
          'state': 'Ready',
          'priority': 'High',
          'issues': <Object>[],
          'warnings': [_warning('IdTwice', _twice)],
        },
    ]);

    expect(tester.takeException(), isNull, reason: 'the column is built');
    expect(find.text('First copy'), findsOneWidget);
    expect(find.text('Second copy'), findsOneWidget);
  });

  // Harm: a move waiting for its save drops the mark, so a warned item looks sound until the board is read again.
  test('a story and its issues keep their warnings through a move', () {
    final story = PlanStory.from({
      'id': 'S1',
      'title': 'Warned',
      'state': 'Ready',
      'issues': [
        _issue('I1', 'Ready', warnings: [_warning('IdTwice', _twice)]),
      ],
      'warnings': [_warning('StateOutsideWorkflow', _outside)],
    });

    final moved = PlanStory.withMoves(story, {'I1': 'Building'}, 'Building');

    expect(moved.warnings.map((warning) => warning.text), [_outside]);
    expect(moved.issues.single.warnings.map((warning) => warning.kind), ['IdTwice']);
  });
}
