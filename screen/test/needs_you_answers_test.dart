import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart' show FakePlanningClient, provider;
import 'support/story_work_support.dart';

const _run = '00000000-0000-4000-9000-0000000000aa';
const _other = '00000000-0000-4000-9000-0000000000bb';

/// A board whose story's run ended with [reason] on its one issue, standing in [state]; [ended] is the end of the
/// task that raised it, a [role]'s, and [stopped] whether its reading stopped.
Map<String, dynamic> _board(
  String reason, {
  String run = _run,
  String revision = 'r1',
  String state = 'Building',
  String role = 'BUILDER',
  Map<String, dynamic>? ended,
  bool stopped = false,
}) => {
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
      'state': state,
      'issues': [
        {
          'id': 'AA1',
          'repository': 'repo',
          'state': state,
          'needsYou': true,
          'worker': 'AGENT',
          'readingStopped': stopped,
          'rounds': 0,
          'roundsMost': 2,
        },
      ],
      'work': {
        'run': run,
        'atWork': false,
        'tasks': [
          if (ended != null)
            {
              'task': 'raising-task',
              'containerId': 'container',
              'machine': 'local',
              'repository': 'repo',
              'issues': ['AA1'],
              'state': state,
              'done': 'Review',
              'mode': 'UNATTENDED',
              'agent': 'codex',
              'model': 'model',
              'role': role,
              'since': '',
              'atWork': false,
              'ended': ended,
            },
        ],
        'issues': [
          {
            'issue': 'AA1',
            'reason': reason,
            'needsYou': true,
            'readingStopped': stopped,
            'findings': <Object>[],
            'rounds': 0,
            'roundsMost': 2,
          },
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

  Future<void> open(
    String reason, {
    PlanningStartHooks? hooks,
    PlanningTaskChanges? changes,
    Map<String, dynamic>? served,
  }) async {
    backend = MockPlanningSource()..boards['checkout'] = served ?? _board(reason);
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

  /// A builder's end that ran out of time, leaving [unpushed] commits.
  Map<String, dynamic> outOfTime({int unpushed = 0}) => {
    'at': '2026-01-01T00:00:00Z',
    'finished': false,
    'why': 'Out of time',
    'answer': '',
    'unpushed': unpushed,
  };

  // Harm: a failed builder's row with no way on but a move by hand, or a Start that passes over another reason.
  testWidgets('a failed builder\'s row starts again passing over its failure and moves on', (tester) async {
    await tester.runAsync(
      () => open(
        'FAILED',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board('FAILED', ended: outOfTime()),
      ),
    );
    await show(tester);

    expect(button(tester, 'story-work-move-on AA1').onPressed, isNotNull, reason: 'a failed row moves on');
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
      'reason': 'FAILED',
      'revision': 'r1',
      'run': _run,
    }, reason: 'the Start passes over exactly the failure the row showed');
  });

  // Harm: a new builder starts beside commits only the failed task can push, and they are lost.
  testWidgets('a failed builder\'s row whose task left commits unpushed offers no Start and says why', (tester) async {
    await tester.runAsync(
      () => open(
        'FAILED',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board('FAILED', ended: outOfTime(unpushed: 2)),
      ),
    );
    await show(tester);

    expect(button(tester, 'story-work-start AA1').onPressed, isNull, reason: 'the unpushed work holds the Start');
    expect(find.byKey(const Key('story-work-start AA1 why')), findsOneWidget, reason: 'the row says why');
    expect(button(tester, 'story-work-move-on AA1').onPressed, isNotNull, reason: 'the row still moves on');
  });

  // Harm: a stop renames the failure, and a new builder starts beside commits only the failed task can push.
  testWidgets('a stopped row whose failed task left commits unpushed offers no Start and says why', (tester) async {
    await tester.runAsync(
      () => open(
        'STOPPED',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board('STOPPED', ended: outOfTime(unpushed: 2)),
      ),
    );
    await show(tester);

    expect(button(tester, 'story-work-start AA1').onPressed, isNull, reason: 'the unpushed work holds the Start');
    expect(find.byKey(const Key('story-work-start AA1 why')), findsOneWidget, reason: 'the row says why');
  });

  // Harm: an agent started again over a block the task named, which nothing has lifted.
  testWidgets('a blocked builder\'s row moves on but offers no Start', (tester) async {
    await tester.runAsync(
      () => open(
        'BLOCKED',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board(
          'BLOCKED',
          ended: {...outOfTime(), 'finished': true, 'why': 'A key is missing', 'outcome': 'BLOCKED'},
        ),
      ),
    );
    await show(tester);

    expect(find.byKey(const Key('story-work-start AA1')), findsNothing, reason: 'a block takes no Start');
    await tester.tap(find.byKey(const Key('story-work-move-on AA1')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await settle(tester);
    expect(movesSent(), [
      {'issue': 'AA1', 'to': 'Review'},
    ], reason: 'the move on is the person\'s own move to where the rule leads');
  });

  // Harm: a builder started for an issue standing in a reading, or a rework with nothing to work from.
  testWidgets('a failed reader\'s row reads again or goes to Review and offers no Start or Rework', (tester) async {
    await tester.runAsync(
      () => open(
        'FAILED',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board('FAILED', state: 'Reading', role: 'READER', stopped: true, ended: outOfTime()),
      ),
    );
    await show(tester);

    expect(find.byKey(const Key('story-work-start AA1')), findsNothing, reason: 'a stopped reading takes no Start');
    expect(find.byKey(const Key('reading-resolve AA1 ONE_MORE_ROUND')), findsOneWidget, reason: 'it reads again');
    expect(find.byKey(const Key('reading-resolve AA1 REVIEW_ANYWAY')), findsOneWidget, reason: 'it goes to Review');
    expect(find.byKey(const Key('reading-resolve AA1 REWORK')), findsNothing, reason: 'nothing guides a rework');
    expect(find.text('FAILED'), findsOneWidget, reason: 'the row keeps its reason');
    expect(find.byKey(const Key('story-work-open-task AA1')), findsOneWidget, reason: 'the row opens the failed task');
  });

  // Harm: a Start offered over a reading that ran out of rounds launches work past findings nobody answered.
  testWidgets('a row whose reading rounds ran out offers no Start', (tester) async {
    await tester.runAsync(
      () => open(
        'ROUND_LIMIT',
        hooks: _hooks(),
        changes: const NoTasks(),
        served: _board('ROUND_LIMIT', state: 'Reading', stopped: true),
      ),
    );
    await show(tester);

    expect(find.byKey(const Key('story-work-start AA1')), findsNothing, reason: 'its reason takes no Start');
  });

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
