import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: preparing a mock order reports running work or mutates a board before Start confirms anything.
  test('a mock order starts nothing and changes no planning state or revision', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final before = jsonEncode(source.board('demo'));
    final starts = <String>[];
    source.onStarted = (project, work) => starts.add(project);
    final proposal = await source.proposeStoryWork('demo', 'ST291', 'Implementing');
    final order = await source.startStoryWork(
      StoryWorkRequest(
        project: 'demo',
        story: 'ST291',
        to: 'Implementing',
        mode: 'AGENT',
        revision: proposal.revision,
        subAgents: const [],
        proposedRepositories: proposal.repositories,
      ),
    );
    expect(order.tasks, isNotEmpty);
    expect(order.tasks.map((task) => task.role), everyElement(isIn(['MAIN', 'BUILDER'])));
    expect(order.tasks.every((task) => task.prompt.isNotEmpty), isTrue);
    expect(order.revision, proposal.revision);
    expect(jsonEncode(source.board('demo')), before);
    expect(starts, isEmpty);
  });
  // Harm: the stand-in names a refusal differently from the daemon, so the screen's words differ in tests alone.
  // Harm: a stand-in that answers a new story for a repeated hand-in lets the window's tests pass a second story.
  test('the stand-in records each hand-in of an idea and answers one story per offer', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final idea = <String, Object?>{'story': <String, Object?>{}, 'issues': <Object?>[]};
    final first = await source.handIdea('demo', 'offer-a', idea);
    expect(await source.handIdea('demo', 'offer-a', idea), first, reason: 'the same offer, the same story');
    expect(await source.handIdea('demo', 'offer-b', idea), isNot(first), reason: 'another offer, another story');
    expect(source.handedIdeas.map((call) => call.$2), ['offer-a', 'offer-a', 'offer-b'], reason: 'every hand-in');
    expect(RegExp(r'^ST[0-9]{1,5}$').hasMatch(first), isTrue, reason: 'a story id as the planner answers it');
  });

  test('a stand-in refusal is worded as the daemon words it', () {
    final refusal = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'revision': 'r2'});

    expect(refusal.name, 'StaleRevision');
    expect(refusal.parameters, {'revision': 'r2'});
    expect(refusal.toString(), 'org.fuin.porelo.Tasks1.StaleRevision {revision: r2}');
    expect(refusal.notOffered, isFalse);
    expect(mockRefusal('org.varlink.service.MethodNotFound').notOffered, isTrue);
  });

  // Harm: the stand-in's board drifts from what the screen is shown, and every test reads a board nobody sees.
  test('the board shows the stored board and every replacement of it', () async {
    final source = MockPlanningSource();
    final seen = <PlanningSnapshot>[];
    final watch = source.watchStories('demo').listen(seen.add);
    addTearDown(watch.cancel);
    await pumpEventQueue();
    expect(source.watchers, 1);
    expect(seen.single.revision, source.boards['demo']!['revision']);

    source.push('demo', mockPlanningBoard()..['revision'] = 'changed');
    source.push('other', mockPlanningBoard()..['revision'] = 'elsewhere');
    await pumpEventQueue();

    expect(seen.map((board) => board.revision), [isNot('changed'), 'changed']);
  });

  // Harm: the window's tests open a Reworking dialog that cannot rework, over rules the planner never serves.
  test('every work rule the stand-in serves names an agent and its model, as the planner\'s do', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);

    final rules = (await source.planningWorkflow('demo')).work;

    expect(rules.map((rule) => rule.state), [
      'In analysis',
      'Under estimation',
      'Implementing',
      'Reading',
      'Reworking',
      'Finishing',
    ], reason: 'the working states the stand-in has rules for');
    expect(
      rules.where((rule) => rule.models.isEmpty).map((rule) => rule.state),
      isEmpty,
      reason: 'a planner\'s rule names the agents that may work under it',
    );
  });

  // Harm: a rule changed on the stand-in never reaches the screen, so the window's tests pass over the old rules.
  test('a rule changed in a board the stand-in pushes reaches the screen\'s board', () async {
    final source = MockPlanningSource();
    final board = PlanningBoard(source, 'demo');
    addTearDown(() async {
      board.dispose();
      await source.end();
    });
    await pumpEventQueue();
    final changed = mockPlanningBoard()..['revision'] = 'changed';
    final reworking = (changed['work'] as List).cast<Map<String, dynamic>>().firstWhere(
      (rule) => rule['state'] == 'Reworking',
    );
    reworking['models'] = {'codex': 'changed-model'};

    source.push('demo', changed);
    await pumpEventQueue();

    expect(board.snapshot?.revision, 'changed', reason: 'the pushed board is shown');
    expect(board.snapshot?.work.firstWhere((rule) => rule.state == 'Reworking').models, {
      'codex': 'changed-model',
    }, reason: 'the pushed board brings its own rules, as a planner\'s does');
  });

  // Harm: a save goes through although its story is refused, and a scenario of a refusal proves nothing.
  test('a refused story stops the whole save and leaves the board as it was', () async {
    final source = MockPlanningSource()
      ..refusedStory = 'ST291'
      ..refusalDetail = 'not now';
    final before = source.board('demo')['revision'] as String;

    await expectLater(
      source.saveMoves('demo', const [PlanningStoryMove('ST291', 'Backlog')], before),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'WorkNotAllowed')),
    );
    expect(source.moveCalls.single['revision'], before);
    expect(source.board('demo')['revision'], before);
  });

  // Harm: a provider without planning answers anyway, and the screen never shows that it has none.
  test('a provider without planning says so on every read', () async {
    final source = MockPlanningSource()..unsupported = true;

    await expectLater(source.stories('demo'), throwsA(isA<PlanningUnsupported>()));
    expect(planningUnsupported(const PlanningUnsupported('this backend has no planning')), isTrue);
  });

  // Harm: a confirmed start never reaches the window, so its run is not shown where runs are shown.
  test('a confirmed start tells who listens what started', () async {
    final source = MockPlanningSource();
    final board = source.board('demo');
    final told = <(String, Map<String, dynamic>)>[];
    source.onStarted = (project, work) => told.add((project, work));
    final story = (board['stories'] as List).cast<Map<String, dynamic>>().firstWhere(
      (story) => (story['issues'] as List? ?? const []).isNotEmpty && story['work'] == null,
    );
    final working = (board['states'] as List).cast<Map<String, dynamic>>().firstWhere(
      (state) => state['kind'] == 'WORKING',
    );

    final order = await source.startStoryWork(
      StoryWorkRequest(
        project: 'demo',
        story: story['id'] as String,
        to: working['name'] as String,
        mode: 'AGENT',
        revision: board['revision'] as String,
        subAgents: const [],
      ),
    );
    final started = await mockStartTask(source, 'demo', story['id'] as String, order.tasks.first);

    expect(told.single.$1, 'demo');
    expect(told.single.$2['atWork'], isTrue);
    final task = (told.single.$2['tasks'] as List).single as Map;
    expect(started.task, task['task']);
    expect(started.container, task['containerId']);
    expect(told.single.$2['tasks'], hasLength(1));
    expect(source.work.calls.where((call) => call.$1 == 'StartStoryWork'), hasLength(1));
  });

  // Harm: the window's own choices of provider and project are lost on the way to the screen.
  test('a client of one source answers it as the current provider', () {
    final source = MockPlanningSource();
    final client = MockPlanningClient.of(source);
    addTearDown(client.dispose);

    expect(client.currentProvider, 'mock');
    expect(client.providers.single.source, same(source));
    expect(client.providers.single.projects, ['demo']);
  });
}
