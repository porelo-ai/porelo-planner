import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

/// A source whose watches, saves and story reads the test holds and answers one by one.
class _Source extends FakePlanningSource {
  _Source() : super(_counted());

  final watches = <StreamController<PlanningSnapshot>>[];
  Completer<void>? saveHeld;
  Completer<void>? storiesHeld;
  Object? saveFailure;
  Completer<void>? refreshHeld;
  Object? watchFailure;
  int storyReads = 0;

  PlanningSnapshot get shown => PlanningSnapshot.from(board);

  /// Sends the board as the planner holds it now on the newest watch.
  void send() => watches.last.add(shown);

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    if (watchFailure case final failure?) throw failure;
    final watch = StreamController<PlanningSnapshot>(sync: true);
    watches.add(watch);
    return watch.stream;
  }

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    String revision, {
    required String digest,
    bool confirm = false,
  }) async {
    if (saveHeld case final held?) await held.future;
    if (saveFailure case final failure?) {
      saves.add((project, moves, revision));
      throw failure;
    }
    return super.saveMoves(project, moves, revision, digest: digest, confirm: confirm);
  }

  @override
  Future<String> refreshProject(String project) async {
    if (refreshHeld case final held?) await held.future;
    return super.refreshProject(project);
  }

  @override
  Future<PlanningSnapshot> stories(String project) async {
    storyReads++;
    if (storiesHeld case final held?) await held.future;
    return super.stories(project);
  }
}

/// The small board with its open-work figures, so the counts are known.
Map<String, dynamic> _counted() => {
  ...smallBoard(),
  'open': _open(2),
  'stories': [
    for (final story in (smallBoard()['stories'] as List).cast<Map<String, dynamic>>()) {...story, 'open': _open(1)},
  ],
};

Map<String, dynamic> _open(int left) => {
  'states': [
    {'name': 'Backlog', 'issues': left},
  ],
  'blocked': 0,
  'sizes': <Object>[],
  'unsized': left,
  'left': left,
  'minutes': null,
  'unestimated': left,
  'review': 0,
};

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
  expect(board.snapshot?.open, isNotNull, reason: 'the board came with its figures');
  return (source, board);
}

/// A board whose watch failed, so only a Reload makes it take moves again.
Future<(_Source, PlanningBoard)> _downBoard() async {
  final (source, board) = await _liveBoard();
  source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
  await _turns();
  expect(board.live, isFalse, reason: 'the watch failed');
  return (source, board);
}

/// Starts a Reload of a failed watch whose fetch the test holds, so the board is reopening.
Future<void> _reloadHeld(_Source source, PlanningBoard board) async {
  source.refreshHeld = Completer<void>();
  unawaited(board.reloadPlanning());
  await _turns();
  expect(board.reopening, isTrue, reason: 'the Reload runs');
}

bool _figuresShown(PlanningBoard board) => planningFiguresKnown(board) && board.snapshot?.open != null;

/// Where the issue of story [id] stands, as the board shows it: a saved move shows on its issues at once, and the
/// story's own state is the planner's, read after the save.
String? _stateOf(PlanningBoard board, String id) =>
    board.stories.where((story) => story.id == id).firstOrNull?.issues.firstOrNull?.state;

