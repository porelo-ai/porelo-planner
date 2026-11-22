import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// A board of one story whose planner names [unlisted] as the issues outside the project, shown through [host]; its
/// source and boards.
Future<(MockPlanningSource, PlanningBoards)> _showBoard(
  WidgetTester tester,
  TestHost host, {
  List<Map<String, Object?>>? unlisted,
}) async {
  tester.view
    ..physicalSize = const Size(1600, 900)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = MockPlanningSource();
  backend.boards['checkout'] = {
    'states': [
      {'name': 'Ready', 'kind': 'WAITING'},
      {'name': 'Done', 'kind': 'CLOSED'},
    ],
    'phases': [
      {
        'name': 'Work',
        'states': ['Ready', 'Done'],
      },
    ],
    'stories': [
      {'id': 'StoryOne', 'title': 'A story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
    ],
    'revision': 'initial',
    'unlisted': ?unlisted,
  };
  final providers = [
    PlanningProvider(name: 'local', connected: true, projects: ['checkout'], source: backend),
  ];
  final client = MockPlanningClient(() => providers, currentProvider: () => 'local');
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: host),
      ),
    ),
  );
  await settle(tester);
  return (backend, boards);
}

Map<String, Object?> _issue(String id, String title, {String kind = 'issue', String? link}) => {
  'id': id,
  'repository': id.substring(0, id.indexOf('#')),
  'title': title,
  'kind': kind,
  'link': link ?? 'https://github.com/example-org/${id.replaceFirst('#', '/issues/')}',
};

void main() {
  // Harm: an issue made by hand on the tracker is never seen in the window, a story made by hand reads as an issue,
  // or an empty list still takes a column.
  testWidgets('each issue outside the project is shown by id, title and kind, and the section only when there is one', (
    tester,
  ) async {
    final host = TestHost();
    final (backend, _) = await _showBoard(
      tester,
      host,
      unlisted: [
        _issue('own#4', 'A story by hand', kind: 'story'),
        _issue('other#2', 'An issue by hand'),
      ],
    );

    expect(find.text('Issues not on the project'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const Key('planning-unlisted own#4')),
        matching: find.text('own#4  A story by hand'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: find.byKey(const Key('planning-unlisted own#4')), matching: find.text('Story')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: find.byKey(const Key('planning-unlisted other#2')), matching: find.text('Issue')),
      findsOneWidget,
    );

    final data = backend.boards['checkout']!;
    data['unlisted'] = <Object>[];
    backend.push('checkout', data);
    await settle(tester);

    expect(find.byKey(const Key('planning-unlisted')), findsNothing, reason: 'an empty list shows no section');

    data['unlisted'] = [_issue('other#5', 'Made later by hand')];
    backend.push('checkout', data);
    await settle(tester);

    expect(
      find.byKey(const Key('planning-unlisted other#5')),
      findsOneWidget,
      reason: 'a list a later board names is shown',
    );
  });

  // Harm: a board from a planner that sends no list shows an empty section, or fails to show.
  testWidgets('a board without the list shows no section', (tester) async {
    await _showBoard(tester, TestHost());

    expect(find.byKey(const Key('planning-card StoryOne')), findsOneWidget, reason: 'the board is shown');
    expect(find.byKey(const Key('planning-unlisted')), findsNothing);
  });

  // Harm: a title typed on the tracker is read as markup, or a long one pushes the card past its column.
  testWidgets('a title is shown as plain text, and a very long one stays within its card', (tester) async {
    final long = 'A very long title ' * 60;
    await _showBoard(tester, TestHost(), unlisted: [_issue('own#1', '<b>x</b>'), _issue('own#2', long)]);

    expect(find.text('own#1  <b>x</b>'), findsOneWidget, reason: 'the markup stays its own characters');
    expect(tester.takeException(), isNull, reason: 'nothing overflows');
    final card = tester.getRect(find.byKey(const Key('planning-unlisted own#2')));
    final section = tester.getRect(find.byKey(const Key('planning-unlisted')));
    expect(card.right, lessThanOrEqualTo(section.right), reason: 'the long title stays within the column');
    expect(card.height, lessThan(200), reason: 'the long title is cut, not shown whole');
  });

  // Harm: a link the tracker gave opens something other than a web page, such as a file or a script.
  testWidgets('only an HTTPS link opens; any other is offered greyed and opens nothing', (tester) async {
    final host = TestHost();
    await _showBoard(
      tester,
      host,
      unlisted: [
        _issue('own#1', 'Safe'),
        _issue('own#2', 'A file', link: 'file:///etc/passwd'),
        _issue('own#3', 'A script', link: 'javascript:alert(1)'),
        _issue('own#4', 'Plain', link: 'http://github.example.org/a/own/issues/4'),
      ],
    );

    await tester.tap(find.byKey(const Key('planning-unlisted-open own#1')));
    for (final id in ['own#2', 'own#3', 'own#4']) {
      await tester.tap(find.byKey(Key('planning-unlisted-open $id')), warnIfMissed: false);
    }
    await settle(tester);

    expect(host.opened, [Uri.parse('https://github.com/example-org/own/issues/1')]);
    for (final id in ['own#2', 'own#3', 'own#4']) {
      final button = tester.widget<IconButton>(find.byKey(Key('planning-unlisted-open $id')));
      expect(button.onPressed, isNull, reason: '$id is offered greyed');
    }
  });

  // Harm: a tracker with thousands of open issues outside the project makes as many actions, built anew on every
  // change, so the window slows on each one.
  testWidgets('the actions name at most a fixed number of issues outside the project', (tester) async {
    final host = TestHost();
    final (_, boards) = await _showBoard(
      tester,
      host,
      unlisted: [for (var n = 1; n <= planningUnlistedActions + 50; n++) _issue('own#$n', 'By hand $n')],
    );

    final named = planningActions(boards, host).where((action) => action.label.startsWith('Open issue own#'));
    expect(named, hasLength(planningUnlistedActions));
    expect(named.first.label, 'Open issue own#1 in browser', reason: 'the first ones in the planner\'s order');
  });
}
