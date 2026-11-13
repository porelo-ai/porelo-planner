import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';
import 'support/story_work_support.dart';

Future<(MockPlanningSource, PlanningBoards)> _showBoard(
  WidgetTester tester, {
  bool twoProjects = false,
  MockPlanningSource? otherProvider,
  MockPlanningSource? source,
  double above = 0,
  ScrollController? primary,
}) async {
  tester.view
    ..physicalSize = const Size(1280, 800)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = source ?? MockPlanningSource();
  backend.boards['checkout'] = {
    'states': [
      {'name': 'Ready', 'kind': 'WAITING'},
      {'name': 'Building', 'kind': 'WORKING'},
      {'name': 'Done', 'kind': 'CLOSED'},
      {'name': 'Review', 'kind': 'WAITING'},
    ],
    'phases': [
      {
        'name': 'Work',
        'states': ['Ready', 'Building', 'Done', 'Review'],
      },
    ],
    'stories': [
      {'id': 'StoryOne', 'title': 'A story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
    ],
    'revision': 'initial',
  };
  if (twoProjects) {
    backend.boards['other'] = {
      ...backend.boards['checkout']!,
      'stories': [
        {'id': 'StoryOne', 'title': 'Second project story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
      ],
    };
  }
  final client = _Providers([
    PlanningProvider(name: 'local', connected: true, projects: ['checkout', if (twoProjects) 'other'], source: backend),
  ]);
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(height: above),
            Expanded(
              child: primary == null
                  ? planningView(boards: boards)
                  : PrimaryScrollController(
                      controller: primary,
                      child: planningView(boards: boards),
                    ),
            ),
          ],
        ),
      ),
    ),
  );
  await settle(tester);
  return (backend, boards);
}

/// Providers a test adds and chooses among, as the window's machines are.
class _Providers extends MockPlanningClient {
  _Providers(List<PlanningProvider> listed) : this._(listed, _Choice());
  _Providers._(this.listed, this.choice) : super(() => listed, currentProvider: () => choice.name);
  final List<PlanningProvider> listed;
  final _Choice choice;

  /// Chooses the provider named [name].
  void choose(String name) {
    choice.name = name;
    changed();
  }
}

class _Choice {
  String name = 'local';
}

/// Drags [id]'s card onto the column of [state] and lets it go.
Future<void> _drop(WidgetTester tester, String id, String state) async {
  final source = find.byKey(Key('planning-card $id'));
  final target = find.byKey(Key('planning-state $state'));
  final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
  await drag.moveBy(const Offset(0, -20));
  await tester.pump();
  await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text(state))));
  await tester.pump();
  await drag.up();
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

/// Gives StoryOne an issue of [repository], waiting in Ready, on a board whose own repository is `own`.
void _withIssueOf(MockPlanningSource backend, String repository) {
  final data = backend.boards['checkout']!;
  data['repositories'] = ['own'];
  (data['stories'] as List<dynamic>).first['issues'] = [
    {'id': 'OT1', 'repository': repository, 'title': 'An issue', 'state': 'Ready'},
  ];
  backend.push('checkout', data);
}

/// A source whose preview of any move warns of running work, issues carried along and a story that will not fit
/// them, two issues each, under the digest the save checks.
class _ThreeWarnings extends MockPlanningSource {
  @override
  Future<PlanningMovePreview> previewMoves(String project, List<PlanningMove> moves, String revision) async {
    final preview = previewPlanningMove(board(project), {
      'moves': moves.map((move) => move.toMap()).toList(),
      'revision': revision,
    });
    Map<String, dynamic> issue(String id) => {'id': id, 'repository': 'other', 'from': 'Ready', 'to': 'Review'};
    Map<String, dynamic> warning(String kind, {List<String> tasks = const []}) => {
      'kind': kind,
      'item': 'StoryOne',
      'from': 'Ready',
      'to': 'Review',
      'tasks': tasks,
      'issues': [issue('OT1'), issue('OT2')],
    };
    return PlanningMovePreview.from({
      ...preview,
      'warnings': [
        warning(PlanningMoveWarning.runningWork, tasks: ['reader-1', 'worker-2']),
        warning(PlanningMoveWarning.issuesAlong),
        warning(PlanningMoveWarning.storyMismatch),
      ],
    });
  }
}

