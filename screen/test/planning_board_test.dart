import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

class _Backend extends Fake implements PlanningSource {
  int workflowReads = 0;
  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async {
    workflowReads++;
    return _snapshot('workflow');
  }

  final controllers = <String, StreamController<PlanningSnapshot>>{};
  final canceled = <String>[];
  @override
  Stream<PlanningSnapshot> watchStories(String project) => controllers
      .putIfAbsent(
        project,
        () => StreamController<PlanningSnapshot>(
          sync: true,
          onCancel: () {
            canceled.add(project);
          },
        ),
      )
      .stream;
}

/// A source whose first watch is refused, as for a repository the planner does not take, and later ones answer.
class _RefusedOnce extends Fake implements PlanningSource {
  int watches = 0;
  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async => _snapshot('workflow');
  @override
  Stream<PlanningSnapshot> watchStories(String project) => ++watches == 1
      ? Stream.error(const PlanningRefusal('NotACloneUrl', {}, 'the repository is not a clone URL'))
      : Stream.value(_snapshot('ST1'));
}

/// A source whose board loads with a workflow fault.
class _Faulty extends Fake implements PlanningSource {
  int watches = 0;
  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async => PlanningSnapshot.from({
    'states': [
      {'name': 'Building', 'kind': 'WORKING'},
    ],
    'phases': [
      {
        'name': 'Build',
        'states': ['Building'],
      },
    ],
    'faults': ['a fault'],
  });
  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    watches++;
    final replies = StreamController<PlanningSnapshot>();
    // A whole board brings its own workflow, and with it its faults.
    replies.add(
      PlanningSnapshot.from({
        'revision': 'r1',
        'states': [
          {'name': 'Building', 'kind': 'WORKING'},
        ],
        'phases': [
          {
            'name': 'Build',
            'states': ['Building'],
          },
        ],
        'faults': ['a fault'],
        'stories': [
          {'id': 'ST1', 'state': 'Building', 'title': 'A story', 'priority': 'High'},
        ],
      }),
    );
    return replies.stream;
  }
}

/// A whole board as the planner streams it: its revision, one state in one phase, and one story in that state.
PlanningSnapshot _complete(String revision, String state, {String phase = 'Build'}) => PlanningSnapshot.from({
  'revision': revision,
  'states': [
    {'name': state, 'kind': 'WORKING'},
  ],
  'phases': [
    {
      'name': phase,
      'states': [state],
    },
  ],
  'work': <Object>[],
  'faults': <String>[],
  'stories': [
    {'id': 'ST1', 'state': state, 'title': 'A story', 'priority': 'High'},
  ],
});

PlanningSnapshot _snapshot(String id) => PlanningSnapshot.from({
  'states': [
    {'name': 'Building', 'kind': 'WORKING'},
  ],
  'phases': [
    {
      'name': 'Build',
      'states': ['Building'],
    },
  ],
  'stories': [
    {'id': id, 'state': 'Building', 'title': 'A story', 'priority': 'High'},
  ],
  'revision': id,
});

