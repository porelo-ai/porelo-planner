import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart' show FakePlanningClient, provider;
import 'support/story_work_support.dart';

const _run = '00000000-0000-4000-9000-0000000000aa';
const _other = '00000000-0000-4000-9000-0000000000bb';

/// A board whose story's run ended with [reason] on its one issue, standing in Building.
Map<String, dynamic> _board(String reason, {String run = _run, String revision = 'r1'}) => {
  'revision': revision,
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Reading', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Backlog', 'Building', 'Reading', 'Review'],
    },
  ],
  'work': [
    for (final state in ['Building', 'Reading'])
      {
        'state': state,
        'from': ['Backlog', 'Building', 'Reading'],
        'done': 'Review',
        'then': null,
        'blocked': null,
        'per': 'repository',
        'modes': ['UNATTENDED'],
        'order': 'Work {issues}',
        'models': {'codex': 'model'},
      },
  ],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A raised story',
      'state': 'Building',
      'issues': [
        {'id': 'AA1', 'repository': 'repo', 'state': 'Building', 'needsYou': true, 'worker': 'AGENT'},
      ],
      'work': {
        'run': run,
        'atWork': false,
        'tasks': <Map<String, dynamic>>[],
        'issues': [
          {'issue': 'AA1', 'reason': reason, 'needsYou': true, 'rounds': 0, 'roundsMost': 2},
        ],
      },
    },
  ],
};

/// The window's start hooks for a board on one machine.
PlanningStartHooks _hooks() => PlanningStartHooks(
  startTask: mockStartTask,
  agents: FakeAgents(),
  machines: (board) => {board: 'local'},
  unavailableMachines: (_) => const [],
);

