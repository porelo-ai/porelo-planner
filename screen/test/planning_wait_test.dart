import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

class _SilentPlanning extends MockPlanningSource {
  final waiting = StreamController<PlanningSnapshot>();
  @override
  Stream<PlanningSnapshot> watchStories(String project) => waiting.stream;
}

/// A planning whose watch tells its first read through [load], as the planner's event stream does.
class _ReadingPlanning extends _SilentPlanning {
  final load = ValueNotifier<PlanningLoad?>(null);
  @override
  Stream<PlanningSnapshot> watchStories(String project) =>
      PlanningWatch(waiting.stream, ValueNotifier(false), loading: load);
}

/// A planning whose Reload is answered when the test lets it, and whose watch tells the read behind it through [load].
class _HeldReload extends MockPlanningSource {
  final read = Completer<void>();
  final load = ValueNotifier<PlanningLoad?>(null);
  final failed = ValueNotifier<PlanningRefusal?>(null);
  @override
  Future<String?> refreshProject(String project) async {
    await read.future;
    return super.refreshProject(project);
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) => PlanningWatch(
    super.watchStories(project),
    ValueNotifier(false),
    loading: load,
    tellsReads: true,
    readFailed: failed,
  );
}

/// A planning whose Reload is refused with [refusal], and whose watch tells reads through [load].
class _RefusedReload extends MockPlanningSource {
  _RefusedReload(this.refusal);
  final PlanningRefusal refusal;
  final load = ValueNotifier<PlanningLoad?>(null);
  @override
  Future<String> refreshProject(String project) async => throw refusal;

  @override
  Stream<PlanningSnapshot> watchStories(String project) =>
      PlanningWatch(super.watchStories(project), ValueNotifier(false), loading: load, tellsReads: true);
}

