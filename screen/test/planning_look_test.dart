import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

/// A source whose watch tells the planner's reads through [load], and whose Reload answers when the test lets it.
class _Source extends FakePlanningSource {
  _Source() : super(smallBoard());

  final load = ValueNotifier<PlanningLoad?>(null);
  final watches = <StreamController<PlanningSnapshot>>[];
  Completer<void>? refreshHeld;

  void send() => watches.last.add(versioned(board));

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>(sync: true);
    watches.add(watch);
    return PlanningWatch(watch.stream, ValueNotifier(false), loading: load, tellsReads: true);
  }

  @override
  Future<String?> refreshProject(String project) async {
    if (refreshHeld case final held?) await held.future;
    return super.refreshProject(project);
  }
}

PlanningLoad _told(Map<String, dynamic> data) =>
    PlanningLoad.from({'state': 'reading', 'since': '2026-11-22T10:00:00Z', 'from': 'GitHub', ...data})!;

Future<void> _turns() async {
  for (var turn = 0; turn < 10; turn++) {
    await Future<void>.delayed(Duration.zero);
  }
}

Future<(_Source, PlanningBoard)> _liveBoard() async {
  final source = _Source();
  final board = PlanningBoard(source, 'demo');
  addTearDown(board.dispose);
  source.send();
  await _turns();
  expect(board.live, isTrue, reason: 'the watch answered');
  return (source, board);
}

void main() {
  group('a load event', () {
    // Harm: a window reading a planner from before the kind was told takes every read for a look, or none.
    test('without a kind or a wait is a whole read that waits for nothing', () {
      final load = _told({'read': 3, 'total': 10});
      expect(load.look, isFalse);
      expect(load.waitsFor, isNull);
      expect(load.words, 'Loading the planning from GitHub: 3 of 10 entries');
    });

    // Harm: a kind or a wait added by a later planner throws or turns the read into something it is not.
    test('with a kind or a wait this window does not know is a whole read that waits for nothing', () {
      final load = _told({'kind': 'future', 'waitsFor': 7, 'read': 3, 'total': 10});
      expect(load.look, isFalse, reason: 'an unknown kind');
      expect(load.waitsFor, isNull, reason: 'a wait that is no text');
      expect(_told({'waitsFor': 'lunch'}).waitsFor, isNull, reason: 'a wait this window does not know');
    });

    // Harm: a look that waits for a save reads as a load of the whole board that stands still.
    test('of a look waiting for a save says both', () {
      final load = _told({'kind': 'look', 'waitsFor': 'save', 'read': 640, 'total': 1524});
      expect(load.look, isTrue);
      expect(load.waitsFor, 'save');
      expect(load.words, 'Looking for changes on GitHub: 640 of 1,524 entries, waiting for a save to finish');
    });
  });

  // Harm: the planner's look for changes, every minute, locks the board or blanks its counts.
  test('a look on a live board keeps it live, taking moves and showing its counts', () async {
    final (source, board) = await _liveBoard();
    source.load.value = _told({'kind': 'look', 'read': 0, 'total': 2});
    await _turns();
    expect(board.live, isTrue);
    expect(board.takesMoves, isTrue);
    expect(planningFiguresKnown(board), isTrue);
    expect(board.lookingLive, isTrue);
  });

  // Harm: Reload greyed out for most of every minute while the planner looks for changes.
  test('a look on a live board leaves Reload enabled', () async {
    final (source, board) = await _liveBoard();
    source.load.value = _told({'kind': 'look', 'read': 0, 'total': 2});
    await _turns();
    expect(board.reloadHeld, isFalse);
  });

  // Harm: a second Reload asked while the planner reads the whole board.
  test('a whole read on a live board holds Reload', () async {
    final (source, board) = await _liveBoard();
    source.load.value = _told({'read': 0, 'total': 2});
    await _turns();
    expect(board.lookingLive, isFalse);
    expect(board.reloadHeld, isTrue);
  });

  // Harm: a Reload waiting behind a look loses its bar, and a second press asks for a second fetch.
  test('a look a Reload waits behind keeps the Reload\'s bar', () async {
    final (source, board) = await _liveBoard();
    source.refreshHeld = Completer<void>();
    unawaited(board.reloadPlanning());
    await _turns();
    source.load.value = _told({'kind': 'look', 'waitsFor': 'save', 'read': 5, 'total': 9});
    await _turns();
    expect(board.fetching, isTrue, reason: 'the Reload\'s own read is not told yet');
    expect(board.reloadHeld, isTrue);
    source.refreshHeld!.complete();
  });

  // Harm: the end of a look a Reload waited behind offers Reload again before the Reload's read began, and a second
  // press asks for a second fetch.
  test('a look\'s end leaves Reload held while the whole read asked for waits', () async {
    final (source, board) = await _liveBoard();
    source.load.value = _told({'kind': 'look', 'read': 5, 'total': 9});
    await _turns();
    unawaited(board.reloadPlanning());
    await _turns();
    // The planner tells the Reload as a whole read from the moment it is asked, while the look still runs.
    source.load.value = _told({'read': 0});
    await _turns();
    // The look ends with its board; the Reload's read has not begun.
    source.send();
    await _turns();
    expect(board.reloadHeld, isTrue, reason: 'the whole read asked for waits');
    source.load.value = null;
    source.send();
    await _turns();
    expect(board.reloadHeld, isFalse, reason: 'the whole read ended with its board');
  });

  // Harm: a look shown as a bar over the board, as if the whole board were loaded again.
  testWidgets('a look on a live board is a line of words, never a bar', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 1000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = _Source();
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
    source.send();
    await settle(tester);
    source.load.value = _told({'kind': 'look', 'read': 1, 'total': 2});
    await settle(tester);
    expect(find.byKey(const Key('planning-look')), findsOneWidget);
    expect(find.text('Looking for changes on GitHub: 1 of 2 entries'), findsOneWidget);
    expect(find.byKey(const Key('planning-load-bar')), findsNothing);
    source.load.value = null;
    await settle(tester);
  });
}
