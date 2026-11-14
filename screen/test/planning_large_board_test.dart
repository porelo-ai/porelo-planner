import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

const _states = ['Idea', 'Ready', 'Building', 'Review', 'Done'];

/// A board of [count] stories with four issues each, spread over two phases' states.
Map<String, dynamic> _board(int count) => {
  'states': [
    for (final state in _states)
      {
        'name': state,
        'kind': switch (state) {
          'Building' => 'WORKING',
          'Done' => 'CLOSED',
          _ => 'WAITING',
        },
      },
  ],
  'phases': [
    {
      'name': 'Plan',
      'states': ['Idea', 'Ready'],
    },
    {
      'name': 'Work',
      'states': ['Building', 'Review', 'Done'],
    },
  ],
  'stories': [
    for (var number = 1; number <= count; number++)
      {
        'id': 'Story$number',
        'title': 'Story number $number with a title long enough to wrap over two lines of its card',
        'state': _states[number % _states.length],
        'priority': 'High',
        'size': 'M',
        'issues': [
          for (var issue = 1; issue <= 4; issue++)
            {
              'id': 'Issue$number.$issue',
              'repository': 'planner',
              'title': 'Issue $issue of story $number',
              'state': _states[number % _states.length],
            },
        ],
      },
  ],
  'revision': 'r$count',
};

/// The fastest of three tries of [step], so a busy moment of the host counts once at most.
Future<Duration> _fastest(Future<void> Function() step) async {
  Duration? best;
  for (var turn = 0; turn < 3; turn++) {
    final watch = Stopwatch()..start();
    await step();
    watch.stop();
    if (best == null || watch.elapsed < best) best = watch.elapsed;
  }
  return best!;
}

/// The times to open Planning, to return to it after leaving and to fold and unfold a phase, on a board of [count].
Future<({Duration open, Duration back, Duration fold})> _times(WidgetTester tester, int count) async {
  final source = MockPlanningSource()..boards['demo'] = _board(count);
  final provider = PlanningProvider(name: 'local', connected: true, projects: ['demo'], source: source);
  final client = MockPlanningClient(() => [provider], currentProvider: () => 'local');
  final boards = PlanningBoards(client);
  Widget shown(bool planning) => MaterialApp(
    home: Scaffold(body: planning ? planningView(boards: boards) : const SizedBox.shrink()),
  );
  await tester.pumpWidget(shown(true));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
  expect(find.byKey(const Key('planning-fold-phase Work')), findsOneWidget, reason: 'the board of $count stands');
  final open = await _fastest(() async {
    await tester.pumpWidget(shown(false));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await tester.pump();
  });
  final back = await _fastest(() async {
    await tester.pumpWidget(shown(false));
    await tester.pumpWidget(shown(true));
    await tester.pump();
  });
  final fold = await _fastest(() async {
    await tester.tap(find.byKey(const Key('planning-fold-phase Work')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('planning-fold-phase Work')));
    await tester.pump();
  });
  await tester.pumpWidget(const SizedBox.shrink());
  boards.dispose();
  client.dispose();
  await tester.runAsync(source.end);
  return (open: open, back: back, fold: fold);
}

void main() {
  // Harm: Planning holds the window for seconds on a large project, the first time and on every return, and a fold
  // freezes it again; the person takes the window for hung.
  testWidgets('a board of hundreds of stories opens, returns and folds about as fast as a small one', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 800)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    // A first run warms the code up, so neither board pays for it.
    await _times(tester, 35);
    final small = await _times(tester, 35);
    final large = await _times(tester, 360);
    final report = 'small $small, large $large';
    expect(large.open.inMicroseconds, lessThan(small.open.inMicroseconds * 3), reason: 'open: $report');
    expect(large.back.inMicroseconds, lessThan(small.back.inMicroseconds * 3), reason: 'return: $report');
    expect(large.fold.inMicroseconds, lessThan(small.fold.inMicroseconds * 2.75), reason: 'fold: $report');
  });
}
