import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// The stand-in planner, holding its discussion answer until a test lets it go.
class _Source extends MockPlanningSource {
  Completer<void>? gate;
  Object? refusal;
  final answered = <PlanningTaskOrder>[];

  @override
  Future<PlanningTaskOrder> discussIdea(String project, String idea) async {
    final order = await super.discussIdea(project, idea);
    if (refusal case final refusal?) throw refusal;
    if (gate case final gate?) await gate.future;
    answered.add(order);
    return order;
  }
}

typedef _Start = ({PlanningSource source, String project, String story, PlanningTaskOrder task});

Future<(_Source, MockPlanningClient, List<_Start>)> _showBoard(WidgetTester tester) async {
  tester.view
    ..physicalSize = const Size(1280, 800)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final source = _Source();
  final client = MockPlanningClient.of(source);
  final boards = PlanningBoards(client);
  final starts = <_Start>[];
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await source.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: PlanningScreen(
          boards: boards,
          host: TestHost(),
          startTask: (source, project, story, task) async {
            starts.add((source: source, project: project, story: story, task: task));
            return const PlanningTaskStarted(task: 'discussion', container: 'discussion-container');
          },
          taskChanges: const NoTasks(),
          agents: FakeAgents(),
        ),
      ),
    ),
  );
  await settle(tester);
  return (source, client, starts);
}

Future<void> _chooseDiscuss(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('planning-board-menu')));
  await settle(tester);
  await tester.tap(find.byKey(const Key('planning-discuss-idea')), warnIfMissed: false);
  await settle(tester);
}

void main() {
  // Harm: one idea starts two tasks, or its task reaches Start bound to a story's run.
  testWidgets('discussing an idea hands its order to Start once with no story', (tester) async {
    final (source, client, starts) = await _showBoard(tester);
    source.gate = Completer<void>();
    await _chooseDiscuss(tester);
    await tester.enterText(find.byKey(const Key('planning-idea-words')), 'A board that remembers.');
    await tester.pump();
    await tester.tap(find.byKey(const Key('planning-idea-discuss')));
    await settle(tester);
    expect(source.ideas, [('demo', 'A board that remembers.')], reason: 'the idea is asked for once, as typed');

    await _chooseDiscuss(tester);
    expect(find.byKey(const Key('planning-idea-words')), findsNothing, reason: 'a pending discussion opens no other');
    expect(source.ideas, hasLength(1), reason: 'a second press while the first is pending asks for nothing');
    await tester.tapAt(Offset.zero);
    await settle(tester);

    source.gate!.complete();
    await settle(tester);
    expect(starts, hasLength(1), reason: 'one idea starts one task');
    final start = starts.single;
    expect(
      [start.source, start.project, start.story, start.task],
      [source, 'demo', '', source.answered.single],
      reason: 'the order goes to Start unchanged, on its provider, with no story',
    );
    expect(start.task.role, 'DISCUSSION', reason: 'the order is the discussion the planner offered');
    expect(client.events, isEmpty, reason: 'no story run owns a discussion, so no task event is reported');
  });

  // Harm: a caller asking twice while the first is pending starts two tasks for one idea.
  test('a board asks for one discussion at a time', () async {
    final source = _Source()..gate = Completer<void>();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    final starts = <String>[];
    Future<PlanningTaskStarted> start(
      PlanningSource source,
      String project,
      String story,
      PlanningTaskOrder task,
    ) async {
      starts.add(task.offer!);
      return const PlanningTaskStarted(task: 'discussion', container: 'discussion-container');
    }

    final first = board.discussIdea('An idea', start);
    final second = board.discussIdea('An idea', start);
    source.gate!.complete();
    await Future.wait([first, second]);
    expect(source.ideas, hasLength(1), reason: 'the second call came while the first was pending');
    expect(starts, hasLength(1), reason: 'one idea starts one task');
  });

  // Harm: an error shown for the person's own choice, read as a fault of the planner.
  test('a discussion whose Start the person cancelled shows no error and stays offered', () async {
    final source = _Source();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    await pumpEventQueue();
    await board.discussIdea(
      'An idea',
      (source, project, story, task) async =>
          throw const PlanningRefusal('NotStarted', {}, 'The person did not start the task.'),
    );
    expect(board.actionProblem, isNull, reason: 'cancelling the Start is no fault');
    expect(board.unavailableActions, isNot(contains('DiscussIdea')), reason: 'the discussion stays offered');
    expect(board.discussing, isFalse, reason: 'the next idea may be discussed');
  });

  // Harm: a discussion asked for on one project starts after the person moved to another.
  test('a board switched to another project starts no discussion asked for before', () async {
    final source = _Source()..gate = Completer<void>();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    final starts = <String>[];
    Future<PlanningTaskStarted> start(
      PlanningSource source,
      String project,
      String story,
      PlanningTaskOrder task,
    ) async {
      starts.add(project);
      return const PlanningTaskStarted(task: 'discussion', container: 'discussion-container');
    }

    final pending = board.discussIdea('An idea', start);
    await board.changeProject(source, 'other');
    expect(board.discussing, isFalse, reason: 'the other project may discuss an idea of its own');
    source.gate!.complete();
    await pending;
    expect(starts, isEmpty, reason: 'the idea was asked for on a project no longer shown');
    expect(board.discussing, isFalse, reason: 'the stale answer leaves the new project as it was');
  });

  // Harm: a refused discussion looks as if it started, and the person waits for a task that never comes.
  testWidgets('a refused discussion shows the planner\'s words and starts nothing', (tester) async {
    final (source, _, starts) = await _showBoard(tester);
    source.refusal = mockRefusal('org.fuin.porelo.Tasks1.NoDiscussion', {'detail': 'no sound work rule'});
    await _chooseDiscuss(tester);
    await tester.enterText(find.byKey(const Key('planning-idea-words')), 'An idea');
    await tester.pump();
    await tester.tap(find.byKey(const Key('planning-idea-discuss')));
    await settle(tester);
    expect(starts, isEmpty, reason: 'a refusal starts nothing');
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-action-error'))).data,
      contains('no sound work rule'),
      reason: 'the refusal is shown in the planner\'s words',
    );
  });
}
