import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';
import 'support/story_work_support.dart';

class _Source extends MockPlanningSource {
  _Source() {
    boards['demo'] = {
      'revision': 'r1',
      'states': [
        {'name': 'Backlog', 'kind': 'WAITING'},
        {'name': 'Building', 'kind': 'WORKING'},
      ],
      'phases': <Map<String, dynamic>>[],
      'stories': [
        {'id': 'ST1', 'state': 'Backlog', 'issues': <Map<String, dynamic>>[]},
      ],
    };
  }

  Completer<void>? orderGate;
  int orders = 0;
  String offerPrefix = 'offer';
  late StoryWorkOrder order;

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to) async =>
      StoryWorkOrder.readProposal(servedProposal());

  @override
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request) async {
    orders++;
    final answer = servedOrder();
    for (final (index, task) in (answer['tasks'] as List).indexed) {
      (task as Map<String, dynamic>)['offer'] = servedOffer(
        offerPrefix.codeUnits.fold<int>(0, (sum, unit) => sum + unit) * 10 + index,
      );
    }
    order = StoryWorkOrder.from(answer, request: request, proposal: StoryWorkOrder.readProposal(servedProposal()));
    if (orderGate case final gate?) await gate.future;
    return order;
  }
}

Map<String, dynamic> _fields(PlanningTaskOrder task) => {
  'offer': task.offer,
  'repository': task.repository,
  'issues': task.issues,
  'role': task.role,
  'agent': task.agent,
  'model': task.model,
  'mode': task.mode,
  'prompt': task.prompt,
};

