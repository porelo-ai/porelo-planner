import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/ui/story_work_wizard.dart' show showStoryWork;

import 'support/story_work_support.dart';

/// One story with one agent's issue, on a workflow with one working state.
Map<String, dynamic> _board() => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Implementing', 'kind': 'WORKING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Implementing', 'Finished'],
    },
  ],
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A story',
      'state': 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'issues': [
        {
          'id': 'AA1',
          'repository': 'repo',
          'title': 'Issue AA1',
          'state': 'Backlog',
          'size': 'S',
          'blockedBy': <String>[],
          'parent': null,
          'link': 'https://example.org/issues/AA1',
          'worker': 'AGENT',
          'atWork': false,
          'needsYou': false,
          'readingStopped': false,
          'findings': <Map<String, dynamic>>[],
        },
      ],
    },
  ],
  'revision': 'r1',
  'repositories': ['repo'],
};

const _sub = 'repo/AA1/helper';

/// The board of [_board] with a second agent's issue in a second repository.
Map<String, dynamic> _twoRepositories() {
  final board = _board();
  final story = (board['stories'] as List).single as Map<String, dynamic>;
  final first = (story['issues'] as List).single as Map<String, dynamic>;
  story['issues'] = [
    first,
    {...first, 'id': 'AB1', 'repository': 'repo2', 'title': 'Issue AB1', 'link': 'https://example.org/issues/AB1'},
  ];
  board['repositories'] = ['repo', 'repo2'];
  return board;
}

/// A window's wizard that keeps the steps it was handed and never closes on its own.
class _Wizards implements PlanningWizards {
  List<PlanningWizardStep> steps = const [];
  final closed = Completer<bool>();

  @override
  Future<bool> show(
    BuildContext context, {
    required String title,
    required List<PlanningWizardStep> steps,
    required Listenable changes,
    required String Function() startLabel,
    required Future<bool> Function() start,
    required bool Function() canCancel,
  }) {
    this.steps = steps;
    return closed.future;
  }
}

Future<(MockPlanningSource, PlanningBoard)> _open(WidgetTester tester, {List<String>? modes}) async {
  final source = MockPlanningSource()..boards['demo'] = _board();
  if (modes != null) {
    final proposal = source.work.call('ProposeStoryWork', {'project': 'demo', 'story': 'ST1', 'to': 'Implementing'});
    source.work.proposals['ST1'] = {...proposal, 'modes': modes};
  }
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  addTearDown(() async {
    board.dispose();
    await tester.runAsync(source.end);
  });
  return (source, board);
}

/// The agents a machine offers, listed only once [held] completes, as a window that is still asking.
class _HeldAgents implements PlanningAgents {
  _HeldAgents(this.agents);

  final List<PlanningAgent> agents;
  final held = Completer<void>();

  @override
  Future<PlanningAgentList> on(PlanningSource source) async {
    await held.future;
    return PlanningAgentList(agents);
  }
}

/// Makes the proposal for ST1 offer [offered] for each repository's main, as a planner that names them does.
void _offer(MockPlanningSource source, List<Map<String, String>> offered) {
  final proposal = source.work.call('ProposeStoryWork', {'project': 'demo', 'story': 'ST1', 'to': 'Implementing'});
  source.work.proposals['ST1'] = {
    ...proposal,
    'repositories': [
      for (final repo in (proposal['repositories'] as List).cast<Map<String, dynamic>>())
        {
          ...repo,
          'main': {...(repo['main'] as Map<String, dynamic>), 'offered': offered},
        },
    ],
  };
}

/// A planner whose next proposal is refused for a blocker while an unrelated save moves the board's revision.
class _MovedWhileRefused extends MockPlanningSource {
  bool refuseNext = true;
  int asked = 0;

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues}) async {
    asked++;
    if (!refuseNext) return super.proposeStoryWork(project, story, to, issues: issues);
    refuseNext = false;
    push(project, _board()..['revision'] = 'r2');
    await Future<void>.delayed(const Duration(milliseconds: 5));
    throw mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {
      'from': 'Backlog',
      'to': to,
      'reason': 'AA1 waits on AA0. Work on it starts once that is done.',
    });
  }
}

const _stale = 'The board changed or is no longer live. Cancel and open Start work again.';

StoryWork _work(PlanningBoard board, List<PlanningAgent> agents) {
  final work = planningStoryWork(board, board.stories.single, 'Implementing', agents: FakeAgents(agents));
  addTearDown(work.dispose);
  return work;
}

