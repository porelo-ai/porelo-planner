import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/story_groups.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

Map<String, dynamic> _issue(
  String id,
  String state, {
  Object blockedBy = const <String>[],
  Object waitsOn = const <Map<String, dynamic>>[],
}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': blockedBy,
  'waitsOn': waitsOn,
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
Map<String, dynamic> _board({
  Map<String, dynamic>? open,
  Object waitsOn = const <Map<String, dynamic>>[],
  Object blockedBy = const <String>[],
  Object blockerWaitsOn = const <Map<String, dynamic>>[],
}) => {
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
        _issue('PL1', 'Backlog', blockedBy: ['PL9'], waitsOn: blockerWaitsOn),
        _issue('PL2', 'Review', blockedBy: blockedBy, waitsOn: waitsOn),
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

Future<void> _show(
  WidgetTester tester,
  PlanningBoard board, {
  ValueChanged<String>? onIssue,
  Future<void> Function(Uri)? openLink,
}) async {
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
              openLink: openLink ?? (_) async {},
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
  // Harm: a story the planner could not count reads as having nothing left, and a count known before a save turns
  // unknown while the save runs, though it stands until the planner's read after the save replaces it.
  testWidgets('the head reads unknown without a count, and a count once known stays while a move is saved', (
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
    expect(board.saving, isTrue, reason: 'the move is being saved');
    expect(_figure(tester, 'planning-story-left'), '1 of 2', reason: 'the count known before the save stays');
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
    expect(find.byTooltip('Waits on PL9 (Implementing)'), findsOneWidget, reason: 'the row names what it waits on');

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

  // Harm: a wait's icon hands the person's browser an address of another scheme, a file or a script, that the
  // planner reported as the waited issue's link.
  testWidgets('a wait\'s icon opens its HTTPS link in the browser and no link of another scheme', (tester) async {
    final (_, board) = await _open(
      tester,
      _board(
        open: _count,
        waitsOn: [
          {'reference': 'other/web#1', 'done': false, 'link': 'https://example.org/other/web/1'},
          {'reference': 'other/file#2', 'done': false, 'link': 'file:///etc/passwd'},
          {'reference': 'other/script#3', 'done': false, 'link': 'javascript:alert(1)'},
          {'reference': 'other/none#4', 'done': null, 'link': null},
          {'reference': 'other/plain#5', 'done': false, 'link': 'http://example.org/other/plain/5'},
          {'reference': 'other/hostless#6', 'done': false, 'link': 'https:///other/hostless/6'},
          {'reference': 'other/done#7', 'done': true, 'link': 'https://example.org/other/done/7'},
          {'reference': null, 'done': null, 'link': null},
        ],
      ),
    );
    final opened = <Uri>[];
    await _show(tester, board, openLink: (link) async => opened.add(link));

    await tester.tap(find.byKey(const Key('planning-wait PL2 other/none#4')));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    expect(opened, isEmpty, reason: 'a wait without a link stands and opens nothing');
    expect(find.byKey(const Key('planning-wait PL2 other/done#7')), findsNothing, reason: 'a done wait holds nothing');

    expect(find.byTooltip('Waits on other/file#2 (open)'), findsOneWidget, reason: 'the hover names it and its state');
    expect(find.byTooltip('Waits on other/none#4 (not read)'), findsOneWidget);
    for (final key in [
      'planning-wait PL1 PL9',
      'planning-wait PL2 other/web#1',
      'planning-wait PL2 other/file#2',
      'planning-wait PL2 other/script#3',
      'planning-wait PL2 other/plain#5',
      'planning-wait PL2 other/hostless#6',
      'planning-wait PL2 unreadable 6',
    ]) {
      await tester.tap(find.byKey(Key(key)));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await settle(tester);
    }

    expect(opened, [
      Uri.parse('https://example.org/issues/PL9'),
      Uri.parse('https://example.org/other/web/1'),
    ], reason: 'only the HTTPS links reach the browser');
  });

  // Harm: a malformed blocker or wait list reads as nothing to wait on, so the person takes a held issue as free.
  testWidgets('a row says when its blockers or waits were not reported', (tester) async {
    final (_, board) = await _open(tester, _board(open: _count, waitsOn: 'none', blockedBy: 'PL9'));
    await _show(tester, board);

    expect(find.byKey(const Key('planning-waits-unreported PL2')), findsOneWidget);
    expect(find.byKey(const Key('planning-blockers-unreported PL2')), findsOneWidget);
    expect(find.byKey(const Key('planning-waits-unreported PL1')), findsNothing, reason: 'PL1 reported its waits');
    expect(find.byKey(const Key('planning-blockers-unreported PL1')), findsNothing);
  });

  // Harm: one issue a row waits on is shown twice, as a blocker and as an outside wait, and two unreadable waits
  // share one key, so the second cannot be reached or the row fails to draw.
  testWidgets('a wait on a board blocker is shown once, and each unreadable wait apart', (tester) async {
    final (_, board) = await _open(
      tester,
      _board(
        open: _count,
        blockerWaitsOn: [
          {'reference': 'PL9', 'done': false, 'link': null},
          {'reference': 'example/repo#9', 'done': false, 'link': 'https://example.org/issues/PL9'},
          {'reference': 'other/web#1', 'done': false, 'link': 'https://example.org/other/web/1'},
        ],
        waitsOn: [
          {'reference': null, 'done': false, 'link': null},
          {'reference': null, 'done': null, 'link': null},
        ],
      ),
    );
    await _show(tester, board);

    expect(find.byTooltip('Waits on PL9 (Implementing)'), findsOneWidget, reason: 'the blocker');
    expect(find.byTooltip('Waits on PL9 (open)'), findsNothing, reason: 'the same issue by its id');
    expect(find.byTooltip('Waits on example/repo#9 (open)'), findsNothing, reason: 'the same issue by its link');
    expect(find.byTooltip('Waits on other/web#1 (open)'), findsOneWidget, reason: 'another issue stays');
    expect(find.byKey(const Key('planning-wait PL2 unreadable 0')), findsOneWidget);
    expect(find.byKey(const Key('planning-wait PL2 unreadable 1')), findsOneWidget);
  });

  // Harm: a wait's link is reachable only by pointer, so the window's command palette cannot open it.
  testWidgets('the planning actions open a wait outside the board in the browser, an HTTPS link only', (tester) async {
    final source = MockPlanningSource()
      ..boards['demo'] = _board(
        waitsOn: [
          {'reference': 'other/web#1', 'done': false, 'link': 'https://example.org/other/web/1'},
          {'reference': 'other/file#2', 'done': false, 'link': 'file:///etc/passwd'},
        ],
      );
    final host = TestHost();
    final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
    addTearDown(() async {
      boards.dispose();
      await tester.runAsync(source.end);
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: planningView(boards: boards, host: host),
        ),
      ),
    );
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);

    final actions = {for (final action in planningActions(boards, host)) action.label: action};
    expect(actions['Open other/file#2 in browser']?.available, isFalse, reason: 'no HTTPS link');
    final web = actions['Open other/web#1 in browser'];
    expect(web?.available, isTrue);
    web!.run();
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    expect(host.opened, [Uri.parse('https://example.org/other/web/1')]);
  });
}