void main() {
  late _Source source;
  late PlanningBoard board;
  final forms = <StoryWork>[];
  Future<StoryWork> form(PlanningStartTask start) async {
    final work = StoryWork(board, board.stories.single, 'Building', startTask: start, agentChoices: FakeAgents());
    forms.add(work);
    await work.load();
    work.chooseMode('AGENT');
    return work;
  }

  Future<void> reportWork(bool running) async {
    source.push('demo', {
      ...source.boards['demo']!,
      'stories': [
        {
          'id': 'ST1',
          'state': 'Backlog',
          'issues': <Map<String, dynamic>>[],
          'work': {
            'atWork': running,
            'tasks': [
              {'task': 'actual-task', 'atWork': running},
            ],
          },
        },
      ],
    });
    await pumpEventQueue();
  }

  setUp(() async {
    source = _Source();
    board = PlanningBoard(source, 'demo');
    await pumpEventQueue();
  });
  tearDown(() async {
    for (final work in forms) {
      work.dispose();
    }
    forms.clear();
    board.dispose();
    await source.end();
  });

  // Harm: a late order starts tasks for a board, provider, project or story that is no longer selected.
  test('a late order reaches no Start after its binding changes', () async {
    for (final change in ['revision', 'source', 'project', 'story', 'board', 'generation', 'disposed']) {
      final next = _Source();
      addTearDown(next.end);
      final calls = <PlanningTaskOrder>[];
      final work = await form((source, project, story, task) async {
        calls.add(task);
        return const PlanningTaskStarted(task: 'task', container: 'container');
      });
      source.orderGate = Completer<void>();
      final pending = work.start();
      switch (change) {
        case 'revision':
          source.push('demo', {...source.boards['demo']!, 'revision': 'r2'});
          await pumpEventQueue();
        case 'source':
          await board.changeProject(next, 'demo');
        case 'project':
          await board.changeProject(source, 'other');
        case 'story':
          work.story = PlanStory.from({'id': 'ST2'});
        case 'board':
          work.board = PlanningBoard(source, 'demo');
          addTearDown(work.board.dispose);
        case 'generation':
          await board.retry();
        case 'disposed':
          work.dispose();
          forms.remove(work);
      }
      source.orderGate!.complete();
      expect(await pending, isFalse, reason: change);
      expect(calls, isEmpty, reason: change);
      if (forms.remove(work)) work.dispose();
      await board.changeProject(source, 'demo');
      source.push('demo', {...source.boards['demo']!, 'revision': 'r1'});
      await pumpEventQueue();
    }
  });

  // Harm: a refused Start launches later tasks or hides tasks that have already started.
  test('a refused Start stops the order and marks only the refused task', () async {
    final calls = <PlanningTaskOrder>[];
    final refusal = mockRefusal('StartRefused', {'detail': 'The selected agent cannot start.'});
    final work = await form((source, project, story, task) async {
      calls.add(task);
      if (calls.length == 2) throw refusal;
      return const PlanningTaskStarted(task: 'actual-task', container: 'actual-container');
    });
    expect(await work.start(), isFalse);
    expect(calls, source.order.tasks);
    expect(work.problem, isNull);
    expect(work.tasks.first.started?.task, 'actual-task');
    expect(work.tasks.first.started?.container, 'actual-container');
    expect(work.tasks.first.problem, isNull);
    expect(work.tasks.last.started, isNull);
    expect(work.tasks.last.refusal, same(refusal));
    expect(work.tasks.last.problem, planningFailureWords(refusal));
    expect(work.startedTasks, [(source.order.tasks.first, work.tasks.first.started!)]);
    source.offerPrefix = 'fresh-offer';
    final fresh = await form((source, project, story, task) async {
      fail('A confirmed task must prevent a second order with fresh offers.');
    });
    expect(await fresh.start(), isFalse);
    expect(source.orders, 1);
    await reportWork(true);
    expect(fresh.canStart, isFalse);
    await reportWork(false);
    expect(fresh.canStart, isTrue);
    source.offerPrefix = 'offer';
    final retry = await form((source, project, story, task) async {
      fail('An offer already handed to Start must never be handed again.');
    });
    expect(await retry.start(), isFalse);
    expect(source.orders, 2);

    final next = _Source();
    addTearDown(next.end);
    final nextBoard = PlanningBoard(next, 'demo');
    addTearDown(nextBoard.dispose);
    await pumpEventQueue();
    final stopped = StoryWork(
      nextBoard,
      nextBoard.stories.single,
      'Building',
      startTask: (source, project, story, task) async {
        calls.add(task);
        throw refusal;
      },
      agentChoices: FakeAgents(),
    );
    forms.add(stopped);
    await stopped.load();
    stopped.chooseMode('AGENT');
    calls.clear();
    expect(await stopped.start(), isFalse);
    expect(calls, [next.order.tasks.first]);
    expect(stopped.tasks.first.refusal, same(refusal));
    expect(stopped.tasks.last.refusal, isNull);
    expect(stopped.tasks.last.started, isNull);
  });

  // Harm: the window never follows a task that runs because a later task of its order was refused.
  test('the tasks started before a refusal are told to the window when the order ends', () async {
    final told = <(PlanningTaskOrder, PlanningTaskStarted)>[];
    var calls = 0;
    final work = StoryWork(
      board,
      board.stories.single,
      'Building',
      startTask: (source, project, story, task) async {
        if (++calls == 2) throw mockRefusal('StartRefused', {'detail': 'No.'});
        return const PlanningTaskStarted(task: 'actual-task', container: 'actual-container');
      },
      agentChoices: FakeAgents(),
      onStarted: (source, project, story, tasks) async => told.addAll(tasks),
    );
    forms.add(work);
    await work.load();
    work.chooseMode('AGENT');
    expect(await work.start(), isFalse);
    expect(told, [(source.order.tasks.first, work.tasks.first.started!)]);
  });

  // Harm: duplicate or rewritten Start arguments launch the wrong work or repeat a side effect.
  test('each task reaches the hook once in order with every field unchanged', () async {
    final calls = <(PlanningSource, String, String, PlanningTaskOrder)>[];
    final first = Completer<PlanningTaskStarted>();
    final work = await form((source, project, story, task) async {
      calls.add((source, project, story, task));
      return calls.length == 1
          ? first.future
          : const PlanningTaskStarted(task: 'builder-task', container: 'builder-container');
    });
    final pending = work.start();
    await pumpEventQueue();
    expect(calls, [(source, 'demo', 'ST1', source.order.tasks.first)]);
    first.complete(const PlanningTaskStarted(task: 'main-task', container: 'main-container'));
    expect(await pending, isTrue);
    expect(calls.map((call) => call.$4), source.order.tasks);
    expect(calls.map((call) => (call.$1, call.$2, call.$3)), [(source, 'demo', 'ST1'), (source, 'demo', 'ST1')]);
    expect(calls.map((call) => _fields(call.$4)), [
      for (final (index, task) in (servedOrder()['tasks'] as List).indexed)
        {
          ...task as Map<String, dynamic>,
          'offer': servedOffer('offer'.codeUnits.fold<int>(0, (sum, unit) => sum + unit) * 10 + index),
        },
    ]);
    expect(work.startedTasks.map((task) => (task.$2.task, task.$2.container)), [
      ('main-task', 'main-container'),
      ('builder-task', 'builder-container'),
    ]);
    expect(await work.start(), isFalse);
    expect(source.orders, 1);
    expect(calls, hasLength(2));
  });

  // Harm: an order of two repositories never ends, so the form stays open and its second builder is never reported.
  test("a board moved by the order's own first start does not stop its later tasks", () async {
    final calls = <PlanningTaskOrder>[];
    final work = await form((_, _, _, task) async {
      calls.add(task);
      if (calls.length == 1) {
        // The first builder's STARTED moves its issue, so the board's revision moves before the next task starts.
        source.push('demo', {...source.boards['demo']!, 'revision': 'r2'});
        await pumpEventQueue();
      }
      return PlanningTaskStarted(task: 'task-${calls.length}', container: 'container-${calls.length}');
    });
    expect(await work.start(), isTrue, reason: 'every task of the accepted order started');
    expect(calls, source.order.tasks, reason: 'the second task starts after the board moved');
  });

  // Harm: a second form or repeated click starts the same story while its first order is still pending.
  test('a second start is unavailable until the first order fails', () async {
    final gate = Completer<PlanningTaskStarted>();
    final first = await form((source, project, story, task) => gate.future);
    final second = await form((source, project, story, task) async {
      fail('A pending order must prevent a second Start.');
    });
    source.orderGate = Completer<void>();
    final pending = first.start();
    expect(second.canStart, isFalse);
    expect(await first.start(), isFalse);
    expect(await second.start(), isFalse);
    expect(source.orders, 1);
    source.orderGate!.complete();
    await pumpEventQueue();
    expect(second.canStart, isFalse);
    expect(await second.start(), isFalse);
    gate.completeError(mockRefusal('StartRefused', {'detail': 'No capacity.'}));
    expect(await pending, isFalse);
    expect(second.canStart, isTrue);
  });

  // Harm: a successful but unreported order allows duplicate starts before the board confirms its run.
  test('a successful order blocks another start until its reported work ends', () async {
    final first = await form(
      (source, project, story, task) async => PlanningTaskStarted(task: task.role, container: 'container-${task.role}'),
    );
    final second = await form((source, project, story, task) async {
      fail('An unconfirmed or running order must prevent another Start.');
    });
    expect(await first.start(), isTrue);
    expect(second.canStart, isFalse);
    expect(await second.start(), isFalse);
    expect(source.orders, 1);
    for (final running in [true, false]) {
      source.push('demo', {
        ...source.boards['demo']!,
        'stories': [
          {
            'id': 'ST1',
            'state': 'Backlog',
            'issues': <Map<String, dynamic>>[],
            'work': {
              'atWork': running,
              'tasks': [
                {'task': 'MAIN', 'atWork': running},
              ],
            },
          },
        ],
      });
      await pumpEventQueue();
      expect(second.canStart, !running);
    }
  });

  // Harm: a story started once can never be started again while its machine records no run.
  test('a successful order releases the story on the next board that shows no run', () async {
    final first = await form(
      (source, project, story, task) async => PlanningTaskStarted(task: task.role, container: 'container-${task.role}'),
    );
    expect(await first.start(), isTrue);
    final second = await form((source, project, story, task) async => throw StateError('not started here'));
    expect(second.canStart, isFalse, reason: 'held until a board answers');
    source.push('demo', {...source.boards['demo']!, 'revision': 'r1'});
    await pumpEventQueue();
    expect(board.startUnavailable(board.stories.single), isNull);
  });

  // Harm: a late Start answer launches the rest of an order after its form or board was replaced.
  test('a changed binding stops further tasks after a pending Start answers', () async {
    for (final disposed in [false, true]) {
      source.offerPrefix = disposed ? 'disposed' : 'replaced';
      final gate = Completer<PlanningTaskStarted>();
      final calls = <PlanningTaskOrder>[];
      final work = await form((source, project, story, task) {
        calls.add(task);
        return gate.future;
      });
      final pending = work.start();
      await pumpEventQueue();
      if (disposed) {
        work.dispose();
        forms.remove(work);
      } else {
        await board.retry();
      }
      gate.complete(const PlanningTaskStarted(task: 'actual-task', container: 'actual-container'));
      expect(await pending, isFalse);
      expect(calls, [source.order.tasks.first]);
      expect(work.startedTasks.single.$2.task, 'actual-task');
      await pumpEventQueue();
      final orders = source.orders;
      final next = await form((source, project, story, task) async {
        fail('A late confirmed Start must prevent another order.');
      });
      expect(next.canStart, isFalse);
      expect(await next.start(), isFalse);
      expect(source.orders, orders);
      await reportWork(true);
      await reportWork(false);
    }
  });

  // Harm: replacing a board forgets consumed offers and launches the same task again.
  test('a consumed offer is never handed to Start again after its board is replaced', () async {
    var calls = 0;
    Future<PlanningTaskStarted> start(
      PlanningSource source,
      String project,
      String story,
      PlanningTaskOrder task,
    ) async {
      calls++;
      throw const PlanningRefusal('StartRefused');
    }

    final first = await form(start);
    expect(await first.start(), isFalse);
    board.dispose();
    board = PlanningBoard(source, 'demo');
    await pumpEventQueue();
    final second = await form(start);
    expect(await second.start(), isFalse);
    expect(source.orders, 2);
    expect(calls, 1);
  });
}
