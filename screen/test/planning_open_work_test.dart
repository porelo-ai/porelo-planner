import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/ui/open_work_rows.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

/// A workflow whose review state is named by its work rule alone, with a state the screen cannot place.
const _workflow = [
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Implementing', 'kind': 'WORKING'},
  {'name': 'Approval', 'kind': 'WAITING'},
  {'name': 'Waiting on legal', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
];

/// A story's figures; [states] by name, the rest as the planner counts them.
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

Map<String, dynamic> _story(String id, Map<String, dynamic>? open, {String priority = 'High', String text = ''}) => {
  'id': id,
  'title': 'Story $id',
  'state': 'Backlog',
  'priority': priority,
  'description': text,
  'issues': [
    {'id': '$id-1', 'repository': 'repo', 'title': 'An issue', 'state': 'Backlog', 'blockedBy': <String>[]},
  ],
  'open': ?open,
};

/// A small story, a big one, one without figures, one whose minutes nobody knows, one with nothing left but review,
/// and a finished one, out of their order by what is left.
Map<String, dynamic> _board() => {
  'revision': 'r1',
  'states': _workflow,
  'phases': [
    {'name': 'All', 'states': _workflow.map((state) => state['name']).toList()},
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
  'outside': <Object>[],
  'stories': [
    _story(
      'SMALL',
      _open(states: {'Backlog': 1}, left: 1, minutes: 20),
      text: '# Small\n\n**What must be true.** A [small](https://example.org) _thing_ is done.\n\nThe second part.',
    ),
    _story('NONE', null, priority: 'Highest'),
    _story(
      'BIG',
      _open(
        states: {'Implementing': 2, 'Approval': 1, 'Waiting on legal': 1, 'Finished': 4},
        blocked: 1,
        left: 4,
        review: 1,
        minutes: 200,
      ),
      priority: 'Medium',
    ),
    _story('NOTIME', _open(states: {'Backlog': 2}, left: 2), priority: 'Highest'),
    _story('REVIEW', _open(states: {'Approval': 2}, review: 2)),
    _story('DONE', _open(states: {'Finished': 3})),
  ],
  'open': _open(states: {'Backlog': 3, 'Implementing': 2}, blocked: 1, left: 7, review: 3, minutes: 220),
};

/// A board of [count] stories, each with something left, the first the least.
Map<String, dynamic> _many(int count) => _board()
  ..['stories'] = [
    for (var n = 1; n <= count; n++) _story('MANY$n', _open(states: {'Backlog': 1}, left: 1, minutes: n * 10)),
  ];

/// The planner's reads of stories, each held until it is let go, counting how many run at once.
class _HeldReads {
  final List<Completer<void>> waiting = [];
  int running = 0;
  int most = 0;
  int reads = 0;

  Future<PlanStoryDetail> read(String project, String story) async {
    reads++;
    running++;
    most = max(most, running);
    final gate = Completer<void>();
    waiting.add(gate);
    await gate.future;
    running--;
    return PlanStoryDetail.from({
      'story': {
        'id': story,
        'title': 'Story $story',
        'state': 'Backlog',
        'description': 'Story $story holds this.',
        'descriptionFormat': 'MARKDOWN',
        'issues': <Object>[],
      },
      'revision': 'r1',
    });
  }

  /// Lets every read waiting now go, but the last [kept].
  Future<void> letGo(WidgetTester tester, {int kept = 0}) async {
    final going = waiting.sublist(0, waiting.length - kept);
    waiting.removeRange(0, going.length);
    for (final gate in going) {
      gate.complete();
    }
    await tester.pump();
    await tester.pump();
  }
}

Future<(MockPlanningSource, TestHost)> _show(WidgetTester tester, {void Function(MockPlanningSource)? setUp}) async {
  tester.view
    ..physicalSize = const Size(1600, 1400)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final source = MockPlanningSource()..boards['demo'] = _board();
  setUp?.call(source);
  final host = TestHost()..go(const PlanningPlace(view: PlanningView.openWork));
  host.history.clear();
  final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
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

String _text(WidgetTester tester, String key) => switch (tester.widget(find.byKey(Key(key)))) {
  Text(:final data?) => data,
  Text(:final textSpan?) => textSpan.toPlainText(),
  _ => throw StateError('$key is no text'),
};

/// The listed stories in the order shown, read from the rows' keys.
List<String> _listed(WidgetTester tester) => [
  for (final element in find.byWidgetPredicate((widget) => widget is InkWell).evaluate())
    if (element.widget.key case ValueKey<String>(:final value) when value.startsWith('planning-open-work-row '))
      value.substring('planning-open-work-row '.length),
];

void main() {
  final snapshot = PlanningSnapshot.from(_board());
  final review = planningReviewStates(snapshot);

  // Harm: the story with the most left buried below small ones, or a story whose time nobody knows sorted as if it
  // had none left, so the person plans by a wrong order.
  test('the stories sort by the minutes left, the most first, and an unknown time sorts last', () {
    final list = planningOpenWork(snapshot.stories, snapshot.states, review, known: true);
    expect(list.rows.map((row) => row.story.id), [
      'BIG',
      'SMALL',
      'NOTIME',
      'NONE',
      'REVIEW',
    ], reason: 'by minutes, then the unknown ones by issues left and priority');
    expect(list.rows[3].open, isNull, reason: 'a story without figures reads unknown, never 0');
    expect(list.rows[2].minutes, isNull, reason: 'minutes nobody knows stay unknown');
  });

  // Harm: finished stories crowding Open work, or a story whose open work is unknown dropped as if it were done.
  test('a story with nothing left to build is left out and counted, one with unknown figures or review stays', () {
    final list = planningOpenWork(snapshot.stories, snapshot.states, review, known: true);
    final ids = list.rows.map((row) => row.story.id);
    expect(ids, isNot(contains('DONE')), reason: 'nothing of it is left');
    expect(ids, contains('REVIEW'), reason: 'its issues wait for the person');
    expect(list.finished, 1, reason: 'DONE is said to be left out');
    expect(ids, contains('NONE'), reason: 'it may still hold work');
  });

  // Harm: the last figures shown as current on a stale board, or stories hidden by them.
  test('a stale board tells no figure and leaves no story out', () {
    final list = planningOpenWork(snapshot.stories, snapshot.states, review, known: false);
    expect(list.rows, hasLength(6), reason: 'every story, since none is known to be finished');
    expect(list.rows.where((row) => row.open != null || row.now.isNotEmpty), isEmpty, reason: 'no figure known');
    expect(list.finished, 0, reason: 'none is known to be finished');
  });

  // Harm: blocked or review work missing from Now, a finished issue shown as open, or a state the screen cannot place
  // dropped.
  test('Now holds each open kind of state with its issues, blocked ones included and finished ones not', () {
    final big = planningOpenWork(snapshot.stories, snapshot.states, review, known: true).rows.first;
    expect(big.now, [
      (PlanningStateKind.implementing, 2),
      (PlanningStateKind.blocked, 1),
      (PlanningStateKind.review, 1),
      (PlanningStateKind.other, 1),
    ]);
  });

  // Harm: a description's raw markup or a later paragraph shown as what the story holds.
  test('what a story holds is its first paragraph without markup', () {
    expect(
      planningFirstParagraph(
        '# Title\n\n**What must be true.** A [link](https://x.org) and `code`.\n\nMore.',
        'MARKDOWN',
      ),
      'What must be true. A link and code.',
    );
    expect(planningFirstParagraph('Title\n=====\nThe first\nline.\n\nMore.', 'MARKDOWN'), 'The first line.');
    expect(planningFirstParagraph('Title\n---\nThe first line.', 'MARKDOWN'), 'The first line.', reason: 'dashes');
    expect(planningFirstParagraph('- one\n- two\n\nMore.', 'MARKDOWN'), 'one two', reason: 'list markers stripped');
    expect(planningFirstParagraph('**kept** as it is\n\nMore.', 'PLAIN'), '**kept** as it is', reason: 'plain text');
    expect(planningFirstParagraph('', 'MARKDOWN'), isEmpty, reason: 'nothing to show');
  });

  // Harm: Open work not shown where the window asks for it, a tap that bypasses the window's history, or a way back
  // that lands on the board instead of Open work.
  testWidgets('Open work lists the stories by what is left, and a story opened from it returns to it', (tester) async {
    final (_, host) = await _show(tester);

    expect(_listed(tester), [
      'BIG',
      'SMALL',
      'NOTIME',
      'NONE',
      'REVIEW',
    ], reason: 'the rows in the order by what is left');
    expect(_text(tester, 'planning-open-work-left NONE'), isNot(matches(RegExp(r'\d'))), reason: 'unknown, not 0');
    expect(_text(tester, 'planning-open-work-open NONE'), isNot(matches(RegExp(r'\d'))), reason: 'unknown, not 0');
    expect(_text(tester, 'planning-open-work-left NOTIME'), isNot(matches(RegExp(r'\d'))), reason: 'unknown minutes');
    expect(_text(tester, 'planning-open-work-open BIG'), startsWith('4'), reason: 'four issues left');
    expect(
      _text(tester, 'planning-open-work-holds SMALL'),
      'What must be true. A small thing is done.',
      reason: 'the first paragraph read from the story\'s own page',
    );

    await tester.tap(find.byKey(const Key('planning-open-work-row SMALL')));
    await tester.pumpAndSettle();
    expect(host.history.last.story, 'SMALL', reason: 'the story is opened through the window');
    expect(host.history.last.view, PlanningView.openWork, reason: 'the story remembers Open work for the way back');

    await tester.tap(find.byKey(const Key('planning-crumb 0')));
    await tester.pumpAndSettle();
    expect(host.place.story, isNull, reason: 'back from the story');
    expect(host.place.view, PlanningView.openWork, reason: 'back on Open work');
    expect(find.byKey(const Key('planning-open-work-row SMALL')), findsOneWidget, reason: 'Open work is shown');
  });

  // Harm: the last figures shown as current once the board stopped answering, so a row reads as what is left now.
  testWidgets('a stale board shows no figure of a story as known', (tester) async {
    final (source, _) = await _show(tester);
    expect(_text(tester, 'planning-open-work-left BIG'), contains('3 h'), reason: 'the figures as read');

    source.fail(StateError('gone'));
    await tester.pumpAndSettle();

    for (final id in ['BIG', 'SMALL', 'NOTIME', 'NONE', 'REVIEW', 'DONE']) {
      expect(_text(tester, 'planning-open-work-left $id'), isNot(matches(RegExp(r'\d'))), reason: '$id not known');
      expect(_text(tester, 'planning-open-work-open $id'), isNot(matches(RegExp(r'\d'))), reason: '$id not known');
    }
  });

  // Harm: Open work without the filter and sort the window's other lists have, so a long list cannot be narrowed or
  // reordered, or a choice lost on the way back from a story.
  testWidgets('the filter narrows Open work and the sort reorders it, both kept on the way back', (tester) async {
    final (_, host) = await _show(tester);
    expect(_text(tester, 'planning-open-work-sort-label'), 'Left', reason: 'it starts by what is left');

    await tester.tap(find.byKey(const Key('planning-open-work-search-open')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('planning-open-work-search')), 'highest');
    await tester.pumpAndSettle();
    expect(_listed(tester), [
      'NOTIME',
      'NONE',
    ], reason: 'the stories of priority Highest, in the order by what is left');
    expect(_text(tester, 'planning-open-work-summary'), contains('2 match the filter'));

    await tester.tap(find.byKey(const Key('planning-open-work-sort')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('planning-open-work-sort-to story')).last);
    await tester.pumpAndSettle();
    expect(_listed(tester), ['NONE', 'NOTIME'], reason: 'by story');
    expect(_text(tester, 'planning-open-work-sort-label'), 'Story');

    await tester.tap(find.byKey(const Key('planning-open-work-row NONE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('planning-crumb 0')));
    await tester.pumpAndSettle();
    expect(_listed(tester), ['NONE', 'NOTIME'], reason: 'the filter and the sort kept under the story');
    expect(_text(tester, 'planning-open-work-sort-label'), 'Story');

    // The board in its place builds Open work anew on the way back.
    final place = host.place;
    host.go(PlanningPlace(project: place.project, source: place.source, view: PlanningView.board));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('planning-open-work')), findsNothing, reason: 'the board stands in its place');
    host.go(PlanningPlace(project: place.project, source: place.source, view: PlanningView.openWork));
    await tester.pumpAndSettle();
    expect(_listed(tester), ['NONE', 'NOTIME'], reason: 'the filter and the sort kept with the project');
    expect(_text(tester, 'planning-open-work-sort-label'), 'Story');

    await tester.enterText(find.byKey(const Key('planning-open-work-search')), 'no such story');
    await tester.pumpAndSettle();
    expect(_listed(tester), isEmpty);
    expect(find.text('Nothing matches.'), findsOneWidget, reason: 'an empty match is said, not shown as no work');
    await tester.tap(find.byKey(const Key('planning-open-work-search-clear')));
    await tester.pumpAndSettle();
    expect(_listed(tester), ['BIG', 'NONE', 'NOTIME', 'REVIEW', 'SMALL'], reason: 'every story again, by story');
    expect(find.byKey(const Key('planning-open-work-search')), findsNothing, reason: 'the filter is closed');
  });

  // Harm: a planner flooded by forty reads at once when Open work opens.
  testWidgets('the descriptions are read at most eight at a time, all forty of them', (tester) async {
    final held = _HeldReads();
    await _show(
      tester,
      setUp: (source) => source
        ..boards['demo'] = _many(45)
        ..readStory = held.read,
    );
    for (var round = 0; round < 20 && held.waiting.isNotEmpty; round++) {
      await held.letGo(tester);
    }
    await tester.pumpAndSettle();
    expect(held.most, 8, reason: 'eight at once and never more');
    expect(held.reads, 40, reason: 'the first forty rows are read, the rest not');
    expect(_text(tester, 'planning-open-work-holds MANY45'), 'Story MANY45 holds this.', reason: 'the most left first');
  });

  // Harm: the column filling row by row, slowly enough to watch, so the person reads a list that is still moving.
  testWidgets('no row shows what it holds while another is still read', (tester) async {
    final held = _HeldReads();
    await _show(
      tester,
      setUp: (source) => source
        ..boards['demo'] = _many(10)
        ..readStory = held.read,
    );
    await held.letGo(tester);
    expect(held.waiting, hasLength(8), reason: 'after the first, eight at once');
    await held.letGo(tester, kept: 1);
    await held.letGo(tester, kept: 1);
    expect(held.waiting, hasLength(1), reason: 'one read still under way');
    for (var n = 1; n <= 10; n++) {
      expect(_text(tester, 'planning-open-work-holds MANY$n'), isEmpty, reason: 'MANY$n waits for the others');
    }
    expect(find.text('What it holds · Reading…'), findsOneWidget, reason: 'the column says it is read');

    await held.letGo(tester);
    await tester.pumpAndSettle();
    for (var n = 1; n <= 10; n++) {
      expect(_text(tester, 'planning-open-work-holds MANY$n'), 'Story MANY$n holds this.');
    }
    expect(find.text('What it holds'), findsOneWidget, reason: 'read whole');
  });

  // Harm: a planner that refuses every read asked once per story, each waiting its time, or a refusal shown as what
  // the story holds.
  testWidgets('a refused description read shows Not read and asks no more until the board changes', (tester) async {
    var reads = 0;
    await _show(
      tester,
      setUp: (source) => source.readStory = (project, story) async {
        reads++;
        throw StateError('refused');
      },
    );
    expect(reads, 1, reason: 'one refusal stops the reads');
    expect(find.text('Not read'), findsOneWidget, reason: 'the refused story says so');
  });

  // Harm: a planner that refused once asked again on every frame, or never again after the board changed.
  testWidgets('a refused read is asked again once, after the board changes', (tester) async {
    final asked = <String>[];
    final (source, _) = await _show(
      tester,
      setUp: (source) => source.readStory = (project, story) async {
        asked.add(story);
        throw StateError('refused');
      },
    );
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(asked, hasLength(1), reason: 'the same board asks no more');

    source.push('demo', {..._board(), 'revision': 'r2'});
    await tester.pumpAndSettle();

    expect(asked, [asked.first, asked.first], reason: 'the refused story read once again on the changed board');
  });

  // Harm: a read that never answers holds the column at Reading for good, so no description shows again.
  testWidgets('a read that never answers counts as refused after its time', (tester) async {
    await _show(tester, setUp: (source) => source.readStory = (project, story) => Completer<PlanStoryDetail>().future);
    expect(find.textContaining('Reading'), findsOneWidget, reason: 'the read is under way');

    await tester.pump(const Duration(seconds: 31));
    await tester.pumpAndSettle();

    expect(find.textContaining('Reading'), findsNothing, reason: 'the hung read gave up');
    expect(find.text('Not read'), findsOneWidget, reason: 'the hung story says so');
  });
}
