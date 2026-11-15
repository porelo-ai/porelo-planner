import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// The marks a window keeps, as the window owns them.
class _Seen extends ChangeNotifier implements PlanningSeen {
  final Set<PlanningPutAway> rows = {};
  final Set<PlanningStoryEnd> ends = {};

  @override
  bool putAwaySeen(PlanningPutAway row) => rows.contains(row);

  @override
  bool endSeen(PlanningStoryEnd end) => ends.contains(end);

  void mark(PlanningNeedsYou row) {
    if (row.attention.putAwayOn(row.machine) case final kept?) rows.add(kept);
    if (row.attention.endOn(row.machine) case final end?) ends.add(end);
    notifyListeners();
  }

  void unmark() {
    rows.clear();
    ends.clear();
    notifyListeners();
  }
}

/// A board whose one story, Held, holds a stopped run: its issue needs the person, and with [endedAt] its task ended
/// unfinished at that time.
Map<String, dynamic> _board({String? endedAt}) => {
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Building'],
    },
  ],
  'stories': [
    {
      'id': 'Held',
      'title': 'A held story',
      'state': 'Building',
      'issues': [
        {'id': 'Stopped', 'repository': 'repo', 'state': 'Building', 'needsYou': true, 'attention': 'STOPPED'},
      ],
      'work': {
        'run': 'run-1',
        'atWork': false,
        'since': '2026-01-01T00:00:00Z',
        'tasks': [
          {
            'task': 'build',
            'containerId': 'box-build',
            'machine': 'local',
            'repository': 'repo',
            'issues': ['Stopped'],
            'state': 'Building',
            'done': 'Review',
            'mode': 'UNATTENDED',
            'agent': 'an-agent',
            'model': 'a-model',
            'role': 'MAIN',
            'since': '2026-01-01T00:00:00Z',
            'atWork': false,
            if (endedAt != null) 'ended': {'at': endedAt, 'finished': false, 'why': 'It was stopped', 'answer': ''},
          },
        ],
        'issues': [
          {'issue': 'Stopped', 'reason': 'STOPPED', 'needsYou': true},
        ],
      },
    },
  ],
  'revision': 'r1',
};

