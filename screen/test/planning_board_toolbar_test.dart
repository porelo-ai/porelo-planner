import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

const _workflow = [
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Implementing', 'kind': 'WORKING'},
  {'name': 'Review', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
];

Map<String, dynamic> _open({Map<String, int> states = const {}, int blocked = 0, int left = 0, int review = 0}) => {
  'states': [
    for (final name in _workflow.map((state) => state['name'] as String)) {'name': name, 'issues': states[name] ?? 0},
  ],
  'blocked': blocked,
  'sizes': [
    if (left > 0) {'name': 'S', 'issues': left},
  ],
  'unsized': 0,
  'left': left,
  'minutes': 60,
  'unestimated': 0,
  'review': review,
};

Map<String, dynamic> _story(String id, String state, String priority) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'priority': priority,
  'issues': [
    {'id': '$id-1', 'repository': 'repo', 'title': 'An issue of $id', 'state': state, 'blockedBy': <String>[]},
  ],
  'open': _open(states: {state: 1}, left: 1),
};

/// A board that offers its figures and filters, with one planning note.
Map<String, dynamic> _board({int notes = 1}) => {
  'revision': 'r1',
  'states': _workflow,
  'phases': [
    for (final state in _workflow)
      {
        'name': state['name'],
        'states': [state['name']],
      },
  ],
  'work': [
    {
      'state': 'Implementing',
      'from': ['Backlog'],
      'done': 'Review',
      'per': 'ISSUE',
      'modes': <String>[],
      'order': '',
    },
  ],
  'faults': <Object>[],
  'repositories': ['repo'],
  'stories': [_story('SA', 'Backlog', 'Low'), _story('SB', 'Implementing', 'High'), _story('SC', 'Review', 'High')],
  'open': _open(states: {'Backlog': 1, 'Implementing': 1, 'Review': 1}, left: 2, review: 1),
  'rules': [
    for (var at = 0; at < notes; at++)
      {'item': 'SA', 'rule': 'StoryPriorityBelowIssue', 'words': 'SA has a lower priority than its open issue SA-$at.'},
  ],
};

Future<PlanningBoards> _show(
  WidgetTester tester, {
  Size size = const Size(1600, 1000),
  int notes = 1,
  Widget? pageButton,
}) async {
  tester.view
    ..physicalSize = size
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final source = MockPlanningSource()..boards['demo'] = _board(notes: notes);
  final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
  addTearDown(() async {
    boards.dispose();
    await source.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: TestHost(), pageButton: pageButton),
      ),
    ),
  );
  await tester.pumpAndSettle();
  for (var tries = 0; tries < 50 && boards.selected?.rules.isEmpty != false; tries++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    await tester.pumpAndSettle();
  }
  return boards;
}

/// The vertical middle of a widget, to tell whether two stand in one row.
double _middle(WidgetTester tester, Finder finder) => tester.getCenter(finder.first).dy;

