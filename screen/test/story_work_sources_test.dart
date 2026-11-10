import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _board(
  String revision, {
  bool completeWork = true,
  String workingState = 'Building',
  bool includeStory = true,
}) => {
  'states': <Map<String, dynamic>>[
    {'name': workingState, 'kind': 'WORKING'},
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Done', 'kind': 'CLOSED'},
  ],
  'phases': <Map<String, dynamic>>[],
  'revision': revision,
  'stories': <Map<String, dynamic>>[
    if (includeStory)
      {
        'id': 'story',
        'state': completeWork ? 'Backlog' : workingState,
        'issues': <Map<String, dynamic>>[],
        if (completeWork) 'work': {'atWork': false},
        if (completeWork) 'ended': null,
      },
  ],
};
Map<String, dynamic> _proposal(String model) => {
  'modes': ['UNATTENDED'],
  'coordinator': 'START',
  'comment': '',
  'readingRounds': 3,
  'issueReadingRounds': <Map<String, dynamic>>[],
  'repositories': [
    {
      'repository': 'repo',
      'issues': <String>[],
      'main': {'name': 'main', 'role': 'MAIN', 'agent': 'agent', 'model': model, 'brief': 'Work'},
      'subAgents': <Map<String, dynamic>>[],
    },
  ],
};

class _Source extends MockPlanningSource {
  _Source();
  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to) {
    final proposal = work.proposals[story];
    if (proposal != null) proposal['revision'] = boards[project]!['revision'];
    return super.proposeStoryWork(project, story, to);
  }

  int workReads = 0;
  Object? workError;
  Completer<StoryWorkStatus>? heldWork;
  Completer<PlanningTaskStarted>? heldStart;
  @override
  Future<PlanningTaskStarted> simulateStart(String project, String story, PlanningTaskOrder task) =>
      heldStart?.future ?? super.simulateStart(project, story, task);
  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) async {
    workReads++;
    if (heldWork case final held?) return held.future;
    if (workError case final error?) throw error;
    return super.getStoryWork(project, story);
  }
}

