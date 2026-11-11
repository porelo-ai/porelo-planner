import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _ended({required bool finished, int unpushed = 0}) => {
  'at': '2026-01-01T00:00:00Z',
  'finished': finished,
  'why': '',
  'answer': '',
  'unpushed': unpushed,
};

Map<String, dynamic> _task(String name, String issue, Map<String, dynamic> ended, {String role = 'BUILDER'}) => {
  'task': name,
  'containerId': 'box-$name',
  'machine': 'local',
  'repository': 'repo',
  'issues': [issue],
  'state': 'Building',
  'done': 'Reading',
  'mode': 'AGENT',
  'agent': 'codex',
  'model': 'model',
  'role': role,
  'since': '2026-01-01T00:00:00Z',
  'atWork': false,
  'ended': ended,
};

Map<String, dynamic> _rule(String state, {String? blocked, required List<String> modes}) => {
  'state': state,
  'from': ['Building'],
  'done': 'Review',
  'then': null,
  'blocked': blocked,
  'per': 'repository',
  'modes': modes,
  'order': 'Work {issues}',
  'models': {'claude': 'opus', 'codex': 'model'},
};

/// A board whose story holds a failed, an unpushed, a lost and a stopped issue, and one whose task changed later.
Map<String, dynamic> _board() => {
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Reading', 'kind': 'WORKING'},
    {'name': 'Reworking', 'kind': 'WORKING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Building', 'Reading', 'Reworking'],
    },
  ],
  'work': [
    _rule('Reading', blocked: 'Reworking', modes: ['UNATTENDED']),
    _rule('Reworking', modes: ['UNATTENDED', 'AGENT']),
  ],
  'stories': [
    {
      'id': 'Held',
      'title': 'A held story',
      'state': 'Building',
      'issues': [
        {'id': 'Broken', 'repository': 'repo', 'state': 'Building', 'needsYou': true},
        {'id': 'Unpushed', 'repository': 'repo', 'state': 'Building', 'needsYou': true},
        {'id': 'Nameless', 'repository': 'repo', 'state': 'Building', 'needsYou': true},
        {
          'id': 'Stopped',
          'repository': 'repo',
          'state': 'Reading',
          'readingStopped': true,
          'needsYou': true,
          'rounds': 1,
          'roundsMost': 2,
        },
      ],
      'work': {
        'atWork': false,
        'tasks': [
          _task('build-broken', 'Broken', _ended(finished: false)),
          _task('build-unpushed', 'Unpushed', _ended(finished: true, unpushed: 2)),
          _task('build-nameless', 'Nameless', _ended(finished: true)),
        ],
        'issues': [
          {'issue': 'Broken', 'reason': 'FAILED', 'needsYou': true},
          {'issue': 'Unpushed', 'reason': 'UNPUSHED', 'needsYou': true},
          // A finished build cannot have raised FAILED, so no task is told for it.
          {'issue': 'Nameless', 'reason': 'FAILED', 'needsYou': true},
          {
            'issue': 'Stopped',
            'reason': 'BLOCKING_FINDINGS',
            'readingStopped': true,
            'needsYou': true,
            'rounds': 1,
            'roundsMost': 2,
          },
        ],
      },
    },
    {
      'id': 'Lost',
      'title': 'A story whose run was lost',
      'state': 'Building',
      'issues': [
        {'id': 'Orphan', 'repository': 'repo', 'state': 'Building', 'needsYou': false, 'noRunHere': true},
      ],
    },
  ],
  'revision': 'r1',
};

/// The window's task hook, recording what it was asked to open.
class _Opened extends NoTasks {
  final List<String> tasks = [];

  @override
  void open(PlanningBoard board, String task) => tasks.add(task);
}

/// A host counting how often the screen sent the person to Needs you.
class _Host extends TestHost {
  int needsYou = 0;

  @override
  void openNeedsYou() => needsYou++;
}

