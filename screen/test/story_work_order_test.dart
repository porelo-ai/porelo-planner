import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

Map<String, dynamic> _discussion() => {
  'offer': servedOffer(7),
  'kind': 'discussion',
  'opensTerminal': true,
  'issues': <String>[],
  'agent': 'codex',
  'model': 'idea-model',
  'mode': 'AGENT',
  'prompt': 'Discuss the idea.\n',
  'readOnly': true,
  'allRepositories': true,
  'rights': ['report'],
  'readLanded': <String>[],
  'projectServices': true,
};

/// A task of [role] at another task's work, at the pull request it reads, as the planner names it.
Map<String, dynamic> _atPullRequest(String role) => servedFollowUp(1, role: role)
  ..addAll({
    'place': {'pullRequest': 'https://github.com/acme/shop/pull/12'},
    'writeAs': mockRole(role)['writeAs'],
  });

/// A stage of the analyst, which hands back a result, over PR1.
Map<String, dynamic> _stage() => servedFollowUp(1, role: 'analyst');

void main() {
  // Harm: the window cannot start a discussion the planner offered, or starts it with altered arguments.
  test('a discussion order reads without a repository, a role or a result file', () {
    final task = PlanningTaskOrder.from(_discussion());
    expect(
      [
        task.offer,
        task.kind,
        task.repository,
        task.issues,
        task.role,
        task.agent,
        task.model,
        task.mode,
        task.prompt,
        task.result,
        task.opensTerminal,
        task.readOnly,
        task.allRepositories,
        task.rights,
      ],
      [
        servedOffer(7),
        'discussion',
        null,
        <String>[],
        null,
        'codex',
        'idea-model',
        'AGENT',
        'Discuss the idea.\n',
        null,
        true,
        true,
        true,
        ['report'],
      ],
      reason: 'every Start argument must come through as served',
    );
  });

  // Harm: a task reaches Start over one repository it should not own, unattended, over issues, or hands back a
  // file outside its result directory.
  test('a discussion or task order that breaks the discussion rules is refused', () {
    expect(
      [PlanningTaskOrder.from(_discussion()).kind, PlanningTaskOrder.from(servedFollowUp(1, role: 'author')).kind],
      ['discussion', 'work'],
      reason: 'the unchanged fixtures must read, so each refusal below is its change\'s',
    );
    final changes = <String, Map<String, dynamic>>{
      'discussion with a repository': _discussion()..['repository'] = 'repo',
      'discussion with a null repository': _discussion()..['repository'] = null,
      'discussion unattended': _discussion()..['mode'] = 'UNATTENDED',
      'discussion in a shell': _discussion()..['mode'] = 'SHELL',
      'discussion over issues': _discussion()..['issues'] = ['PR1'],
      'discussion with a role': _discussion()..['role'] = mockRole('author'),
      // Harm: a discussion told to hand back a file nobody reads, so the person's idea is lost.
      for (final name in ['idea.json', '../idea.json', ''])
        'discussion with the result $name': _discussion()..['result'] = name,
      'discussion with a null result': _discussion()..['result'] = null,
      'work without a repository': (servedFollowUp(1, role: 'author')..remove('repository')),
      'work without a role': (servedFollowUp(1, role: 'author')..remove('role')),
      'work of another kind': (servedFollowUp(1, role: 'author')..['kind'] = 'build'),
      'work without a kind': (servedFollowUp(1, role: 'author')..remove('kind')),
      'work that pushes with a result': servedFollowUp(1, role: 'author')..['result'] = 'analysis.json',
      'a reading with a result': servedFollowUp(1)..['result'] = 'analysis.json',
      'discussion with an overlong agent': _discussion()..['agent'] = 'a' * 129,
      'discussion with an overlong model': _discussion()..['model'] = 'm' * 129,
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a role the screen reads otherwise than the planner served it is shown, or decides a start, wrongly; a role
  // named by a word the screen never heard of is refused.
  test('a role of any name is read with its end kind and parts, and one not of its shape refuses the task', () {
    final role = PlanningTaskOrder.from(_atPullRequest('tester')).role!;
    expect(
      [role.name, role.endKind, role.workspace, role.readLanded, role.projectServices, role.writeAs, role.rights],
      ['tester', PlanningEndKind.reportsFindings, 'foreign', false, false, 'review', ?mockRole('tester')['rights']],
    );
    expect(role.readsWork, isTrue, reason: 'it reports findings, so it reads the work');
    expect(role.toJson(), mockRole('tester'), reason: 'it reads back as served');
    Map<String, dynamic> roled(Map<String, dynamic> change) {
      final bound = {...mockRole('tester'), ...change};
      return _atPullRequest('tester')
        ..['role'] = bound
        ..['rights'] = bound['rights'];
    }

    final changes = <String, Map<String, dynamic>>{
      'a role name in place of a role': servedFollowUp(1)..['role'] = 'tester',
      'an empty name': roled({'name': ''}),
      'a name over 64 code points': roled({'name': 'é' * 65}),
      'a name with a line break': roled({'name': 'test\ner'}),
      // A name shown as given turns no text around it, joins nothing and hides nothing.
      for (final mark in ['\u202E', '\u200B', '\uFEFF', '\u2066', '\u061C', '\u200F', '\u2060'])
        'a name with U+${mark.runes.single.toRadixString(16).toUpperCase().padLeft(4, '0')}': roled({
          'name': 'te${mark}ster',
        }),
      'services at another task\'s work': roled({'projectServices': true}),
      'an unknown end kind': roled({'endKind': 'READS'}),
      'an unknown workspace': roled({'workspace': 'mine'}),
      'an unknown account': roled({'writeAs': 'own'}),
      'readLanded not a flag': roled({'readLanded': 'yes'}),
      'project services not a flag': roled({'projectServices': null}),
      'a right Porelo does not know': roled({
        'rights': ['readOthers'],
      }),
      'a right twice': roled({
        'rights': ['report', 'report'],
      }),
      'another member': servedFollowUp(1)..['role'] = {...mockRole('tester'), 'runRole': 'x'},
      'a member missing': servedFollowUp(1)..['role'] = ({...mockRole('tester')}..remove('writeAs')),
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
    final named = {...mockRole('tester'), 'name': 'Prüfer 2'};
    expect(
      PlanningTaskOrder.from(_atPullRequest('tester')..['role'] = named).role?.name,
      'Prüfer 2',
      reason: 'a name the person gave is shown as given',
    );
  });

  // Harm: the window derives or drops a part of Start, so Porelo starts the task with other rights, another
  // workspace or another account than the planner ordered; or a part the planner never hands on reaches Start.
  test("a work order hands Start's members as served and refuses one Start is not handed", () {
    final order = PlanningTaskOrder.from(_atPullRequest('screener'));
    expect(
      [
        order.rights,
        order.readLanded,
        order.projectServices,
        order.writeAs,
        order.noGate,
        order.readOnly,
        order.allRepositories,
        order.opensTerminal,
      ],
      [
        ['reply', 'openThreads', 'reject'],
        <String>[],
        false,
        'review',
        false,
        false,
        false,
        false,
      ],
    );
    final reading = PlanningTaskOrder.from(servedFollowUp(1, role: 'reader'));
    expect(reading.readLanded, ['builder-task'], reason: 'the tasks whose work it reads besides, as served');
    final changes = <String, Map<String, dynamic>>{
      'no rights': servedFollowUp(1)..remove('rights'),
      'other rights than the role': servedFollowUp(1)..['rights'] = ['openThreads', 'report', 'push'],
      'fewer rights than the role': servedFollowUp(1)..['rights'] = <String>[],
      'no readLanded': servedFollowUp(1)..remove('readLanded'),
      'a bad task name read': servedFollowUp(1)..['readLanded'] = ['../foo'],
      'a task read that is no name': servedFollowUp(1)..['readLanded'] = [4],
      'landed work read by a role at its own work': servedFollowUp(1, role: 'author')..['readLanded'] = ['b'],
      'landed work read by a discussion': _discussion()..['readLanded'] = ['b'],
      'landed work read at another task\'s work': _atPullRequest('tester')..['readLanded'] = ['b'],
      'no project services': servedFollowUp(1)..remove('projectServices'),
      'project services not a flag': servedFollowUp(1)..['projectServices'] = 'no',
      'project services other than the role': servedFollowUp(1)..['projectServices'] = false,
      'project services at another task\'s work': _atPullRequest('tester')..['projectServices'] = true,
      // Start's workspace members are the role's workspace, in Start's words.
      'a read-only role without readOnly': servedFollowUp(1)..remove('readOnly'),
      'a read-only role over every repository': servedFollowUp(1)..['allRepositories'] = true,
      'a read-only role without a gate': servedFollowUp(1)..['noGate'] = true,
      'a role at its own work read only': servedFollowUp(1, role: 'author')..['readOnly'] = true,
      'a role at its own work without a gate': servedFollowUp(1, role: 'author')..['noGate'] = true,
      'a role at another task\'s work read only': _atPullRequest('tester')..['readOnly'] = true,
      'a role at another task\'s work without a gate': _atPullRequest('tester')..['noGate'] = true,
      'a discussion not over every repository': _discussion()..remove('allRepositories'),
      'a discussion not read only': _discussion()..remove('readOnly'),
      'a discussion without a gate': _discussion()..['noGate'] = true,
      // A task at another task's work always names that work's place, and asks Start under a name of its own.
      'another task\'s work at no place': servedFollowUp(1, role: 'tester'),
      'another task\'s work under its own task\'s name': _atPullRequest('tester')..remove('ownTask'),
      'an account at no pull request': servedFollowUp(1)..['writeAs'] = 'review',
      'an account Porelo does not know': servedFollowUp(1)
        ..addAll({
          'place': {'pullRequest': 'https://github.com/acme/shop/pull/12'},
          'writeAs': 'own',
        }),
      for (final key in ['opensTerminal', 'readOnly', 'allRepositories', 'noGate']) ...{
        '$key as false': servedFollowUp(1)..[key] = false,
        '$key as a word': servedFollowUp(1)..[key] = 'true',
      },
      'credentials': servedFollowUp(1)..['credentials'] = ['token'],
      'a run role': servedFollowUp(1)..['runRole'] = 'x',
      'the tasks it reads by their old name': servedFollowUp(1)..['reads'] = ['builder-task'],
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: an author sent back to its work starts as a new task beside its own, or a task at another task's work is
  // run as that task, with its workspace.
  test('a rework order names the task it runs again, and one at another task\'s work or a bad name is refused', () {
    final rework = servedFollowUp(1, role: 'author')
      ..addAll({
        'task': 'builder-task',
        'place': {
          'gatePush': {'name': 'builder-task'},
        },
      });
    expect(PlanningTaskOrder.from(rework).task, 'builder-task');
    expect(
      PlanningTaskOrder.from(
        servedFollowUp(1, role: 'author')..addAll({
          'task': 'builder-task',
          'place': {'ownPullRequest': true},
          'writeAs': 'author',
        }),
      ).task,
      'builder-task',
      reason: "at its task's own pull request the kept place names the task",
    );
    expect(PlanningTaskOrder.from(servedFollowUp(1, role: 'author')).task, isNull, reason: 'a new task names none');
    final changes = <String, Map<String, dynamic>>{
      'at another task\'s work': servedFollowUp(1, role: 'reviewer')..['task'] = 'builder-task',
      'a discussion': _discussion()..['task'] = 'builder-task',
      'a path': Map.of(rework)..['task'] = '../builder-task',
      'with a name of its own': Map.of(rework)..['ownTask'] = true,
      'no name': Map.of(rework)..['task'] = 4,
      'at no place': servedFollowUp(1, role: 'author')..['task'] = 'builder-task',
      'at a pull request': servedFollowUp(1, role: 'author')
        ..addAll({
          'task': 'builder-task',
          'place': {'pullRequest': 'https://github.com/acme/shop/pull/12'},
          'writeAs': 'author',
        }),
      'at the gate push of another task': Map.of(rework)..['task'] = 'other-task',
      'sent back to its gate push without the task': Map.of(rework)..remove('task'),
      'sent back to its own pull request without the task': servedFollowUp(1, role: 'author')
        ..addAll({
          'place': {'ownPullRequest': true},
          'writeAs': 'author',
        }),
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a task at another task's work is started under that task's name, or one named by the window is also run
  // again, so Start mixes two tasks' work.
  test('a task asked under a name of its own says so only as true, never beside a task it runs again', () {
    final reviewer = servedFollowUp(1, role: 'reviewer')
      ..addAll({
        'ownTask': true,
        'place': {'pullRequest': 'https://github.com/acme/shop/pull/12'},
        'writeAs': 'review',
      });
    expect(PlanningTaskOrder.from(reviewer).ownTask, isTrue);
    expect(PlanningTaskOrder.from(servedFollowUp(1, role: 'author')).ownTask, isFalse, reason: 'absent is false');
    for (final value in <Object?>[false, 'true', null]) {
      expect(() => PlanningTaskOrder.from(Map.of(reviewer)..['ownTask'] = value), throwsFormatException);
    }
  });

  // Harm: a stage's task is started without the issues' current texts, or a served order names a file outside its
  // issues, writes an idea, or gives a pushing task files it does not read.
  test('a stage order keeps its result and given files and refuses any other', () {
    Map<String, dynamic> stage() => _stage()
      ..['result'] = 'analysis.json'
      ..['given'] = [
        {'name': 'PR1.md', 'text': '# PR1\n'},
      ];
    final order = PlanningTaskOrder.from(stage());
    expect(order.result, 'analysis.json', reason: 'the window starts the stage with its result file');
    expect(
      [for (final file in order.given) '${file.name}=${file.text}'],
      ['PR1.md=# PR1\n'],
      reason: 'the window mounts each issue\'s text read only',
    );
    final changes = <String, Map<String, dynamic>>{
      'an idea result': stage()..['result'] = 'idea.json',
      'a reading with a stage result': servedFollowUp(1)..['result'] = 'analysis.json',
      'given without a result': stage()..remove('result'),
      'a file of another issue': stage()
        ..['given'] = [
          {'name': 'PR2.md', 'text': ''},
        ],
      'a path': stage()
        ..['given'] = [
          {'name': '../PR1.md', 'text': ''},
        ],
      'one issue twice': stage()
        ..['given'] = [
          {'name': 'PR1.md', 'text': ''},
          {'name': 'PR1.md', 'text': ''},
        ],
      'a text over 64 KiB': stage()
        ..['given'] = [
          {'name': 'PR1.md', 'text': 'x' * 65537},
        ],
      'a file without text': stage()
        ..['given'] = [
          {'name': 'PR1.md'},
        ],
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a readiness check cannot start because the window refuses its result file, or a stage writes a file the
  // planner never reads.
  test('a stage hands back each of the planner\'s three result files and no other', () {
    Map<String, dynamic> stage(String result) => _stage()
      ..['result'] = result
      ..['given'] = [
        {'name': 'PR1.md', 'text': '# PR1\n'},
      ];
    for (final result in ['analysis.json', 'estimate.json', 'readiness.json']) {
      expect(PlanningTaskOrder.from(stage(result)).result, result, reason: result);
    }
    expect(() => PlanningTaskOrder.from(stage('other.json')), throwsFormatException, reason: 'other.json');
  });

  // Harm: a stage over a tracker's issue cannot start, or Porelo mounts a file whose name holds a '#'.
  test('a tracker issue\'s given file is named with a dash in place of the hash', () {
    Map<String, dynamic> stage(String name) => _stage()
      ..['issues'] = ['porelo-x#3']
      ..['result'] = 'analysis.json'
      ..['given'] = [
        {'name': name, 'text': '# x\n'},
      ];
    expect(
      [for (final file in PlanningTaskOrder.from(stage('porelo-x-3.md')).given) file.name],
      ['porelo-x-3.md'],
      reason: 'the planner names the tracker issue\'s file without a hash',
    );
    for (final name in ['porelo-x#3.md', 'porelo-x-4.md', 'porelo-x.md']) {
      expect(() => PlanningTaskOrder.from(stage(name)), throwsFormatException, reason: name);
    }
  });

  // Harm: Start names another tracker issue than the planner's, or a malformed one, or a discussion names one.
  test('a stage order reads the tracker work it carries and refuses one that is not its shape', () {
    Map<String, dynamic> stage() => _stage()..['issues'] = ['porelo-x#3'];
    expect(PlanningTaskOrder.from(stage()).work, isNull, reason: 'an order without work stays as it was');
    final work = PlanningTaskOrder.from(stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3', 'phase': 'Analysis'})
        .work;
    expect(work, const PlanningWork('porelo-ai/porelo-x#3', 'Analysis'));
    expect(work?.toJson(), {'issue': 'porelo-ai/porelo-x#3', 'phase': 'Analysis'});
    final changes = <String, Map<String, dynamic>>{
      'work as null': stage()..['work'] = null,
      'no owner': stage()..['work'] = {'issue': 'porelo-x#3', 'phase': 'Analysis'},
      'white space': stage()..['work'] = {'issue': 'porelo-ai/porelo-x #3', 'phase': 'Analysis'},
      'a second item': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3#4', 'phase': 'Analysis'},
      'over 200 characters': stage()..['work'] = {'issue': '${'a' * 190}/porelo-x#3', 'phase': 'Analysis'},
      'a line break in the phase': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3', 'phase': 'Analysis\nx'},
      'no phase': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3'},
      'another member': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3', 'phase': 'A', 'more': 1},
      'a discussion': _discussion()..['work'] = {'issue': 'porelo-ai/porelo-x#3', 'phase': 'Analysis'},
      'two dots in the owner': stage()..['work'] = {'issue': 'a..b/porelo-x#3', 'phase': 'Analysis'},
      'two dots ending the owner': stage()..['work'] = {'issue': 'a../porelo-x#3', 'phase': 'Analysis'},
      'another issue than the task\'s': stage()..['work'] = {'issue': 'porelo-ai/other-repo#99', 'phase': 'Analysis'},
      'another number than the task\'s': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#4', 'phase': 'Analysis'},
      'a blank phase': stage()..['work'] = {'issue': 'porelo-ai/porelo-x#3', 'phase': '   '},
    };
    for (final MapEntry(key: name, value: map) in changes.entries) {
      expect(() => PlanningTaskOrder.from(map), throwsFormatException, reason: name);
    }
  });

  // Harm: a task over an issue a tracker keeps cannot be started, or one naming a path or two items is.
  test('a task order names an issue by its tracker reference and refuses one that is no id', () {
    expect(PlanningTaskOrder.from(servedFollowUp(1)..['issues'] = ['porelo-x#3']).issues, [
      'porelo-x#3',
    ], reason: 'a tracker reference is an issue id');
    for (final id in ['porelo-x#03', 'porelo-x#0', 'a..b#1', 'x/y#1', 'porelo-x#3#4', 'Porelo-x#3', 'porelo-x #3']) {
      expect(() => PlanningTaskOrder.from(servedFollowUp(1)..['issues'] = [id]), throwsFormatException, reason: id);
    }
  });

  // Harm: a reading started by hand runs as a build, so its findings are never read and its issue moves on unread.
  test('a reading by hand is ordered as one task of the offered role per issue', () {
    Map<String, dynamic> reading() => {
      ...servedProposal(),
      'repositories': [
        {
          'repository': 'repo',
          'issues': ['PR1', 'PR2'],
          'main': {
            'name': 'main',
            'role': mockRole('reader'),
            'agent': 'codex',
            'model': 'main-model',
            'brief': 'Read.',
          },
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
      seen: const {'ST1': servedVersion},
      subAgents: const [],
      proposedRepositories: proposal.repositories,
      issues: const ['PR1', 'PR2'],
    );
    Map<String, dynamic> task(int offer, String issue, {String role = 'reader'}) => {
      ...servedStart(role),
      'offer': servedOffer(offer),
      'repository': 'repo',
      'issues': [issue],
      'agent': 'codex',
      'model': 'main-model',
      'mode': 'AGENT',
      'prompt': 'Read.\n',
    };
    final order = StoryWorkOrder.from(
      {
        'revision': 'r1',
        'tasks': [task(1, 'PR1'), task(2, 'PR2')],
      },
      request: request,
      proposal: proposal,
    );
    expect(order.tasks.map((task) => task.role?.name), ['reader', 'reader'], reason: 'one reading per issue');
    for (final (name, tasks) in [
      ('a build in place of a reading', [task(1, 'PR1', role: 'author'), task(2, 'PR2')]),
      ('a task of another role', [task(1, 'PR1', role: 'analyst'), task(2, 'PR2')]),
      (
        'the offered role by its name with other parts',
        [
          task(1, 'PR1')..['role'] = {...mockRole('reader'), 'writeAs': 'author'},
          task(2, 'PR2'),
        ],
      ),
      ('one reading for both issues', [task(1, 'PR1')]),
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

  // Harm: a request sends back the offered role's name over other parts, so the person confirmed one role and the
  // window starts another.
  test('a request that sends back the offered role by its name with other parts is refused', () {
    final proposal = StoryWorkOrder.readProposal(servedProposal());
    final changed = servedProposal();
    (((changed['repositories'] as List).single as Map)['main'] as Map)['role'] = {
      ...mockRole('author'),
      'rights': ['push'],
    };
    StoryWorkOrder from(List<PlanRepositoryWork> repositories) => StoryWorkOrder.from(
      servedOrder(),
      request: StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        seen: const {'ST1': servedVersion},
        subAgents: const [],
        proposedRepositories: repositories,
      ),
      proposal: proposal,
    );
    expect(from(proposal.repositories).tasks, hasLength(2), reason: 'the offered role sent back is taken');
    expect(() => from(StoryWorkProposal.from(changed).repositories), throwsFormatException);
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
      (answer) => answer.remove('revision'),
      (answer) => answer.remove('tasks'),
      (answer) => answer['tasks'] = [false],
      (answer) => (answer['tasks'] as List).removeLast(),
      (answer) => (answer['tasks'] as List).add((answer['tasks'] as List).first),
      for (final field in [
        'offer',
        'kind',
        'repository',
        'issues',
        'role',
        'agent',
        'model',
        'mode',
        'prompt',
        'rights',
        'readLanded',
        'projectServices',
      ])
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
      // A helper builds, so a task of a role that only reads in its place is refused, as is a role word.
      for (final value in [mockRole('tester'), mockRole('screener'), 'author', 'tester'])
        (answer) => ((answer['tasks'] as List).last as Map)
          ..['role'] = value
          ..['rights'] = value is Map ? value['rights'] : <String>[],
      // The main task is of the role offered, by its name.
      (answer) => ((answer['tasks'] as List).first as Map)['role'] = {...mockRole('author'), 'name': 'builder'},
      (answer) => ((answer['tasks'] as List).last as Map)['kind'] = 'discussion',
      (answer) => ((answer['tasks'] as List).last as Map)['opensTerminal'] = true,
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
      [task.offer, task.repository, task.issues, task.role?.name, task.agent, task.model, task.mode, task.prompt],
      [
        servedOffer(1),
        'repo',
        ['PR1', 'PR1.1'],
        'author',
        'codex',
        'main-model',
        'AGENT',
        ((answer['tasks'] as List).first as Map)['prompt'],
      ],
    );
    expect(task.opensTerminal, isTrue, reason: 'the window opens the terminal the planner marked');
    expect(order.tasks.last.opensTerminal, isFalse);
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
        seen: const {'ST1': servedVersion},
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

  // Harm: a helper of another kind than one that builds is kept or changed into an initial task.
  test('only helpers that build can be kept or changed at start', () {
    for (final kind in ['REVIEWING', 'TESTING', 'OTHER']) {
      final map = servedProposal();
      final repo = (map['repositories'] as List).single as Map;
      ((repo['subAgents'] as List).single as Map)['kind'] = kind;
      final proposal = StoryWorkProposal.from(map);
      final request = StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        seen: const {'ST1': servedVersion},
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

  // Harm: a default order starts a proposed helper of another kind as an unreviewed build.
  test('a default order refuses a kept helper that does not build', () {
    for (final kind in ['REVIEWING', 'OTHER', '']) {
      final map = servedProposal();
      final repo = (map['repositories'] as List).single as Map;
      ((repo['subAgents'] as List).single as Map)['kind'] = kind;
      expect(
        () => StoryWorkOrder.from(
          servedOrder(),
          request: servedRequest(defaults: true),
          proposal: StoryWorkOrder.readProposal(map),
        ),
        throwsFormatException,
        reason: kind,
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
      seen: const {'ST1': servedVersion},
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