void main() {
  // Harm: choices are sent to the old machine or use its revision after changing providers.
  test('changing machine re-proposes and sends its repository choice at its revision', () async {
    final first = _Source()..boards['demo'] = _board('first');
    final second = _Source()..boards['demo'] = _board('second');
    first.work.proposals['story'] = _proposal('first-model');
    second.work.proposals['story'] = _proposal('second-model');
    final a = PlanningBoard(first, 'demo');
    final b = PlanningBoard(second, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(
      a,
      a.stories.single,
      'Building',
      machines: {a: 'first machine', b: 'second machine'},
    );
    await work.load();
    work.changeMachine(b);
    await Future<void>.delayed(Duration.zero);
    expect(work.repositories['repo']!.model, 'second-model');
    work.chooseMain('repo', agent: 'changed-agent', model: 'chosen-model');
    expect(work.needsComment, isFalse);
    expect(work.canStart, isTrue);
    expect(await work.start(), isTrue);
    expect(first.work.calls.where((call) => call.$1 == 'StartStoryWork'), isEmpty);
    final sent = second.work.calls.where((call) => call.$1 == 'StartStoryWork').single.$2;
    expect(sent['revision'], 'second');
    expect(sent['repositories'], [
      {
        'repository': 'repo',
        'issues': <String>[],
        'main': {'name': 'main', 'role': 'MAIN', 'agent': 'changed-agent', 'model': 'chosen-model', 'brief': 'Work'},
        'subAgents': <Map<String, dynamic>>[],
      },
    ]);
    work.dispose();
    a.dispose();
    b.dispose();
    await first.end();
    await second.end();
  });

  // Harm: a start answer opens its terminal on a machine chosen while that start was pending.
  test('a submitted start stays bound to its original machine', () async {
    final first = _Source()..boards['demo'] = _board('first');
    final second = _Source()..boards['demo'] = _board('second');
    first.work.proposals['story'] = _proposal('model');
    first.heldStart = Completer<PlanningTaskStarted>();
    final a = PlanningBoard(first, 'demo');
    final b = PlanningBoard(second, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(a, a.stories.single, 'Building', machines: {a: 'first', b: 'second'});
    await work.load();
    final pending = work.start();
    await pumpEventQueue();
    expect(work.starting, isTrue);
    work.changeMachine(b);
    expect(identical(work.board, a), isTrue);
    first.heldStart!.complete(const PlanningTaskStarted(task: 'first-task', container: 'first-container'));
    expect(await pending, isTrue);
    expect(work.starting, isFalse);
    expect(work.startedTasks.single.$2.task, 'first-task');
    expect(identical(work.backend, first), isTrue);
    work.dispose();
    a.dispose();
    b.dispose();
    await first.end();
    await second.end();
  });

  // Harm: a partial or failed agent list lets a start proceed and permanently poisons retries.
  test('agent read failure blocks start and a successful retry clears it', () async {
    final backend = _Source()..boards['demo'] = _board('revision');
    backend.work.proposals['story'] = _proposal('model');
    final agents = FakeAgents()..failures = {'agent': 'The agent manifest cannot be read.'};
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(board, board.stories.single, 'Building', agents: agents);
    await work.load();
    expect(work.canStart, isFalse);
    expect(work.problem, contains('The agent manifest cannot be read.'));
    agents.failures = {};
    await work.load();
    expect(work.problem, isNull);
    expect(work.canStart, isTrue);
    work.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a second work-status call replaces authoritative watch data or floods an unsupported daemon.
  test('complete watch work avoids GetStoryWork and missing fields preserve unsupported status', () async {
    final backend = _Source()..boards['demo'] = _board('first');
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    expect(backend.workReads, 0);
    expect(board.workOf(board.stories.single)!.atWork, isFalse);
    backend.workError = const PlanningUnsupported('this backend has no story work status');
    backend.push('demo', _board('second', completeWork: false));
    await Future<void>.delayed(Duration.zero);
    expect(backend.workReads, 1);
    expect(board.workUnsupported, {'story'});
    expect(board.live, isTrue);
    expect(board.unsupported, isFalse);
    expect(board.workProblems, isEmpty);
    expect(board.startUnavailable(board.stories.single), 'the machine does not offer story work status');
    backend.push('demo', _board('third'));
    await Future<void>.delayed(Duration.zero);
    expect(backend.workReads, 1);
    expect(board.workUnsupported, isEmpty);
    expect(board.startUnavailable(board.stories.single), isNull);
    board.dispose();
    await backend.end();
  });

  // Harm: a late work-status error from an old project disables a replacement source.
  test('late fallback error cannot modify a new source generation', () async {
    final old = _Source()..boards['demo'] = _board('old', completeWork: false);
    old.heldWork = Completer<StoryWorkStatus>();
    final next = _Source()..boards['demo'] = _board('new');
    final board = PlanningBoard(old, 'demo');
    await Future<void>.delayed(Duration.zero);
    expect(old.workReads, 1);
    await board.changeProject(next, 'demo');
    await Future<void>.delayed(Duration.zero);
    old.heldWork!.completeError(mockRefusal('org.fuin.porelo.Tasks1.Refused', {'detail': 'Old source failure.'}));
    await Future<void>.delayed(Duration.zero);
    expect(board.workProblems, isEmpty);
    expect(board.snapshot!.revision, 'new');
    expect(board.startUnavailable(board.stories.single), isNull);
    board.dispose();
    await old.end();
    await next.end();
  });

  // Harm: a retained attention row attaches or detaches a listener on a disposed source.
  test('a retained reading decision never subscribes to a disposed board', () async {
    final backend = _Source()..boards['demo'] = _board('revision');
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    final story = board.stories.single;
    board.dispose();
    final reading = ReadingDecision(
      PlanningAttention(board, story, issue: PlanIssue.from({'id': 'item', 'rounds': 3})),
    );
    expect(reading.current, isFalse);
    reading.dispose();
    await backend.end();
  });

  // Harm: an action error is stored without rebuilding its visible board error.
  test('action errors notify their board and stop notifying after disposal', () async {
    final backend = _Source()..boards['demo'] = _board('revision');
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    var notifications = 0;
    board.addListener(() => notifications++);
    board.reportActionProblem(mockRefusal('org.fuin.porelo.Tasks1.Refused', {'detail': 'Cannot open the terminal.'}));
    expect(board.actionProblem, 'Cannot open the terminal.');
    expect(notifications, 1);
    board.dispose();
    board.reportActionProblem('Late error');
    expect(notifications, 1);
    expect(board.actionProblem, 'Cannot open the terminal.');
    await backend.end();
  });

  // Harm: malformed proposals start no repository work or an unchanged Change silently passes validation.
  test('empty repositories and unchanged Change cannot start', () async {
    final backend = _Source()..boards['demo'] = _board('revision');
    backend.work.proposals['story'] = {
      'modes': ['UNATTENDED'],
      'readingRounds': 3,
      'repositories': <Map<String, dynamic>>[],
    };
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(board, board.stories.single, 'Building');
    await work.load();
    expect(await work.start(), isFalse);
    backend.work.proposals['story'] = {
      ..._proposal('model'),
      'repositories': [
        {
          'repository': 'repo',
          'issues': <String>[],
          'main': {'name': 'main', 'role': 'MAIN', 'agent': 'agent', 'model': 'model', 'brief': 'Work'},
          'subAgents': [
            {
              'id': 'reader',
              'issue': 'item',
              'kind': 'BUILDER',
              'workingState': 'Reading',
              'brief': 'Read',
              'choice': 'KEEP',
              'name': 'reader',
              'agent': 'agent',
              'model': 'proposal-model',
            },
          ],
        },
      ],
    };
    await work.load();
    work.choose('repo', work.proposal!.repositories.single.subAgents.single, 'CHANGE');
    work.setComment('Use a chosen reader.');
    expect(work.choices[('repo', 'reader')]!.model, 'proposal-model');
    expect(await work.start(), isFalse);
    work.choose('repo', work.proposal!.repositories.single.subAgents.single, 'CHANGE', agent: 'other-agent');
    expect(work.choices[('repo', 'reader')]!.model, 'proposal-model');
    expect(work.canStart, isTrue);
    work.dispose();
    board.dispose();
    await backend.end();
  });
  // Harm: an idle story is blocked or performs fallback calls that belong only to working stories.
  test('waiting and closed stories without work are idle and never read GetStoryWork', () async {
    for (final kind in ['WAITING', 'CLOSED']) {
      final backend = _Source();
      final snapshot = _board('initial', completeWork: false);
      (snapshot['states'] as List).add(<String, dynamic>{'name': 'Idle', 'kind': kind});
      ((snapshot['stories'] as List).single as Map<String, dynamic>)['state'] = 'Idle';
      backend.boards['demo'] = snapshot;
      backend.workError = const PlanningUnsupported('this backend has no story work');
      final board = PlanningBoard(backend, 'demo');
      await Future<void>.delayed(Duration.zero);
      expect(backend.workReads, 0);
      expect(board.startUnavailable(board.stories.single), isNull);
      expect(board.workOf(board.stories.single), isNull);
      backend.push('demo', _board('working', completeWork: false));
      await Future<void>.delayed(Duration.zero);
      expect(backend.workReads, 1);
      expect(board.startUnavailable(board.stories.single), 'the machine does not offer story work status');
      final unknown = _board('unknown', completeWork: false);
      ((unknown['stories'] as List).single as Map<String, dynamic>)['work'] = <String, dynamic>{};
      backend.push('demo', unknown);
      await Future<void>.delayed(Duration.zero);
      // Work without its tasks is incomplete, so the fallback read is tried again.
      expect(backend.workReads, 2);
      expect(board.startUnavailable(board.stories.single), 'the machine does not offer story work status');
      board.dispose();
      await backend.end();
    }
  });

  // Harm: a different machine starts the old machine's story or gets stuck on its obsolete state name.
  test('a selected source uses its working state and rejects a missing story', () async {
    final first = _Source()..boards['demo'] = _board('first');
    final next = _Source()..boards['demo'] = _board('next', workingState: 'Investigating');
    final missing = _Source()..boards['demo'] = _board('missing', includeStory: false);
    first.work.proposals['story'] = _proposal('first-model');
    next.work.proposals['story'] = _proposal('next-model');
    final a = PlanningBoard(first, 'demo');
    final b = PlanningBoard(next, 'demo');
    final c = PlanningBoard(missing, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(a, a.stories.single, 'Building', machines: {a: 'first', b: 'next', c: 'missing'});
    await work.load();
    work.changeMachine(b);
    await Future<void>.delayed(Duration.zero);
    expect(work.to, 'Investigating');
    expect(work.canStart, isTrue);
    expect(next.work.calls.where((call) => call.$1 == 'ProposeStoryWork').single.$2['to'], 'Investigating');
    work.changeMachine(c);
    expect(identical(work.board, c), isTrue);
    expect(work.story.title, isEmpty);
    expect(work.proposal, isNull);
    expect(work.problem, 'The selected machine did not report story story.');
    expect(work.canStart, isFalse);
    await work.load();
    expect(missing.work.calls.where((call) => call.$1 == 'ProposeStoryWork'), isEmpty);
    work.dispose();
    a.dispose();
    b.dispose();
    c.dispose();
    await first.end();
    await next.end();
    await missing.end();
  });

  // Harm: an invalid replacement prevents dropping the builder and starting only the main task.
  test('a dropped builder needs no replacement agent or model', () async {
    final backend = _Source()..boards['demo'] = _board('revision');
    backend.work.proposals['story'] = {
      ..._proposal('model'),
      'repositories': [
        {
          'repository': 'repo',
          'issues': ['PR1'],
          'main': {'name': 'main', 'role': 'MAIN', 'agent': 'agent', 'model': 'model', 'brief': 'Work'},
          'subAgents': [
            {
              'id': 'reader',
              'issue': 'PR1',
              'kind': 'BUILDER',
              'workingState': 'Reading',
              'brief': 'Read',
              'choice': 'KEEP',
              'name': 'reader',
              'agent': 'reader-agent',
              'model': 'builder-model',
            },
          ],
        },
      ],
    };
    final board = PlanningBoard(backend, 'demo');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(board, board.stories.single, 'Building');
    await work.load();
    work.choose('repo', work.proposal!.repositories.single.subAgents.single, 'CHANGE', model: '');
    expect(await work.start(), isFalse);
    expect(backend.work.calls.where((call) => call.$1 == 'StartStoryWork'), isEmpty);
    work.choose('repo', work.proposal!.repositories.single.subAgents.single, 'DROP');
    work.setComment('The main agent handles the reading.');
    expect(await work.start(), isTrue);
    expect(
      (((backend.work.calls.where((call) => call.$1 == 'StartStoryWork').single.$2['repositories'] as List).single
                  as Map)['subAgents']
              as List)
          .single['choice'],
      'DROP',
    );
    work.dispose();
    board.dispose();
    await backend.end();
  });
}
