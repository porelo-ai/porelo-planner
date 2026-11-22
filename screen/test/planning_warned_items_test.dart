import 'package:flutter/gestures.dart';
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

Map<String, dynamic> _warning(String kind, String text, {bool holds = false}) => {
  'kind': kind,
  'text': text,
  'holds': holds,
};

const _outside = 'Its state is none of the workflow\'s states, so no work starts on it.';
const _twice = 'Its id names two issues; it can be neither worked nor moved until one id is changed.';

/// Shows a board of [stories] and the issues in no story [issues] in the states Ready, Building and Finished, in one
/// phase named [phase], as a planner serves it.
Future<PlanningBoards> _show(
  WidgetTester tester,
  List<Map<String, dynamic>> stories, {
  List<Map<String, dynamic>> issues = const [],
  String phase = 'Work',
}) async {
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
          'name': phase,
          'states': ['Ready', 'Building', 'Finished'],
        },
      ],
      'stories': stories,
      'issues': issues,
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
  return boards;
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
    expect(card('planning-unknown-state Parked', 'S4'), findsNothing, reason: 'no state is a column of its own');
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

  // Harm: an issue in no story whose state the workflow lacks is served but stands in no column, so the person never
  // sees it, nor what to set right in its file.
  testWidgets('an issue in no story in a state the workflow lacks stands in that state\'s column', (tester) async {
    await _show(
      tester,
      [
        {'id': 'S1', 'title': 'Ready one', 'state': 'Ready', 'priority': 'High', 'issues': <Object>[]},
      ],
      issues: [
        _issue('I9', 'Parked', warnings: [_warning('StateOutsideWorkflow', _outside, holds: true)]),
      ],
    );
    await tester.tap(find.byKey(const Key('planning-no-story-expand Parked')));
    await settle(tester);

    expect(
      find.descendant(
        of: find.byKey(const Key('planning-unknown-state Parked')),
        matching: find.byKey(const Key('planning-issue-row I9')),
      ),
      findsOneWidget,
    );
  });

  // Harm: a phase whose name is the key a column at the board's end once took shares that column's scroll, so a
  // scroll of the one moves or breaks the other.
  testWidgets('a phase named as an unknown state\'s column once was keeps a scroll of its own', (tester) async {
    await _show(tester, [
      for (var at = 1; at <= 30; at++)
        {
          'id': 'P$at',
          'title': 'Parked $at',
          'state': 'Parked',
          'stateGiven': true,
          'priority': 'High',
          'issues': <Object>[],
        },
      {'id': 'S2', 'title': 'Ready one', 'state': 'Ready', 'priority': 'High', 'issues': <Object>[]},
    ], phase: 'unplaced Parked');
    final ready = tester.getRect(find.byKey(const Key('planning-card S2')));
    final parked = tester.getRect(find.byKey(const Key('planning-card P8'))).top;

    // A wheel scrolls the column, where a drag would move the card.
    final wheel = TestPointer(1, PointerDeviceKind.mouse);
    await tester.sendEventToBinding(wheel.hover(tester.getCenter(find.byKey(const Key('planning-card P3')))));
    await tester.sendEventToBinding(wheel.scroll(const Offset(0, 400)));
    await settle(tester);

    expect(tester.takeException(), isNull, reason: 'the column scrolls on its own');
    expect(tester.getRect(find.byKey(const Key('planning-card P8'))).top, lessThan(parked), reason: 'it scrolled');
    expect(tester.getRect(find.byKey(const Key('planning-card S2'))), ready, reason: 'the phase stays where it was');
  });

  // Harm: the search finds an issue in the second copy of a story id but scrolls to the first, so the found issue
  // stays out of sight.
  testWidgets('the search brings the copy of a story id that holds the found issue into view', (tester) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'needle first',
        'state': 'Ready',
        'priority': 'Highest',
        'issues': <Object>[],
        'warnings': [_warning('IdTwice', _twice, holds: true)],
      },
      for (var at = 1; at <= 40; at++)
        {'id': 'F$at', 'title': 'needle filler $at', 'state': 'Ready', 'priority': 'High', 'issues': <Object>[]},
      {
        'id': 'S1',
        'title': 'needle second',
        'state': 'Ready',
        'priority': 'Low',
        'issues': [
          {..._issue('I9', 'Ready'), 'title': 'needle'},
        ],
        'warnings': [_warning('IdTwice', _twice, holds: true)],
      },
    ]);
    await tester.enterText(find.byKey(const Key('planning-search')), 'needle');
    await settle(tester);

    final second = find.text('needle second');
    expect(second, findsOneWidget, reason: 'the copy with the found issue is built');
    expect(tester.getRect(second).top, inInclusiveRange(0, 2000), reason: 'and stands in view');
  });

  // Harm: a card the planner holds still takes a drag, and one whose id names two stories still opens a page no read
  // by that id finds, so the person is offered what the planner refuses.
  testWidgets('a held card takes no drag but out of an unknown state, and an id named twice opens no page', (
    tester,
  ) async {
    await _show(tester, [
      {
        'id': 'S1',
        'title': 'Named twice',
        'state': 'Ready',
        'priority': 'High',
        'issues': <Object>[],
        'warnings': [_warning('IdTwice', _twice, holds: true)],
      },
      {
        'id': 'S2',
        'title': 'Parked',
        'state': 'Parked',
        'stateGiven': true,
        'priority': 'High',
        'issues': <Object>[],
        'warnings': [_warning('StateOutsideWorkflow', _outside, holds: true)],
      },
      {'id': 'S3', 'title': 'Sound', 'state': 'Ready', 'priority': 'High', 'issues': <Object>[]},
    ]);

    int? drags(String story) => (tester.widget(
      find.ancestor(
        of: find.byKey(Key('planning-card $story')),
        matching: find.byWidgetPredicate((w) => w is Draggable),
      ),
    ) as Draggable).maxSimultaneousDrags;
    VoidCallback? opens(String story) => tester.widget<InkWell>(find.byKey(Key('planning-story $story'))).onTap;

    expect(drags('S1'), 0, reason: 'held where it stands');
    expect(drags('S2'), 1, reason: 'held only in a state the workflow lacks, which a move sets right');
    expect(drags('S3'), 1);
    expect(opens('S1'), isNull, reason: 'no read by an id named twice finds a page');
    expect(opens('S3'), isNotNull);
  });

  // Harm: the story page and its issue rows look sound for a warned story, so the person cannot tell there why it
  // holds back its work.
  testWidgets('the story page marks a warned story and its warned issue with what is wrong', (tester) async {
    tester.view
      ..physicalSize = const Size(1600, 2000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = MockPlanningSource()
      ..boards['demo'] = {
        'states': [
          {'name': 'Ready', 'kind': 'WAITING'},
          {'name': 'Finished', 'kind': 'CLOSED'},
        ],
        'phases': [
          {
            'name': 'Work',
            'states': ['Ready', 'Finished'],
          },
        ],
        'stories': [
          {
            'id': 'S1',
            'title': 'Warned',
            'state': 'Ready',
            'priority': 'High',
            'issues': [
              _issue('I1', 'Parked', warnings: [_warning('StateOutsideWorkflow', _outside, holds: true)]),
            ],
            'warnings': [_warning('IdTwice', _twice, holds: true)],
          },
        ],
        'revision': 'r1',
      };
    final board = PlanningBoard(source, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    final detail = PlanningDetail(board, 'S1', null);
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

    expect(_tooltip(tester, 'planning-warned S1'), _twice, reason: 'beside the story\'s state');
    expect(_tooltip(tester, 'planning-warned-issue I1'), _outside, reason: 'in its issue\'s row');
  });
}