void main() {
  late MockPlanningSource backend;
  late PlanningBoard board;
  setUp(() async {
    backend = MockPlanningSource()..boards['checkout'] = _board();
    backend.work.findings['Stopped'] = [
      {'number': 1, 'severity': 'BLOCKING', 'text': 'A credential would escape.'},
    ];
    board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
  });
  tearDown(() async {
    board.dispose();
    await backend.end();
  });

  PlanningAttention row(String story, String issue) => PlanningBoards.storyAttention(
    board,
    board.stories.firstWhere((each) => each.id == story),
  ).firstWhere((row) => row.issue?.id == issue);

  Future<void> show(WidgetTester tester, PlanningAttention row, {PlanningTaskChanges? changes}) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: StoryWorkAttentionView(row: row, resolved: false, machine: 'local', taskChanges: changes),
          ),
        ),
      ),
    );
    await settle(tester);
  }

  // Harm: a person sent to a failed or unpushed task's row cannot reach its result, or reaches another task's.
  for (final (issue, task) in [('Broken', 'build-broken'), ('Unpushed', 'build-unpushed')]) {
    testWidgets('the row of $issue opens the task that raised it', (tester) async {
      final opened = _Opened();
      await show(tester, row('Held', issue), changes: opened);
      await tester.tap(find.byKey(Key('story-work-open-task $issue')));
      await settle(tester);
      expect(opened.tasks, [task], reason: 'the row opens exactly the task whose end raised it');
    });
  }

  // Harm: a row offers to open a task that did not raise its reason.
  for (final (story, issue) in [('Held', 'Nameless')]) {
    testWidgets('the row of $issue, with no task to tell, says its reason instead', (tester) async {
      final opened = _Opened();
      await show(tester, row(story, issue), changes: opened);
      expect(find.byKey(Key('story-work-open-task $issue')), findsNothing, reason: 'no task can be told');
      expect(find.byKey(Key('story-work-reason $issue')), findsOneWidget, reason: 'the reason stands in words');
      expect(opened.tasks, isEmpty, reason: 'nothing is opened');
    });
  }

  // Harm: a row whose board never met the planning screen offers an action that cannot run.
  testWidgets('a row without the window hook says why it cannot open the task', (tester) async {
    await show(tester, row('Held', 'Broken'));
    final button = tester.widget<TextButton>(find.byKey(const Key('story-work-open-task Broken')));
    expect(button.onPressed, isNull, reason: 'there is nothing to open the task with');
    expect(find.byKey(const Key('story-work-reason Broken')), findsOneWidget, reason: 'the row says why');
  });

  // Harm: a rework from the row runs in settings the person never saw, or waits for words it does not need.
  testWidgets('the Reworking dialog sends a rework in the first mode and model, without words', (tester) async {
    await show(tester, row('Held', 'Stopped'));
    await tester.tap(find.byKey(const Key('reading-resolve Stopped REWORK')));
    await settle(tester);
    expect(find.byKey(const Key('reading-rework-review')), findsOneWidget, reason: 'Review anyway is offered');
    expect(find.byKey(const Key('reading-rework-more')), findsOneWidget, reason: 'One more reading is offered');
    await tester.tap(find.byKey(const Key('reading-rework-send')));
    await settle(tester);
    final sent = backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading').map((call) => call.$2);
    expect(sent, hasLength(1), reason: 'the blocking findings let the rework go without words');
    expect(
      [sent.single['choice'], sent.single['mode'], sent.single['agent'], sent.single['model']],
      ['REWORK', 'UNATTENDED', 'claude', 'opus'],
      reason: 'the Reworking rule\'s first mode and model, as preselected',
    );
  });

  // Harm: a failed task leaves its card looking as if work went on, so nobody comes to it.
  testWidgets('a card with a raised issue leads to Needs you', (tester) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() {
      boards.dispose();
      client.dispose();
    });
    final host = _Host();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: planningView(boards: boards, host: host),
        ),
      ),
    );
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-warning Held')));
    await settle(tester);
    expect(host.needsYou, 1, reason: 'the warning leads to Needs you');
  });
}
