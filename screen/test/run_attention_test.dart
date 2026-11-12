import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// A board whose waiting story has one issue whose reading stopped and one whose run failed, beside a running story.
Map<String, dynamic> _board() => {
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
      'state': 'Ready',
      'issues': [
        {
          'id': 'Stopped',
          'repository': 'repo',
          'state': 'Reading',
          'readingStopped': true,
          'rounds': 2,
          'roundsMost': 2,
        },
        {'id': 'Broken', 'repository': 'repo', 'state': 'Implementing'},
      ],
      'work': {
        'atWork': false,
        'tasks': <Map<String, dynamic>>[],
        'issues': [
          {'issue': 'Stopped', 'reason': 'ROUND_LIMIT', 'readingStopped': true, 'rounds': 2, 'roundsMost': 2},
          {'issue': 'Broken', 'reason': 'FAILED'},
        ],
      },
    },
    {
      'id': 'Running',
      'title': 'A running story',
      'state': 'Building',
      'issues': <Map<String, dynamic>>[],
      'work': {'atWork': true, 'tasks': <Map<String, dynamic>>[], 'issues': <Map<String, dynamic>>[]},
    },
  ],
  'revision': 'r1',
};

/// A source whose reading decisions wait until the test lets them answer.
class _HeldResolve extends MockPlanningSource {
  Completer<void>? held;
  int asked = 0;
  @override
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    String revision, {
    String? comment,
    PlanningRework? rework,
  }) async {
    asked++;
    await held?.future;
    return super.resolveIssueReading(project, story, issue, round, choice, revision, comment: comment, rework: rework);
  }
}