void main() {
  // Harm: the watch fails while a slow save runs, the save's end is skipped, and the board stays locked for good.
  test('a save that answers after its watch failed still ends and shows its move', () async {
    final (source, board) = await _liveBoard();
    source.saveHeld = Completer<void>();
    unawaited(board.move('S1', 'Review'));
    await _turns();
    expect(board.saving, isTrue, reason: 'the save runs');
    source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
    await _turns();
    source.saveHeld!.complete();
    await _turns();
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(board.pending, isEmpty, reason: 'its move is no longer in flight');
    expect(_stateOf(board, 'S1'), 'Review', reason: 'the saved move is shown');
  });

  // Harm: a Reload's read of the story list holds every move and blanks the counts for as long as the planner reads.
  test('a drag during a Reload\'s read is saved and the known counts stay', () async {
    final (source, board) = await _liveBoard();
    source.storiesHeld = Completer<void>();
    unawaited(board.reloadPlanning());
    await _turns();
    expect(source.storyReads, 1, reason: 'the Reload reads the story list');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay while it reads');
    unawaited(board.move('S1', 'Review'));
    await _turns();
    expect(source.saves, hasLength(1), reason: 'the drag was saved during the read');
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(_stateOf(board, 'S1'), 'Review', reason: 'the saved move is shown');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay');
    source.storiesHeld!.complete();
    await _turns();
    expect(_stateOf(board, 'S1'), 'Review', reason: 'the Reload\'s late read never takes the move back');
  });

  // Harm: the read after a save hangs on a planner that reads its board whole, and the board stays locked meanwhile.
  test('a save whose read afterwards hangs ends at once and the next drag is saved', () async {
    final (source, board) = await _liveBoard();
    source.storiesHeld = Completer<void>();
    unawaited(board.move('S1', 'Review'));
    await _turns();
    expect(source.saves, hasLength(1), reason: 'the move was saved');
    expect(source.storyReads, 1, reason: 'the read after the save runs');
    expect(board.saving, isFalse, reason: 'the save ended before its read');
    expect(_stateOf(board, 'S1'), 'Review', reason: 'the saved move is shown');
    unawaited(board.move('S2', 'Finished'));
    await _turns();
    expect(source.saves, hasLength(2), reason: 'the next drag was saved');
    source.storiesHeld!.complete();
    await _turns();
    expect(board.saving, isFalse);
  });

  // Harm: a save that only the window stopped waiting for blanks every count and locks the other cards (the 2-minute
  // write timeout against a planner retrying GitHub's 502s).
  test('a save that timed out keeps the counts known and the other cards take moves', () async {
    final (source, board) = await _liveBoard();
    source.saveFailure = const PlanningRefusal('TimedOut', {}, 'The planner did not answer in time.');
    await board.move('S1', 'Review');
    expect(board.pending['S1']?.unanswered, isTrue, reason: 'the save may still land');
    expect(board.saving, isFalse, reason: 'the window stopped waiting');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay');
    source.saveFailure = null;
    await board.move('S2', 'Finished');
    expect(source.saves, hasLength(2), reason: 'another card\'s drag was saved');
    expect(_stateOf(board, 'S2'), 'Finished', reason: 'and is shown');
  });

  // Harm: a Reload asked for during a slow save opens the watch anew under it, and the save's end is lost.
  test('a Reload whose watch opens anew during a save waits for the save and keeps its move', () async {
    final (source, board) = await _downBoard();
    await _reloadHeld(source, board);
    source.saveHeld = Completer<void>();
    unawaited(board.move('S1', 'Review'));
    await _turns();
    expect(board.saving, isTrue, reason: 'the move made during the Reload is being saved');
    source.refreshHeld!.complete();
    await _turns();
    expect(source.watches, hasLength(1), reason: 'the watch waits for the save to open anew');
    source.saveHeld!.complete();
    await _turns();
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(_stateOf(board, 'S1'), 'Review', reason: 'its move is shown');
    expect(source.watches, hasLength(2), reason: 'the watch opened anew once the save ended');
    source.send();
    await _turns();
    expect(board.live, isTrue, reason: 'live again');
    expect(_stateOf(board, 'S1'), 'Review', reason: 'the move is kept by the new watch\'s board');
  });

  // Harm: a move on the board shown during a Reload is saved blind over a newer board.
  test('a move during a Reload against a revision the planner moved past is refused as stale', () async {
    final (source, board) = await _downBoard();
    await _reloadHeld(source, board);
    source.board = {...source.board, 'revision': 'r9'};
    await board.move('S1', 'Review');
    expect(source.saves, isEmpty, reason: 'nothing was saved');
    expect(board.moveProblem, contains('Planning changed'), reason: 'the stale move is said');
    source.refreshHeld!.complete();
  });

  group('a Reload\'s reopening ends', () {
    // Harm: a board whose reopen ended keeps taking moves against a watch that no longer brings anything.
    test('when the new watch fails', () async {
      final (source, board) = await _downBoard();
      await _reloadHeld(source, board);
      source.refreshHeld!.complete();
      await _turns();
      source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'Down again.'));
      await _turns();
      expect(board.reopening, isFalse);
      expect(board.takesMoves, isFalse);
    });

    test('when the new watch ends', () async {
      final (source, board) = await _downBoard();
      await _reloadHeld(source, board);
      source.refreshHeld!.complete();
      await _turns();
      await source.watches.last.close();
      await _turns();
      expect(board.reopening, isFalse);
      expect(board.takesMoves, isFalse);
    });

    test('when the watch cannot be opened', () async {
      final (source, board) = await _downBoard();
      await _reloadHeld(source, board);
      source.watchFailure = const PlanningRefusal('Unreachable', {}, 'No planner.');
      source.refreshHeld!.complete();
      await _turns();
      expect(board.reopening, isFalse);
      expect(board.takesMoves, isFalse);
    });

    test('when another project is shown', () async {
      final (source, board) = await _downBoard();
      await _reloadHeld(source, board);
      await board.changeProject(source, 'other');
      expect(board.reopening, isFalse);
      expect(board.takesMoves, isFalse);
      source.refreshHeld!.complete();
      await _turns();
      expect(board.reopening, isFalse, reason: 'the old project\'s fetch reopens nothing');
    });

    test('when the board is closed', () async {
      final source = _Source();
      final board = PlanningBoard(source, 'demo');
      source.send();
      await _turns();
      source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'Down.'));
      await _turns();
      await _reloadHeld(source, board);
      board.dispose();
      expect(board.reopening, isFalse);
      source.refreshHeld!.complete();
    });
  });

  // Harm: a move made in one project is saved or shown on another project's board.
  test('a move in flight when another project is chosen is never shown on that project', () async {
    final (source, board) = await _liveBoard();
    source.saveHeld = Completer<void>();
    unawaited(board.move('S1', 'Review'));
    await _turns();
    unawaited(board.changeProject(source, 'other'));
    await _turns();
    expect(board.project, 'demo', reason: 'the switch waits for the save');
    source.saveHeld!.complete();
    await _turns();
    expect(board.project, 'other', reason: 'switched once the save ended');
    expect(board.pending, isEmpty, reason: 'no move of demo is carried over');
    expect(board.snapshot, isNull, reason: 'nothing of demo is shown on other');
  });
}