Future<void> _answered(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

Future<void> _next(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('story-work-next')));
  await settle(tester);
}

/// The text the frame's title shows, naming the step.
String _title(WidgetTester tester) =>
    tester.widget<Text>(find.descendant(of: find.byType(AlertDialog), matching: find.byType(Text)).first).data!;

String _problem(WidgetTester tester) => tester.widget<Text>(find.byKey(const Key('story-work-step-problem'))).data!;

bool _canGoOn(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('story-work-next'))).onPressed != null;

void main() {
  // Prevents a person from facing a list with one entry, or a step passed over while the proposal is not yet there.
  testWidgets('a single machine, working state and mode show as text and their steps wait for the proposal', (
    tester,
  ) async {
    final (_, board) = await _open(tester, modes: ['UNATTENDED']);
    final work = _work(board, const [PlanningAgent('codex')]);
    final wizards = _Wizards();
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (each) {
            context = each;
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    unawaited(showStoryWork(context, work, wizards: wizards));
    expect(wizards.steps.map((step) => step.title), ['Where', 'How', 'Who', 'Reading'], reason: 'the steps');
    expect(wizards.steps.first.shown(), isTrue, reason: 'the first step is never passed over while it loads');
    expect(wizards.steps.first.check(), isNotNull, reason: 'nobody goes on before the proposal');
    await _answered(tester);
    expect(work.proposal, isNotNull, reason: 'the proposal was read: ${work.problem}');
    expect(wizards.steps[0].shown(), isFalse, reason: 'one machine and one working state leave nothing to choose');
    expect(wizards.steps[1].shown(), isFalse, reason: 'one mode leaves nothing to choose');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(children: [for (final step in wizards.steps.take(2)) Builder(builder: step.panel)]),
        ),
      ),
    );
    await settle(tester);
    expect(find.byType(DropdownButton<PlanningBoard>), findsNothing, reason: 'no list of one machine');
    expect(find.byType(DropdownButton<String>), findsNothing, reason: 'no list of one working state');
    expect(find.byKey(const Key('story-work-mode UNATTENDED')), findsNothing, reason: 'no card of one mode');
    expect(find.textContaining('Runs on'), findsNothing, reason: 'one machine is no choice and is not shown');
    expect(find.text('Working state: Implementing'), findsOneWidget, reason: 'the working state is named');
    expect(find.byKey(const Key('story-work-mode-name')), findsOneWidget, reason: 'the mode is named');
    expect(wizards.steps[1].summary(), ['Unattended'], reason: 'the review names the mode');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Harm: a Start refused for its blockers reads as a board that changed, and Retry can never ask again, so the person
  // cannot tell why the work does not start nor start it once the blocker is done.
  testWidgets(
    'a refused proposal while the board moves shows the refusal alone and Retry asks on the board as it stands',
    (tester) async {
      final source = _MovedWhileRefused()..boards['demo'] = _board();
      final board = PlanningBoard(source, 'demo');
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      addTearDown(() async {
        board.dispose();
        await tester.runAsync(source.end);
      });
      final work = _work(board, const [PlanningAgent('codex')]);
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
      await _answered(tester);
      expect(board.snapshot?.revision, 'r2', reason: 'the board moved while the proposal was asked');
      expect(work.problem, contains('AA1 waits on AA0'), reason: 'the refusal');
      expect(_problem(tester), work.problem, reason: 'the step names the refusal');
      expect(find.text(_stale), findsNothing, reason: 'a refused proposal has nothing to go stale');

      await tester.tap(find.byKey(const Key('story-work-retry')));
      await _answered(tester);
      expect(source.asked, 2, reason: 'Retry asked again');
      expect(work.problem, isNull, reason: 'the new answer stands: ${work.problem}');
      expect(work.proposal, isNotNull, reason: 'proposed on the board as it stands');
      expect(work.proposal!.revision, 'r2', reason: 'on the moved board');
      expect(work.current, isTrue, reason: 'the proposal is current');
      expect(work.canStart, isTrue, reason: 'the person can start it');
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  // Harm: work starts on a proposal the board has moved past without the person seeing that it went stale.
  testWidgets('a proposal given and then passed by the board still says the board changed', (tester) async {
    final (source, board) = await _open(tester);
    final work = _work(board, const [PlanningAgent('codex')]);
    final wizards = _Wizards();
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (each) {
            context = each;
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    unawaited(showStoryWork(context, work, wizards: wizards));
    await _answered(tester);
    expect(work.proposal, isNotNull, reason: 'the proposal was read: ${work.problem}');
    expect(wizards.steps.first.check(), isNull, reason: 'a current proposal lets the person go on');
    source.push('demo', _board()..['revision'] = 'r2');
    await _answered(tester);
    expect(wizards.steps.first.check(), _stale, reason: 'the stale proposal stops the person, saying why');
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: Builder(builder: wizards.steps.first.panel)),
      ),
    );
    await settle(tester);
    expect(find.text(_stale), findsOneWidget, reason: 'the step says the board changed');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Harm: a proposal with a problem of its own that the board then passes names only one of the two, so the person
  // either starts on a stale proposal or never learns what was wrong with it.
  testWidgets('a proposal with a problem that the board then passed names the problem first, then the stale line', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    final agents = FakeAgents(const [PlanningAgent('codex')])..failures = {'claude': 'Its models could not be read.'};
    final work = planningStoryWork(board, board.stories.single, 'Implementing', agents: agents);
    addTearDown(work.dispose);
    final wizards = _Wizards();
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (each) {
            context = each;
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    unawaited(showStoryWork(context, work, wizards: wizards));
    await _answered(tester);
    expect(work.proposal, isNotNull, reason: 'the proposal was read');
    final problem = work.problem!;
    expect(problem, contains('claude'), reason: 'the agent that could not be read');
    expect(wizards.steps.first.check(), problem, reason: 'a current proposal names its problem alone');
    source.push('demo', _board()..['revision'] = 'r2');
    await _answered(tester);
    expect(work.current, isFalse, reason: 'the board moved past the proposal');
    expect(wizards.steps.first.check(), '$problem\n$_stale', reason: 'the problem first, then the stale line');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents a second machine or working state from being out of the person's reach once one choice stands as text.
  testWidgets('two machines and two working states are labeled lists the person chooses from', (tester) async {
    final (_, board) = await _open(tester);
    final data = _board();
    (data['states'] as List).insert(2, {'name': 'Reviewing', 'kind': 'WORKING'});
    ((data['phases'] as List).single as Map)['states'] = ['Backlog', 'Implementing', 'Reviewing', 'Finished'];
    final second = MockPlanningSource()..boards['demo'] = data;
    final other = PlanningBoard(second, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    addTearDown(() async {
      other.dispose();
      await tester.runAsync(second.end);
    });
    final work = planningStoryWork(
      board,
      board.stories.single,
      'Implementing',
      machines: {board: 'first machine', other: 'second machine'},
      agents: FakeAgents(const [PlanningAgent('codex')]),
    );
    addTearDown(work.dispose);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    expect(_title(tester), 'Start work — Where', reason: 'a choice of machine is a step');
    expect(find.text('Runs on'), findsOneWidget, reason: 'the machine list is labeled');

    await tester.tap(find.byKey(const Key('story-work-machine')));
    await settle(tester);
    await tester.tap(find.text('second machine').last);
    await _answered(tester);
    expect(identical(work.board, other), isTrue, reason: 'the second machine can be chosen');
    expect(find.text('Working state'), findsOneWidget, reason: 'the list of two working states is labeled');

    await tester.tap(find.byKey(const Key('story-work-target')));
    await settle(tester);
    await tester.tap(find.text('Reviewing').last);
    await _answered(tester);
    expect(work.to, 'Reviewing', reason: 'the second working state can be chosen');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents a task from starting with a model or effort proposed for another agent than the one chosen.
  testWidgets('choosing another agent starts its model and effort again from the proposal', (tester) async {
    final (_, board) = await _open(tester);
    final work = _work(board, const [
      PlanningAgent('codex', efforts: ['low', 'high']),
      PlanningAgent('other-agent', efforts: ['low']),
      PlanningAgent('third-agent'),
    ]);
    await tester.runAsync(work.load);
    final sub = work.proposal!.repositories.single.subAgents.single;
    expect(work.repositories['repo']!.effort, 'high', reason: 'the proposed agent lists the proposed effort');

    work.chooseMain('repo', model: 'typed-model', effort: 'low');
    work.chooseMain('repo', agent: 'other-agent');
    expect(work.repositories['repo']!.model, '', reason: 'a model proposed for another agent is not kept');
    expect(work.repositories['repo']!.effort, isNull, reason: 'an effort the agent does not list is dropped');
    work.chooseMain('repo', agent: 'third-agent');
    expect(work.repositories['repo']!.effort, 'high', reason: 'an agent whose efforts are unknown keeps the proposal');
    work.chooseMain('repo', agent: 'codex');
    expect(work.repositories['repo']!.model, 'proposed-model', reason: 'the proposed agent gets the proposed model');
    expect(work.repositories['repo']!.effort, 'high', reason: 'and the proposed effort');

    work.choose('repo', sub, 'CHANGE', model: 'typed-model', effort: 'low');
    work.choose('repo', sub, 'CHANGE', agent: 'other-agent');
    expect(work.choices[('repo', _sub)]!.model, '', reason: 'a changed sub-agent drops the other agent\'s model');
    expect(work.choices[('repo', _sub)]!.effort, isNull, reason: 'and takes the proposal\'s effort, none here');
    work.choose('repo', sub, 'CHANGE', agent: 'codex');
    expect(work.choices[('repo', _sub)]!.model, sub.model, reason: 'the proposed agent gets the proposed model');
  });

  // Prevents a task started with an agent that cannot work on that machine, preselected for the person.
  testWidgets('an offered agent ready there is preselected with its model and the one passed over is named', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    _offer(source, const [
      {'agent': 'codex', 'model': 'proposed-model'},
      {'agent': 'claude', 'model': 'claude-model'},
    ]);
    final work = _work(board, const [PlanningAgent('codex', unready: 'Not signed in here.'), PlanningAgent('claude')]);
    await tester.runAsync(work.load);
    final main = work.repositories['repo']!;
    expect((main.agent, main.model), ('claude', 'claude-model'), reason: 'the first offered agent ready there');
    expect(
      work.passedOver['repo'],
      allOf(contains('codex'), contains('Not signed in here.')),
      reason: 'codex is named as passed over, with the window\'s reason',
    );
    work.chooseMode('AGENT');
    // The helper is proposed on codex too; only the main is preselected, so the helper is dropped here.
    work.choose('repo', work.proposal!.repositories.single.subAgents.single, 'DROP');
    work.setComment('The main builds it alone.');
    expect(await tester.runAsync(work.start), isTrue, reason: 'the preselection starts as it stands');
    final request = source.work.calls.where((call) => call.$1 == 'StartStoryWork').single.$2;
    final sent = ((request['repositories'] as List).single as Map<String, dynamic>)['main'] as Map<String, dynamic>;
    expect((sent['agent'], sent['model']), ('claude', 'claude-model'), reason: 'the start names the agent ready there');
  });

  // Prevents a task started with an agent that cannot work on that machine, preselected for the person.
  testWidgets('with no offered agent ready there none is preselected and the words say so', (tester) async {
    final (source, board) = await _open(tester);
    _offer(source, const [
      {'agent': 'codex', 'model': 'proposed-model'},
      {'agent': 'claude', 'model': 'claude-model'},
    ]);
    final work = _work(board, const [
      PlanningAgent('codex', unready: 'Not signed in here.'),
      PlanningAgent('claude', unready: 'Not installed here.'),
    ]);
    await tester.runAsync(work.load);
    expect(work.repositories['repo']!.agent, isEmpty, reason: 'no agent is preselected');
    expect(
      work.passedOver['repo'],
      allOf(contains('none is preselected'), contains('Not signed in here.'), contains('Not installed here.')),
      reason: 'the words say none is ready, and why for each',
    );
    expect(work.canStart, isFalse, reason: 'nothing starts until the person chooses');
  });

  // Prevents the preselection from overriding an agent the person chose themselves.
  testWidgets('a person\'s own choice of an agent not ready there stays theirs', (tester) async {
    final (source, board) = await _open(tester);
    _offer(source, const [
      {'agent': 'codex', 'model': 'proposed-model'},
      {'agent': 'claude', 'model': 'claude-model'},
    ]);
    final work = _work(board, const [PlanningAgent('codex', unready: 'Not signed in here.'), PlanningAgent('claude')]);
    await tester.runAsync(work.load);
    work.chooseMain('repo', agent: 'codex');
    work.chooseMode('AGENT');
    final main = work.repositories['repo']!;
    expect(
      (main.agent, main.model),
      ('codex', 'proposed-model'),
      reason: 'the person\'s choice, with its offered model',
    );
    expect(work.whoProblem, contains('Not signed in here.'), reason: 'the window\'s reason still stands under it');
    work.chooseMain('repo', agent: 'claude');
    final back = work.repositories['repo']!;
    expect(
      (back.agent, back.model),
      ('claude', 'claude-model'),
      reason: 'another offered agent gets its offered model',
    );
  });

  // Prevents a kept helper's task from starting on an agent the window does not list on that machine.
  testWidgets('a kept helper on an agent the window does not list there is refused', (tester) async {
    final (source, board) = await _open(tester);
    _offer(source, const [
      {'agent': 'codex', 'model': 'proposed-model'},
      {'agent': 'claude', 'model': 'claude-model'},
    ]);
    final work = _work(board, const [PlanningAgent('claude')]);
    await tester.runAsync(work.load);
    work.chooseMode('AGENT');
    expect(work.repositories['repo']!.agent, 'claude', reason: 'the main passes over the agent the window lacks');
    expect(work.choices[('repo', _sub)]!.choice, 'KEEP', reason: 'the helper is kept on codex as proposed');
    expect(work.whoProblem, contains('codex'), reason: 'the Who step names the agent that cannot work there');
    expect(work.canStart, isFalse, reason: 'nor can it start');
    expect(await tester.runAsync(work.start), isFalse, reason: 'a start is refused');
    expect(
      source.work.calls.where((call) => call.$1 == 'StartStoryWork'),
      isEmpty,
      reason: 'nothing reached the planner',
    );
  });

  // Prevents the person from seeing, and going on with, an agent that cannot work there as the one preselected.
  testWidgets('the agent picker shows the preselected agent once the window has listed its agents', (tester) async {
    final (source, board) = await _open(tester, modes: ['UNATTENDED']);
    _offer(source, const [
      {'agent': 'codex', 'model': 'proposed-model'},
      {'agent': 'claude', 'model': 'claude-model'},
    ]);
    final agents = _HeldAgents(const [PlanningAgent('codex', unready: 'Not signed in here.'), PlanningAgent('claude')]);
    final work = planningStoryWork(board, board.stories.single, 'Implementing', agents: agents);
    addTearDown(work.dispose);
    final wizards = _Wizards();
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (each) {
            context = each;
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    unawaited(showStoryWork(context, work, wizards: wizards));
    await _answered(tester);
    expect(work.busy, isTrue, reason: 'the window has not listed its agents yet');
    final who = wizards.steps[2];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListenableBuilder(
            listenable: work,
            builder: (context, _) => ListView(children: [Builder(builder: who.panel)]),
          ),
        ),
      ),
    );
    await settle(tester);
    final early = find.descendant(
      of: find.byKey(const Key('story-work-main-agent repo')),
      matching: find.byType(DropdownButton<String>),
    );
    expect(
      early.evaluate().map((each) => (each.widget as DropdownButton<String>).value),
      isNot(contains('codex')),
      reason: 'while the window still lists its agents, the agent it may pass over is not shown as the choice',
    );
    agents.held.complete();
    await _answered(tester);
    expect(work.repositories['repo']!.agent, 'claude', reason: 'claude is preselected');
    final picker = tester.widget<DropdownButton<String>>(
      find.descendant(
        of: find.byKey(const Key('story-work-main-agent repo')),
        matching: find.byType(DropdownButton<String>),
      ),
    );
    expect(picker.value, 'claude', reason: 'the picker shows the agent preselected, not the one passed over');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents an older planner's proposal, without the offered agents, from losing its agent or being refused.
  testWidgets('a proposal without offered agents keeps its agent as before', (tester) async {
    final (_, board) = await _open(tester);
    final work = _work(board, const [PlanningAgent('codex', unready: 'Not signed in here.'), PlanningAgent('claude')]);
    await tester.runAsync(work.load);
    expect(work.problem, isNull, reason: 'the proposal is read');
    expect(work.repositories['repo']!.agent, 'codex', reason: 'the proposal\'s agent stays preselected');
    expect(work.passedOver, isEmpty, reason: 'nothing is said to be passed over');
  });

  // Prevents a kept sub-agent's task from starting on an agent that cannot work on that machine.
  testWidgets('a kept sub-agent on an agent that cannot work there is refused', (tester) async {
    final (source, board) = await _open(tester);
    final work = _work(board, const [PlanningAgent('codex', unready: 'Not signed in.'), PlanningAgent('ok-agent')]);
    await tester.runAsync(work.load);
    work.chooseMain('repo', agent: 'ok-agent', model: 'a-model');
    expect(work.choices[('repo', _sub)]!.choice, 'KEEP', reason: 'the sub-agent is kept on the proposed agent');
    expect(work.whoProblem, contains('Not signed in.'), reason: 'the Who step says why in the window\'s words');
    expect(work.canStart, isFalse, reason: 'nor can it start');
    expect(await tester.runAsync(work.start), isFalse, reason: 'a start is refused');
    expect(
      source.work.calls.where((call) => call.$1 == 'StartStoryWork'),
      isEmpty,
      reason: 'nothing reached the planner',
    );
  });

  // Harm: a person starts a story's building and is not told which issues stay behind, or why, so a blocked issue
  // looks forgotten; or a held list that repeats an issue or leaves one out stops the Who step drawing.
  testWidgets('the Who step lists each issue a blocker holds back, with its blocker, and none when none is held', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    final proposal = source.work.call('ProposeStoryWork', {'project': 'demo', 'story': 'ST1', 'to': 'Implementing'});
    source.work.proposals['ST1'] = {
      ...proposal,
      'held': [
        {'issue': 'AA2', 'reason': 'AA2 waits on AA1. Work on it starts once that is done.'},
        {'issue': 'AA2', 'reason': 'again'},
        {'reason': 'no issue'},
        {'issue': 7, 'reason': 'not a text'},
      ],
    };
    final work = _work(board, const [PlanningAgent('codex')]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    await nextUntil(tester, find.byKey(const Key('story-work-held')));
    expect(
      tester.widget<Text>(find.byKey(const Key('story-work-held AA2'))).data,
      'AA2: AA2 waits on AA1. Work on it starts once that is done.',
      reason: 'the held issue is named with what it waits on',
    );
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget.key is ValueKey<String> && (widget.key! as ValueKey<String>).value.startsWith('story-work-held '),
      ),
      findsOneWidget,
      reason: 'an issue named twice is listed once, and an entry without an issue not at all',
    );
    await tester.pumpWidget(const SizedBox.shrink());

    source.work.proposals['ST1'] = proposal;
    final plain = _work(board, const [PlanningAgent('codex')]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(plain))));
    await _answered(tester);
    await nextUntil(tester, find.byKey(const Key('story-work-coordinator')));
    expect(find.byKey(const Key('story-work-held')), findsNothing, reason: 'nothing held, no part');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents the Who step from failing to draw when two of its tasks name the same agent.
  testWidgets('the Who step draws two repositories\' tasks on the same agent', (tester) async {
    final source = MockPlanningSource()..boards['demo'] = _twoRepositories();
    final board = PlanningBoard(source, 'demo');
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    addTearDown(() async {
      board.dispose();
      await tester.runAsync(source.end);
    });
    final work = _work(board, const [
      PlanningAgent('codex', efforts: ['low', 'high']),
    ]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    expect(work.proposal!.repositories, hasLength(2), reason: 'both repositories get a task');
    work.chooseMain('repo', agent: 'codex', model: 'a-model');
    work.chooseMain('repo2', agent: 'codex', model: 'a-model');
    await _next(tester);
    expect(tester.takeException(), isNull, reason: 'the step draws both effort choices');
    expect(_title(tester), 'Start work — Who', reason: 'the Who step shows');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents a task from starting with another model than the one the person chose or typed.
  testWidgets('a model chosen from the agent\'s list or typed reaches the start', (tester) async {
    final (source, board) = await _open(tester);
    final work = _work(board, const [
      PlanningAgent(
        'codex',
        models: [
          PlanningModel('model-one', label: 'Model one'),
          PlanningModel('proposed-model'),
        ],
      ),
    ]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    expect(_title(tester), 'Start work — How', reason: 'the first step had nothing to choose');
    await _next(tester);

    await tester.tap(find.byKey(const Key('story-work-main-models repo')));
    await settle(tester);
    expect(find.text('proposed-model (proposed)'), findsOneWidget, reason: 'the proposed model is marked');
    await tester.tap(find.text('Model one'));
    await settle(tester);
    expect(work.repositories['repo']!.model, 'model-one', reason: 'the list fills the model');
    expect(
      tester
          .widget<EditableText>(
            find.descendant(
              of: find.byKey(const Key('story-work-main-model repo')),
              matching: find.byType(EditableText),
            ),
          )
          .controller
          .text,
      'model-one',
      reason: 'the field shows the model chosen',
    );
    await tester.enterText(find.byKey(const Key('story-work-main-model repo')), ' typed-model ');
    await _next(tester);
    await _next(tester);
    expect(find.text('repo: codex · typed-model · effort high'), findsOneWidget, reason: 'the review names it');
    await tester.tap(find.byKey(const Key('story-work-start')));
    await _answered(tester);

    final sent = source.work.calls.where((call) => call.$1 == 'StartStoryWork').single.$2;
    final main = ((sent['repositories'] as List).single as Map)['main'] as Map;
    expect(main['model'], 'typed-model', reason: 'the typed model, as one trimmed line, reaches the start');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents a task from starting with no model, or on an agent that cannot work on that machine.
  testWidgets('the Who step refuses an empty model and an agent that cannot work there', (tester) async {
    final (_, board) = await _open(tester);
    final work = _work(board, const [PlanningAgent('codex'), PlanningAgent('broken', unready: 'Not signed in.')]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    await _next(tester);
    expect(_title(tester), 'Start work — Who');
    expect(_canGoOn(tester), isTrue, reason: 'the proposal goes on');

    await tester.enterText(find.byKey(const Key('story-work-main-model repo')), '  ');
    await settle(tester);
    expect(_canGoOn(tester), isFalse, reason: 'an empty model does not go on');
    expect(_problem(tester), contains('Choose a model'), reason: 'the step says why');

    await tester.tap(find.byKey(const Key('story-work-main-agent repo')));
    await settle(tester);
    expect(find.text('Cannot work there: Not signed in.').hitTestable(), findsOneWidget, reason: 'the list says why');
    await tester.tap(find.text('broken').hitTestable().last);
    await settle(tester);
    await tester.enterText(find.byKey(const Key('story-work-main-model repo')), 'a-model');
    await settle(tester);
    expect(_canGoOn(tester), isFalse, reason: 'an agent that cannot work there does not go on');
    expect(_problem(tester), contains('Not signed in.'), reason: 'the step says why in the window\'s words');
    expect(work.canStart, isFalse, reason: 'nor can it start');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // Prevents a start the person did not see in full, or one that skips the comment a later change requires.
  testWidgets('the review lists every choice and a change that needs a comment stops at the Reading step', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    final work = _work(board, const [PlanningAgent('codex')]);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkWizard(work))));
    await _answered(tester);
    await _next(tester);
    await _next(tester);
    await _next(tester);
    expect(_title(tester), 'Start work — Review');
    for (final line in [
      'Working state: Implementing',
      'Unattended',
      'repo: codex · proposed-model · effort high',
      'Most reading rounds: 2',
      'No comment',
      'Start the project coordinator.',
    ]) {
      expect(find.text(line), findsOneWidget, reason: 'the review lists $line');
    }
    expect(find.textContaining('Runs on '), findsNothing, reason: 'one machine is no choice and is not shown');
    expect(find.textContaining('repo helper: keep codex'), findsOneWidget, reason: 'the review names the sub-agent');
    expect(find.byKey(const Key('story-work-change 0')), findsNothing, reason: 'nothing to change where');

    await tester.tap(find.byKey(const Key('story-work-change 2')));
    await settle(tester);
    expect(_title(tester), 'Start work — Who');
    await tester.ensureVisible(find.byKey(const Key('story-work-choice repo $_sub DROP')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('story-work-choice repo $_sub DROP')));
    await settle(tester);
    await _next(tester);
    expect(_title(tester), 'Start work — Reading', reason: 'the change goes through the later steps again');
    expect(_canGoOn(tester), isFalse, reason: 'a dropped sub-agent needs a comment');
    expect(_problem(tester), contains('comment'), reason: 'the step says why');
    await tester.enterText(find.byKey(const Key('story-work-comment')), 'Built by hand.');
    await settle(tester);
    await _next(tester);
    expect(find.text('repo helper: drop'), findsOneWidget, reason: 'the review names the drop');
    expect(find.text('Comment: Built by hand.'), findsOneWidget, reason: 'and the comment');
    expect(source.work.calls.where((call) => call.$1 == 'StartStoryWork'), isEmpty, reason: 'nothing started yet');
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