void main() {
  late MockPlanningSource backend;
  late PlanningBoard board;
  final opened = <PlanningAttention>[];

  Future<void> open(String reason, {PlanningStartHooks? hooks, PlanningTaskChanges? changes}) async {
    backend = MockPlanningSource()..boards['checkout'] = _board(reason);
    board = PlanningBoard(backend, 'checkout')
      ..startHooks = hooks
      ..taskChanges = changes;
    await Future<void>.delayed(Duration.zero);
  }

  Future<void> show(WidgetTester tester) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    final row = PlanningBoards.storyAttention(board, board.stories.single).firstWhere((row) => row.issue != null);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: StoryWorkAttentionView(row: row, resolved: false, machine: 'local', onStory: opened.add),
          ),
        ),
      ),
    );
    await settle(tester);
  }

  OutlinedButton button(WidgetTester tester, String key) => tester.widget<OutlinedButton>(find.byKey(Key(key)));

  List<Object?> movesSent() => [for (final call in backend.moveCalls) ...call['moves'] as List];

  setUp(opened.clear);

  // Harm: a refused save nobody can answer from its row holds the story, or a Start passes over another reason.
  testWidgets('a row whose save was refused starts again passing over exactly its reason', (tester) async {
    await tester.runAsync(() => open('SAVE_REFUSED', hooks: _hooks(), changes: const NoTasks()));
    await show(tester);

    await tester.tap(find.byKey(const Key('story-work-start AA1')));
    await settle(tester);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);
    await tester.tap(find.byKey(const Key('story-work-start')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);

    final sent = backend.work.calls.where((call) => call.$1 == 'StartStoryWork').map((call) => call.$2).toList();
    expect(sent, hasLength(1), reason: 'the row\'s Start reaches the planner once');
    expect(sent.single['passOver'], {
      'issue': 'AA1',
      'reason': 'SAVE_REFUSED',
      'revision': 'r1',
      'run': _run,
    }, reason: 'the Start names the issue, its reason, the revision and the run the row showed');
    expect(sent.single['replaces'], _run, reason: 'the Start names the run it replaces');
  });

  // Harm: a Start offered past findings that could not be read launches work over a reading nobody answered.
  testWidgets('a row whose findings could not be read offers no Start but goes on to Review', (tester) async {
    await tester.runAsync(() => open('INVALID_FINDINGS', hooks: _hooks(), changes: const NoTasks()));
    await show(tester);

    expect(find.byKey(const Key('story-work-start AA1')), findsNothing, reason: 'its reason takes no Start');
    await tester.tap(find.byKey(const Key('story-work-review AA1')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);
    expect(movesSent(), [
      {'issue': 'AA1', 'to': 'Review'},
    ], reason: 'the move to the reading\'s done state is saved at once');
    expect(opened, hasLength(1), reason: 'the story opens where the person sees the move');
  });

  // Harm: a stopped row the person cannot move on stays raised with nothing that clears it.
  testWidgets('a stopped row moves its issue on and opens its story', (tester) async {
    await tester.runAsync(() => open('STOPPED', hooks: _hooks(), changes: const NoTasks()));
    await show(tester);

    expect(button(tester, 'story-work-start AA1').onPressed, isNotNull, reason: 'a stopped run starts again');
    await tester.tap(find.byKey(const Key('story-work-move-on AA1')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);
    expect(movesSent(), [
      {'issue': 'AA1', 'to': 'Review'},
    ], reason: 'the move on is saved at once');
    expect(opened, hasLength(1), reason: 'the story opens where the person sees the move');
  });

  // Harm: an ended run with reasons nobody can stop keeps its unstarted work offered.
  testWidgets('a row of an ended run with reasons offers Stop and stops the run it showed', (tester) async {
    await tester.runAsync(() => open('NEXT_STEP_UNAVAILABLE', hooks: _hooks(), changes: const NoTasks()));
    await show(tester);

    await tester.tap(find.byKey(const Key('story-work-stop AA1')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('story-work-stop-confirm')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);

    final sent = backend.work.calls.where((call) => call.$1 == 'StopStoryWork').map((call) => call.$2).toList();
    expect(sent, hasLength(1), reason: 'the stop reaches the planner once');
    expect(sent.single['run'], _run, reason: 'the stop names the run the row showed');
  });

  // Harm: a run the person never saw is stopped, since the confirmation read the run only once confirmed.
  testWidgets('a stop confirmed after another run took the story names the run the row showed', (tester) async {
    await tester.runAsync(() => open('NEXT_STEP_UNAVAILABLE', hooks: _hooks(), changes: const NoTasks()));
    await show(tester);

    await tester.tap(find.byKey(const Key('story-work-stop AA1')));
    await settle(tester);
    backend.boards['checkout'] = _board('NEXT_STEP_UNAVAILABLE', run: _other, revision: 'r2');
    await tester.runAsync(board.reload);
    expect(board.workOf(board.stories.single)?.run, _other, reason: 'the board now shows another run');
    await tester.tap(find.byKey(const Key('story-work-stop-confirm')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);

    final sent = backend.work.calls.where((call) => call.$1 == 'StopStoryWork').map((call) => call.$2).toList();
    expect(sent.map((call) => call['run']), [_run], reason: 'the stop names the run shown when it was confirmed');
  });

  // Harm: a stop recorded while the agent goes on running, since nothing could end its task.
  testWidgets('a row whose board holds no hook that ends tasks offers no Stop and says why', (tester) async {
    await tester.runAsync(() => open('NEXT_STEP_UNAVAILABLE', hooks: _hooks()));
    await show(tester);

    expect(button(tester, 'story-work-stop AA1').onPressed, isNull, reason: 'nothing can end the task');
    expect(find.byKey(const Key('story-work-stop AA1 why')), findsOneWidget, reason: 'the row says why');
  });

  // Harm: a row offers a Start that cannot run, or none without saying why.
  testWidgets('a row whose board holds no start hooks offers no Start and says why', (tester) async {
    await tester.runAsync(() => open('STOPPED', changes: const NoTasks()));
    await show(tester);

    expect(button(tester, 'story-work-start AA1').onPressed, isNull, reason: 'nothing can start the work');
    expect(find.byKey(const Key('story-work-start AA1 why')), findsOneWidget, reason: 'the row says why');
  });

  // Harm: a row shown before the planning screen ever was cannot start its work.
  test('the boards hand the window\'s start hooks to every board they make', () async {
    final source = MockPlanningSource()..boards['checkout'] = _board('STOPPED');
    final client = FakePlanningClient([
      provider('local', source, project: 'checkout'),
      provider('remote', MockPlanningSource(), project: 'checkout', connected: false),
    ]);
    final boards = PlanningBoards(client, startTask: mockStartTask, agents: FakeAgents());
    addTearDown(() async {
      boards.dispose();
      await source.end();
    });

    final made = boards.sources.single;
    expect(made.startHooks, isNotNull, reason: 'the board holds the hooks before any screen is shown');
    expect(made.startHooks!.machines(made), {made: 'local'}, reason: 'a start runs on the boards of its project');
    expect(made.startHooks!.unavailableMachines(made), [
      'remote',
    ], reason: 'a machine that has not supplied the project is named as unavailable');
  });
}
