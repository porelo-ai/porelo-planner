import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/served_story_work.dart';
import 'support/story_work_support.dart';

/// A right-to-left override and an escape, as a hostile agent or repository would write them.
const _bad = '\u202e\x1b';

/// Control characters but line breaks and tabs, and bidirectional marks, overrides and isolates.
final _unsafe = RegExp(r'[\x00-\x08\x0b-\x1f\x7f-\x9f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]');

/// Fields the screen reads as names of its own choices, never shows as written; a hostile value would only be refused.
const _choices = {'kind', 'worker', 'revision', 'reason', 'role', 'mode', 'modes', 'choice', 'coordinator', 'per'};

/// [value] with every text an agent or a repository wrote carrying [_bad].
Object? _hostile(Object? value, {Set<String> keep = const {}}) => switch (value) {
  final String text => '$text$_bad',
  final List<Object?> list => [for (final item in list) _hostile(item, keep: keep)],
  final Map<String, dynamic> map => <String, dynamic>{
    for (final entry in map.entries)
      entry.key: _choices.contains(entry.key) || keep.contains(entry.key)
          ? entry.value
          : _hostile(entry.value, keep: keep),
  },
  _ => value,
};

/// Every text on screen: plain and selectable texts and the tooltips' messages.
List<String> _shown(WidgetTester tester) => [
  for (final text in tester.widgetList<Text>(find.byType(Text, skipOffstage: false)))
    text.data ?? text.textSpan?.toPlainText() ?? '',
  for (final text in tester.widgetList<SelectableText>(find.byType(SelectableText, skipOffstage: false)))
    text.data ?? text.textSpan?.toPlainText() ?? '',
  for (final tip in tester.widgetList<Tooltip>(find.byType(Tooltip, skipOffstage: false))) tip.message ?? '',
];

void _expectClean(WidgetTester tester, String where) {
  final shown = _shown(tester);
  expect(shown.where((text) => text.contains('First story')), isNotEmpty, reason: '$where shows the story');
  expect(shown.where(_unsafe.hasMatch), isEmpty, reason: '$where shows no control or bidirectional character');
}

/// A proposal whose helper names, agents, models and briefs carry [_bad]; repositories and issues are refused
/// otherwise, so they stay as the planner must send them.
Map<String, dynamic> _proposal() =>
    _hostile(servedProposal(), keep: {'repository', 'issues', 'issue', 'id'}) as Map<String, dynamic>;

/// A provider whose story work cannot be read: refused for the first story, not offered for the others.
class _WorkUnread extends FakePlanningSource {
  _WorkUnread(super.board);

  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) async => story.startsWith('S1')
      ? throw PlanningRefusal('Refused', {'detail': 'The work cannot be read.'})
      : super.getStoryWork(project, story);
}

/// The board view over [source], at a size that shows every card.
Future<PlanningBoards> _showBoard(WidgetTester tester, FakePlanningSource source) async {
  tester.view
    ..physicalSize = const Size(1600, 1200)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final client = FakePlanningClient([provider('local', source)]);
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await source.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(body: planningView(boards: boards)),
    ),
  );
  await settle(tester);
  return boards;
}

class _Source extends MockPlanningSource {
  _Source(Map<String, dynamic> board) {
    boards['demo'] = board;
  }

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues}) async =>
      StoryWorkOrder.readProposal(_proposal());

  @override
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request) async {
    final order = servedOrder();
    for (final task in (order['tasks'] as List).cast<Map<String, dynamic>>()) {
      task['agent'] = '${task['agent']}$_bad';
      task['model'] = '${task['model']}$_bad';
      task['prompt'] = '${task['prompt']}$_bad';
    }
    return StoryWorkOrder.from(order, request: request, proposal: StoryWorkOrder.readProposal(_proposal()));
  }
}

