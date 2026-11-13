import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/served_story_work.dart';
import 'support/story_work_support.dart';

class _Source extends FakePlanningSource {
  _Source()
    : super({
        ...smallBoard(),
        'stories': [
          {
            'id': 'ST1',
            'state': 'Backlog',
            'issues': [servedReadingIssue(stopped: false)],
          },
        ],
      });

  final reports = <(String, String, PlanningTaskEvent, String, String, String, PlanningTaskEnded?)>[];
  final decisions = <Map<String, dynamic>>[];
  Map<String, dynamic> reply = {'revision': 'r1', 'work': servedContinuationWork(), 'tasks': <dynamic>[]};
  Completer<TaskEventResolution>? reportGate;
  Completer<StoryWorkOrder>? orderGate;
  Completer<ReadingResolution>? decisionGate;
  final reads = <Completer<PlanningIssueDetail>>[];
  bool holdReads = false;
  Object? decisionError;
  Object? reportError;
  Object? stopError;
  bool reloadFails = false;
  int stops = 0;

  @override
  Future<PlanningSnapshot> stories(String project) async {
    if (reloadFails) throw const PlanningRefusal('Unreachable');
    return super.stories(project);
  }

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues}) async =>
      StoryWorkOrder.readProposal(servedProposal());

  @override
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request) async =>
      orderGate?.future ??
      StoryWorkOrder.from(servedOrder(), request: request, proposal: StoryWorkOrder.readProposal(servedProposal()));

  @override
  Future<TaskEventResolution> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
    List<PlanningLanding> landed = const [],
  }) async {
    reports.add((project, task, event, offer, container, machine, ended));
    if (reportError case final error?) throw error;
    return reportGate?.future ?? TaskEventResolution.from(reply);
  }

  @override
  Future<StoryWorkStatus> stopStoryWork(String project, String story, {String? run}) async {
    stops++;
    if (stopError case final error?) throw error;
    return StoryWorkStatus.from({
      'work': servedWork()
        ..['tasks'] = [servedRunTask()]
        ..['issues'] = [_reason('STOPPED')],
    });
  }

  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) async {
    if (holdReads) {
      final gate = Completer<PlanningIssueDetail>();
      reads.add(gate);
      return gate.future;
    }
    return PlanningIssueDetail.from({'revision': board['revision'], 'issue': servedReadingIssue()});
  }

  @override
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    String revision, {
    String? comment,
    PlanningRework? rework,
  }) async {
    decisions.add({
      'project': project,
      'story': story,
      'issue': issue,
      'round': round,
      'choice': choice,
      'revision': revision,
      'comment': comment,
      'rework': rework,
    });
    if (decisionError case final error?) throw error;
    return decisionGate?.future ?? ReadingResolution.from({...reply, 'issue': servedReadingIssue(stopped: false)});
  }

  void showWork(Map<String, dynamic>? work, {bool stopped = false, String revision = 'r1'}) => replace({
    ...board,
    'revision': revision,
    'stories': [
      {
        'id': 'ST1',
        'state': 'Building',
        'work': work,
        'issues': [servedReadingIssue(stopped: stopped)],
      },
    ],
  });
}

class _Changes extends NoTasks {
  final tracked = <(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)>[];
  final stopped = <(PlanningSource, String, String, List<PlanningTaskStarted>)>[];

  @override
  Future<void> started(
    PlanningSource source,
    String project,
    String story,
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
  ) async => tracked.add((source, project, story, tasks));

  @override
  Future<void> stop(PlanningSource source, String project, String story, List<PlanningTaskStarted> tasks) async =>
      stopped.add((source, project, story, tasks));
}

Map<String, dynamic> _follow(int number) => servedFollowUp(number);

Map<String, dynamic> _reason(String reason, {bool stopped = false}) => {
  'issue': 'PR1',
  'reason': reason,
  'rounds': 12,
  'roundsMost': 13,
  'atWork': false,
  'needsYou': true,
  'readingStopped': stopped,
  'findings': <dynamic>[],
};

