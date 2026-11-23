import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

void main() {
  // Harm: appending an issue refuses a story's existing tracker sub-issues instead of preserving them.
  test('a mock story keeps an existing parented issue when another issue is appended', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final data = smallBoard();
    ((data['stories'] as List).first['issues'] as List).first['parent'] = 'S1';
    source.boards['demo'] = {
      ...data,
      'issues': [
        {'id': 'B900', 'state': 'Backlog'},
      ],
    };
    await source.setStoryIssues('demo', 'S1', ['I1', 'B900'], seenOn(source.board('demo')));
    final story = mockPlanningStory(source.board('demo'), 'S1')!;
    expect((story['issues'] as List).map((dynamic issue) => (issue as Map)['id']), ['I1', 'B900']);
  });

  // Harm: a story whose issues were never reported reads as a known empty list.
  test('a mock story without an issue list reads its list as unreported, never as empty', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final data = smallBoard();
    (data['stories'] as List).first.remove('issues');
    source.boards['demo'] = data;
    final list = await source.listStoryIssues('demo', 'S1');
    expect(list.supplied, isFalse);
    expect(list.issues, isEmpty);
    final listed = await source.listStoryIssues('demo', 'S2');
    expect(listed.supplied, isTrue);
    expect(listed.issues, isNotEmpty);
  });

  // Harm: an unchanged list advances the revision and emits a misleading change event.
  test('setting the same mock story list keeps the revision and emits no event', () async {
    final source = MockPlanningSource();
    source.boards['demo'] = smallBoard();
    final seen = <PlanningSnapshot>[];
    final watch = source.watchStories('demo').listen(seen.add);
    addTearDown(() async {
      await watch.cancel();
      await source.end();
    });
    await pumpEventQueue();
    expect(await source.setStoryIssues('demo', 'S1', ['I1'], seenOn(source.board('demo'))), 'r1');
    await pumpEventQueue();
    expect(source.board('demo')['revision'], 'r1');
    expect(seen.map((reply) => reply.revision), ['r1']);
  });

  // Harm: mock refusals omit contract fields that the window needs to explain a refused edit.
  test('mock story list refusals name the issues field and the story with running work', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final data = smallBoard();
    source.boards['demo'] = {
      ...data,
      'issues': [
        {'id': 'B900', 'parent': 'B899', 'state': 'Backlog'},
      ],
    };
    await expectLater(
      source.setStoryIssues('demo', 'S1', ['I1', 'B900'], seenOn(source.board('demo'))),
      throwsA(
        isA<PlanningRefusal>()
            .having((error) => error.name, 'name', 'EditNotAllowed')
            .having((error) => error.parameters['field'], 'field', 'issues'),
      ),
    );
    ((data['stories'] as List).first['issues'] as List).first['atWork'] = true;
    await expectLater(
      source.setStoryIssues('demo', 'S1', [], seenOn(source.board('demo'))),
      throwsA(
        isA<PlanningRefusal>()
            .having((error) => error.name, 'name', 'WorkRunning')
            .having((error) => error.parameters['story'], 'story', 'S1'),
      ),
    );
  });

  // Harm: the standalone mock issue opens without its criteria or leaks detail-only fields into list replies.
  test('the mock standalone issue has full details and its list answer contains only board fields', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final detail = await source.getIssue('demo', 'B900');
    expect(detail.whatMustBeTrue, ['The work meets the issue description.']);
    expect(detail.subIssuesReported, isTrue);
    expect(detail.subIssues, isEmpty);
    final listed = (await source.listIssues('demo')).issues.single;
    expect(listed.id, 'B900');
    expect(listed.whatMustBeTrue, isNull);
    final raw = mockPlanningNoStoryIssues(source.board('demo')).single;
    expect(
      raw.keys.where((key) => ['text', 'textFormat', 'whatMustBeTrue', 'subAgents', 'subIssues'].contains(key)),
      isEmpty,
    );
  });

  // Harm: creating an issue reuses a standalone issue's reference or loses the new issue from the board.
  test('a mock new issue is numbered after standalone issues and is listed with its saved fields', () async {
    final source = MockPlanningSource();
    addTearDown(source.end);
    final added = await source.createIssue(
      'demo',
      'porelo',
      'New idea',
      'It works.',
      'M',
      const {},
      worker: IssueWorker.manual,
    );
    expect(added.id, 'B901');
    final issues = (await source.listIssues('demo')).issues;
    expect(issues.map((issue) => issue.id), containsAll(['B900', 'B901']));
    final created = issues.singleWhere((issue) => issue.id == added.id);
    expect(created.title, 'New idea');
    expect(created.state, 'New');
    expect(created.size, 'M');
    expect(created.worker, IssueWorker.manual);
    expect((await source.getIssue('demo', added.id)).whatMustBeTrue, ['It works.']);
  });

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
        seen: seenOn(source.board('demo')),
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
    final refusal = mockRefusal('org.fuin.porelo.Tasks1.ItemChanged', {
      'changed': ['S1'],
    });

    expect(refusal.name, 'ItemChanged');
    expect(refusal.parameters, {
      'changed': ['S1'],
    });
    expect(refusal.toString(), 'org.fuin.porelo.Tasks1.ItemChanged {changed: [S1]}');
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
    const moves = [PlanningStoryMove('ST291', 'Rejected')];
    final preview = await source.previewMoves('demo', moves);

    await expectLater(
      source.saveMoves('demo', moves, preview.targets, digest: preview.digest),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'WorkNotAllowed')),
    );
    expect(source.moveCalls.single['seen'], preview.targets);
    expect(source.board('demo')['revision'], before);
  });

  // Harm: the window's tests move an open story between open states against a mock that lets it, where the planner
  // refuses it, or reject a story whose open issues stay open.
  test('an open story moved to an open state is refused, and rejected it closes its open issues', () async {
    final source = MockPlanningSource();
    final before = source.board('demo')['revision'] as String;

    await expectLater(
      source.previewMoves('demo', const [PlanningStoryMove('ST295', 'Review')]),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'MoveNotAllowed')),
    );
    expect(source.board('demo')['revision'], before, reason: 'the refused move wrote nothing');

    const moves = [PlanningStoryMove('ST295', 'Rejected')];
    final preview = await source.previewMoves('demo', moves);
    await source.saveMoves('demo', moves, preview.targets, digest: preview.digest);
    final story = mockPlanningStory(source.board('demo'), 'ST295')!;
    expect(story['state'], 'Rejected', reason: 'the story is closed');
    expect([for (final issue in story['issues'] as List) issue['state']], everyElement('Rejected'));
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
        seen: seenOn(board),
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

  // Harm: the window's planning tests rehearse a directory choice the mock neither keeps nor refuses as the planner.
  test(
    'the mock keeps a local project\'s directory, refuses one it is told to, and offers none to GitHub\'s',
    () async {
      final source = MockPlanningSource();
      addTearDown(source.end);
      source.boards['demo'] = {
        ...smallBoard(),
        'tracker': {'kind': 'local', 'items': '/home/mock/planning', 'default': source.defaultItemsDirectory},
      };
      source.refusedDirectories['/home/mock/gone'] = 'The directory does not exist.';

      final other = PlanningTracker.local('/home/mock/other', source.defaultItemsDirectory);
      expect(await source.setItemsDirectory('demo', '/home/mock/other', const {}), other);
      expect((await source.planningWorkflow('demo')).tracker, other);
      expect(other.awayFromDefault, isTrue);
      expect(
        (await source.setItemsDirectory('demo', null, const {})).awayFromDefault,
        isFalse,
        reason: 'null goes back to the default',
      );
      await source.setItemsDirectory('demo', '/home/mock/other', const {});
      final gone = await source
          .setItemsDirectory('demo', '/home/mock/gone', const {})
          .then<Object?>((_) => null, onError: (Object error) => error);
      expect(ItemsDirectoryHeader.refusedOf(gone as PlanningRefusal?), (
        directory: '/home/mock/gone',
        words: 'The directory does not exist.',
      ));
      expect(
        (await source.planningWorkflow('demo')).tracker?.items,
        '/home/mock/other',
        reason: 'nothing kept on a refusal',
      );

      source.boards['other'] = smallBoard()..['tracker'] = {'kind': 'github'};
      final github = await source
          .setItemsDirectory('other', '/home/mock/other', const {})
          .then<Object?>((_) => null, onError: (Object error) => error);
      expect(planningUnsupported(github!), isTrue, reason: 'not offered for GitHub');
    },
  );
}
