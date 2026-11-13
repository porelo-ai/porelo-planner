import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';
import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, String worker, {String state = 'Backlog', String? parent}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': <String>[],
  'parent': parent,
  'link': 'https://example.org/issues/$id',
  'worker': worker,
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
};

/// One story worked by an agent (PL1) and by a person (PL2), and a working state no agent issue stands in.
Map<String, dynamic> _board({String manualState = 'Backlog', Object? work}) => {
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
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'Mixed story',
      'state': manualState == 'Implementing' ? 'Implementing' : 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'issues': [_issue('PL1', 'AGENT', state: 'Review'), _issue('PL2', 'MANUAL', state: manualState)],
      'work': work,
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

Future<PlanningDetail> _show(
  WidgetTester tester,
  PlanningBoard board, {
  String? issue,
  void Function(String issue, String to)? onStartIssue,
}) async {
  tester.view
    ..physicalSize = const Size(1280, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final detail = PlanningDetail(board, 'ST1', issue);
  addTearDown(detail.dispose);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ListenableBuilder(
            listenable: detail,
            builder: (_, _) => issue == null
                ? PlanningStoryView(
                    detail: detail,
                    onStartIssue: onStartIssue,
                    onIssue: (_) {},
                    onTask: (_) {},
                    taskUnavailable: (_) => null,
                    openLink: (_) async {},
                  )
                : PlanningIssueView(
                    detail: detail,
                    onStory: (_) {},
                    onIssue: (_) {},
                    onTask: (_) {},
                    taskUnavailable: (_) => null,
                    openLink: (_) async {},
                  ),
          ),
        ),
      ),
    ),
  );
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  await settle(tester);
  return detail;
}

String _stateOf(MockPlanningSource source, String id) =>
    mockPlanningIssue(source.boards['demo']!, id)!['state'] as String;

