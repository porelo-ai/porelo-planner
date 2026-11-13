import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

Map<String, dynamic> _discussion() => {
  'offer': servedOffer(7),
  'issues': <String>[],
  'role': 'DISCUSSION',
  'agent': 'codex',
  'model': 'idea-model',
  'mode': 'AGENT',
  'prompt': 'Discuss the idea.\n',
  'result': 'idea.json',
};

void main() {
  // Harm: the window cannot start a discussion the planner offered, or starts it with altered arguments.
  test('a discussion order reads without a repository and keeps its result', () {
    final task = PlanningTaskOrder.from(_discussion());
    expect(
      [
        task.offer,
        task.repository,
        task.issues,
        task.role,
        task.agent,
        task.model,
        task.mode,
        task.prompt,
        task.result,
      ],
      [
        servedOffer(7),
        null,
        <String>[],
        'DISCUSSION',
        'codex',
        'idea-model',
        'AGENT',
        'Discuss the idea.\n',
        'idea.json',
      ],
      reason: 'every Start argument must come through as served',
    );
  });

  // Harm: a task reaches Start over one repository it should not own, unattended, over issues, or hands back a
  // file outside its result directory.
  test('a discussion or task order that breaks the discussion rules is refused', () {
    expect(
      [PlanningTaskOrder.from(_discussion()).role, PlanningTaskOrder.from(servedFollowUp(1, role: 'MAIN')).role],
      ['DISCUSSION', 'MAIN'],
      reason: 'the unchanged fixtures must read, so each refusal below is its change\'s',
    );
    final changes = <String, Map<String, dynamic>>{
      'discussion with a repository': _discussion()..['repository'] = 'repo',
      'discussion with a null repository': _discussion()..['repository'] = null,
      'discussion unattended': _discussion()..['mode'] = 'UNATTENDED',
      'discussion in a shell': _discussion()..['mode'] = 'SHELL',
      'discussion over issues': _discussion()..['issues'] = ['PR1'],
      'discussion without a result': _discussion()..remove('result'),
      for (final name in ['../idea.json', 'a/idea.json', '..', '.', '.idea.json', '', ' idea.json', 'a\\b'])
        'discussion result $name': _discussion()..['result'] = name,
      'main without a repository': (servedFollowUp(1, role: 'MAIN')..remove('repository')),
      'main with a result': servedFollowUp(1, role: 'MAIN')..['result'] = 'idea.json',
      'reader with a result': servedFollowUp(1)..['result'] = 'idea.json',
      'discussion with an overlong agent': _discussion()..['agent'] = 'a' * 129,
      'discussion with an overlong model': _discussion()..['model'] = 'm' * 129,
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a reader started naming no build reads nothing and polls; another task given a build's work could change it.
  test('only a reader names the tasks whose work it reads, and it names at least one', () {
    expect(PlanningTaskOrder.from(servedFollowUp(1)..['reads'] = ['porelo-foo-project_foo-1']).reads, [
      'porelo-foo-project_foo-1',
    ], reason: 'the window passes the served tasks to Start unchanged');
    expect(
      PlanningTaskOrder.from(servedFollowUp(1, role: 'BUILDER')..remove('reads')).reads,
      isEmpty,
      reason: 'a builder reads no other task\'s work',
    );
    final changes = <String, Map<String, dynamic>>{
      'reader reading nothing': servedFollowUp(1)..['reads'] = <String>[],
      'reader without reads': servedFollowUp(1)..remove('reads'),
      'reader reading a bad name': servedFollowUp(1)..['reads'] = ['../foo'],
      'reader reading a non-string': servedFollowUp(1)..['reads'] = [4],
      'builder reading a build': servedFollowUp(1, role: 'BUILDER')..['reads'] = ['porelo-foo-project_foo-1'],
      'discussion reading a build': _discussion()..['reads'] = ['porelo-foo-project_foo-1'],
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a reading started by hand runs as a build, so its findings are never read and its issue moves on unread.
  test('a reading by hand is ordered as one reader per issue, each naming the build it reads', () {
    Map<String, dynamic> reading() => {
      ...servedProposal(),
      'repositories': [
        {
          'repository': 'repo',
          'issues': ['PR1', 'PR2'],
          'main': {'name': 'main', 'role': 'READER', 'agent': 'codex', 'model': 'main-model', 'brief': 'Read.'},
          'subAgents': <Map<String, dynamic>>[],
        },
      ],
    };
    final proposal = StoryWorkOrder.readProposal(reading());
    final request = StoryWorkRequest(
      project: 'demo',
      story: 'ST1',
      to: 'Reading',
      mode: 'AGENT',
      revision: 'r1',
      subAgents: const [],
      proposedRepositories: proposal.repositories,
      issues: const ['PR1', 'PR2'],
    );
    Map<String, dynamic> task(int offer, String issue, {String role = 'READER', List<String> reads = const ['b']}) => {
      'offer': servedOffer(offer),
      'repository': 'repo',
      'issues': [issue],
      'role': role,
      'agent': 'codex',
      'model': 'main-model',
      'mode': 'AGENT',
      'prompt': 'Read.\n',
      'reads': reads,
    };
    final order = StoryWorkOrder.from(
      {
        'revision': 'r1',
        'tasks': [task(1, 'PR1'), task(2, 'PR2')],
      },
      request: request,
      proposal: proposal,
    );
    expect(order.tasks.map((task) => task.role), ['READER', 'READER'], reason: 'one reader per issue');
    for (final (name, tasks) in [
      ('a build in place of a reader', [task(1, 'PR1', role: 'MAIN', reads: const []), task(2, 'PR2')]),
      ('a reader of no build', [task(1, 'PR1', reads: const []), task(2, 'PR2')]),
      ('one reader for both issues', [task(1, 'PR1')]),
    ]) {
      expect(
        () => StoryWorkOrder.from({'revision': 'r1', 'tasks': tasks}, request: request, proposal: proposal),
        throwsFormatException,
        reason: name,
      );
    }
    expect(
      () => StoryWorkOrder.readProposal(
        reading()
          ..['repositories'] = [
            {
              ...(reading()['repositories'] as List).single as Map<String, dynamic>,
              'subAgents': (servedProposal()['repositories'] as List).single['subAgents'],
            },
          ],
      ),
      throwsFormatException,
      reason: 'a reading has no helpers',
    );
  });

  // Harm: a story's work order smuggles a discussion or a result file into a story's Start.
  test('a story order refuses a task that carries a result', () {
    final answer = servedOrder();
    ((answer['tasks'] as List).last as Map)['result'] = 'idea.json';
    expect(
      () => StoryWorkOrder.from(
        answer,
        request: servedRequest(),
        proposal: StoryWorkOrder.readProposal(servedProposal()),
      ),
      throwsFormatException,
    );
  });

  // Harm: an altered task is accepted alongside valid tasks and reaches the wrong Start.
  test('an order rejects the whole answer when any task or envelope is invalid', () {
    final proposal = StoryWorkOrder.readProposal(servedProposal());
    final changes = <void Function(Map<String, dynamic>)>[
      (answer) => answer['revision'] = 'r2',
      (answer) => answer.remove('tasks'),
      (answer) => answer['tasks'] = [false],
      (answer) => (answer['tasks'] as List).removeLast(),
      (answer) => (answer['tasks'] as List).add((answer['tasks'] as List).first),
      for (final field in ['offer', 'repository', 'issues', 'role', 'agent', 'model', 'mode', 'prompt'])
        (answer) => ((answer['tasks'] as List).last as Map).remove(field),
      for (final value in [null, false, 4, ' ', 'other'])
        (answer) => ((answer['tasks'] as List).last as Map)['model'] = value,
      for (final value in ['../repo', 'other', 'Repo'])
        (answer) => ((answer['tasks'] as List).last as Map)['repository'] = value,
      for (final value in [
        <String>[],
        ['PR9'],
        ['PR1.1', 'PR1.1'],
        ['bad'],
        [4],
      ])
        (answer) => ((answer['tasks'] as List).last as Map)['issues'] = value,
      for (final value in ['READER', 'OTHER', 'MAIN'])
        (answer) => ((answer['tasks'] as List).last as Map)['role'] = value,
      for (final value in ['UNATTENDED', 'FUTURE']) (answer) => ((answer['tasks'] as List).last as Map)['mode'] = value,
      (answer) => ((answer['tasks'] as List).last as Map)['prompt'] = 'é' * 32769,
      (answer) => ((answer['tasks'] as List).last as Map)['offer'] = '',
      (answer) {
        for (final task in answer['tasks'] as List) {
          (task as Map)['offer'] = 'same';
        }
      },
    ];
    for (final (index, change) in changes.indexed) {
      final answer = servedOrder();
      answer['tasks'] = [for (final task in answer['tasks'] as List) Map<String, dynamic>.from(task as Map)];
      change(answer);
      expect(
        () => StoryWorkOrder.from(answer, request: servedRequest(), proposal: proposal),
        throwsFormatException,
        reason: 'mutation $index',
      );
    }
  });

  // Harm: strict decoding rewrites Start arguments or treats a prepared order as running work.
  test('an accepted order preserves every Start argument and remains immutable', () {
    final answer = servedOrder();
    ((answer['tasks'] as List).first as Map)['offer'] = servedOffer(1);
    ((answer['tasks'] as List).last as Map)['prompt'] = 'é' * 32768;
    final order = StoryWorkOrder.from(
      answer,
      request: servedRequest(),
      proposal: StoryWorkOrder.readProposal(servedProposal()),
    );
    final task = order.tasks.first;
    expect(
      [task.offer, task.repository, task.issues, task.role, task.agent, task.model, task.mode, task.prompt],
      [
        servedOffer(1),
        'repo',
        ['PR1', 'PR1.1'],
        'MAIN',
        'codex',
        'main-model',
        'AGENT',
        ((answer['tasks'] as List).first as Map)['prompt'],
      ],
    );
    expect(() => order.tasks.clear(), throwsUnsupportedError);
    expect(() => task.issues.clear(), throwsUnsupportedError);
  });

  // Harm: a chosen builder is silently replaced or an explicitly dropped helper is still started.
  test('an order matches changed main and builder choices and omits dropped builders', () {
    final proposal = StoryWorkOrder.readProposal(servedProposal());
    for (final choice in ['KEEP', 'CHANGE', 'DROP']) {
      final request = StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        revision: 'r1',
        subAgents: [
          StoryWorkChoice(repository: 'repo', name: 'builder', choice: choice, agent: 'other', model: 'chosen'),
        ],
        repositories: const [StoryWorkMainAgent(repository: 'repo', agent: 'main-agent', model: 'chosen-main')],
        proposedRepositories: proposal.repositories,
        comment: choice == 'KEEP' ? null : 'Choose this work.',
      );
      final answer = servedOrder();
      ((answer['tasks'] as List).first as Map).addAll(<String, Object>{'agent': 'main-agent', 'model': 'chosen-main'});
      if (choice == 'CHANGE') {
        ((answer['tasks'] as List).last as Map).addAll(<String, Object>{'agent': 'other', 'model': 'chosen'});
      }
      if (choice == 'DROP') (answer['tasks'] as List).removeLast();
      expect(
        StoryWorkOrder.from(answer, request: request, proposal: proposal).tasks,
        hasLength(choice == 'DROP' ? 1 : 2),
      );
    }
  });

  // Harm: a reader or unknown participant is kept or changed into an initial task.
  test('only builders can be kept or changed at start', () {
    for (final role in ['READER', 'TESTER', 'OTHER']) {
      final map = servedProposal();
      final repo = (map['repositories'] as List).single as Map;
      ((repo['subAgents'] as List).single as Map)['kind'] = role;
      final proposal = StoryWorkProposal.from(map);
      final request = StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        revision: 'r1',
        subAgents: const [
          StoryWorkChoice(repository: 'repo', name: 'builder', choice: 'CHANGE', agent: 'x', model: 'y'),
        ],
        proposedRepositories: proposal.repositories,
      );
      final selected = (request.toMap()['repositories'] as List).single as Map;
      expect(((selected['subAgents'] as List).single as Map)['choice'], 'DROP');
      final answer = servedOrder();
      expect(() => StoryWorkOrder.from(answer, request: request, proposal: proposal), throwsFormatException);
    }
  });

  // Harm: a default order starts a proposed reader as an unreviewed builder.
  test('a default order refuses a kept helper that is not a builder', () {
    for (final role in ['READER', 'OTHER', '']) {
      final map = servedProposal();
      final repo = (map['repositories'] as List).single as Map;
      ((repo['subAgents'] as List).single as Map)['kind'] = role;
      expect(
        () => StoryWorkOrder.from(
          servedOrder(),
          request: servedRequest(defaults: true),
          proposal: StoryWorkOrder.readProposal(map),
        ),
        throwsFormatException,
        reason: role,
      );
    }
  });
  // Harm: an effort dropped or replaced between the planner, the person and the window.
  test('an order keeps the effort and its origin as served, and one served without an effort has none', () {
    final served = PlanningTaskOrder.from(
      servedFollowUp(1)..addAll(<String, Object>{'effort': 'max', 'chosenBy': 'Reading, risk harm'}),
    );
    expect([served.effort, served.chosenBy], ['max', 'Reading, risk harm'], reason: 'both reach the hook as served');
    final none = PlanningTaskOrder.from(servedFollowUp(1));
    expect([none.effort, none.chosenBy], [null, null], reason: 'an order without an effort starts with none');
    for (final value in <Object>['', ' ', 'high\u0001', 'high\u2028', 'e' * 129, 4]) {
      expect(
        () => PlanningTaskOrder.from(servedFollowUp(1)..['effort'] = value),
        throwsFormatException,
        reason: 'effort $value',
      );
    }
  });

  // Harm: the window starts another effort than the one the person confirmed.
  test('an order whose effort differs from the one sent back is refused before any start', () {
    final map = servedProposal();
    final repo = (map['repositories'] as List).single as Map<String, dynamic>;
    (repo['main'] as Map).addAll(<String, String>{'effort': 'max', 'chosenBy': 'Building, risk harm'});
    ((repo['subAgents'] as List).single as Map).addAll(<String, String>{
      'effort': 'max',
      'chosenBy': 'Building, risk harm',
    });
    final proposal = StoryWorkOrder.readProposal(map);
    expect(proposal.repositories.single.main.effort, 'max', reason: 'the rule\'s effort is read');
    final request = StoryWorkRequest(
      project: 'demo',
      story: 'ST1',
      to: 'Building',
      mode: 'AGENT',
      revision: 'r1',
      subAgents: const [StoryWorkChoice(repository: 'repo', name: 'builder', choice: 'KEEP')],
      repositories: const [StoryWorkMainAgent(repository: 'repo', agent: 'codex', model: 'main-model', effort: 'low')],
      proposedRepositories: proposal.repositories,
    );
    final sent = (request.toMap()['repositories'] as List).single as Map<String, dynamic>;
    expect((sent['main'] as Map)['effort'], 'low', reason: 'the changed effort is sent back');
    expect((sent['main'] as Map).containsKey('chosenBy'), isFalse, reason: 'where it came from is the planner\'s');
    expect(((sent['subAgents'] as List).single as Map)['effort'], 'max', reason: 'a kept helper keeps the offer\'s');
    Map<String, dynamic> answer(Object? main, Object? builder) {
      final served = servedOrder();
      final tasks = [for (final task in served['tasks'] as List) Map<String, dynamic>.from(task as Map)];
      if (main != null) tasks.first['effort'] = main;
      if (builder != null) tasks.last['effort'] = builder;
      return served..['tasks'] = tasks;
    }

    expect(
      StoryWorkOrder.from(answer('low', 'max'), request: request, proposal: proposal).tasks.map((task) => task.effort),
      ['low', 'max'],
      reason: 'the efforts sent back reach the hook',
    );
    for (final (main, builder) in [('max', 'max'), (null, 'max'), ('low', null), ('low', 'low')]) {
      expect(
        () => StoryWorkOrder.from(answer(main, builder), request: request, proposal: proposal),
        throwsFormatException,
        reason: 'main $main, builder $builder',
      );
    }
  });
}
