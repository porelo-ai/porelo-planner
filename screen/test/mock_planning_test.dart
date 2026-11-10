import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: the stand-in names a refusal differently from the daemon, so the screen's words differ in tests alone.
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

    final started = await mockStartTask(
      source,
      StoryWorkRequest(
        project: 'demo',
        story: story['id'] as String,
        to: working['name'] as String,
        mode: 'AGENT',
        revision: board['revision'] as String,
        subAgents: const [],
      ),
    );

    expect(told.single.$1, 'demo');
    expect(told.single.$2['atWork'], isTrue);
    expect(started.started, isTrue);
    expect(source.work.calls.first.$1, 'StartStoryWork');
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