void _desktop(WidgetTester tester) {
  tester.view
    ..physicalSize = const Size(1600, 1200)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

void main() {
  group('the reason a start is held', () {
    late MockPlanningSource backend;
    late PlanningBoard board;
    setUp(() async {
      backend = MockPlanningSource()..boards['checkout'] = _board();
      board = PlanningBoard(backend, 'checkout');
      await Future<void>.delayed(Duration.zero);
    });
    tearDown(() async {
      board.dispose();
      await backend.end();
    });

    // Harm: the person cannot find which issue holds the work, or why.
    test('a story whose reading stopped names that issue and its reason', () {
      final hold = board.startHold(board.stories.firstWhere((story) => story.id == 'Held'));
      final issues = hold?.attention?.issues ?? const <PlanningIssueAttention>[];
      expect(
        [for (final issue in issues) (issue.issue, issue.reason, issue.readingStopped)],
        [('Stopped', 'ROUND_LIMIT', true), ('Broken', 'FAILED', false)],
        reason: 'the hold names each issue that needs attention with its reported reason',
      );
      for (final issue in issues) {
        expect(hold!.words, contains(issue.issue), reason: 'the words name the issue ${issue.issue}');
        for (final cause in issue.causes) {
          expect(hold.words, contains(cause), reason: 'the words name why ${issue.issue} needs attention');
        }
      }
      expect(
        board.startUnavailable(board.stories.firstWhere((story) => story.id == 'Held')),
        hold!.words,
        reason: 'the menu shows the same words the hold carries',
      );
    });

    // Harm: a task that declared its work blocked or failed, after a run that finished and pushed, is not raised,
    // so its issue is taken as done and nobody opens its why.
    test('a declared blocked or failed end raises its own reason and no other', () {
      PlanWorkTask task(String role, String? outcome) => PlanWorkTask.from({
        'role': role,
        'issues': ['A'],
        'ended': {
          'at': '2026-01-01T00:00:00Z',
          'finished': true,
          'why': 'A key is missing',
          'answer': 'Findings: none',
          'unpushed': 0,
          'outcome': ?outcome,
        },
      });
      final blocked = task('READER', 'BLOCKED');
      expect(planningEndRaised('BLOCKED', blocked), isTrue, reason: 'the blocked end raises BLOCKED');
      expect(planningEndRaised('INVALID_FINDINGS', blocked), isFalse, reason: 'a blocked reader is no bad reading');
      expect(planningEndRaised('FAILED', blocked), isFalse, reason: 'a blocked end is not a failure');
      expect(
        planningEndRaised('FAILED', task('BUILDER', 'FAILED')),
        isTrue,
        reason: 'a declared failure raises FAILED',
      );
      expect(planningEndRaised('BLOCKED', task('BUILDER', 'DONE')), isFalse, reason: 'a done end raises nothing');
      expect(planningEndRaised('BLOCKED', task('BUILDER', null)), isFalse, reason: 'no outcome raises nothing');
      expect(planningTaskEndReasons, contains('BLOCKED'), reason: "a blocked end's result can be opened");
      final work = PlanWork.from({
        'tasks': [
          {
            'ended': {
              'at': '',
              'finished': true,
              'why': 'A key is missing',
              'answer': '',
              'unpushed': 0,
              'outcome': 'BLOCKED',
            },
          },
        ],
      });
      expect(work.ended?.why, 'A key is missing', reason: 'the story shows the blocked end and its why');
    });

    // Harm: the window's declared outcome is lost on the wire, so the planner judges a blocked end done.
    test('a task end sends a declared outcome unchanged and leaves an undeclared one out', () {
      const declared = PlanningTaskEnded(
        at: '',
        finished: true,
        why: 'Why',
        answer: '',
        unpushed: 0,
        outcome: 'BLOCKED',
      );
      expect(declared.toMap()['outcome'], 'BLOCKED', reason: 'the declared outcome is sent');
      const none = PlanningTaskEnded(at: '', finished: true, why: '', answer: '', unpushed: 0);
      expect(none.toMap().containsKey('outcome'), isFalse, reason: 'a planner without outcomes refuses the field');
    });

    // Harm: a reason is shown as a bare wire name, or as nothing at all.
    test('every reason has words and an unknown one shows its wire name', () {
      const known = [
        'ROUND_LIMIT',
        'FAILED',
        'BLOCKED',
        'UNPUSHED',
        'INVALID_FINDINGS',
        'STOPPED',
        'SAVE_REFUSED',
        'NEXT_STEP_UNAVAILABLE',
      ];
      for (final reason in known) {
        final words = planningReasonWords(reason);
        expect(words.trim(), isNotEmpty, reason: '$reason has words');
        expect(words, isNot(contains(reason)), reason: '$reason is said in words, not by its wire name');
      }
      expect(known.map(planningReasonWords).toSet(), hasLength(known.length), reason: 'each reason reads apart');
      expect(planningReasonWords('A_NEW_REASON'), 'A_NEW_REASON', reason: 'an unknown reason shows its wire name');
      expect(
        const PlanningIssueAttention('Issue', readingStopped: true).causes,
        isNotEmpty,
        reason: 'a stopped reading without a reason still names a cause',
      );
    });

    // Harm: a way on is offered for a reason that no attention resolves, leading the person nowhere.
    test('work running carries no attention', () {
      final hold = board.startHold(board.stories.firstWhere((story) => story.id == 'Running'));
      expect(hold, isNotNull, reason: 'running work holds the start');
      expect(hold!.attention, isNull, reason: 'running work is not attention and offers no way on');
    });
  });

  group('the way on', () {
    late MockPlanningSource backend;
    late PlanningBoards boards;
    late MockPlanningClient client;
    setUp(() {
      backend = MockPlanningSource()..boards['checkout'] = _board();
      client = MockPlanningClient.of(backend, projects: ['checkout']);
      boards = PlanningBoards(client);
    });
    tearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });

    // Harm: a held start leaves the person with a dead end instead of the run that holds it.
    testWidgets('an enabled way on opens the story page, which shows the attention row', (tester) async {
      _desktop(tester);
      final host = TestHost();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: planningView(boards: boards, host: host),
          ),
        ),
      );
      await settle(tester);
      await tester.tap(find.byKey(const Key('planning-menu Held')));
      await settle(tester);
      final start = tester.widget<PopupMenuItem<String>>(find.byKey(const ValueKey<String>('planning-start Held')));
      expect(start.enabled, isFalse, reason: 'a held start stays disabled');
      final wayOn = find.byKey(const ValueKey<String>('planning-start Held way-on'));
      expect(wayOn, findsOneWidget, reason: 'the held start offers its way on');
      expect(tester.widget<PopupMenuItem<String>>(wayOn).enabled, isTrue, reason: 'the way on can be tapped');
      await tester.tap(wayOn);
      await settle(tester);
      expect(host.place.story, 'Held', reason: 'the way on opens the story page');
      expect(host.place.issue, isNull, reason: 'the story page, not an issue page, is opened');
      final row = find.byKey(const Key('planning-story-attention Stopped'));
      expect(row, findsOneWidget, reason: 'the story page shows the stopped reading');
      expect(find.byType(StoryWorkAttentionView), findsNothing, reason: 'a row loads its detail only when opened');
      await tester.ensureVisible(row);
      await tester.tap(row);
      await settle(tester);
      expect(
        find.byKey(const Key('reading-resolve Stopped REVIEW_ANYWAY')),
        findsOneWidget,
        reason: 'the opened row offers the reading decision',
      );
      expect(backend.work.calls.where((call) => call.$1 == 'GetIssue').map((call) => call.$2['issue']), [
        'Stopped',
      ], reason: 'only the opened row read its findings');
    });

    // Harm: a held start runs anyway, starting work over a run that needs attention.
    testWidgets('tapping the disabled start work runs nothing', (tester) async {
      _desktop(tester);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: planningView(boards: boards)),
        ),
      );
      await settle(tester);
      await tester.tap(find.byKey(const Key('planning-menu Held')));
      await settle(tester);
      await tester.tap(find.byKey(const ValueKey<String>('planning-start Held')), warnIfMissed: false);
      await settle(tester);
      expect(
        backend.work.calls.map((call) => call.$1).where((method) => method.contains('StoryWork')),
        isEmpty,
        reason: 'a held start neither proposes nor starts work',
      );
      expect(find.byType(StoryWorkDialog), findsNothing, reason: 'a held start opens no start dialog');
    });

    // Harm: a way on read from a board that changed since the menu opened leads somewhere stale.
    testWidgets('a way on whose attention was settled meanwhile runs nothing', (tester) async {
      _desktop(tester);
      final host = TestHost();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: planningView(boards: boards, host: host),
          ),
        ),
      );
      await settle(tester);
      await tester.tap(find.byKey(const Key('planning-menu Held')));
      await settle(tester);
      final settled = _board();
      final held = (settled['stories'] as List).first as Map<String, dynamic>;
      held['issues'] = [
        {'id': 'Stopped', 'repository': 'repo', 'state': 'Review'},
      ];
      held['work'] = {'atWork': false, 'tasks': <Map<String, dynamic>>[], 'issues': <Map<String, dynamic>>[]};
      settled['revision'] = 'r2';
      backend.push('checkout', settled);
      // The watch was opened outside the test's clock, so its answer arrives on real time.
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await settle(tester);
      expect(
        boards.selected!.startHold(boards.selected!.stories.first),
        isNull,
        reason: 'the board took the settled run before the way on is tapped',
      );
      await tester.tap(find.byKey(const ValueKey<String>('planning-start Held way-on')), warnIfMissed: false);
      await settle(tester);
      expect(host.history, isEmpty, reason: 'the way on is checked again when tapped and leads nowhere stale');
    });

    // Harm: the way on is offered where no attention holds the start.
    testWidgets('running work offers no way on', (tester) async {
      _desktop(tester);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: planningView(boards: boards)),
        ),
      );
      await settle(tester);
      await tester.tap(find.byKey(const Key('planning-menu Running')));
      await settle(tester);
      expect(
        find.byKey(const ValueKey<String>('planning-start Running way-on')),
        findsNothing,
        reason: 'running work has no way on',
      );
    });
  });

  group('one reading decision per board and issue', () {
    late _HeldResolve backend;
    late PlanningBoard board;
    setUp(() {
      backend = _HeldResolve()..boards['checkout'] = _board();
      board = PlanningBoard(backend, 'checkout');
    });
    tearDown(() async {
      board.dispose();
      await backend.end();
    });

    PlanningAttention row() {
      final story = board.stories.firstWhere((story) => story.id == 'Held');
      return PlanningAttention(
        board,
        story,
        issue: PlanningBoards.attentionIssues(board, story).firstWhere((issue) => issue.id == 'Stopped'),
      );
    }

    Future<void> views(WidgetTester tester, List<String> names) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ListView(
              children: [
                for (final name in names)
                  StoryWorkAttentionView(key: Key(name), row: row(), resolved: false, machine: 'local'),
              ],
            ),
          ),
        ),
      );
      await settle(tester);
    }

    Future<void> twoViews(WidgetTester tester) async {
      _desktop(tester);
      await settle(tester);
      await views(tester, ['first', 'second']);
    }

    Finder problemIn(String view) =>
        find.descendant(of: find.byKey(Key(view)), matching: find.byKey(const Key('reading-error Stopped')));

    int reads() => backend.work.calls.where((call) => call.$1 == 'GetIssue').length;

    Finder decide(String view) => find.descendant(
      of: find.byKey(Key(view)),
      matching: find.byKey(const Key('reading-resolve Stopped REVIEW_ANYWAY')),
    );

    // Harm: the story page and Needs you both send a decision for the same stopped reading.
    testWidgets('two views of one stopped reading send a single decision', (tester) async {
      backend.work.deferEcho = true;
      await twoViews(tester);
      expect(tester.widget<OutlinedButton>(decide('second')).enabled, isTrue, reason: 'the decision is open at first');
      await tester.tap(decide('first'));
      await settle(tester);
      expect(
        tester.widget<OutlinedButton>(decide('second')).enabled,
        isFalse,
        reason: 'the other view knows the decision was sent',
      );
      await tester.tap(decide('second'), warnIfMissed: false);
      await settle(tester);
      expect(
        backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading'),
        hasLength(1),
        reason: 'one stopped reading takes one decision',
      );
    });

    // Harm: a refused decision is reported in one view while the other still offers it as if it could run.
    testWidgets('a refused decision shows its problem in every view', (tester) async {
      await twoViews(tester);
      backend.work.refusal = 'The round changed on this machine.';
      await tester.tap(decide('first'));
      await settle(tester);
      for (final view in ['first', 'second']) {
        expect(
          find.descendant(of: find.byKey(Key(view)), matching: find.byKey(const Key('reading-error Stopped'))),
          findsOneWidget,
          reason: 'the $view view shows the refusal',
        );
      }
    });

    // Harm: a view opened later clears a refusal from every view, so Needs you no longer shows why the decision failed.
    testWidgets('a view opened after a refusal keeps the refusal and reads nothing', (tester) async {
      _desktop(tester);
      await settle(tester);
      await views(tester, ['first']);
      backend.work.refusal = 'The round changed on this machine.';
      await tester.tap(decide('first'));
      await settle(tester);
      backend.work.refusal = null;
      final before = reads();
      await views(tester, ['first', 'second']);
      for (final view in ['first', 'second']) {
        expect(problemIn(view), findsOneWidget, reason: 'the $view view still shows the refusal');
      }
      expect(reads(), before, reason: 'mounting another view reads no findings again');
    });

    // Harm: a decision in flight while its row is folded away is lost, and a remounted row sends it a second time.
    testWidgets('a decision in flight outlives its views and is not sent twice', (tester) async {
      _desktop(tester);
      await settle(tester);
      await views(tester, ['first']);
      backend.held = Completer<void>();
      await tester.tap(decide('first'));
      await tester.pump();
      await views(tester, []);
      await views(tester, ['again']);
      expect(
        tester.widget<OutlinedButton>(decide('again')).enabled,
        isFalse,
        reason: 'the remounted row knows the decision is in flight',
      );
      await tester.tap(decide('again'), warnIfMissed: false);
      backend.work.refusal = 'The round changed on this machine.';
      backend.held!.complete();
      await settle(tester);
      expect(problemIn('again'), findsOneWidget, reason: 'the refusal of the decision in flight is shown');
      expect(backend.asked, 1, reason: 'one stopped reading takes one decision');
    });
  });
}