void main() {
  // Harm: the board repeats the app bar's title and its description above the lanes, taking their room.
  testWidgets('the board shows no heading and keeps its description and source in its info button', (tester) async {
    await _show(tester);

    expect(find.text('Board'), findsNothing, reason: 'the app bar names the page');
    expect(find.textContaining('by state, the most important first'), findsNothing, reason: 'no description line');
    expect(find.text('From mock'), findsNothing, reason: 'no source line');
    final info = tester.widget<Tooltip>(find.byKey(const Key('planning-board-info')));
    expect(
      info.message,
      'The stories of project demo by state, the most important first.\nFrom mock',
      reason: 'the description and the source in the info button',
    );
  });

  // Harm: the controls above the board take several rows, so the lanes start far down.
  testWidgets('filters, priority, search, reload and the menu stand in one toolbar row', (tester) async {
    await _show(tester);

    final row = _middle(tester, find.byKey(const Key('planning-board-info')));
    for (final key in [
      'planning-filter all',
      'planning-priority',
      'planning-search-open',
      'planning-reload',
      'planning-board-menu',
    ]) {
      expect(_middle(tester, find.byKey(Key(key))), closeTo(row, 4), reason: '$key stands in the toolbar row');
    }
    expect(find.text('Show stories with'), findsNothing, reason: 'the chips need no label line');
    expect(find.byKey(const Key('planning-search')), findsNothing, reason: 'search is a button until opened');
    expect(find.byType(IconButton).hitTestable(), findsWidgets);
    expect(
      tester.widget<IconButton>(find.byKey(const Key('planning-reload'))).tooltip,
      'Reload planning',
      reason: 'reload is an icon that names itself',
    );
  });

  // Harm: the search can no longer be reached, or stays filtering after it was closed.
  testWidgets('the search button opens its field, Escape clears it and the close button closes it', (tester) async {
    final boards = await _show(tester);
    final board = boards.selected!;

    await tester.tap(find.byKey(const Key('planning-search-open')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('planning-search')), findsOneWidget, reason: 'the field opened');
    await tester.enterText(find.byKey(const Key('planning-search')), 'SB');
    await tester.pumpAndSettle();
    expect(board.preferences.search, 'SB', reason: 'the field searches');

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(board.preferences.search, '', reason: 'Escape clears the search');
    expect(find.byKey(const Key('planning-search')), findsOneWidget, reason: 'Escape keeps the field open');

    await tester.enterText(find.byKey(const Key('planning-search')), 'SC');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('planning-search-close')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('planning-search')), findsNothing, reason: 'the field closed');
    expect(board.preferences.search, '', reason: 'closing ends the search');
    expect(find.byKey(const Key('planning-search-open')), findsOneWidget, reason: 'the button is back');
  });

  // Harm: the figures fill a boxed band of their own, or a note stands as a line above the lanes.
  testWidgets('the figures are one plain line and a planning note opens from its marker', (tester) async {
    // Wide enough for the test font, whose every letter is as wide as it is high.
    await _show(tester, size: const Size(2800, 1000));

    final summary = find.byKey(const Key('planning-summary'));
    expect(
      find
          .ancestor(of: summary, matching: find.byWidgetPredicate((widget) => widget is Container))
          .evaluate()
          .where((element) => (element.widget as Container).decoration != null),
      isEmpty,
      reason: 'no box around the figures',
    );
    final line = _middle(tester, find.byKey(const Key('planning-summary stories')));
    expect(_middle(tester, find.byKey(const Key('planning-summary blocked'))), closeTo(line, 4), reason: 'one line');
    expect(find.text('SA has a lower priority than its open issue SA-0.'), findsNothing, reason: 'the note waits');
    final marker = find.byKey(const Key('planning-rules'));
    expect(
      find.descendant(of: marker, matching: find.text('1 note')),
      findsOneWidget,
      reason: 'the marker counts',
    );
    expect(_middle(tester, marker), closeTo(line, 6), reason: 'the marker stands in the figures line');

    await tester.tap(marker);
    await tester.pumpAndSettle();
    expect(find.text('SA has a lower priority than its open issue SA-0.'), findsOneWidget, reason: 'the note opens');
  });

  // Harm: at phone width the toolbar overflows or a control falls out of reach.
  testWidgets('at phone width every control stays reachable and the chips scroll sideways', (tester) async {
    await _show(tester, size: const Size(360, 740), notes: 3);

    expect(tester.takeException(), isNull, reason: 'nothing overflows');
    for (final key in [
      'planning-board-info',
      'planning-priority',
      'planning-search-open',
      'planning-reload',
      'planning-board-menu',
      'planning-filter all',
      'planning-rules',
    ]) {
      expect(find.byKey(Key(key)).hitTestable(), findsOneWidget, reason: '$key can be reached');
    }
    final chips = find.ancestor(
      of: find.byKey(const Key('planning-filter all')),
      matching: find.byWidgetPredicate(
        (widget) => widget is SingleChildScrollView && widget.scrollDirection == Axis.horizontal,
      ),
    );
    expect(chips, findsOneWidget, reason: 'the chips scroll sideways');
    expect(find.text('3 notes'), findsOneWidget, reason: 'several notes behind one marker');
  });

  // Harm: at phone width the choices crowd the buttons, so an open search or a second project overflows the row.
  testWidgets('at phone width two projects and the open search share a row of their own without overflow', (
    tester,
  ) async {
    tester.view
      ..physicalSize = const Size(360, 740)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = MockPlanningSource()..boards['demo'] = _board();
    final boards = PlanningBoards(
      FakePlanningClient([
        PlanningProvider(
          name: 'local',
          connected: true,
          projects: ['demo', 'a-project-with-a-long-name'],
          source: source,
        ),
      ]),
    );
    addTearDown(() async {
      boards.dispose();
      await source.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: planningView(boards: boards, host: TestHost()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('planning-search-open')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull, reason: 'nothing overflows');
    for (final key in [
      'planning-project',
      'planning-priority',
      'planning-search',
      'planning-search-close',
      'planning-reload',
      'planning-board-menu',
    ]) {
      expect(find.byKey(Key(key)).hitTestable(), findsWidgets, reason: '$key can be reached');
    }
    final choices = _middle(tester, find.byKey(const Key('planning-project')));
    expect(
      _middle(tester, find.byKey(const Key('planning-search'))),
      closeTo(choices, 4),
      reason: 'one row of choices',
    );
    expect(
      _middle(tester, find.byKey(const Key('planning-reload'))),
      lessThan(choices),
      reason: 'the buttons stand in the row above',
    );
  });

  // Harm: the window's page button and the board's own info stand side by side as two equal info icons.
  testWidgets('beside the window\'s page button the board draws no info icon of its own', (tester) async {
    await _show(
      tester,
      pageButton: IconButton(
        key: const Key('window-page-button'),
        tooltip: 'What this page is',
        onPressed: () {},
        icon: const Icon(Icons.info_outline),
      ),
    );

    final toolbar = find.byKey(const Key('planning-toolbar'));
    expect(
      find.descendant(of: toolbar, matching: find.byIcon(Icons.info_outline)),
      findsOneWidget,
      reason: 'one info icon, the window\'s',
    );
    expect(find.byKey(const Key('window-page-button')), findsOneWidget, reason: 'the window\'s button stays');
    final info = find.byKey(const Key('planning-board-info'));
    expect(
      tester.widget<Tooltip>(info).message,
      'The stories of project demo by state, the most important first.\nFrom mock',
      reason: 'the description and the source stay with the board',
    );
    expect(
      find.descendant(of: info, matching: find.byKey(const Key('planning-summary stories'))),
      findsOneWidget,
      reason: 'they are told on the board\'s first figure',
    );
  });
}
