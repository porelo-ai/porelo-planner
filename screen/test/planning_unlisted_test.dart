import 'dart:async';

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

Map<String, Object?> _issue(
  String id,
  String title, {
  String kind = 'issue',
  String? link,
  String? author,
  String? association,
}) => {
  'id': id,
  'repository': id.substring(0, id.indexOf('#')),
  'title': title,
  'kind': kind,
  'link': link ?? 'https://github.com/example-org/${id.replaceFirst('#', '/issues/')}',
  'author': ?author,
  'authorAssociation': ?association,
};

Finder _inRow(String id, Finder matching) =>
    find.descendant(of: find.byKey(Key('planning-unlisted $id')), matching: matching);

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

  // Harm: the person adds an issue anyone could open seeing only its title, or a login typed with markup is read as
  // markup.
  testWidgets('each offered issue shows its author and association as plain text, and none when the planner names '
      'none', (tester) async {
    await _showBoard(
      tester,
      TestHost(),
      unlisted: [
        _issue('own#1', 'By a stranger', author: 'a-person', association: 'FIRST_TIMER'),
        _issue('own#2', 'By nobody named'),
      ],
    );

    expect(_inRow('own#1', find.text('Issue  by a-person  FIRST_TIMER')), findsOneWidget);
    expect(_inRow('own#2', find.text('Issue')), findsOneWidget, reason: 'no author is made up');
  });

  // Harm: Add writes another issue or against another board, the row stays offered after the add, or the issue
  // vanishes from both the offer and the board.
  testWidgets('Add sends the offered issue at the shown revision, and the new board shows it on the project, offered '
      'no longer', (tester) async {
    final (backend, _) = await _showBoard(
      tester,
      TestHost(),
      unlisted: [_issue('own#4', 'An issue by hand'), _issue('other#2', 'Another')],
    );

    await tester.tap(find.byKey(const Key('planning-unlisted-add own#4')));
    await settle(tester);

    expect(backend.listCalls, [
      {'project': 'checkout', 'issue': 'own#4', 'revision': 'initial'},
    ]);
    expect(find.byKey(const Key('planning-unlisted own#4')), findsNothing, reason: 'offered no longer');
    expect(find.byKey(const Key('planning-unlisted other#2')), findsOneWidget, reason: 'the other stays offered');
    expect(find.text('own#4 added to the project.'), findsOneWidget);
    final added = (backend.boards['checkout']!['issues'] as List).cast<Map<String, dynamic>>().single;
    expect(added['id'], 'own#4', reason: 'the issue stands on the board');
    expect(added['state'], 'Ready', reason: 'in the first state');
  });

  // Harm: a second add while one is out writes against a board the first one changes, or the person cannot tell
  // which row is being added.
  testWidgets('while an add is out its row says so and no row offers a second add', (tester) async {
    final (backend, _) = await _showBoard(
      tester,
      TestHost(),
      unlisted: [_issue('own#4', 'An issue by hand'), _issue('other#2', 'Another')],
    );
    final gate = Completer<void>();
    backend.moveGate = gate;

    await tester.tap(find.byKey(const Key('planning-unlisted-add own#4')));
    await tester.pump();

    expect(_inRow('own#4', find.text('Adding…')), findsOneWidget);
    for (final id in ['own#4', 'other#2']) {
      final add = tester.widget<TextButton>(find.byKey(Key('planning-unlisted-add $id')));
      expect(add.onPressed, isNull, reason: '$id offers no add meanwhile');
    }
    gate.complete();
    await settle(tester);
    expect(backend.listCalls, hasLength(1));
  });

  // Harm: an issue the planner no longer offers is shown as added, or kept offered after the board said it is gone.
  testWidgets('an add the planner refuses names the reason, and the board streamed next shows what it offers now', (
    tester,
  ) async {
    final (backend, _) = await _showBoard(
      tester,
      TestHost(),
      unlisted: [_issue('own#4', 'An issue by hand'), _issue('other#2', 'Another')],
    );
    backend.moveFailure = const PlanningRefusal('NotUnlisted', {'issue': 'own#4'}, 'not offered');

    await tester.tap(find.byKey(const Key('planning-unlisted-add own#4')));
    await settle(tester);
    final data = backend.boards['checkout']!;
    data['unlisted'] = [_issue('other#2', 'Another')];
    data['revision'] = 'later';
    backend.push('checkout', data);
    await settle(tester);

    expect(tester.widget<Text>(find.byKey(const Key('planning-move-error'))).data, startsWith('own#4 was not added: '));
    expect(
      find.byKey(const Key('planning-unlisted own#4')),
      findsNothing,
      reason: 'the streamed board offers it no longer',
    );
    expect(find.byKey(const Key('planning-unlisted other#2')), findsOneWidget);
  });

  // Harm: an add that may stand on the project outside the workflow is told as not saved, and the person adds it
  // once more or never looks for it.
  testWidgets('an add partly saved says the issue may stand on the project without its state', (tester) async {
    final (backend, _) = await _showBoard(tester, TestHost(), unlisted: [_issue('own#4', 'An issue by hand')]);
    backend.moveFailure = const PlanningRefusal('PartlySaved', {}, 'partly saved');

    await tester.tap(find.byKey(const Key('planning-unlisted-add own#4')));
    await settle(tester);

    expect(
      tester.widget<Text>(find.byKey(const Key('planning-move-error'))).data,
      startsWith('own#4 may stand on the project outside the workflow, without its state: '),
    );
  });

  // Harm: a story whose add was cut off after the add is said to stand outside the workflow without its state, which
  // a story never has, or the screen's mock adds a story as an issue with a worker.
  testWidgets('an offered story is added as a story, and partly saved it is said to lack its priority and rank', (
    tester,
  ) async {
    final backend = await _showBoard(tester, TestHost(), unlisted: [_issue('own#4', 'A story by hand', kind: 'story')]);

    await tester.tap(find.byKey(const Key('planning-unlisted-add own#4')));
    await settle(tester);

    final stories = (backend.boards['checkout']!['stories'] as List).cast<Map<String, dynamic>>();
    expect(stories.map((story) => story['id']), contains('own#4'), reason: 'the story stands among the stories');
    expect(backend.boards['checkout']!['issues'], anyOf(isNull, isEmpty), reason: 'and is no issue');

    final data = backend.boards['checkout']!;
    data['unlisted'] = [_issue('own#5', 'Another story', kind: 'story')];
    data['revision'] = 'later';
    backend.push('checkout', data);
    await settle(tester);
    backend.moveFailure = const PlanningRefusal('PartlySaved', {}, 'partly saved');
    await tester.tap(find.byKey(const Key('planning-unlisted-add own#5')));
    await settle(tester);

    expect(
      tester.widget<Text>(find.byKey(const Key('planning-move-error'))).data,
      startsWith('own#5 may stand on the project without its priority and rank: '),
    );
  });
}
