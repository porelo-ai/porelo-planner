import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/ui/planning_issue_row.dart';

Map<String, dynamic> _issue({Object? waitsOn = const <Map<String, dynamic>>[], bool sayWaits = true}) => {
  'id': 'PL1',
  'repository': 'repo',
  'title': 'Issue PL1',
  'state': 'Backlog',
  'size': 'S',
  'blockedBy': <String>[],
  'waitsOn': ?(sayWaits ? waitsOn : null),
  'parent': null,
  'link': 'https://example.org/issues/PL1',
  'worker': 'AGENT',
};

const _waits = [
  {'reference': 'other-org/done#1', 'done': true, 'link': 'https://github.com/other-org/done/issues/1'},
  {'reference': 'other-org/open#2', 'done': false, 'link': null},
  {'reference': null, 'done': null, 'link': null},
];

Future<void> _row(WidgetTester tester, Map<String, dynamic> issue) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(
      body: PlanningIssueRow(issue: PlanIssue.from(issue), onOpen: () {}),
    ),
  ),
);

void main() {
  // Harm: a missing field from an older planner read as "waits on nothing", so the person takes a held issue as free.
  test('a missing waitsOn is not reported, an empty one is nothing to wait on', () {
    final missing = PlanIssue.from(_issue(sayWaits: false));
    expect(missing.waitsOnReported, isFalse, reason: 'an older planner says nothing');
    expect(PlanIssue.from(_issue(waitsOn: 'none')).waitsOnReported, isFalse, reason: 'a malformed field');
    final empty = PlanIssue.from(_issue());
    expect(empty.waitsOnReported, isTrue);
    expect(empty.waitsOn, isEmpty);
    final waiting = PlanIssue.from(_issue(waitsOn: _waits));
    expect(waiting.waitsOn.map((wait) => (wait.spoken, wait.done)), [
      ('other-org/done#1', true),
      ('other-org/open#2', false),
      ('something not readable', null),
    ]);
    expect(waiting.waitsHolding.map((wait) => wait.spoken), ['other-org/open#2', 'something not readable']);
    expect(PlanIssue.withState(waiting, 'Building').waitsOn, hasLength(3), reason: 'a moved issue keeps its waits');
  });

  // Harm: a row shows an issue free whose blocker in another repository is open or unknown.
  testWidgets('a row names the waits that still hold it and says when they were not reported', (tester) async {
    await _row(tester, _issue(waitsOn: _waits));
    expect(find.text('Waits on other-org/open#2, something not readable'), findsOneWidget);

    await _row(tester, _issue(waitsOn: [_waits.first]));
    expect(find.byKey(const Key('planning-waits PL1')), findsNothing, reason: 'a done wait holds nothing');

    await _row(tester, _issue(sayWaits: false));
    expect(find.text('Waits not reported'), findsOneWidget);
  });

  // Harm: the issue page hides what an issue waits on, or offers a link the planner did not give.
  testWidgets('the issue page lists each wait with its state and a link when it has one', (tester) async {
    final source = MockPlanningSource()
      ..boards['demo'] = {
        'states': [
          {'name': 'Backlog', 'kind': 'WAITING'},
        ],
        'phases': [
          {
            'name': 'All',
            'states': ['Backlog'],
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
            'issues': [_issue(waitsOn: _waits)],
          },
        ],
      };
    final board = PlanningBoard(source, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    final detail = PlanningDetail(board, 'ST1', 'PL1');
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
              builder: (_, _) => PlanningIssueView(
                detail: detail,
                onStory: (_) {},
                onIssue: (_) {},
                onTask: (_) {},
                taskUnavailable: (task) => 'unknown $task',
                openLink: (_) async {},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pumpAndSettle();

    expect(find.text('Waits on'), findsOneWidget);
    for (final (spoken, state) in [
      ('other-org/done#1', 'done'),
      ('other-org/open#2', 'open'),
      ('something not readable', 'not read'),
    ]) {
      final wait = find.byKey(Key('planning-waits-on $spoken'));
      expect(wait, findsOneWidget, reason: spoken);
      expect(
        find.descendant(
          of: find.ancestor(of: wait, matching: find.byType(Wrap)).first,
          matching: find.text(state),
        ),
        findsOneWidget,
        reason: 'the state of $spoken',
      );
    }
    expect(find.byType(PlanningWebLink), findsNWidgets(2), reason: 'the issue\'s own link and the done wait\'s');
  });
}