void main() {
  Future<(PlanningBoards, MockPlanningSource, _Seen)> show(WidgetTester tester, Map<String, dynamic> board) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = MockPlanningSource()..boards['checkout'] = board;
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final seen = _Seen();
    final boards = PlanningBoards(client, seen: seen);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      seen.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    return (boards, backend, seen);
  }

  // Harm: a person sent to a story that needs nothing, by a card that keeps saying "needs you" and a Planning count
  // that never clears after the person marked the stopped run's row seen.
  testWidgets('a stopped run whose row the window marks seen leaves the card and the count until it is unmarked', (
    tester,
  ) async {
    final (boards, _, seen) = await show(tester, _board());
    final warning = find.byKey(const Key('planning-warning Held'));
    expect(warning, findsOneWidget, reason: 'the stopped run needs the person at first');
    expect(boards.needsYou, hasLength(1), reason: 'the count holds the stopped issue at first');

    seen.mark(boards.needsYou.single);
    await settle(tester);
    expect(warning, findsNothing, reason: 'the card no longer says the story needs the person');
    expect(boards.needsYou, isEmpty, reason: 'the count drops by the row marked seen');

    seen.unmark();
    await settle(tester);
    expect(warning, findsOneWidget, reason: 'a mark taken back shows the row again');
    expect(boards.needsYou, hasLength(1), reason: 'and counts it again');
  });

  // Harm: a count that never clears after the person saw how a story's run ended, or a later end hidden by an older
  // mark.
  testWidgets('a story end the window marks seen leaves the count while it ended at that time', (tester) async {
    final (boards, backend, seen) = await show(tester, _board(endedAt: '2026-01-01T01:00:00Z'));
    List<String> rows() => [for (final row in boards.needsYou) row.id];
    expect(rows(), hasLength(2), reason: 'the run\'s end and the stopped issue need the person');

    for (final row in boards.needsYou.toList()) {
      seen.mark(row);
    }
    await settle(tester);
    expect(rows(), isEmpty, reason: 'both rows marked seen leave the count');

    backend.push('checkout', _board(endedAt: '2026-01-01T02:00:00Z'));
    await settle(tester);
    expect(rows(), hasLength(2), reason: 'a later end of the story, and of its issue\'s task, is seen anew');
  });

  // Harm: a person sent to a reading that needs nothing, by an unfolded card that keeps saying its reading stopped
  // and needs them after the person marked that row seen.
  testWidgets('a stopped reading the window marks seen leaves the unfolded card', (tester) async {
    final board = _board();
    final story = (board['stories'] as List).single as Map<String, dynamic>;
    story
      ..['state'] = 'Building'
      ..['issues'] = [
        {
          'id': 'Read',
          'repository': 'repo',
          'state': 'Building',
          'readingStopped': true,
          'rounds': 2,
          'roundsMost': 2,
          'attention': 'NONE',
        },
      ]
      ..['work'] = {
        'run': 'run-1',
        'atWork': false,
        'tasks': <Map<String, dynamic>>[],
        'issues': [
          {'issue': 'Read', 'reason': 'NONE', 'readingStopped': true, 'rounds': 2, 'roundsMost': 2},
        ],
      };
    final (boards, _, seen) = await show(tester, board);
    final line = find.text('stopped in Reading - needs you');
    expect(line, findsOneWidget, reason: 'the stopped reading needs the person at first');
    expect(boards.needsYou, hasLength(1), reason: 'the count holds the stopped reading at first');

    seen.mark(boards.needsYou.single);
    await settle(tester);
    expect(boards.needsYou, isEmpty, reason: 'the reading marked seen leaves the count');
    expect(line, findsNothing, reason: 'the unfolded card no longer sends the person to the reading');
  });

  /// Held with a stopped reading of its issue Read, and with [end] a task of [endOf] ended unfinished at that time.
  Map<String, dynamic> reading({String? end, String endOf = 'Read'}) {
    final board = _board();
    final story = (board['stories'] as List).single as Map<String, dynamic>;
    story
      ..['issues'] = [
        {'id': 'Read', 'repository': 'repo', 'state': 'Building', 'readingStopped': true, 'attention': 'NONE'},
        if (endOf != 'Read') {'id': endOf, 'repository': 'repo', 'state': 'Building', 'attention': 'NONE'},
      ]
      ..['work'] = {
        'run': 'run-1',
        'atWork': false,
        'tasks': [
          if (end != null)
            {
              'task': 'reader',
              'containerId': 'box-reader',
              'machine': 'local',
              'repository': 'repo',
              'issues': [endOf],
              'state': 'Building',
              'done': 'Review',
              'mode': 'UNATTENDED',
              'agent': 'an-agent',
              'model': 'a-model',
              'role': 'MAIN',
              'since': '2026-01-01T00:00:00Z',
              'atWork': false,
              'ended': {'at': end, 'finished': false, 'why': 'It broke', 'answer': ''},
            },
        ],
        'issues': [
          {'issue': 'Read', 'reason': 'NONE', 'readingStopped': true},
        ],
      };
    return board;
  }

  // Harm: a run's end hidden from the person, because the reading it ended was marked seen before it ended.
  testWidgets('a reading marked seen still counts and shows once a later end of its issue is reported', (tester) async {
    final (boards, backend, seen) = await show(tester, reading());
    seen.mark(boards.needsYou.single);
    await settle(tester);
    expect(boards.needsYou, isEmpty, reason: 'the reading marked seen leaves the count');

    backend.push('checkout', reading(end: '2026-01-01T03:00:00Z'));
    await settle(tester);
    expect(
      [for (final row in boards.needsYou) row.id],
      contains('reading mock/checkout/Read'),
      reason: 'the issue\'s end, reported after the mark, counts again',
    );
    expect(find.text('stopped in Reading - needs you'), findsOneWidget, reason: 'and the card shows it');
  });

  // Harm: a run's end hidden from the person, by a card that a seen reading quiets while the story's end is unseen.
  testWidgets('a card stays loud while the story\'s end is unseen and quiets once every row is seen', (tester) async {
    final (boards, _, seen) = await show(tester, reading(end: '2026-01-01T03:00:00Z', endOf: 'Other'));
    final line = find.text('stopped in Reading - needs you');
    final readingRow = boards.needsYou.singleWhere((row) => row.attention.issue?.id == 'Read');
    final endRow = boards.needsYou.singleWhere((row) => row.attention.issue == null);

    seen.mark(readingRow);
    await settle(tester);
    expect(boards.needsYou.map((row) => row.id), [endRow.id], reason: 'the unseen end still counts');
    expect(line, findsOneWidget, reason: 'the card stays loud while the story\'s end is unseen');

    seen.mark(endRow);
    await settle(tester);
    expect(boards.needsYou, isEmpty, reason: 'every row seen leaves the count');
    expect(line, findsNothing, reason: 'the story\'s end marked seen quiets the card');
  });
}
