import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

/// A source whose watch shows a board only when the test sends it, so the time between a save's answer and the
/// board that carries it can be seen.
class _Source extends FakePlanningSource {
  _Source(super.board);

  final watches = <String, StreamController<PlanningSnapshot>>{};

  void send(String project) => watches[project]!.add(PlanningSnapshot.from(board));

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>();
    watches[project] = watch;
    return watch.stream;
  }

  @override
  Future<void> close() async {
    for (final watch in watches.values) {
      if (!watch.isClosed) await watch.close();
    }
    await super.close();
  }
}

/// The small board with both stories waiting in Backlog, the first at High and the second at Medium.
Map<String, dynamic> _oneColumn() {
  final board = smallBoard();
  return {
    ...board,
    'stories': [
      for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {...story, 'state': 'Backlog'},
    ],
  };
}

Future<PlanningBoards> _show(WidgetTester tester, _Source source) async {
  tester.view
    ..physicalSize = const Size(1280, 1000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final client = FakePlanningClient([provider('local', source)]);
  final boards = PlanningBoards(client);
  final host = TestHost();
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    host.dispose();
    await source.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: host),
      ),
    ),
  );
  source.send('demo');
  await settle(tester);
  return boards;
}

List<String> _ids(List<PlanStory> stories) => [for (final story in stories) story.id];

void main() {
  // Harm: a priority chosen on the card that never reaches the planner, or one shown as saved before the board
  // carries it, sorts the person's stories by a priority the store does not hold.
  testWidgets('a priority chosen in the card menu is sent and stays pending until the board carries it', (
    tester,
  ) async {
    final source = _Source(_oneColumn());
    final boards = await _show(tester, source);
    final board = boards.selected!;
    expect(_ids(board.cards('Backlog')), ['S1', 'S2'], reason: 'High sorts before Medium');

    await tester.tap(find.byKey(const Key('planning-menu S2')));
    await settle(tester);
    await tester.tap(find.byKey(const ValueKey<String>('planning-priority S2 Highest')));
    await settle(tester);

    expect(source.priorities, [('demo', 'S2', 'Highest', 'r1')], reason: 'the choice is sent at the shown revision');
    expect(board.pendingPriorities, {'S2': 'Highest'}, reason: 'pending until the board carries it');
    expect(find.byKey(const Key('planning-priority-pending S2')), findsOneWidget, reason: 'the card shows it pending');
    expect(_ids(board.cards('Backlog')), ['S1', 'S2'], reason: 'the board sorts by what it carries');

    source.send('demo');
    await settle(tester);

    expect(board.pendingPriorities, isEmpty, reason: 'the board carries the priority now');
    expect(find.byKey(const Key('planning-priority-pending S2')), findsNothing, reason: 'nothing is pending');
    expect(_ids(board.cards('Backlog')), ['S2', 'S1'], reason: 'the board sorts the story by its new priority');
  });

  // Harm: a refused priority left pending shows a priority the store refused as if it were coming.
  testWidgets('a refused priority is dropped and its refusal shown', (tester) async {
    final source = _Source(_oneColumn())..priorityFailure = const PlanningRefusal('StaleRevision', {'revision': 'r9'});
    final boards = await _show(tester, source);
    final board = boards.selected!;

    expect(await board.setPriority('S2', 'Low'), isFalse, reason: 'the planner refused it');

    expect(source.priorities, hasLength(1), reason: 'the choice was sent once');
    expect(board.pendingPriorities, isEmpty, reason: 'nothing stays pending after a refusal');
    expect(board.actionProblem, isNotNull, reason: 'the refusal is shown');
  });

  // Harm: a priority sent against no board, or while a save runs, would be refused as stale or race the save.
  test('no priority is sent while the board is not live', () async {
    final source = _Source(_oneColumn());
    final board = PlanningBoard(source, 'demo');
    addTearDown(() async {
      board.dispose();
      await source.close();
    });

    expect(board.priorityUnavailable('S2'), isNotNull, reason: 'no board was shown yet');
    expect(await board.setPriority('S2', 'Low'), isFalse, reason: 'nothing is sent');
    expect(source.priorities, isEmpty, reason: 'the source was never asked');
  });

  // Harm: a priority chosen on the story's page that never reaches the planner, or one shown as saved before it is.
  testWidgets('a priority chosen on the story page is sent and stays pending until the board carries it', (
    tester,
  ) async {
    final backend = MockPlanningSource()..moveGate = Completer<void>();
    final board = PlanningBoard(backend, 'demo');
    final detail = PlanningDetail(board, 'ST291', null);
    addTearDown(() async {
      detail.dispose();
      board.dispose();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: ListenableBuilder(
              listenable: detail,
              builder: (_, _) => PlanningStoryView(
                detail: detail,
                onIssue: (_) {},
                onTask: (_) {},
                taskUnavailable: (_) => null,
                openLink: (_) async {},
              ),
            ),
          ),
        ),
      ),
    );
    await settle(tester);

    await tester.tap(find.byKey(const Key('planning-priority ST291')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-priority-to ST291 Lowest')));
    await settle(tester);

    expect(backend.priorityCalls.single['priority'], 'Lowest', reason: 'the choice is sent');
    expect(find.byKey(const Key('planning-priority-pending ST291')), findsOneWidget, reason: 'shown pending');

    backend.moveGate!.complete();
    await settle(tester);

    expect(board.pendingPriorities, isEmpty, reason: 'the board carries the priority now');
    expect(find.byKey(const Key('planning-priority-pending ST291')), findsNothing, reason: 'nothing is pending');
    expect(
      board.stories.firstWhere((story) => story.id == 'ST291').priority,
      'Lowest',
      reason: 'the board shows the saved priority',
    );
  });
}