/// The height the window's own panes take above the screen, which leaves the board less than the view.
const double _windowPanes = 300;

/// The last row of the warnings, below every other.
final _lastWarning = find.byKey(const Key('planning-move-warning-issue STORY_MISMATCH OT2'));

/// Moves StoryOne to Review as a drop on the board does, since a short board shows Review only after a scroll.
Future<void> _move(WidgetTester tester, PlanningBoards boards) async {
  unawaited(boards.selected!.move('StoryOne', 'Review'));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

/// Fails unless [key]'s widget lies wholly inside the test view.
void _expectInView(WidgetTester tester, String key) {
  final view = Offset.zero & tester.view.physicalSize / tester.view.devicePixelRatio;
  final rect = tester.getRect(find.byKey(Key(key)));
  expect(view.intersect(rect), rect, reason: '$key lies inside the view');
}

/// Shows the three warnings below [above] of the window's panes and [problems] lines above the board, and fails
/// unless Cancel and then Confirm lie in the view and do what they say.
Future<void> _confirmAndCancelInView(WidgetTester tester, {required double above, int problems = 0}) async {
  final (backend, boards) = await _showBoard(tester, source: _ThreeWarnings(), above: above);
  for (var line = 1; line <= problems; line++) {
    boards.selected!.workProblems['Story$line'] = 'the planner could not report its work';
  }

  await _move(tester, boards);

  expect(tester.takeException(), isNull, reason: 'nothing overflows');
  expect(find.textContaining('Story$problems: the planner'), problems == 0 ? findsNothing : findsOneWidget);
  expect(_lastWarning, findsOneWidget);
  final scrollable = find.ancestor(of: _lastWarning, matching: find.byType(Scrollable)).first;
  final viewport = tester.getRect(scrollable);
  final first = tester.getRect(find.byKey(const Key('planning-move-warning-item RUNNING_WORK StoryOne')));
  expect(viewport.intersect(first), first, reason: 'the first warning is in view once the move is asked about');
  expect(
    tester.getSize(find.byKey(const Key('planning-board-sideways'))).height,
    greaterThan(0),
    reason: 'the board keeps room',
  );
  _expectInView(tester, 'planning-move-cancel');
  _expectInView(tester, 'planning-move-confirm');
  await tester.tap(find.byKey(const Key('planning-move-cancel')));
  await settle(tester);
  expect(find.byKey(const Key('planning-move-warning')), findsNothing, reason: 'Cancel closes the question');
  expect(backend.moveCalls, isEmpty, reason: 'Cancel sends nothing');

  await _move(tester, boards);
  expect(tester.takeException(), isNull, reason: 'nothing overflows');
  _expectInView(tester, 'planning-move-confirm');
  await tester.tap(find.byKey(const Key('planning-move-confirm')));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
  expect(backend.moveCalls, hasLength(1), reason: 'Confirm saves the move once');
  expect(find.byKey(const Key('planning-move-warning')), findsNothing);
}

void main() {
  // Harm: a person cannot confirm or cancel a move over running work, since the warning pushes its buttons out of
  // the window.
  testWidgets('a move warning with every list shown keeps Confirm and Cancel in a 1280x800 window', (tester) async {
    await _confirmAndCancelInView(tester, above: _windowPanes);
  });

  // Harm: on a short board with problem lines above it, a person cannot confirm or cancel a move over running work.
  testWidgets('a move warning under five problem lines keeps Confirm and Cancel in a short window', (tester) async {
    await _confirmAndCancelInView(tester, above: 400, problems: 5);
  });

  // Harm: a person confirms a move over running work without being able to read the warnings the cap holds back.
  testWidgets('warnings taller than their room scroll, with a scrollbar, to the last one', (tester) async {
    final (_, boards) = await _showBoard(tester, source: _ThreeWarnings(), above: _windowPanes);
    await _move(tester, boards);

    final scrollable = find.ancestor(of: _lastWarning, matching: find.byType(Scrollable)).first;
    final position = tester.state<ScrollableState>(scrollable).position;
    expect(position.maxScrollExtent, greaterThan(0), reason: 'the warnings are capped below their height');
    expect(
      find.ancestor(
        of: scrollable,
        matching: find.byWidgetPredicate((w) => w is Scrollbar && w.thumbVisibility == true),
      ),
      findsOneWidget,
      reason: 'a scrollbar shows that more lies below',
    );
    await tester.scrollUntilVisible(_lastWarning, 40, scrollable: scrollable);
    final viewport = tester.getRect(scrollable);
    final last = tester.getRect(_lastWarning);
    expect(viewport.intersect(last), last, reason: 'scrolling brings the last warning into view');
  });

  // Harm: the warnings take the window's primary scroll controller, so the window's own scrolling moves them or fails.
  testWidgets('the warnings do not take the window\'s primary scroll controller', (tester) async {
    final primary = ScrollController();
    addTearDown(primary.dispose);
    final (_, boards) = await _showBoard(tester, source: _ThreeWarnings(), above: _windowPanes, primary: primary);
    await _move(tester, boards);

    expect(_lastWarning, findsOneWidget);
    expect(primary.hasClients, isFalse, reason: "the window's controller drives none of the screen's scrolling");
  });

  // Harm: a drop that carries an issue of another repository along is saved unasked, or Cancel still saves it.
  testWidgets('a drop that carries another repository\'s issue along asks first and Cancel saves nothing', (
    tester,
  ) async {
    final (backend, boards) = await _showBoard(tester);
    _withIssueOf(backend, 'other');
    await settle(tester);

    await _drop(tester, 'StoryOne', 'Review');

    expect(find.byKey(const Key('planning-move-warning')), findsOneWidget, reason: 'the move is asked about');
    expect(
      find.byKey(const Key('planning-move-warning-issue ISSUES_ALONG OT1')),
      findsOneWidget,
      reason: 'the issue carried along is named',
    );
    expect(backend.moveCalls, isEmpty, reason: 'nothing is saved before Confirm');
    await tester.tap(find.byKey(const Key('planning-move-cancel')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);
    expect(find.byKey(const Key('planning-move-warning')), findsNothing);
    expect(backend.moveCalls, isEmpty, reason: 'Cancel sends nothing');
    expect(boards.selected!.pending, isEmpty);
    expect(
      find.descendant(
        of: find.byKey(const Key('planning-state Ready')),
        matching: find.byKey(const Key('planning-card StoryOne')),
      ),
      findsOneWidget,
      reason: 'the card stands where it stood',
    );
  });

  // Harm: a confirmed move is saved twice, or under another preview than the one the person confirmed.
  testWidgets('a drop confirmed over its warning is saved once with the digest of what was shown', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    _withIssueOf(backend, 'other');
    await settle(tester);
    await _drop(tester, 'StoryOne', 'Review');
    final shown = boards.selected!.question!.preview;

    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);

    expect(backend.moveCalls, hasLength(1), reason: 'saved once');
    expect(backend.moveCalls.single['moves'], [
      {'story': 'StoryOne', 'to': 'Review'},
    ], reason: 'the move the person was shown');
    expect(backend.moveCalls.single['digest'], shown.digest, reason: 'under the digest of the preview shown');
    expect(find.byKey(const Key('planning-move-warning')), findsNothing);
  });

  // Harm: a reader or reworker the window is starting, which the planner cannot report yet, keeps working on a story
  // moved unasked.
  testWidgets('a drop while a follow-up task is being started asks first and saves nothing', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    _withIssueOf(backend, 'own');
    await settle(tester);
    final board = boards.selected!;
    final run = board.runOf('StoryOne');
    final starting = Completer<PlanningTaskStarted>();
    run.startTask = (source, project, story, order) => starting.future;
    final following = run.follow([PlanningTaskOrder.from(servedFollowUp(1))], run.epoch);
    await tester.pump();
    expect(run.starting, isTrue, reason: 'the start is in flight');

    await _drop(tester, 'StoryOne', 'Review');

    expect(
      find.byKey(const Key('planning-move-warning-item RUNNING_WORK StoryOne')),
      findsOneWidget,
      reason: 'the start the planner does not report yet is named',
    );
    expect(backend.moveCalls, isEmpty, reason: 'nothing is saved before Confirm');
    await tester.tap(find.byKey(const Key('planning-move-cancel')));
    await settle(tester);
    starting.completeError(const PlanningRefusal('StartFailed'));
    await tester.runAsync(() => following.catchError((Object _) {}));
    expect(run.starting, isFalse, reason: 'a failed start holds nothing');
  });

  // Harm: a card dragged while another window finished its story reopens the finished work, previewed against the
  // board that arrived during the drag and saved without a question.
  testWidgets('a drop after the board changed during the drag moves nothing', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    _withIssueOf(backend, 'own');
    await settle(tester);
    final source = find.byKey(const Key('planning-card StoryOne'));
    final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
    await drag.moveBy(const Offset(0, -20));
    await tester.pump();
    final data = jsonDecode(jsonEncode(backend.boards['checkout'])) as Map<String, dynamic>;
    data['revision'] = 'elsewhere';
    final story = (data['stories'] as List<dynamic>).first as Map<String, dynamic>;
    story['state'] = 'Done';
    ((story['issues'] as List<dynamic>).first as Map<String, dynamic>)['state'] = 'Done';
    backend.push('checkout', data);
    await tester.pump();
    expect(boards.selected!.snapshot!.revision, 'elsewhere', reason: 'the board changed during the drag');

    final target = find.byKey(const Key('planning-state Review'));
    await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text('Review'))));
    await tester.pump();
    await drag.up();
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);

    expect(backend.previewCalls.single['revision'], 'initial', reason: 'previewed against the board the drag began on');
    expect(backend.moveCalls, isEmpty, reason: 'nothing was moved');
    expect(boards.selected!.moveProblem, contains('nothing was moved'));
  });

  // Harm: a move that can do no harm still waits for a Save or a question, and is lost when the person leaves.
  testWidgets('a drop that asks nothing is saved at once, once', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    _withIssueOf(backend, 'own');
    await settle(tester);

    await _drop(tester, 'StoryOne', 'Review');

    expect(find.byKey(const Key('planning-move-warning')), findsNothing, reason: 'nothing to ask');
    expect(backend.moveCalls, hasLength(1), reason: 'saved on the drop');
    expect(backend.moveCalls.single['moves'], [
      {'story': 'StoryOne', 'to': 'Review'},
    ]);
    expect(boards.selected!.pending, isEmpty, reason: 'the save was answered');
  });
  // Harm: a WORKING drop starts without the shell's request or silently does nothing.
  testWidgets('an unfolded working drop opens the shipped start dialog', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final source = find.byKey(const Key('planning-card StoryOne'));
    final target = find.byKey(const Key('planning-state Building'));
    final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
    await drag.moveBy(const Offset(0, -20));
    await tester.pump();
    await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text('Building'))));
    await tester.pump();
    await drag.up();
    await settle(tester);
    expect(find.byKey(const Key('story-work-dialog')), findsOneWidget);
    await tester.tap(find.byKey(const Key('story-work-cancel')));
    await settle(tester);
    expect(find.descendant(of: find.byKey(const Key('planning-state Ready')), matching: source), findsOneWidget);
    expect(find.byKey(const Key('planning-move-error')), findsNothing);
    expect(backend.moveCalls, isEmpty);
    expect(boards.selected!.pending, isEmpty);
  });

  // Harm: Reload planning shows the board the planner last polled, which the repositories may no longer hold, and
  // the person moves stories on it.
  testWidgets('Reload planning has the planner fetch the repositories and says when it cannot', (tester) async {
    final (backend, _) = await _showBoard(tester);
    expect(backend.refreshes, isEmpty, reason: 'nothing asked for a fetch yet');
    await tester.tap(find.byKey(const Key('planning-reload')));
    await settle(tester);
    expect(backend.refreshes, ['checkout'], reason: 'Reload planning asks the planner to fetch the project');
    backend.failure = mockRefusal('org.fuin.porelo.Planner.Unreachable', {'detail': 'The remote cannot be reached.'});
    await tester.tap(find.byKey(const Key('planning-reload')));
    await settle(tester);
    expect(backend.refreshes, ['checkout'], reason: 'the refused fetch is not counted as done');
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-move-error'))).data,
      'The remote cannot be reached.',
      reason: 'a refused fetch is said, not taken for a fresh board',
    );
  });

  // Harm: dropping a card back on its own state requests another start or offers a misleading drop.
  testWidgets('a card over its current waiting or working state has no hint or start request', (tester) async {
    final (backend, _) = await _showBoard(tester);
    for (final state in ['Ready', 'Building']) {
      final data = backend.boards['checkout']!;
      (data['stories'] as List<dynamic>).first['state'] = state;
      backend.push('checkout', data);
      await settle(tester);
      final source = find.byKey(const Key('planning-card StoryOne'));
      final target = find.byKey(Key('planning-state $state'));
      final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
      await drag.moveBy(const Offset(0, -20));
      await tester.pump();
      await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text(state))));
      await tester.pump();
      expect(find.text('Drop here for $state'), findsNothing);
      await drag.up();
      await settle(tester);
      expect(find.byKey(const Key('story-work-dialog')), findsNothing);
      expect(find.descendant(of: target, matching: source), findsOneWidget);
      expect(backend.moveCalls, isEmpty, reason: 'nothing was moved');
    }
  });

  // Harm: a project switch while a move's save is unanswered leaves without asking, or Stay leaves the selector
  // showing another project.
  testWidgets('a project is switched over an unanswered save only after confirming, and Stay restores the selector', (
    tester,
  ) async {
    final (backend, boards) = await _showBoard(tester, twoProjects: true);
    backend.moveFailure = mockRefusal('TimedOut', const {});
    await tester.runAsync(() => boards.selected!.move('StoryOne', 'Done'));
    await settle(tester);
    expect(boards.selected!.pending['StoryOne']?.unanswered, isTrue, reason: 'the planner did not answer the save');
    final selector = find.byKey(const Key('planning-project'));
    Future<void> select(String name) async {
      await tester.tap(selector);
      await settle(tester);
      await tester.tap(find.text(name).last);
      await settle(tester);
    }

    await select('other');
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-stay')));
    await settle(tester);
    expect(find.descendant(of: selector, matching: find.text('checkout')), findsOneWidget);
    expect(find.descendant(of: selector, matching: find.text('other')), findsNothing);
    await select('other');
    await tester.tap(find.byKey(const Key('planning-leave')));
    await settle(tester);
    expect(find.text('Second project story'), findsOneWidget);
    expect(find.descendant(of: selector, matching: find.text('other')), findsOneWidget);
    expect(backend.moveCalls, hasLength(1), reason: 'the move was sent once and not again');
  });

  // Harm: switching machines hides a save still out, or chooses another provider with one over the preferred one.
  testWidgets('a provider with a save out stays shown, and the current one wins when both have one', (tester) async {
    final other = MockPlanningSource();
    addTearDown(other.end);
    final (backend, boards) = await _showBoard(tester, otherProvider: other);
    other.boards['checkout'] = {
      ...backend.boards['checkout']!,
      'stories': [
        {'id': 'StoryOne', 'title': 'Other provider story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
      ],
    };
    final client = boards.client as _Providers;
    final localBoard = boards.selected!;
    client.listed.add(PlanningProvider(name: 'other-provider', connected: true, projects: ['checkout'], source: other));
    client.choose('other-provider');
    await settle(tester);
    final otherBoard = boards.selected!;
    expect(find.text('Other provider story'), findsOneWidget);
    backend.moveGate = Completer<void>();
    other.moveGate = Completer<void>();
    unawaited(localBoard.move('StoryOne', 'Review'));
    await settle(tester);
    expect(find.text('A story'), findsOneWidget);
    expect(find.text('Other provider story'), findsNothing);
    expect(localBoard.pending.keys, ['StoryOne']);
    unawaited(otherBoard.move('StoryOne', 'Review'));
    await settle(tester);
    expect(find.text('Other provider story'), findsOneWidget);
    expect(find.text('A story'), findsNothing);
    expect(otherBoard.pending.keys, ['StoryOne']);
    client.choose('local');
    await settle(tester);
    expect(find.text('A story'), findsOneWidget);
    expect(find.text('Other provider story'), findsNothing);
    backend.moveGate!.complete();
    other.moveGate!.complete();
    await settle(tester);
  });

  // Harm: a leave question opened during a save stays blocked after the machine answers.
  testWidgets('a leave question updates when its in-flight save finishes', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final board = boards.selected!;
    backend.moveGate = Completer<void>();
    final saved = board.move('StoryOne', 'Done');
    await tester.pump();
    final leaving = confirmPlanningLeave(tester.element(find.byType(PlanningScreen)), [board]);
    await settle(tester);
    expect(find.text('Planning is saving'), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsNothing);
    backend.moveGate!.complete();
    await saved;
    await settle(tester);
    expect(find.text('Planning saved'), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-leave')));
    await settle(tester);
    expect(await leaving, isTrue);
    expect(find.byType(AlertDialog), findsNothing);
  });

  // Harm: simultaneous leave requests stack duplicate questions and resume two actions.
  testWidgets('a second leave request keeps exactly one question open', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final board = boards.selected!;
    backend.moveFailure = mockRefusal('TimedOut', const {});
    await tester.runAsync(() => board.move('StoryOne', 'Done'));
    await settle(tester);
    final context = tester.element(find.byType(PlanningScreen));
    final first = confirmPlanningLeave(context, [board]);
    await settle(tester);
    final second = await confirmPlanningLeave(context, [board], quitting: true);
    expect(second, isFalse);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.byKey(const Key('planning-stay')), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-stay')));
    await settle(tester);
    expect(await first, isFalse);
  });

  // Harm: an issue searched for by its number lies in a story far down its column, unseen, and is taken for missing.
  testWidgets('a search for an issue opens its story, marks the issue and brings the card into view', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final data = backend.boards['checkout']!;
    data['stories'] = [
      for (var number = 1; number <= 30; number++)
        {
          'id': 'Story$number',
          // Every story matches the search by its own words, so the search alone does not move the card up.
          'title': 'Story number $number after ab42',
          'state': 'Ready',
          'priority': 'High',
          'issues': [
            if (number == 30) {'id': 'AB42', 'repository': 'planner', 'title': 'The far issue', 'state': 'Ready'},
          ],
        },
    ];
    backend.push('checkout', data);
    await settle(tester);
    final view = tester.getRect(find.byKey(const Key('planning-board-scroll')));
    final card = find.byKey(const Key('planning-card Story30'));
    expect(view.overlaps(tester.getRect(card)), isFalse, reason: 'the card starts below the visible board');
    await tester.enterText(find.byKey(const Key('planning-search')), 'ab42');
    await settle(tester);
    expect(find.byKey(const Key('planning-issue-found AB42')), findsOneWidget, reason: 'the found issue is marked');
    final shown = tester.getRect(card);
    expect(
      shown.top >= view.top && shown.bottom <= view.bottom,
      isTrue,
      reason: 'the card $shown is brought inside the visible board $view',
    );
    expect(boards.selected!.preferences.expandedStories, isEmpty, reason: 'the person opened no issue list');

    // Harm: a person who scrolled away while typing is pulled back on every key.
    tester
        .state<ScrollableState>(
          find.descendant(of: find.byKey(const Key('planning-board-scroll')), matching: find.byType(Scrollable)).first,
        )
        .position
        .jumpTo(0);
    await settle(tester);
    expect(view.overlaps(tester.getRect(card)), isFalse, reason: 'the person scrolled the card out of view again');
    await tester.enterText(find.byKey(const Key('planning-search')), 'ab42 ');
    await settle(tester);
    expect(
      view.overlaps(tester.getRect(card)),
      isFalse,
      reason: 'a search that finds the same first card does not scroll again',
    );
  });
}