void main() {
  // Harm: a synchronous refusal leaves a board looking live or offers calls an older daemon cannot serve.
  for (final (failure, unsupported, words) in <(Object, bool, String?)>[
    (const PlanningUnsupported('this backend has no planning'), true, null),
    (mockRefusal('org.varlink.service.MethodNotFound', {'method': 'WatchStories'}), true, null),
    (
      mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {'detail': 'Planning store cannot be read'}),
      false,
      'Planning store cannot be read',
    ),
  ]) {
    test('a synchronous watch failure marks the board unavailable: $failure', () {
      final board = PlanningBoard(_SynchronousFailure(failure), 'project');
      addTearDown(board.dispose);
      expect(board.live, isFalse);
      expect(board.unsupported, unsupported);
      expect(board.problem, words);
    });
  }

  // Harm: the unsupported-planning page attributes the refusal to another machine after selection changes.
  test('when no provider offers planning the selected board belongs to the current machine', () async {
    final first = MockPlanningSource()..unsupported = true;
    final second = MockPlanningSource()..unsupported = true;
    var current = 'local';
    final listed = [
      PlanningProvider(name: 'local', connected: true, projects: const ['demo'], source: first),
    ];
    final client = MockPlanningClient(() => listed, currentProvider: () => current);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await first.end();
      await second.end();
    });
    listed.add(PlanningProvider(name: 'other', connected: true, projects: const ['demo'], source: second));
    client.changed();
    await Future<void>.delayed(Duration.zero);
    expect(boards.projects, isEmpty);
    expect(boards.selected!.unsupported, isTrue);
    expect(boards.selected!.backend, same(first));

    current = 'other';
    client.changed();
    expect(boards.selected!.unsupported, isTrue);
    expect(boards.selected!.backend, same(second));

    current = 'local';
    client.changed();
    expect(boards.selected!.backend, same(first));
  });

  // Harm: the person sees a refusal's name and an empty map instead of what went wrong.
  test('a refusal is shown in its own words', () {
    expect(
      planningFailureWords(const PlanningRefusal('PlannerUnreachable', {}, 'the planner cannot be reached: refused')),
      'the planner cannot be reached: refused',
    );
  });

  // Harm: a description bypasses the special wordings, or carries control characters to the screen.
  test('a refusal\'s description comes after the special wordings and is shown without control characters', () {
    expect(
      planningFailureWords(mockRefusal('org.fuin.porelo.Tasks1.EnteredOnlyByWork', {'item': 'ST1', 'to': 'Building'})),
      'Building can be entered only by work.',
    );
    expect(planningFailureWords(const PlanningRefusal('Unreachable', {}, 'no\x07 answer\x1b[2J')), 'no answer[2J');
  });

  // Harm: a live board whose workflow has faults is torn down and read again on every change of the window.
  test('a live board with workflow faults is not loaded again when its provider changes', () async {
    final source = _Faulty();
    final client = FakePlanningClient([provider('local', source)]);
    final boards = PlanningBoards(client);
    addTearDown(() {
      boards.dispose();
      client.dispose();
    });
    await pumpEventQueue();
    expect(boards.selected!.live, isTrue);
    expect(boards.selected!.problem, 'a fault');

    client.providers = [provider('local', source)];
    await pumpEventQueue();

    expect(source.watches, 1, reason: 'the live board kept its watch');
  });

  // Harm: a board that failed once stays failed for good, though the repository has since been named.
  test('a board that failed loads again when its provider changes', () async {
    final source = _RefusedOnce();
    final client = FakePlanningClient([provider('local', source)]);
    final boards = PlanningBoards(client);
    addTearDown(() {
      boards.dispose();
      client.dispose();
    });
    await pumpEventQueue();
    expect(boards.selected!.problem, 'the repository is not a clone URL', reason: 'the first watch was refused');

    client.providers = [provider('local', source)];
    await pumpEventQueue();

    expect(source.watches, 2, reason: 'the board watched again');
    expect(boards.selected!.problem, isNull, reason: 'and holds the board now');
    expect(boards.selected!.snapshot, isNotNull);
  });

  // Harm: a workflow changed by a push elsewhere never reaches the board, which keeps the old columns.
  test('a streamed board that is complete is shown with its own workflow, without another read', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    final reads = backend.workflowReads;
    backend.controllers['project']!.add(_complete('r1', 'Building'));
    await pumpEventQueue();
    backend.controllers['project']!.add(_complete('r2', 'Building', phase: 'Ship'));
    await pumpEventQueue();

    expect(board.snapshot!.phases.map((phase) => phase.name), ['Ship'], reason: 'the new workflow, same states');
    expect(backend.workflowReads, reads, reason: 'no workflow read beside the stream');
  });

  // Harm: a workflow broken by a push elsewhere goes unsaid, the board showing the old columns as sound.
  test('a streamed board whose workflow has only faults shows them, without another read', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    backend.controllers['project']!.add(_complete('r1', 'Building'));
    await pumpEventQueue();
    final reads = backend.workflowReads;
    backend.controllers['project']!.add(
      PlanningSnapshot.from({
        'revision': 'r2',
        'states': <Object>[],
        'phases': <Object>[],
        'work': <Object>[],
        'faults': ['the workflow cannot be read'],
        'stories': <Object>[],
      }),
    );
    await pumpEventQueue();

    expect(board.problem, 'the workflow cannot be read');
    expect(backend.workflowReads, reads, reason: 'no workflow read beside the stream');
  });

  // Harm: a silent subscription keeps a project or a disposed window alive indefinitely.
  test('disposing cancels a watch even when it has never answered', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'first');
    var notifications = 0;
    board.addListener(() {
      notifications++;
    });
    board.dispose();
    await Future<void>.delayed(Duration.zero);
    expect(backend.canceled, ['first']);
    backend.controllers['first']!.add(_snapshot('late'));
    await Future<void>.delayed(Duration.zero);
    expect(notifications, 0);
    await backend.controllers['first']!.close();
  });

  // Harm: an old project's answer is shown below another project's name.
  test('changing project cancels the old watch and rejects its late replies', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'first');
    addTearDown(board.dispose);
    backend.controllers['first']!.add(_snapshot('old'));
    await Future<void>.delayed(Duration.zero);
    expect(board.stories.single.id, 'old');
    await board.changeProject(backend, 'second');
    expect(backend.canceled, ['first']);
    expect(board.snapshot, isNull);
    backend.controllers['first']!.add(_snapshot('late'));
    await Future<void>.delayed(Duration.zero);
    backend.controllers['second']!.add(_snapshot('new'));
    await Future<void>.delayed(Duration.zero);
    expect(board.project, 'second');
    expect(board.stories.single.id, 'new');
    await backend.controllers['first']!.close();
  });

  // Harm: deleted stories or old workflow metadata survive a complete replacement.
  test('story answers replace deletions while the separate workflow remains', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    backend.controllers['project']!.add(_snapshot('old'));
    await Future<void>.delayed(Duration.zero);
    expect(board.workingCount, isNull);
    backend.controllers['project']!.add(
      PlanningSnapshot.from({
        'states': <Map<String, dynamic>>[],
        'phases': <Map<String, dynamic>>[],
        'stories': <Map<String, dynamic>>[],
        'revision': 'empty',
      }),
    );
    await Future<void>.delayed(Duration.zero);
    expect(board.stories, isEmpty);
    expect(board.phases.single.name, 'Build');
    expect(board.states.single.name, 'Building');
    expect(board.snapshot!.revision, 'empty');
    expect(board.workingCount, 0);
  });

  // Harm: losing a stream is mistaken for an empty or current board.
  test('errors preserve their words and make the last snapshot stale', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    backend.controllers['project']!.add(_snapshot('retained'));
    await Future<void>.delayed(Duration.zero);
    backend.controllers['project']!.addError(mockRefusal('WorkNotAllowed', {'detail': 'cannot read the store'}));
    await Future<void>.delayed(Duration.zero);
    expect(board.problem, 'cannot read the store');
    expect(board.live, isFalse);
    expect(board.workingCount, isNull);
    expect(board.stories.single.id, 'retained');
  });

  // Harm: a daemon refusal is replaced by a raw exception or a malformed parameter instead of its words.
  test('planning refusal words prefer detail and then message without stringifying other values', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    final cases = <(Map<String, dynamic>, String)>[
      ({'detail': 'The detail', 'message': 'The message'}, 'The detail'),
      ({'detail': ' ', 'message': 'The message'}, 'The message'),
      ({'detail': 7, 'message': 'The message'}, 'The message'),
      // No words among the parameters: the service's own wording of the refusal.
      ({'detail': 7, 'message': false}, 'org.fuin.porelo.Tasks1.WorkNotAllowed {detail: 7, message: false}'),
    ];
    for (final (parameters, words) in cases) {
      backend.controllers['project']!.addError(mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', parameters));
      await Future<void>.delayed(Duration.zero);
      expect(board.problem, words);
      backend.controllers.remove('project');
      await board.retry();
    }
  });

  // Harm: a normally ended stream leaves a frozen board looking current.
  test('a final watch snapshot stays visible but cannot authorize actions', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    backend.controllers['project']!.add(_snapshot('last'));
    await Future<void>.delayed(Duration.zero);
    expect(board.live, isTrue);
    await backend.controllers['project']!.close();
    expect(board.live, isFalse);
    expect(board.startUnavailable(board.stories.single), isNotNull);
    expect(board.problem, isNull);
    expect(board.stories.single.id, 'last');
  });

  // Harm: absent workflow data becomes a reassuring zero or drops an orphan story.
  test('incomplete and inconsistent replies remain unsaid or visibly unplaced', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    backend.controllers['project']!.add(PlanningSnapshot.from({}));
    await Future<void>.delayed(Duration.zero);
    expect(board.workingCount, isNull);
    expect(board.closedCount, isNull);
    backend.controllers['project']!.add(
      PlanningSnapshot.from({
        'states': <Map<String, dynamic>>[],
        'phases': <Map<String, dynamic>>[],
        'stories': [
          {'id': 'orphan', 'state': 'Future state'},
        ],
        'revision': 'orphan',
      }),
    );
    await Future<void>.delayed(Duration.zero);
    expect(board.unplaced.single.id, 'orphan');
    expect(board.workingCount, isNull);
  });
  // Harm: a failed planning stream cannot be resumed while the machine remains connected.
  test('retry replaces an ended watch without resetting the view choices', () async {
    final backend = _Backend();
    final board = PlanningBoard(backend, 'project');
    addTearDown(board.dispose);
    board.filter(PlanningPriority.high);
    board.search('story');
    backend.controllers['project']!.add(_snapshot('before'));
    await Future<void>.delayed(Duration.zero);
    await backend.controllers['project']!.close();
    expect(board.problem, isNull);
    backend.controllers.remove('project');
    await board.retry();
    backend.controllers['project']!.add(_snapshot('after'));
    await Future<void>.delayed(Duration.zero);
    expect(board.live, isTrue);
    expect(board.problem, isNull);
    expect(board.stories.single.id, 'after');
    expect(board.preferences.priority, PlanningPriority.high);
    expect(board.preferences.search, 'story');
  });
  // Harm: a refusal stays invisible while cancellation waits on an unresponsive transport.
  test('a watch error is reported before asynchronous cancellation finishes', () async {
    final canceled = Completer<void>();
    final source = StreamController<PlanningSnapshot>(onCancel: () => canceled.future);
    final backend = _SlowCancellation(source);
    final board = PlanningBoard(backend, 'project');
    source.addError(mockRefusal('WorkNotAllowed', {'detail': 'store unavailable'}));
    await Future<void>.delayed(Duration.zero);
    try {
      expect(board.problem, contains('store unavailable'));
      expect(board.live, isFalse);
    } finally {
      canceled.complete();
      board.dispose();
      await source.close();
    }
  });
}

class _SlowCancellation extends Fake implements PlanningSource {
  _SlowCancellation(this.source);
  final StreamController<PlanningSnapshot> source;
  @override
  Stream<PlanningSnapshot> watchStories(String project) => source.stream;
}

class _SynchronousFailure extends Fake implements PlanningSource {
  _SynchronousFailure(this.failure);
  final Object failure;

  @override
  Stream<PlanningSnapshot> watchStories(String project) => throw failure;
}