void main() {
  // Harm: a text that reorders what the person reads, such as a title that reads as harmless and is not.
  testWidgets('the board shows its cards, issues and tooltips without control or bidirectional characters', (
    tester,
  ) async {
    tester.view
      ..physicalSize = const Size(1600, 1200)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = FakePlanningSource(_hostile(smallBoard()) as Map<String, dynamic>);
    final client = FakePlanningClient([provider('local', source)]);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await source.close();
    });
    final board = boards.selected!;
    board.preferences.expandedStories.add('S1$_bad');
    // A folded state shows its stories as marks with a tooltip.
    board.preferences.foldedStates.add('Review$_bad');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    expect(find.byKey(const Key('planning-card S1$_bad')), findsOneWidget, reason: 'the story stands on the board');
    expect(find.byKey(const Key('planning-card S2$_bad')), findsOneWidget, reason: 'the folded story stands too');
    _expectClean(tester, 'the board');
  });

  // Harm: a brief or a task name that reads as harmless and is not, where the person decides what starts.
  testWidgets('the Start dialog shows the proposal and the started tasks without control characters', (tester) async {
    tester.view
      ..physicalSize = const Size(1600, 4000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final board = smallBoard()..['stories'] = [(smallBoard()['stories'] as List).first];
    final source = _Source(_hostile(board) as Map<String, dynamic>);
    final planning = PlanningBoard(source, 'demo');
    await tester.runAsync(pumpEventQueue);
    final work = planningStoryWork(
      planning,
      planning.stories.single,
      'Building$_bad',
      startTask: (source, project, story, task) async =>
          PlanningTaskStarted(task: 'task-${task.role}$_bad', container: 'box-${task.role}$_bad'),
    );
    addTearDown(() async {
      work.dispose();
      planning.dispose();
      await source.end();
    });
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkDialog(work))));
    await settle(tester);
    expect(work.proposal, isNotNull, reason: 'the proposal was read: ${work.problem}');
    work.chooseMode('AGENT');
    await tester.runAsync(work.start);
    await settle(tester);
    expect(work.tasks.map((task) => task.started?.task), ['task-MAIN$_bad', 'task-BUILDER$_bad']);
    for (final tile in find.byType(ExpansionTile).evaluate().toList()) {
      await tester.tap(find.byWidget(tile.widget));
      await settle(tester);
    }
    final shown = _shown(tester);
    expect(shown.where((text) => text.contains('task-MAIN')), isNotEmpty, reason: 'the started task is named');
    expect(shown.where((text) => text.contains('Build the sub-issue.')), isNotEmpty, reason: 'the brief is shown');
    _expectClean(tester, 'the Start dialog');
  });

  // Harm: a story's title or state that reads as harmless and is not, where the person decides about a run.
  testWidgets('an attention row and its story dialog show the story without control characters', (tester) async {
    tester.view
      ..physicalSize = const Size(1600, 2000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final board = smallBoard();
    final story = (board['stories'] as List).first as Map<String, dynamic>;
    board['states'] = [
      ...board['states'] as List,
      {'name': 'Reading', 'kind': 'WORKING'},
    ];
    story
      ..['state'] = 'Building'
      ..['issues'] = [
        {'id': 'I1', 'repository': 'repo', 'title': 'First issue', 'state': 'Building', 'needsYou': true},
      ]
      ..['work'] = {
        'atWork': false,
        'tasks': [
          {
            'task': 'build',
            'containerId': 'box-build',
            'machine': 'local',
            'repository': 'repo',
            'issues': ['I1'],
            'state': 'Building',
            'done': 'Reading',
            'mode': 'AGENT',
            'agent': 'codex',
            'model': 'model',
            'role': 'BUILDER',
            'since': '2026-01-01T00:00:00Z',
            'atWork': false,
            'ended': {'at': '2026-01-01T00:00:00Z', 'finished': false, 'why': 'It broke', 'answer': 'An answer'},
          },
        ],
        'issues': [
          {'issue': 'I1', 'reason': 'FAILED', 'needsYou': true},
        ],
      };
    final source = _Source(_hostile(board) as Map<String, dynamic>);
    final planning = PlanningBoard(source, 'demo');
    await tester.runAsync(pumpEventQueue);
    addTearDown(() async {
      planning.dispose();
      await source.end();
    });
    final rows = PlanningBoards.storyAttention(planning, planning.stories.first);
    expect(rows, hasLength(2), reason: 'the run\'s end and the failed issue need the person');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: Column(
              children: [
                for (final row in rows)
                  StoryWorkAttentionView(row: row, resolved: false, machine: 'local', taskChanges: const NoTasks()),
              ],
            ),
          ),
        ),
      ),
    );
    await settle(tester);
    _expectClean(tester, 'the attention rows');
    await tester.tap(find.text('Open story').first);
    await settle(tester);
    expect(
      _shown(tester).where((text) => text.startsWith('Building\n')),
      isNotEmpty,
      reason: 'the story dialog shows the story\'s state and why its run ended',
    );
    _expectClean(tester, 'the story dialog');
  });

  // Harm: a story or state name that reads as harmless and is not, in the notice of a move made elsewhere.
  testWidgets('the notices of pending moves settled elsewhere show no control characters', (tester) async {
    final source = FakePlanningSource(_hostile(smallBoard()) as Map<String, dynamic>);
    final board = (await _showBoard(tester, source)).selected!;
    board
      ..move('S1$_bad', 'Review$_bad')
      ..move('S2$_bad', 'Backlog$_bad');
    final stories = (source.board['stories'] as List).cast<Map<String, dynamic>>();
    stories.first['state'] = 'Review$_bad';
    stories.removeLast();
    await tester.runAsync(board.reload);
    await settle(tester);
    final shown = _shown(tester);
    expect(shown.where((text) => text.contains('elsewhere')), isNotEmpty, reason: 'the moved story is named');
    expect(shown.where((text) => text.contains('no longer reported')), isNotEmpty, reason: 'the gone story is named');
    _expectClean(tester, 'the move notices');
  });

  // Harm: a machine's agent name or failure that reads as harmless and is not, where the person decides what starts.
  testWidgets('the Start dialog shows the agents that could not be read without control characters', (tester) async {
    final board = smallBoard()..['stories'] = [(smallBoard()['stories'] as List).first];
    final source = _Source(_hostile(board) as Map<String, dynamic>);
    final planning = PlanningBoard(source, 'demo');
    await tester.runAsync(pumpEventQueue);
    final agents = FakeAgents()..failures = {'agent$_bad': 'The manifest cannot be read.$_bad'};
    final work = planningStoryWork(planning, planning.stories.single, 'Building$_bad', agents: agents);
    addTearDown(() async {
      work.dispose();
      planning.dispose();
      await source.end();
    });
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkDialog(work))));
    await settle(tester);
    final shown = _shown(tester);
    expect(shown.where((text) => text.contains('manifest cannot be read')), isNotEmpty, reason: 'the failure shows');
    _expectClean(tester, 'the Start dialog');
  });

  // Harm: a story id that reads as harmless and is not, in the line that says its work cannot be read.
  testWidgets('the board names stories whose work cannot be read without control characters', (tester) async {
    final board = smallBoard();
    for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {
      story['state'] = 'Building';
    }
    await _showBoard(tester, _WorkUnread(_hostile(board) as Map<String, dynamic>));
    final shown = _shown(tester);
    expect(shown.where((text) => text.contains('The work cannot be read.')), isNotEmpty, reason: 'the refusal shows');
    expect(shown.where((text) => text.contains('does not offer story work')), isNotEmpty, reason: 'unsupported shows');
    _expectClean(tester, 'the work problems');
  });
}
