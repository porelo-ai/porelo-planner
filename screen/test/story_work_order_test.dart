import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

void main() {
  // Harm: an altered task is accepted alongside valid tasks and reaches the wrong Start.
  test('an order rejects the whole answer when any task or envelope is invalid', () {
    final proposal = StoryWorkOrder.readProposal(servedProposal());
    final changes = <void Function(Map<String, dynamic>)>[
      (answer) => answer['revision'] = 'r2',
      (answer) => answer.remove('tasks'),
      (answer) => answer['tasks'] = [false],
      (answer) => (answer['tasks'] as List).removeLast(),
      (answer) => (answer['tasks'] as List).add((answer['tasks'] as List).first),
      for (final field in ['repository', 'issues', 'role', 'agent', 'model', 'mode', 'prompt'])
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
    ((answer['tasks'] as List).first as Map)['offer'] = 'offer-one';
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
        'offer-one',
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
}