void main() {
  // Harm: a person cannot move the issue they work through the work, or an agent's issue enters work without a task.
  testWidgets('an issue a person works is offered and saved into a working state, an agent\'s is not offered', (
    tester,
  ) async {
    final (source, board) = await _open(tester, _board());
    await _show(tester, board);

    await tester.tap(find.byKey(const Key('planning-move PL1')));
    await settle(tester);
    expect(find.byKey(const Key('planning-move-to PL1 Implementing')), findsNothing, reason: 'an agent\'s issue');
    expect(find.byKey(const Key('planning-move-to PL1 Backlog')), findsOneWidget, reason: 'its waiting states stay');
    await tester.tapAt(Offset.zero);
    await settle(tester);
    await tester.runAsync(() => board.moveIssue('PL1', 'Implementing'));
    expect(source.moveCalls, isEmpty, reason: 'nor is it moved when asked directly');

    await tester.tap(find.byKey(const Key('planning-move PL2')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-move-to PL2 Implementing')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    await settle(tester);
    expect(_stateOf(source, 'PL2'), 'Implementing', reason: 'saved by hand, with no task');
    expect(source.work.calls, isEmpty, reason: 'no work was started for it');
  });

  // Harm: an agent's issue cannot be started on its own from the story, or a person's issue is handed to an agent.
  testWidgets('a working state offered for an agent\'s issue opens its start and stages no move', (tester) async {
    final (source, board) = await _open(tester, _board());
    final started = <(String, String)>[];
    await _show(tester, board, onStartIssue: (issue, to) => started.add((issue, to)));

    await tester.tap(find.byKey(const Key('planning-move PL2')));
    await settle(tester);
    expect(find.byKey(const Key('planning-start-issue PL2 Implementing')), findsNothing, reason: 'a person\'s issue');
    await tester.tapAt(Offset.zero);
    await settle(tester);

    await tester.tap(find.byKey(const Key('planning-move PL1')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-start-issue PL1 Implementing')));
    await settle(tester);
    expect(started, [('PL1', 'Implementing')], reason: 'the start opens for that issue alone');
    expect(board.pendingIssues, isEmpty, reason: 'a start stages no move');
    expect(source.work.calls, isEmpty, reason: 'nothing starts before the dialog confirms');
  });

  // Harm: the stand-in the window's planning tests run on lets an agent's issue into work, or keeps a person's out.
  test('the stand-in moves only an issue a person works into a working state', () {
    final data = _board();
    Map<String, dynamic> move(String issue) => applyPlanningMove(data, {
      'revision': 'r1',
      'moves': [
        {'issue': issue, 'to': 'Implementing'},
      ],
    });
    expect(
      () => move('PL1'),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'EnteredOnlyByWork')),
      reason: 'an agent\'s issue enters only by its work',
    );
    expect(mockPlanningIssue(move('PL2'), 'PL2')!['state'], 'Implementing', reason: 'a person\'s issue is moved');
    expect(
      () => applyPlanningMove(data, {
        'revision': 'r1',
        'moves': [
          {'story': 'ST1', 'to': 'Implementing'},
        ],
      }),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'EnteredOnlyByWork')),
      reason: 'a story enters only by its work',
    );
  });

  // Harm: an issue switched without the planner, or a refused switch shown as done, leaves it to the wrong worker.
  testWidgets('the issue view switches the worker through the planner and shows a refusal in its words', (
    tester,
  ) async {
    final (source, board) = await _open(tester, _board(manualState: 'Implementing'));
    await _show(tester, board, issue: 'PL1');

    await tester.tap(find.byKey(const Key('planning-worker-switch')));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    expect(source.workerCalls, [
      {'project': 'demo', 'issue': 'PL1', 'worker': 'MANUAL', 'revision': 'r1'},
    ], reason: 'one call, against the board shown');
    expect(mockPlanningIssue(source.boards['demo']!, 'PL1')!['worker'], 'MANUAL', reason: 'the planner saved it');
    expect(find.byKey(const Key('planning-worker-problem')), findsNothing, reason: 'nothing was refused');

    await tester.pumpWidget(const SizedBox.shrink());
    await _show(tester, board, issue: 'PL2');
    await tester.tap(find.byKey(const Key('planning-worker-switch')));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    expect(source.workerCalls.last['worker'], 'AGENT', reason: 'the switch back was asked');
    expect(mockPlanningIssue(source.boards['demo']!, 'PL2')!['worker'], 'MANUAL', reason: 'refused, so unchanged');
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-worker-problem'))).data,
      contains('PL2 stands in Implementing, which only its work enters.'),
      reason: 'the planner\'s own words',
    );
  });

  // Harm: a worker changed while a move of the issue is saved makes the planner refuse one of them unseen.
  testWidgets('no worker change is offered while a move of the issue or a sub-issue is saved or the board is partial', (
    tester,
  ) async {
    final data = _board();
    ((data['stories'] as List).first['issues'] as List).add(_issue('PL2.1', 'MANUAL', parent: 'PL2'));
    final (source, board) = await _open(tester, data);
    PlanIssue issue(String id) => board.stories.single.issues.firstWhere((each) => each.id == id);
    expect(board.workerUnavailable(issue('PL2')), isNull, reason: 'nothing is saved, so the switch is offered');

    for (final moved in ['PL2', 'PL2.1']) {
      final gate = source.moveGate = Completer<void>();
      late Future<void> moving;
      await tester.runAsync(() async {
        moving = board.moveIssue(moved, 'Implementing');
        await Future<void>.delayed(const Duration(milliseconds: 10));
      });
      expect(board.pendingIssues.keys, [moved], reason: 'the fixture holds the save of $moved');
      expect(board.workerUnavailable(issue('PL2')), isNotNull, reason: '$moved has a move being saved');
      await tester.runAsync(() async {
        gate.complete();
        await moving;
      });
      source.moveGate = null;
    }
    expect(board.workerUnavailable(issue('PL2')), isNull, reason: 'the saved moves free the switch again');
    source.moveFailure = mockRefusal('TimedOut', const {});
    await tester.runAsync(() => board.moveIssue('PL2.1', 'Backlog'));
    source.moveFailure = null;
    expect(board.pendingIssues['PL2.1']?.unanswered, isTrue, reason: 'the fixture leaves the save unanswered');
    expect(board.saving, isFalse, reason: 'nothing is being saved any more');
    expect(board.workerUnavailable(issue('PL2')), isNotNull, reason: 'its sub-issue\'s save is unanswered');
    final shown = board.snapshot!;
    final head = issue('PL2');
    board.snapshot = PlanningSnapshot.from({'revision': shown.revision, 'stories': <dynamic>[]});
    expect(board.snapshot!.complete, isFalse, reason: 'the fixture shows a partial board');
    expect(board.workerUnavailable(head), isNotNull, reason: 'a partial board cannot tell what is staged');
    board.snapshot = shown;
  });

  // Harm: the stand-in gives a sub-issue a worker of its own, which the planner refuses, so the window's tests pass a
  // switch that fails for real.
  test('the stand-in refuses a sub-issue a worker of its own and a person\'s working issue an agent', () {
    final data = _board(manualState: 'Implementing');
    ((data['stories'] as List).first['issues'] as List).add(_issue('PL1.1', 'AGENT', parent: 'PL1'));
    String refused(String issue, String worker) {
      try {
        applyPlanningWorker(data, issue, worker, 'r1');
      } on PlanningRefusal catch (refusal) {
        return refusal.name;
      }
      return 'saved';
    }

    expect(refused('PL1.1', 'MANUAL'), 'MoveNotAllowed', reason: 'a sub-issue takes its parent\'s worker');
    expect(refused('PL2', 'AGENT'), 'MoveNotAllowed', reason: 'no task entered its working state');
    final saved = applyPlanningWorker(data, 'PL1', 'MANUAL', 'r1');
    expect(
      [mockPlanningIssue(saved, 'PL1')!['worker'], mockPlanningIssue(saved, 'PL1.1')!['worker']],
      ['MANUAL', 'MANUAL'],
      reason: 'the head and its sub-issue',
    );
  });

  // Harm: an agent gets a task for an issue a person works, and the person is not told which issues stay theirs.
  testWidgets('the proposal leaves a person\'s issue out and the start dialog names it', (tester) async {
    final (source, board) = await _open(tester, _board());
    final proposal = (await tester.runAsync(() => source.proposeStoryWork('demo', 'ST1', 'Implementing')))!;
    expect(proposal.issues.map((issue) => issue.id), ['PL1'], reason: 'only the agent\'s issue gets a task');
    expect(proposal.manual, ['PL2'], reason: 'the person\'s issue is named');

    final work = planningStoryWork(board, board.stories.single, 'Implementing');
    addTearDown(work.dispose);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkDialog(work))));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    expect(
      tester.widget<Text>(find.byKey(const Key('story-work-manual'))).data,
      contains('PL2'),
      reason: 'the dialog names it',
    );
    expect(tester.widget<Text>(find.byKey(const Key('story-work-issues repo'))).data, isNot(contains('PL2')));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Harm: the effort a person types or clears in the dialog is not the one the task starts with.
  testWidgets('the dialog offers the rule\'s effort and what the person types or clears is the one chosen', (
    tester,
  ) async {
    final (_, board) = await _open(tester, _board());
    final work = planningStoryWork(board, board.stories.single, 'Implementing');
    addTearDown(work.dispose);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkDialog(work))));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    final field = find.byKey(const Key('story-work-main-effort repo'));
    expect(
      tester.widget<EditableText>(find.descendant(of: field, matching: find.byType(EditableText))).controller.text,
      'high',
      reason: 'the rule\'s effort is offered as proposed',
    );
    await tester.enterText(field, 'low');
    expect(work.repositories['repo']!.effort, 'low', reason: 'the typed effort is the one chosen');
    await tester.enterText(field, '');
    expect(work.repositories['repo']!.effort, isNull, reason: 'a cleared effort is none');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Harm: a story whose every issue a person works starts agents with nothing to do.
  test('the stand-in proposes no task for a story whose every issue a person works', () async {
    final source = MockPlanningSource()..boards['demo'] = _board();
    ((source.boards['demo']!['stories'] as List).first['issues'] as List).removeAt(0);
    await expectLater(
      source.proposeStoryWork('demo', 'ST1', 'Implementing'),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'WorkNotAllowed')),
    );
  });

  // Harm: a proposal that gives a person's issue to a task too is accepted, so both work it.
  test('a proposal naming an issue both for a task and as a person\'s is refused', () {
    final answer = {
      ...servedProposal(),
      'manual': ['PR1'],
    };
    expect(() => StoryWorkOrder.readProposal(answer), throwsFormatException, reason: 'worked twice');
    expect(
      StoryWorkOrder.readProposal({
        ...servedProposal(),
        'manual': ['PR2'],
      }).manual,
      ['PR2'],
      reason: 'a person\'s issue apart from the tasks is read',
    );
    expect(StoryWorkOrder.readProposal(servedProposal()).manual, isEmpty, reason: 'an older planner names none');
  });

  // Harm: an issue a person moved into work raises an alarm or blocks the story's start as lost work.
  testWidgets('an issue a person works raises no attention and counts as no work without a run', (tester) async {
    final (_, board) = await _open(
      tester,
      _board(
        manualState: 'Implementing',
        work: {
          'tasks': <dynamic>[],
          'coordinator': null,
          'since': '',
          'atWork': false,
          'repositories': <dynamic>[],
          'comment': '',
          'readingRounds': 2,
          'issueReadingRounds': <dynamic>[],
          'issues': [
            {
              'issue': 'PL2',
              'rounds': 0,
              'roundsMost': 2,
              'atWork': false,
              'needsYou': false,
              'readingStopped': false,
              'findings': <dynamic>[],
              'reason': 'MANUAL',
            },
          ],
        },
      ),
    );
    final story = board.stories.single;
    expect(PlanningBoards.attentionIssues(board, story), isEmpty, reason: 'reason MANUAL needs nobody');
    expect(board.startUnavailable(story), isNull, reason: 'the story can still start its agent\'s issue');

    final (_, unrun) = await _open(tester, _board(manualState: 'Implementing'));
    expect(unrun.workingCount, 0, reason: 'a person\'s issue in work is no run lost');
    expect(unrun.startUnavailable(unrun.stories.single), isNull, reason: 'nor does it block a start');
  });
}