void main() {
  late _Source source;
  late PlanningBoard board;
  late FakePlanningClient client;
  late _Changes changes;
  final starts = <(PlanningSource, String, String, PlanningTaskOrder)>[];
  late PlanningStartTask start;

  setUp(() async {
    source = _Source();
    changes = _Changes();
    starts.clear();
    client = FakePlanningClient([provider('origin', source)]);
    board = PlanningBoard(source, 'demo')..taskChanges = changes;
    start = (source, project, story, order) async {
      starts.add((source, project, story, order));
      return PlanningTaskStarted(task: 'task-${starts.length}', container: 'container-${starts.length}');
    };
    board.startTask = start;
    await pumpEventQueue();
  });
  tearDown(() async {
    board.dispose();
    client.dispose();
    await source.close();
  });

  Future<StoryWork> form() async {
    final work = StoryWork(board, board.stories.single, 'Building', startTask: start, agentChoices: FakeAgents());
    addTearDown(work.dispose);
    await work.load();
    return work;
  }

  Future<void> report({
    String? offer,
    String project = 'demo',
    String task = 'task-1',
    String container = 'container-1',
    String machine = 'Machine 🚀',
    PlanningTaskEvent event = PlanningTaskEvent.started,
    PlanningTaskEnded? ended,
  }) => client.taskEvent(
    project,
    task,
    event,
    offer: offer ?? servedOffer(1),
    container: container,
    machine: machine,
    ended: ended,
  );

  Future<ReadingDecision> reading() async {
    source.showWork(servedWork(atWork: false), stopped: true);
    await pumpEventQueue();
    final decision = ReadingDecision(
      PlanningAttention(board, board.stories.single, issue: board.stories.single.issues.single),
    );
    addTearDown(decision.dispose);
    await decision.load();
    return decision;
  }

  // Harm: a report or follow-up is routed to the selected board instead of the source and story that offered it.
  test('reports and replayed follow-ups stay on their offer origin after provider changes', () async {
    expect(await (await form()).start(), isTrue);
    final other = _Source();
    addTearDown(other.close);
    client.retainPlanningSources();
    client.providers = [provider('origin', source), provider('selected', other)];
    client.currentProvider = 'selected';
    source.reply['tasks'] = [_follow(10)];
    await report();
    await report();
    expect(source.reports, hasLength(2));
    expect(other.reports, isEmpty);
    expect(starts.map((call) => (call.$1, call.$2, call.$3)), everyElement((source, 'demo', 'ST1')));
    expect(starts.map((call) => call.$4.offer), [servedOffer(1), servedOffer(2), servedOffer(10)]);
    expect(changes.tracked.expand((batch) => batch.$4).map((task) => task.$2.task), ['task-1', 'task-2', 'task-3']);
    client.providers = [provider('selected', other)];
    await report();
    expect(source.reports, hasLength(3));
  });

  // Harm: ending the last running task loses its offered follow-up or starts it twice on replay.
  test('an ENDED answer with atWork false starts its follow-up exactly once', () async {
    expect(await (await form()).start(), isTrue);
    const ended = PlanningTaskEnded(at: '', finished: true, why: '', answer: 'Findings:\n', unpushed: 0);
    Future<void> builderReport(PlanningTaskEvent event) => report(
      offer: servedOffer(2),
      task: 'task-2',
      container: 'container-2',
      event: event,
      ended: event == PlanningTaskEvent.ended ? ended : null,
    );
    await builderReport(PlanningTaskEvent.started);
    source.reply = {
      'revision': 'r2',
      'work': servedWork(atWork: false)
        ..['tasks'] = [
          servedRunTask(task: 'task-2', active: false, ended: ended.toMap())
            ..['containerId'] = 'container-2'
            ..['issues'] = ['PR1.1']
            ..['state'] = 'Building'
            ..['role'] = 'BUILDER'
            ..['model'] = 'builder-model',
        ],
      'tasks': [
        _follow(10)..['issues'] = ['PR1.1'],
      ],
    };
    await builderReport(PlanningTaskEvent.ended);
    await builderReport(PlanningTaskEvent.ended);
    expect(source.reports.skip(1).map((call) => call.$3), everyElement(PlanningTaskEvent.ended));
    expect(starts.map((call) => call.$4.offer), [servedOffer(1), servedOffer(2), servedOffer(10)]);
  });

  // Harm: an ordinary inactive watch refresh cancels a Start already in flight and drops the rest of its batch.
  test('an inactive watch keeps an in-flight follow-up batch authorized', () async {
    expect(await (await form()).start(), isTrue);
    final gate = Completer<PlanningTaskStarted>();
    board.runOf('ST1').startTask = (source, project, story, task) =>
        task.offer == servedOffer(10) ? gate.future : start(source, project, story, task);
    source.reply['tasks'] = [
      _follow(10),
      _follow(11)..['issues'] = ['PR1.1'],
    ];
    final pending = report();
    await pumpEventQueue();
    source.showWork(servedContinuationWork());
    await pumpEventQueue();
    gate.complete(const PlanningTaskStarted(task: 'reader', container: 'reader-container'));
    await pending;
    expect(starts.last.$4.offer, servedOffer(11));
    expect(changes.stopped, isEmpty);
    expect(changes.tracked.last.$4.map((entry) => entry.$1.offer), [servedOffer(10), servedOffer(11)]);
  });

  // Harm: an unknown offer or a contradictory runtime identity reports or starts another story's work.
  test('unknown offers and changed project task container or machine never reach the source', () async {
    expect(await (await form()).start(), isTrue);
    await report();
    for (final call in <Future<void> Function()>[
      () => report(offer: servedOffer(99)),
      () => report(project: 'foreign'),
      () => report(task: 'foreign'),
      () => report(container: 'foreign'),
      () => report(machine: 'Other machine'),
    ]) {
      await expectLater(call(), throwsA(isA<PlanningRefusal>()));
    }
    expect(source.reports, hasLength(1));
    expect(starts, hasLength(2));
  });

  // Harm: an invalid first machine name permanently poisons an offer's runtime binding.
  test('machine bindings reject invalid names and keep 128 Unicode code points verbatim', () async {
    expect(await (await form()).start(), isTrue);
    for (final machine in [
      '',
      'a' * 129,
      'bad\nname',
      'bad\u0085name',
      'bad\u2028name',
      'bad\u2029name',
      String.fromCharCode(0xd800),
    ]) {
      await expectLater(report(machine: machine), throwsA(isA<PlanningRefusal>()));
    }
    expect(source.reports, isEmpty);
    final machine = '🚀' * 128;
    await report(machine: machine);
    expect(source.reports.single.$6, machine);
  });

  // Harm: a rejected STARTED report binds a machine and blocks the actual machine from reporting or ending.
  test('a failed STARTED report leaves no machine binding', () async {
    expect(await (await form()).start(), isTrue);
    source.reportError = const PlanningRefusal('TaskEventConflict');
    await expectLater(report(machine: 'Rejected machine'), throwsA(isA<PlanningRefusal>()));
    source.reportError = null;
    await expectLater(report(event: PlanningTaskEvent.ended), throwsA(isA<PlanningRefusal>()));
    expect(source.reports, hasLength(1));
    await report(machine: 'Actual machine');
    await report(machine: 'Actual machine', event: PlanningTaskEvent.stopped);
    expect(source.reports.skip(1).map((call) => call.$6), ['Actual machine', 'Actual machine']);
  });

  // Harm: one provider reuses an offer under another project and starts the same work twice.
  test('an offer is consumed across every project and story on its source', () async {
    expect(await (await form()).start(), isTrue);
    final other = PlanningBoard(source, 'other');
    addTearDown(other.dispose);
    await pumpEventQueue();
    final work = StoryWork(other, other.stories.single, 'Building', startTask: start, agentChoices: FakeAgents());
    addTearDown(work.dispose);
    await work.load();
    expect(await work.start(), isFalse);
    expect(starts, hasLength(2));
    expect(work.tasks.first.refusal?.name, 'OfferConsumed');
  });

  // Harm: a second task's failed Start makes the first running task disappear from the window's tracking.
  test('a partial follow-up start is tracked and its refusal is surfaced', () async {
    expect(await (await form()).start(), isTrue);
    board.runOf('ST1').startTask = (source, project, story, task) async {
      if (task.offer == servedOffer(11)) throw const PlanningRefusal('StartRefused');
      return start(source, project, story, task);
    };
    source.reply['tasks'] = [_follow(10), _follow(11)];
    await expectLater(report(), throwsA(isA<PlanningRefusal>().having((error) => error.name, 'name', 'StartRefused')));
    expect(changes.tracked.last.$4.single.$1.offer, servedOffer(10));
    await expectLater(report(), throwsA(isA<PlanningRefusal>()));
    expect(starts, hasLength(3));
  });

  // Harm: an in-flight report hands new work to Start after Stop was requested.
  test('stop voids a report batch and applies active tasks even when reload fails', () async {
    expect(await (await form()).start(), isTrue);
    source.showWork(servedWork()..['tasks'] = [servedRunTask()]);
    await pumpEventQueue();
    source.reportGate = Completer<TaskEventResolution>();
    final pending = report();
    await pumpEventQueue();
    source.reloadFails = true;
    await board.stopWork(
      'ST1',
      run: board.workOf(board.stories.firstWhere((each) => each.id == 'ST1'))?.run,
      revision: 'older-revision',
      source: source,
      sourceProject: 'demo',
    );
    expect(source.stops, 1);
    expect(changes.stopped.single.$4.single.task, 'main-task');
    expect(board.workOf(board.stories.single)!.tasks.single.atWork, isTrue);
    source.reportGate!.complete(
      TaskEventResolution.from({
        'revision': 'r1',
        'work': servedContinuationWork(),
        'tasks': [_follow(10)],
      }),
    );
    await pending;
    expect(starts, hasLength(2));
  });

  // Harm: a Start finishing after Stop reports a voided offer instead of stopping its runtime task.
  test('late confirmed starts go directly to the window stop hook without reports', () async {
    final gate = Completer<PlanningTaskStarted>();
    start = (source, project, story, order) => gate.future;
    final work = StoryWork(
      board,
      board.stories.single,
      'Building',
      startTask: start,
      agentChoices: FakeAgents(),
      onStarted: (source, project, story, tasks) async {
        for (final (order, started) in tasks) {
          await client.taskEvent(
            project,
            started.task,
            PlanningTaskEvent.started,
            offer: order.offer!,
            container: started.container,
            machine: 'Machine 🚀',
          );
        }
      },
    );
    addTearDown(work.dispose);
    await work.load();
    final pending = work.start();
    await pumpEventQueue();
    source.showWork(servedWork()..['tasks'] = [servedRunTask()]);
    await pumpEventQueue();
    await board.stopWork(
      'ST1',
      run: board.workOf(board.stories.firstWhere((each) => each.id == 'ST1'))?.run,
      revision: 'r1',
      source: source,
      sourceProject: 'demo',
    );
    gate.complete(const PlanningTaskStarted(task: 'late', container: 'late-container'));
    expect(await pending, isFalse);
    expect(changes.tracked, isEmpty);
    expect(changes.stopped.last.$4.single.task, 'late');
    expect(source.reports, isEmpty);
  });

  // Harm: a recorded stop lets an in-flight batch start more tasks while another task is still active.
  test('a STOPPED issue cancels follow-ups even while atWork is true', () async {
    expect(await (await form()).start(), isTrue);
    source.showWork(servedWork(atWork: false));
    await pumpEventQueue();
    final gate = Completer<PlanningTaskStarted>();
    final launched = <String?>[];
    board.runOf('ST1').startTask = (source, project, story, task) async {
      launched.add(task.offer);
      return task.offer == servedOffer(10) ? gate.future : start(source, project, story, task);
    };
    source.reply['tasks'] = [_follow(10), _follow(11)];
    final pending = report();
    await pumpEventQueue();
    source.showWork(
      servedWork()
        ..['tasks'] = [servedRunTask()]
        ..['issues'] = [_reason('STOPPED')],
    );
    await pumpEventQueue();
    gate.complete(const PlanningTaskStarted(task: 'late', container: 'late-container'));
    await pending;
    expect(launched, [servedOffer(10)]);
    expect(changes.stopped.last.$4.single.task, 'late');
  });

  // Harm: an unchecked follow-up starts a shell, a coordinator, another issue scope or a different execution mode.
  test('the whole follow-up batch is checked before any Start', () async {
    expect(await (await form()).start(), isTrue);
    for (final mutation in <Map<String, dynamic>>[
      {'mode': 'SHELL'},
      {'role': 'MAIN'},
      {'issues': <String>[]},
      {
        'issues': ['PR1', 'PR1.1'],
      },
      {'mode': 'UNATTENDED'},
      {'repository': 'foreign'},
      {
        'issues': ['PR9'],
      },
    ]) {
      final work = servedContinuationWork(anotherAtWork: true);
      if (mutation['mode'] == 'SHELL') {
        for (final task in (work['tasks'] as List).cast<Map<String, dynamic>>()) {
          task['mode'] = 'SHELL';
        }
      }
      source.reply['work'] = work;
      source.reply['tasks'] = [
        _follow(10)..['mode'] = mutation['mode'] == 'SHELL' ? 'SHELL' : 'AGENT',
        {..._follow(11), ...mutation},
      ];
      await expectLater(report(), throwsA(isA<FormatException>()), reason: '$mutation');
      expect(starts, hasLength(2), reason: '$mutation');
    }
    source.reply['work'] = servedContinuationWork(anotherAtWork: true);
    source.reply['tasks'] = [_follow(10)];
    await report();
    expect(starts.last.$4.offer, servedOffer(10));
  });

  // Harm: a same-revision run update authorizes a start after another window has already started or halted work.
  test('a changed run invalidates an in-flight initial order without a revision change', () async {
    final work = await form();
    source.orderGate = Completer<StoryWorkOrder>();
    final pending = work.start();
    source.showWork(servedWork()..['tasks'] = [servedRunTask()]);
    await pumpEventQueue();
    source.orderGate!.complete(
      StoryWorkOrder.from(
        servedOrder(),
        request: servedRequest(),
        proposal: StoryWorkOrder.readProposal(servedProposal()),
      ),
    );
    expect(await pending, isFalse);
    expect(starts, isEmpty);
  });

  // Harm: a halted or lost record permits new work or a planning state invents activity.
  test('start permission and activity use runtime records independently of planning states', () async {
    source.showWork(null);
    await pumpEventQueue();
    expect(board.startUnavailable(board.stories.single), isNotNull);
    expect(board.workingCount, isNull);
    source.showWork(servedWork(atWork: false));
    await pumpEventQueue();
    expect(board.workingCount, 0);
    expect(board.startUnavailable(board.stories.single), isNull);
    source.showWork(
      servedWork(atWork: false)
        ..['tasks'] = [
          servedRunTask(
            active: false,
            ended: {'at': '', 'finished': false, 'why': 'Need access', 'answer': 'raw', 'unpushed': 0},
          ),
        ],
    );
    await pumpEventQueue();
    expect(board.startUnavailable(board.stories.single), isNotNull);
    source.showWork(
      servedWork(atWork: false)
        ..['tasks'] = [
          servedRunTask(
            active: false,
            ended: {'at': '', 'finished': true, 'why': 'Unpushed work', 'answer': 'raw', 'unpushed': 1},
          ),
        ],
    );
    await pumpEventQueue();
    expect(board.startUnavailable(board.stories.single), isNotNull);
    source.showWork(servedWork(atWork: false)..['issues'] = [_reason('SAVE_REFUSED')]);
    await pumpEventQueue();
    expect(board.startUnavailable(board.stories.single), isNotNull);
    source.showWork(servedWork()..['tasks'] = [servedRunTask()]);
    await pumpEventQueue();
    expect(board.workingCount, 1);
  });

  // Harm: successful readers with invalid findings or refused saves disappear from Needs you with their raw answers.
  test('every durable issue reason remains in Needs you with the matching raw task answer', () async {
    final boards = PlanningBoards(client);
    addTearDown(boards.dispose);
    for (final reason in [
      'ROUND_LIMIT',
      'UNPUSHED',
      'FAILED',
      'STOPPED',
      'INVALID_FINDINGS',
      'SAVE_REFUSED',
      'NEXT_STEP_UNAVAILABLE',
    ]) {
      source.showWork(
        servedWork(atWork: false)
          ..['tasks'] = [
            servedRunTask(
              active: false,
              ended: {'at': '', 'finished': true, 'why': 'raw why', 'answer': 'raw answer', 'unpushed': 0},
            ),
          ]
          ..['issues'] = [_reason(reason)],
      );
      await pumpEventQueue();
      final row = boards.needsYou.single.attention;
      expect(row.reason, reason);
      expect(row.ended!.finished, isTrue);
      expect(row.ended!.answer, 'raw answer');
      expect(row.issue!.roundsMost, 13);
    }
  });

  // Harm: a choice based on stale or mismatched findings accepts another revision, issue or completed round.
  test('reading load generations and exact source facts gate decisions', () async {
    final decision = await reading();
    expect(decision.canResolve, isTrue);
    source.holdReads = true;
    final old = decision.load();
    final fresh = decision.load();
    source.reads.last.complete(PlanningIssueDetail.from({'revision': 'r1', 'issue': servedReadingIssue()}));
    await fresh;
    source.reads.first.complete(PlanningIssueDetail.from({'revision': 'r0', 'issue': servedReadingIssue(round: 1)}));
    await old;
    expect(decision.detail!.revision, 'r1');
    expect(decision.canResolve, isTrue);
    for (final answer in [
      {'revision': 'r0', 'issue': servedReadingIssue()},
      {'revision': 'r1', 'issue': servedReadingIssue()..['id'] = 'PR2'},
      {'revision': 'r1', 'issue': servedReadingIssue(round: 1)},
      {'revision': 'r1', 'issue': servedReadingIssue(stopped: false)},
    ]) {
      final pending = decision.load();
      source.reads.last.complete(PlanningIssueDetail.from(answer));
      await pending;
      expect(decision.canResolve, isFalse);
    }
  });

  // Harm: a follow-up is lost after the reading choice is accepted.
  test('reading decisions track only returned follow-ups before submission', () async {
    final decision = await reading();
    source.reply['tasks'] = [servedFollowUp(10, role: 'BUILDER')];
    await decision.resolve('REWORK', comment: '  Keep the key inside.\n');
    expect(source.decisions.single, {
      'project': 'demo',
      'story': 'ST1',
      'issue': 'PR1',
      'round': 2,
      'choice': 'REWORK',
      'revision': 'r1',
      'comment': '  Keep the key inside.\n',
      'rework': null,
    });
    expect(starts.single.$4.offer, servedOffer(10));
    expect(changes.tracked.single.$4.single.$1.offer, servedOffer(10));
    expect(decision.submitted, isTrue);
  });

  // Harm: a reading choice starts the wrong role or another issue, or starts work after choosing review.
  test('reading decision mismatches never reach Start', () async {
    final decision = await reading();
    source.reply['work'] = servedContinuationWork(anotherAtWork: true);
    for (final (choice, task) in [
      ('ONE_MORE_ROUND', servedFollowUp(10, role: 'BUILDER')),
      ('ONE_MORE_ROUND', _follow(10)..['issues'] = ['PR1.1']),
      ('REWORK', _follow(10)),
      ('REWORK', servedFollowUp(10, role: 'BUILDER')..['issues'] = ['PR1.1']),
      ('REVIEW_ANYWAY', _follow(10)),
    ]) {
      source.reply['tasks'] = [task];
      await decision.resolve(choice, comment: choice == 'REWORK' ? 'Fix the finding.' : null);
      expect(decision.submitted, isFalse, reason: '$choice $task');
      expect(decision.problem, isNotNull, reason: '$choice $task');
      expect(starts, isEmpty, reason: '$choice $task');
      expect(changes.tracked, isEmpty);
      await decision.load();
    }
    source.reply['tasks'] = [_follow(10)];
    await decision.resolve('ONE_MORE_ROUND');
    expect(decision.submitted, isTrue);
    expect(starts.single.$4.offer, servedOffer(10));
  });

  // Harm: Start refusal is hidden as an accepted reading decision or unsupported choices remain actionable.
  test('reading follow-up errors and unsupported actions do not submit', () async {
    final decision = await reading();
    board.startTask = (source, project, story, task) async => throw const PlanningRefusal('StartRefused');
    source.reply['tasks'] = [_follow(10)];
    await decision.resolve('ONE_MORE_ROUND');
    expect(decision.submitted, isFalse);
    expect(decision.problem, isNotNull);
    await decision.load();
    source.decisionError = const PlanningRefusal('NotOffered', {}, '', true);
    await decision.resolve('ONE_MORE_ROUND');
    expect(board.unavailableActions, contains('ResolveIssueReading'));
  });

  // Harm: a decision already waiting for HTTP starts its returned reader after cancellation.
  test('stop voids the pending reading decision offers', () async {
    final decision = await reading();
    source.decisionGate = Completer<ReadingResolution>();
    final pending = decision.resolve('ONE_MORE_ROUND');
    await pumpEventQueue();
    source.showWork(servedWork()..['tasks'] = [servedRunTask()], stopped: true);
    await pumpEventQueue();
    await board.stopWork(
      'ST1',
      run: board.workOf(board.stories.firstWhere((each) => each.id == 'ST1'))?.run,
      revision: 'r1',
      source: source,
      sourceProject: 'demo',
    );
    source.decisionGate!.complete(
      ReadingResolution.from({
        'revision': 'r1',
        'issue': servedReadingIssue(stopped: false),
        'work': servedContinuationWork(),
        'tasks': [_follow(10)],
      }),
    );
    await pending;
    expect(starts, isEmpty);
  });

  // Harm: a save refusal leaves a pending report's follow-up offers authorized to start.
  test('save refusals cancel pending offers and keep the decision unsubmitted', () async {
    expect(await (await form()).start(), isTrue);
    final decision = await reading();
    source.reportGate = Completer<TaskEventResolution>();
    final pending = report();
    await pumpEventQueue();
    source.reloadFails = true;
    source.decisionError = const PlanningRefusal('SaveRefused');
    await decision.resolve('ONE_MORE_ROUND');
    expect(decision.submitted, isFalse);
    expect(decision.problem, isNotNull);
    source.reportGate!.complete(
      TaskEventResolution.from({
        'revision': 'r1',
        'work': servedContinuationWork(),
        'tasks': [_follow(10)],
      }),
    );
    await pending;
    expect(starts, hasLength(2));
  });

  // Harm: controls or directional characters hide findings while reported machine and answer fields are rewritten.
  test('display filtering preserves raw records and granted rounds remain known above ten', () {
    final task = PlanWorkTask.from(
      servedRunTask(ended: {'at': '', 'finished': true, 'why': 'a\u202eb', 'answer': 'x\u0000y\n\tz', 'unpushed': 0}),
    );
    expect(task.machine, 'Machine 🚀');
    expect(task.ended!.why, 'a\u202eb');
    expect(planningShown(task.ended!.why), 'ab');
    expect(planningShown(task.ended!.answer), 'xy\n\tz');
    expect(PlanIssue.from(servedReadingIssue()).roundsMost, 12);
    expect(PlanIssue.from(servedReadingIssue()..['rounds'] = -1).rounds, isNull);
    expect(PlanWorkIssue.from(_reason('ROUND_LIMIT')..['roundsMost'] = 0).roundsMost, isNull);
  });

  // Harm: issue failures unrelated to a stopped reading offer reading choices that cannot apply.
  testWidgets('only stopped readings expose the three decision actions', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    for (final stopped in [false, true, false]) {
      // The watch was subscribed in setUp, outside the widget test's fake async zone.
      await tester.runAsync(() async {
        source.showWork(servedWork(atWork: false), stopped: stopped);
        await pumpEventQueue();
      });
      final story = board.stories.single;
      expect(story.issues.single.readingStopped, stopped);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: StoryWorkAttentionView(
              row: PlanningAttention(board, story, issue: story.issues.single),
              resolved: false,
              machine: 'origin',
            ),
          ),
        ),
      );
      await tester.pump();
      for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK']) {
        expect(find.byKey(Key('reading-resolve PR1 $choice')), stopped ? findsOneWidget : findsNothing);
      }
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
