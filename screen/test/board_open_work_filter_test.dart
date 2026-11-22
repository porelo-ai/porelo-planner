import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

/// A story's or the board's figures; [states] by name, the rest as the planner counts them.
Map<String, dynamic> _open({
  Map<String, int> states = const {},
  int blocked = 0,
  int left = 0,
  int review = 0,
  Object? minutes,
  int unestimated = 0,
}) => {
  'states': [
    for (final name in _workflow.map((state) => state['name'] as String)) {'name': name, 'issues': states[name] ?? 0},
  ],
  'blocked': blocked,
  'sizes': [
    if (left > 0) {'name': 'S', 'issues': left},
  ],
  'unsized': 0,
  'left': left,
  'minutes': minutes,
  'unestimated': unestimated,
  'review': review,
};

/// A workflow whose review state is named by its work rule alone, so nothing may know it by the name Review.
const _workflow = [
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Implementing', 'kind': 'WORKING'},
  {'name': 'Approval', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
];

Map<String, dynamic> _story(String id, String state, Map<String, dynamic>? open) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'priority': 'High',
  'issues': [
    {'id': '$id-1', 'repository': 'repo', 'title': 'An issue', 'state': state, 'blockedBy': <String>[]},
  ],
  'open': ?open,
};

/// Stories with a blocked backlog issue, with review only, with an issue being built, with no figures, and with an
/// issue whose minutes nobody knows.
Map<String, dynamic> _board() => {
  'revision': 'r1',
  'states': _workflow,
  // A phase per state, so every card stands in view side by side.
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
      'done': 'Approval',
      'per': 'ISSUE',
      'modes': <String>[],
      'order': '',
    },
  ],
  'faults': <Object>[],
  'repositories': ['repo'],
  'stories': [
    _story('SB', 'Backlog', _open(blocked: 1, left: 1, minutes: 30)),
    _story('SR', 'Approval', _open(states: {'Approval': 2}, review: 2)),
    _story('SI', 'Implementing', _open(states: {'Implementing': 1}, left: 1, minutes: 90)),
    _story('SN', 'Backlog', null),
    _story('SU', 'Backlog', _open(states: {'Backlog': 1}, left: 1)),
  ],
  'open': _open(states: {'Implementing': 1, 'Approval': 2}, blocked: 1, left: 3, review: 2, minutes: 120),
};

late PlanningBoards _boards;