void main() {
  // Harm: a busy planner leaves an empty story page even though its board already contains the story and issues.
  testWidgets('a story shows its board row until its latest details arrive during a save', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 2000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = MockPlanningSource();
    final client = MockPlanningClient.of(backend, projects: ['demo']);
    final boards = PlanningBoards(client);
    final host = TestHost();
    final pending = Completer<PlanStoryDetail>();
    final saved = Completer<void>();
    addTearDown(() async {
      if (!saved.isCompleted) saved.complete();
      boards.dispose();
      client.dispose();
      host.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: planningView(boards: boards, host: host),
        ),
      ),
    );
    await settle(tester);
    final board = boards.selected!;
    final row = board.stories.firstWhere((story) => story.issues.isNotEmpty);
    final moved = row.issues.first.id;
    backend.readStory = (_, _) => pending.future;
    host.go(PlanningPlace(project: board.project, source: boards.sourceOf(board), story: row.id));
    await settle(tester);

    final page = find.byType(PlanningStoryView);
    expect(
      find.descendant(of: page, matching: find.text(row.title)),
      findsOneWidget,
      reason: 'the story title is readable before the planner answers',
    );
    expect(
      find.descendant(of: page, matching: find.text(row.state)),
      findsWidgets,
      reason: 'the story state is already known from the board',
    );
    for (final issue in row.issues) {
      expect(
        find.byKey(Key('planning-issue ${issue.id}')),
        findsOneWidget,
        reason: 'every issue in the board row is readable while details wait',
      );
    }
    final stateMenu = tester.widget<InkWell>(find.byKey(Key('planning-move $moved')));
    expect(stateMenu.onTap, isNull, reason: 'a board row alone cannot authorize a detail-page issue move');

    backend.moveHeld = saved;
    final saving = board.moveIssue(moved, 'Review');
    await settle(tester);
    expect(board.saving, isTrue, reason: 'the window still has a save in progress');
    expect(
      find.text('A change from this window is still being saved.'),
      findsOneWidget,
      reason: 'the story explains why the local change is still pending',
    );
    expect(
      find.descendant(of: page, matching: find.text(row.title)),
      findsOneWidget,
      reason: 'starting a save must not erase the readable fallback',
    );

    pending.complete(
      PlanStoryDetail.from({
        'story': {...mockPlanningStory(backend.boards['demo']!, row.id)!, 'title': 'Latest standalone title'},
      }),
    );
    await settle(tester);
    expect(
      find.descendant(of: page, matching: find.text('Latest standalone title')),
      findsOneWidget,
      reason: 'the standalone read replaces the board row when it arrives',
    );
    expect(
      find.descendant(of: page, matching: find.text(row.title)),
      findsNothing,
      reason: 'the fallback cannot mask newer details',
    );
    saved.complete();
    await tester.runAsync(() => saving);
    await settle(tester);
  });

  // Harm: a silent watch leaves an endless animation, preventing a waiting page or its test from settling.
  testWidgets('a planning page waiting for its first answer has no endless animation', (tester) async {
    final backend = _SilentPlanning();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.waiting.close();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    final waiting = tester.widget<Text>(find.byKey(const Key('planning-waiting'))).data!.toLowerCase();
    expect(waiting, contains('planner'));
    expect(waiting, isNot(contains('machine')));
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
  });

  // Harm: a long first read looks like an odd message or a hang, with nothing to tell how far it is.
  testWidgets('a first read the planner tells is a bar with its share done and one plain sentence', (tester) async {
    final backend = _ReadingPlanning();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.waiting.close();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    final since = DateTime.utc(2026, 11, 21, 15);

    backend.load.value = PlanningLoad(since: since, place: 'GitHub', lastRead: 'porelo-project#200');
    await tester.pump();
    expect(tester.widget<Text>(find.byKey(const Key('planning-waiting'))).data, 'Loading the planning from GitHub…');
    expect(
      tester.widget<LinearProgressIndicator>(find.byKey(const Key('planning-load-bar'))).value,
      isNull,
      reason: 'a bar that moves until the planner knows how many entries there are',
    );

    backend.load.value = PlanningLoad(
      since: since,
      place: 'GitHub',
      read: 600,
      total: 1384,
      lastRead: 'porelo-project#200',
    );
    await tester.pump();
    final words = tester.widget<Text>(find.byKey(const Key('planning-waiting'))).data!;
    expect(words, 'Loading the planning from GitHub: 600 of 1,384 entries');
    expect(words, isNot(contains('#')), reason: 'no internal reference');
    expect(
      tester.widget<LinearProgressIndicator>(find.byKey(const Key('planning-load-bar'))).value,
      closeTo(600 / 1384, 1e-9),
    );
    expect(find.byKey(const Key('planning-loading')), findsNothing, reason: 'the bar takes the still mark\'s place');
  });

  // Harm: a Reload's long read only greys its button, so the page looks stuck, or it holds the person's changes.
  testWidgets('a Reload shows the read over the board, and its button comes back when the read ends', (tester) async {
    final backend = _HeldReload();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    expect(find.byKey(const Key('planning-load-bar')), findsNothing);

    await tester.tap(find.byKey(const Key('planning-reload')));
    await tester.pump();
    expect(find.byKey(const Key('planning-load-bar')), findsNothing, reason: 'a read of a moment shows nothing');
    await tester.pump(const Duration(seconds: 1));

    expect(find.byKey(const Key('planning-load-bar')), findsOneWidget, reason: 'a longer read shows over the board');
    expect(tester.widget<Text>(find.byKey(const Key('planning-waiting'))).data, 'Loading the planning…');
    final board = boards.sources.single;
    expect(board.fetching, isTrue);
    expect(board.saving, isFalse, reason: 'a change made during the read is saved at once');
    expect(tester.widget<TextButton>(find.byKey(const Key('planning-reload'))).onPressed, isNull);

    // The planner answers at once and reads behind its answer, telling the read on the watch a moment later.
    backend.read.complete();
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('planning-load-bar')), findsOneWidget, reason: 'the bar stands until the watch tells');

    backend.load.value = PlanningLoad(since: DateTime.utc(2026, 11, 21, 15), place: 'GitHub', read: 300, total: 1384);
    await tester.pump();
    expect(board.fetching, isFalse, reason: 'the watch tells the read from here');
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-waiting'))).data,
      'Loading the planning from GitHub: 300 of 1,384 entries',
    );
    expect(tester.widget<TextButton>(find.byKey(const Key('planning-reload'))).onPressed, isNull);

    // The read changed nothing: the planner says it is done, without a board.
    backend.load.value = null;
    await settle(tester);

    expect(find.byKey(const Key('planning-load-bar')), findsNothing);
    expect(tester.widget<TextButton>(find.byKey(const Key('planning-reload'))).onPressed, isNotNull);
  });

  // Harm: a planner that tells nothing behind its answer leaves the bar running for good.
  testWidgets('a Reload whose read the watch never tells ends its bar after a while', (tester) async {
    final backend = _HeldReload();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-reload')));
    backend.read.complete();
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.byKey(const Key('planning-load-bar')), findsOneWidget);

    await tester.pump(PlanningBoard.fetchTold);
    await settle(tester);

    expect(find.byKey(const Key('planning-load-bar')), findsNothing);
    expect(tester.widget<TextButton>(find.byKey(const Key('planning-reload'))).onPressed, isNotNull);
  });

  // Harm: a background read that failed is not said, so the person trusts a board the planner could not refresh.
  testWidgets('a read that failed over a shown board is said above it, and the board stays', (tester) async {
    final backend = _HeldReload();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);

    backend.failed.value = const PlanningRefusal('Unreachable', {}, 'GitHub answered 502.');
    await settle(tester);

    final words = tester.widget<Text>(find.byKey(const Key('planning-read-error'))).data!;
    expect(words, contains('could not read the board again'));
    expect(words, contains('GitHub answered 502.'));
    expect(find.byKey(const Key('planning-reload')), findsOneWidget, reason: 'the board stands, with its Reload');

    backend.failed.value = null;
    await settle(tester);
    expect(find.byKey(const Key('planning-read-error')), findsNothing);
  });

  // Harm: a Reload that timed out while its read goes on stays as a failure beside that read's progress, and the
  // person takes a board that is loading for one that cannot be read.
  for (final name in ['TimedOut', 'NoForgeAccess']) {
    testWidgets('a $name Reload is taken back at the read\'s next progress', (tester) async {
      final backend = _RefusedReload(PlanningRefusal(name, const {}, 'the planner did not answer within 30 s'));
      final client = MockPlanningClient.of(backend, projects: ['checkout']);
      final boards = PlanningBoards(client);
      addTearDown(() async {
        boards.dispose();
        client.dispose();
        await backend.end();
      });
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: planningView(boards: boards)),
        ),
      );
      await settle(tester);
      final board = boards.sources.single;
      await tester.tap(find.byKey(const Key('planning-reload')));
      await settle(tester);
      expect(board.refusal?.name, name, reason: 'the Reload\'s refusal is said first');
      expect(board.moveProblem, isNotNull);

      backend.load.value = PlanningLoad(since: DateTime.utc(2026, 11, 23, 5), place: 'GitHub', read: 750, total: 1548);
      await tester.pump();

      expect(board.refusal, isNull, reason: 'a read that progresses proves the planner answers and reads GitHub');
      expect(board.moveProblem, isNull, reason: 'no failure line beside the progress');
    });
  }

  // Harm: a refusal the progress of a read does not undo, such as a project the planner no longer names, is hidden.
  testWidgets('a Reload refused for another cause stays through the read\'s progress', (tester) async {
    final backend = _RefusedReload(const PlanningRefusal('NoSuchProject', {}, 'No project checkout.'));
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    final board = boards.sources.single;
    await tester.tap(find.byKey(const Key('planning-reload')));
    await settle(tester);

    backend.load.value = PlanningLoad(since: DateTime.utc(2026, 11, 23, 5), place: 'GitHub', read: 750, total: 1548);
    await tester.pump();

    expect(board.refusal?.name, 'NoSuchProject');
    expect(board.moveProblem, isNotNull);
  });
}