Future<(MockPlanningSource, TestHost)> _show(WidgetTester tester, {PlanningPlace place = const PlanningPlace()}) async {
  tester.view
    ..physicalSize = const Size(1600, 1400)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final source = MockPlanningSource()..boards['demo'] = _board();
  final host = TestHost()..go(place);
  host.history.clear();
  final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
  _boards = boards;
  addTearDown(() async {
    boards.dispose();
    await source.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: host),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return (source, host);
}

String _text(WidgetTester tester, String key) {
  final widget = tester.widget(find.byKey(Key(key)));
  return switch (widget) {
    Text(:final data?) => data,
    Text(:final textSpan?) => textSpan.toPlainText(),
    _ => throw StateError('$key is no text'),
  };
}

void main() {
  final snapshot = PlanningSnapshot.from(_board());
  final filter = PlanningBoardFilter(
    snapshot.states,
    planningReviewStates(snapshot),
    storyReview: planningStoryReviewState(snapshot),
  );
  final stories = {for (final story in snapshot.stories) story.id: story};

  // Harm: a story whose only open work is blocked missing from Blocked, or counted under Backlog, so the person
  // misses blocked work or looks for it in the wrong place.
  test('a blocked backlog issue counts under Blocked and not under its state', () {
    expect(filter.matches(stories['SB']!, PlanningFilter.blocked), isTrue, reason: 'its issue is blocked');
    expect(filter.matches(stories['SB']!, PlanningFilter.backlog), isFalse, reason: 'it counts as blocked only');
    expect(filter.matches(stories['SB']!, PlanningFilter.openWork), isTrue, reason: 'it is still left to build');
  });

  // Harm: Needs your review keeping the wrong stories on a workflow whose review state is not named Review.
  // Harm: a story counted as needing review for issues in review while the story itself is not, though only a
  // story as a whole can be tested, so the count promises reviews that cannot be made.
  test('Needs your review keeps the stories in the state the work rules end in, never by the name Review', () {
    expect(filter.review, {'Approval'}, reason: 'the state the rule\'s work ends in');
    expect(filter.storyReview, 'Approval', reason: 'the story waits there for review');
    expect(filter.matches(stories['SR']!, PlanningFilter.review), isTrue, reason: 'the story stands in Approval');
    final unread = PlanStory.from(_story('SY', 'Approval', null));
    expect(filter.keeps(unread, PlanningFilter.review), isTrue, reason: 'its state alone says it waits for review');
    expect(filter.counts([unread])[PlanningFilter.review]?.stories, 1, reason: 'counted without its figures');
    expect(filter.counts([unread])[PlanningFilter.review]?.partial, isFalse, reason: 'the count is exact');
    final ruleless = PlanningBoardFilter(snapshot.states, const {});
    expect(ruleless.usable(PlanningFilter.review), isFalse, reason: 'a workflow without work has no review state');
    final building = PlanStory.from(_story('SX', 'Implementing', _open(states: {'Approval': 2}, review: 2)));
    expect(
      filter.matches(building, PlanningFilter.review),
      isFalse,
      reason: 'issues in review under a story still being built are no story to review',
    );
    expect(filter.matches(stories['SR']!, PlanningFilter.openWork), isFalse, reason: 'review is not left to build');
    expect(filter.matches(stories['SI']!, PlanningFilter.review), isFalse, reason: 'nothing waits for review');
    expect(filter.matches(stories['SI']!, PlanningFilter.implementing), isTrue, reason: 'its issue is being built');
  });

  // Harm: a story whose figures are unknown counted as having none, or hidden from Open work, so its work is lost.
  test('a story without figures is in no count, marks the counts partial and stays under Open work alone', () {
    final unknown = stories['SN']!;
    expect(filter.matches(unknown, PlanningFilter.openWork), isNull, reason: 'its figures are unknown');
    expect(filter.keeps(unknown, PlanningFilter.openWork), isTrue, reason: 'Open work never hides it');
    expect(filter.keeps(unknown, PlanningFilter.review), isFalse, reason: 'it is in no other filter');
    final counts = filter.counts(snapshot.stories);
    expect(counts[PlanningFilter.openWork]?.stories, 3, reason: 'SB, SI and SU, not the unknown one');
    expect(counts[PlanningFilter.openWork]?.partial, isTrue, reason: 'one story could not be told');
    expect(counts[PlanningFilter.blocked]?.stories, 1, reason: 'SB alone');
  });

  // Harm: a filter for a state group the workflow lacks kept, showing an empty board as if nothing matched.
  test('a state group the workflow has no state for cannot be used', () {
    expect(filter.usable(PlanningFilter.estimated), isFalse, reason: 'no state is estimated');
    expect(filter.usable(PlanningFilter.backlog), isTrue, reason: 'Backlog is one');
    expect(filter.filters, isNot(contains(PlanningFilter.estimated)), reason: 'the row shows only usable filters');
  });

  // Harm: an unknown figure shown as 0, which reads as nothing left or nothing waiting.
  test('minutes not known read unknown, never as no time', () {
    expect(planningMinutes(null), isNot(contains('0')), reason: 'unknown');
    expect(planningMinutes(0), contains('0'), reason: 'known to be none');
    expect(
      planningStateKind(PlanState.from({'name': 'Waiting on legal', 'kind': 'WAITING'}), const {}),
      PlanningStateKind.other,
      reason: 'a state the screen cannot place gets the neutral mark',
    );
  });

  // Harm: the window's Needs you opening a board that still shows every story, or a tap on a filter lost from the
  // window's history, so Back and a restart do not keep it.
  testWidgets('the place\'s filter keeps only matching cards, and a tap reports the filter through go', (tester) async {
    final (_, host) = await _show(tester, place: const PlanningPlace(filter: PlanningFilter.review));

    expect(find.byKey(const Key('planning-card SR')), findsOneWidget, reason: 'it waits for review');
    for (final other in ['SB', 'SI', 'SN', 'SU']) {
      expect(find.byKey(Key('planning-card $other')), findsNothing, reason: '$other has nothing to review');
    }
    expect(
      _text(tester, 'planning-filter-count review'),
      startsWith('1'),
      reason: 'one story, not the two issues in review, at least since SN is not known',
    );

    await tester.tap(find.byKey(const Key('planning-filter blocked')));
    await tester.pumpAndSettle();

    expect(host.history.last.filter, PlanningFilter.blocked, reason: 'the window holds the new filter');
    expect(find.byKey(const Key('planning-card SB')), findsOneWidget, reason: 'its issue is blocked');
    expect(find.byKey(const Key('planning-card SR')), findsNothing, reason: 'nothing of it is blocked');

    await tester.tap(find.byKey(const Key('planning-filter all')));
    await tester.pumpAndSettle();
    expect(host.history.last.filter, isNull, reason: 'All clears the filter');
    expect(find.byKey(const Key('planning-card SN')), findsOneWidget, reason: 'every story again');
  });

  // Harm: a story opened from a filtered board returning to the bare board, losing what the person chose.
  testWidgets('a story opened from a filtered board keeps the filter for the way back', (tester) async {
    final (_, host) = await _show(tester, place: const PlanningPlace(filter: PlanningFilter.openWork));

    await tester.tap(find.byKey(const Key('planning-story SI')));
    await tester.pumpAndSettle();
    expect(host.place.story, 'SI', reason: 'the story is open');
    expect(host.place.filter, PlanningFilter.openWork, reason: 'the filter stays on the story place');

    await tester.tap(find.byKey(const Key('planning-crumb 0')));
    await tester.pumpAndSettle();
    expect(host.place.story, isNull, reason: 'back on the board');
    expect(host.place.filter, PlanningFilter.openWork, reason: 'the board it came from, filtered');
  });

  // Harm: a filter the screen cannot use kept by the window, so the board stays empty and the window's place lies.
  testWidgets('a filter the workflow cannot use falls back to none, reported through go', (tester) async {
    final (_, host) = await _show(tester, place: const PlanningPlace(filter: PlanningFilter.estimated));

    expect(host.history, isNotEmpty, reason: 'the screen reported the fallback');
    expect(host.history.last.filter, isNull, reason: 'no filter');
    expect(find.byKey(const Key('planning-card SB')), findsOneWidget, reason: 'every story is shown');
  });

  // Harm: the last figures shown as current once the board stopped answering, so a count reads as what is open now.
  testWidgets('a stale board tells no figure as known and filters no story by them', (tester) async {
    final (source, _) = await _show(tester, place: const PlanningPlace(filter: PlanningFilter.review));
    expect(_text(tester, 'planning-summary review'), contains('1'), reason: 'SR stands in review');
    expect(_text(tester, 'planning-card-left SR'), isNotEmpty, reason: 'a card tells its figures');

    source.fail(StateError('gone'));
    await tester.pumpAndSettle();

    for (final key in ['review', 'left', 'blocked']) {
      expect(_text(tester, 'planning-summary $key'), isNot(matches(RegExp(r'\d'))), reason: '$key is not known now');
    }
    expect(_text(tester, 'planning-summary time'), isNot(matches(RegExp(r'\d'))), reason: 'time left not known');
    expect(_text(tester, 'planning-filter-count review'), isNot(matches(RegExp(r'\d'))), reason: 'no count');
    expect(find.byKey(const Key('planning-card-left SR')), findsNothing, reason: 'the card tells no figure');
    expect(find.byKey(const Key('planning-card SB')), findsOneWidget, reason: 'no story is hidden by old figures');
  });

  // Harm: a card or the summary reading 0 or no time for a story or board whose minutes nobody knows.
  testWidgets('unknown minutes and a story without figures read unknown on the board', (tester) async {
    await _show(tester);
    expect(find.byKey(const Key('planning-card-left SN')), findsNothing, reason: 'SN has no figures');
    expect(_text(tester, 'planning-card-open SN'), isNot(matches(RegExp(r'\d'))), reason: 'nothing told as known');
    expect(_text(tester, 'planning-card-time SR'), isEmpty, reason: 'nothing left to build, so no time');
    expect(_text(tester, 'planning-card-left SB'), startsWith('1'), reason: 'one issue left');
    expect(_text(tester, 'planning-card-time SU'), isNot(matches(RegExp(r'\d'))), reason: 'its minutes are unknown');
  });

  // Harm: a story whose issue waits in a state no work rule measures counted as being built, or a working state the
  // screen does not know by name left out of Implementing.
  test('being built follows the working kind alone, never a state\'s name', () {
    expect(
      planningStateKind(PlanState.from({'name': 'Rework', 'kind': 'WAITING'}), const {}),
      isNot(PlanningStateKind.implementing),
      reason: 'a waiting state named Rework',
    );
    expect(
      planningStateKind(PlanState.from({'name': 'Polishing', 'kind': 'WORKING'}), const {}),
      PlanningStateKind.implementing,
      reason: 'a working state of any name',
    );
  });

  // Harm: a count once known turns into "?" while a move is out, and the summary goes blank on every move.
  testWidgets('a move under way keeps the figures known before it', (tester) async {
    final (source, _) = await _show(tester);
    expect(_text(tester, 'planning-summary left'), contains('3'), reason: 'the figures as read');

    source.moveHeld = Completer<void>();
    unawaited(_boards.selected!.moveIssue('SU-1', 'Approval'));
    await tester.pump();
    await tester.pump();

    expect(_text(tester, 'planning-summary left'), contains('3'), reason: 'the figures known before the move');
    source.moveHeld!.complete();
    await tester.pumpAndSettle();
  });
}
