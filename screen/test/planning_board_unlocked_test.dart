import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

/// A source whose watches, saves and story reads the test holds and answers one by one.
class _Source extends FakePlanningSource {
  _Source([Map<String, dynamic>? board]) : super(board ?? _counted());

  final watches = <StreamController<PlanningSnapshot>>[];
  Completer<void>? saveHeld;
  Object? saveFailure;
  Completer<void>? refreshHeld;
  Object? watchFailure;
  int storyReads = 0;

  /// While set, each story read takes its reply at once, as the planner read it then, and answers it only once the
  /// test completes the read's own hold in [heldReads].
  bool holdReads = false;
  final heldReads = <Completer<void>>[];

  /// Every story list asked of it: the story, its issues and the revision.
  final lists = <(String, List<String>, String)>[];

  /// While set, the issues in no story are answered at this revision, as a planner whose list is behind its board.
  String? listedAt;

  /// The moves whose preview the planner refuses as not allowed, by id.
  final Set<String> notAllowed = {};

  /// The moves whose preview finds the board moved on unseen to r9 first, as another person's change would, by id.
  final Set<String> movesOnAtPreviewOf = {};

  /// The refusal each move's save answers with, by id; the preview takes it.
  final Map<String, String> refusedAtSave = {};

  @override
  Future<PlanningMovePreview> previewMoves(String project, List<PlanningMove> moves, String revision) {
    if (moves.any((move) => notAllowed.contains(move.id))) {
      return Future.error(PlanningRefusal('MoveNotAllowed', {}, '${moves.first.id} may not go there.'));
    }
    if (moves.any((move) => movesOnAtPreviewOf.remove(move.id))) board = {...board, 'revision': 'r9'};
    return super.previewMoves(project, moves, revision);
  }

  @override
  Future<PlanIssueList> listIssues(String project) async =>
      PlanIssueList.from({'revision': listedAt ?? board['revision'], 'issues': board['issues'] ?? <Object>[]});

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
    if (refusedAtSave.remove(moves.single.id) case final name?) {
      saves.add((project, moves, revision));
      throw PlanningRefusal(name, {}, '${moves.single.id} was refused at its save.');
    }
    return super.saveMoves(project, moves, revision, digest: digest, confirm: confirm);
  }

  @override
  Future<String?> refreshProject(String project) async {
    if (refreshHeld case final held?) await held.future;
    return super.refreshProject(project);
  }

  @override
  Future<PlanningSnapshot> stories(String project) async {
    storyReads++;
    final reply = await super.stories(project);
    if (holdReads) {
      final held = Completer<void>();
      heldReads.add(held);
      await held.future;
    }
    return reply;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async => PlanningSnapshot.from({
    'states': board['states'],
    'phases': board['phases'],
    'faults': board['faults'] ?? <Object>[],
  });

  @override
  Future<String> setStoryIssues(String project, String story, List<String> issues, String revision) async {
    lists.add((story, List.of(issues), revision));
    board = applyPlanningStoryIssues(board, story, issues, revision);
    return board['revision'] as String;
  }

  @override
  Future<PlanStoryDetail> getStory(String project, String story) async =>
      PlanStoryDetail.from({'story': mockPlanningStory(board, story), 'revision': board['revision']});

  @override
  Future<PlanIssueList> listStoryIssues(String project, String story) async =>
      PlanIssueList.from({'issues': mockPlanningStory(board, story)!['issues'], 'revision': board['revision']});
}

/// The small board with its open-work figures, so the counts are known.
Map<String, dynamic> _counted() => {
  ...smallBoard(),
  'open': _open(2),
  'stories': [
    for (final story in (smallBoard()['stories'] as List).cast<Map<String, dynamic>>()) {...story, 'open': _open(1)},
  ],
};

/// The counted board with an issue in no story and one outside the workflow.
Map<String, dynamic> _wide() {
  final counted = _counted();
  final stories = (counted['stories'] as List).cast<Map<String, dynamic>>();
  return {
    ...counted,
    'stories': [stories.first, stories.last],
    'issues': [
      {
        'id': 'repo#900',
        'repository': 'repo',
        'title': 'Work in no story',
        'state': 'Backlog',
        'blockedBy': <String>[],
        'worker': 'AGENT',
      },
    ],
  };
}

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

Future<(_Source, PlanningBoard)> _liveBoard({Map<String, dynamic>? data, Duration? readWait}) async {
  final source = _Source(data);
  final board = readWait == null ? PlanningBoard(source, 'demo') : PlanningBoard(source, 'demo', readWait: readWait);
  addTearDown(() {
    if (!board.disposed) board.dispose();
  });
  source.send();
  await _turns();
  expect(board.live, isTrue, reason: 'the watch answered');
  expect(board.snapshot?.open, isNotNull, reason: 'the board came with its figures');
  return (source, board);
}

/// A board whose watch failed, so only a Reload makes it take moves again.
Future<(_Source, PlanningBoard)> _downBoard({Map<String, dynamic>? data}) async {
  final (source, board) = await _liveBoard(data: data);
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

/// Where story [id] stands, as the board shows it: a saved close shows on the story at once.
String? _stateOf(PlanningBoard board, String id) => board.stories.where((story) => story.id == id).firstOrNull?.state;

void main() {
  // Harm: the watch fails while a slow save runs, the save's end is skipped, and the board stays locked for good.
  test('a save that answers after its watch failed still ends and shows its move', () async {
    final (source, board) = await _liveBoard();
    source.saveHeld = Completer<void>();
    unawaited(board.move('S1', 'Finished'));
    await _turns();
    expect(board.saving, isTrue, reason: 'the save runs');
    source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
    await _turns();
    source.saveHeld!.complete();
    await _turns();
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(board.pending, isEmpty, reason: 'its move is no longer in flight');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the saved move is shown');
  });

  // Harm: a Reload's read of the story list holds every move and blanks the counts for as long as the planner reads.
  // The Reload's late read of the older revision must also never replace the board saved meanwhile.
  test('a drag during a Reload\'s read is saved and the known counts stay', () async {
    final (source, board) = await _liveBoard();
    source.holdReads = true;
    unawaited(board.reloadPlanning());
    await _turns();
    expect(source.heldReads, hasLength(1), reason: 'the Reload read the story list at r1 and waits');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay while it reads');
    unawaited(board.move('S1', 'Finished'));
    await _turns();
    expect(source.saves, hasLength(1), reason: 'the drag was saved during the read');
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(board.snapshot?.revision, 'r2', reason: 'the saved revision is shown');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the saved move is shown');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay');
    expect(source.heldReads, hasLength(2), reason: 'the read after the save read r2 and waits');
    source.heldReads.first.complete();
    await _turns();
    expect(board.snapshot?.revision, 'r2', reason: 'the Reload\'s late read of r1 never replaces r2');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the Reload\'s late read never takes the move back');
    source.heldReads.last.complete();
    await _turns();
    expect(board.snapshot?.revision, 'r2');
    expect(_stateOf(board, 'S1'), 'Finished');
  });

  // Harm: the read after a save hangs on a planner that reads its board whole, and the board stays locked meanwhile.
  test('a save whose read afterwards hangs ends at once and the next drag is saved', () async {
    final (source, board) = await _liveBoard();
    source.holdReads = true;
    unawaited(board.move('S1', 'Finished'));
    await _turns();
    expect(source.saves, hasLength(1), reason: 'the move was saved');
    expect(source.heldReads, hasLength(1), reason: 'the read after the save runs');
    expect(board.saving, isFalse, reason: 'the save ended before its read');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the saved move is shown');
    unawaited(board.move('S2', 'Finished'));
    await _turns();
    expect(source.saves, hasLength(2), reason: 'the next drag was saved');
    expect(source.heldReads, hasLength(2));
    for (final held in source.heldReads) {
      held.complete();
    }
    await _turns();
    expect(board.saving, isFalse);
    expect(board.snapshot?.revision, 'r3', reason: 'the read after the second save is the board shown');
    expect(_stateOf(board, 'S1'), 'Finished');
    expect(_stateOf(board, 'S2'), 'Finished');
  });

  // Harm: the read after a change of a story's list hangs, and the board stays locked meanwhile.
  test('a story-list change whose read afterwards hangs ends at once and moves are taken', () async {
    final (source, board) = await _liveBoard(data: _wide());
    expect(board.noStory.map((issue) => issue.id), ['repo#900']);
    source.holdReads = true;
    unawaited(board.hangIssue('repo#900', 'S1'));
    await _turns();
    expect(source.lists, hasLength(1), reason: 'the change was saved');
    expect(source.heldReads, hasLength(1), reason: 'the read after the change runs');
    expect(board.saving, isFalse, reason: 'the change ended before its read');
    expect(board.holdsMoves, isFalse, reason: 'moves are taken while it reads');
    expect(board.moveNotice, 'Issue changes saved.');
    source.heldReads.single.complete();
    await _turns();
    expect(board.snapshot?.revision, 'r1:issues', reason: 'the read after the change is shown');
    expect(board.stories.first.issues.map((issue) => issue.id), ['I1', 'repo#900']);
  });

  // Harm: a save that only the window stopped waiting for blanks every count and locks the other cards (the 2-minute
  // write timeout against a planner retrying GitHub's 502s).
  // A move of another card is taken meanwhile and saved only once the late save landed, against the board it left.
  test('a save that timed out keeps the counts known and the other cards take moves', () async {
    final (source, board) = await _liveBoard();
    source.saveFailure = const PlanningRefusal('TimedOut', {}, 'The planner did not answer in time.');
    await board.move('S1', 'Finished');
    expect(board.pending['S1']?.unanswered, isTrue, reason: 'the save may still land');
    expect(board.saving, isFalse, reason: 'the window stopped waiting');
    expect(_figuresShown(board), isTrue, reason: 'the counts known stay');
    source.saveFailure = null;
    await board.move('S2', 'Finished');
    expect(board.queuedMoves, ['S2'], reason: 'another card\'s drag was taken');
    expect(board.displayedState(board.stories.last), 'Finished', reason: 'and is shown where it goes');
    expect(source.saves, hasLength(1), reason: 'not saved beside a save that may still land');
    // A board that does not show the late save yet holds the queue still.
    source.board = {...source.board, 'revision': 'r4'};
    source.send();
    await _turns();
    expect(source.saves, hasLength(1), reason: 'still not saved while the late save may land');
    expect(board.queuedMoves, ['S2']);
    // The late save lands: the planner's board shows S1 moved, at a revision of its own.
    final stories = (source.board['stories'] as List).cast<Map<String, dynamic>>();
    source.board = {
      ...source.board,
      'revision': 'r5',
      'stories': [
        {
          ...stories.first,
          'state': 'Finished',
          'issues': [
            for (final issue in (stories.first['issues'] as List).cast<Map<String, dynamic>>())
              {...issue, 'state': 'Finished'},
          ],
        },
        stories.last,
      ],
    };
    source.send();
    await _turns();
    expect(board.pending.containsKey('S1'), isFalse, reason: 'the late save landed');
    expect(source.saves.last.$2.single.id, 'S2', reason: 'the queued move was saved once it landed');
    expect(source.saves.last.$3, 'r5', reason: 'against the board the late save left');
    expect(_stateOf(board, 'S2'), 'Finished');
  });

  // Harm: a Reload asked for during a slow save opens the watch anew under it, and the save's end is lost.
  test('a Reload whose watch opens anew during a save waits for the save and keeps its move', () async {
    final (source, board) = await _downBoard();
    await _reloadHeld(source, board);
    source.saveHeld = Completer<void>();
    unawaited(board.move('S1', 'Finished'));
    await _turns();
    expect(board.saving, isTrue, reason: 'the move made during the Reload is being saved');
    source.refreshHeld!.complete();
    await _turns();
    expect(source.watches, hasLength(1), reason: 'the watch waits for the save to open anew');
    source.saveHeld!.complete();
    await _turns();
    expect(board.saving, isFalse, reason: 'the save ended');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'its move is shown');
    expect(source.watches, hasLength(2), reason: 'the watch opened anew once the save ended');
    source.send();
    await _turns();
    expect(board.live, isTrue, reason: 'live again');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the move is kept by the new watch\'s board');
  });

  // Harm: a move on the board shown during a Reload is saved blind over a newer board.
  test('a move during a Reload against a revision the planner moved past is refused as stale', () async {
    final (source, board) = await _downBoard();
    await _reloadHeld(source, board);
    source.board = {...source.board, 'revision': 'r9'};
    await board.move('S1', 'Finished');
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
    unawaited(board.move('S1', 'Finished'));
    await _turns();
    unawaited(board.changeProject(source, 'other'));
    await _turns();
    expect(board.project, 'demo', reason: 'the switch waits for the save');
    source.saveHeld!.complete();
    await _turns();
    expect(board.project, 'other', reason: 'switched once the save ended');
    expect(board.pending, isEmpty, reason: 'no move of demo is carried over');
    expect(board.snapshot, isNull, reason: 'nothing of demo is shown on other');
    expect(source.saves.where((save) => save.$1 == 'other'), isEmpty, reason: 'nothing of demo was saved on other');
  });

  // Harm: the watch fails while a story action waits for the planner, and the action never ends: every further
  // action waits for good. A Reload asked for meanwhile opens the watch only once the action ended.
  test('a story action that answers after its watch failed and a Reload was asked ends', () async {
    final (source, board) = await _liveBoard();
    source.priorityHeld = Completer<void>();
    unawaited(board.setPriority('S1', 'Low'));
    await _turns();
    expect(board.actionBusy, isTrue, reason: 'the priority waits for the planner');
    source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
    await _turns();
    unawaited(board.reloadPlanning());
    await _turns();
    expect(source.watches, hasLength(1), reason: 'the reopen waits for the action');
    source.priorityHeld!.complete();
    await _turns();
    expect(board.actionBusy, isFalse, reason: 'the action ended');
    expect(board.pendingPriorities, {'S1': 'Low'}, reason: 'the answered priority waits for a board to carry it');
    expect(source.watches, hasLength(2), reason: 'the watch opened anew once the action ended');
    source.send();
    await _turns();
    expect(board.live, isTrue);
    expect(board.pendingPriorities, isEmpty, reason: 'the new board carries it');
    expect(board.stories.first.priority, 'Low');
  });

  // Harm: a watch opened anew sends a board the planner read before a move saved meanwhile, after the read that
  // followed the save, and the move shows taken back.
  test('a move saved while the watch opens anew stays when its older first board comes late', () async {
    final (source, board) = await _downBoard();
    await _reloadHeld(source, board);
    source.refreshHeld!.complete();
    await _turns();
    expect(source.watches, hasLength(2), reason: 'the watch opened anew and sent nothing yet');
    final before = source.shown;
    await board.move('S1', 'Finished');
    expect(source.saves, hasLength(1), reason: 'the move was saved while the watch opened');
    expect(_stateOf(board, 'S1'), 'Finished');
    // The issue list read with the older board is as old, so it does not catch the board up on its own.
    source.listedAt = 'r1';
    source.watches.last.add(before);
    await _turns();
    expect(board.live, isTrue, reason: 'the new watch answered');
    expect(board.snapshot?.revision, 'r2', reason: 'the board was read again after the older first board');
    expect(_stateOf(board, 'S1'), 'Finished', reason: 'the saved move stays');
  });

  group('a Reload\'s read', () {
    // Harm: a Reload clears the word of a move refused while it read, so the person never learns it was not made.
    test('keeps the word of a move refused while it read', () async {
      final (source, board) = await _liveBoard();
      source.holdReads = true;
      unawaited(board.reloadPlanning());
      await _turns();
      await board.moveIssue('NOPE', 'Review');
      expect(board.moveProblem, contains('NOPE was not moved'));
      source.heldReads.single.complete();
      await _turns();
      expect(board.moveProblem, contains('NOPE was not moved'), reason: 'the Reload\'s read did not see it');
    });

    // Harm: a Reload whose read was not shown clears the words of the board that stands.
    test('whose board is not shown clears nothing', () async {
      final (source, board) = await _liveBoard();
      await board.moveIssue('NOPE', 'Review');
      source.holdReads = true;
      unawaited(board.reloadPlanning());
      await _turns();
      source.board = {...source.board, 'revision': 'r7'};
      source.send();
      await _turns();
      source.heldReads.single.complete();
      await _turns();
      expect(board.snapshot?.revision, 'r7', reason: 'the streamed board is newer than the Reload\'s read');
      expect(board.moveProblem, contains('NOPE was not moved'), reason: 'the read was not shown');
    });

    // Harm: a Reload shows a fault the planner mended, or misses a workflow changed at the store.
    test('reads the workflow, so a mended fault and a changed workflow show', () async {
      final faulty = {
        ..._counted(),
        'faults': ['GitHub asked to wait.'],
      };
      final (source, board) = await _liveBoard(data: faulty);
      expect(board.problem, contains('GitHub asked to wait.'));
      source.board = {
        ..._counted(),
        'faults': <Object>[],
        'states': [
          ...(_counted()['states'] as List),
          {'name': 'Parked', 'kind': 'WAITING'},
        ],
        'phases': [
          ...(_counted()['phases'] as List),
          {
            'name': 'Aside',
            'states': ['Parked'],
          },
        ],
      };
      await board.reloadPlanning();
      await _turns();
      expect(board.problem, isNull, reason: 'the mended fault is gone');
      expect(board.states.map((state) => state.name), contains('Parked'), reason: 'the changed workflow shows');
    });

    // Harm: a Reload's read that hangs holds every later Reload for good.
    test('that hangs ends at the read timeout, and a later Reload reads again', () async {
      final (source, board) = await _liveBoard(readWait: const Duration(milliseconds: 50));
      source.holdReads = true;
      unawaited(board.reloadPlanning());
      await Future<void>.delayed(const Duration(milliseconds: 200));
      expect(board.moveProblem, contains('did not answer'), reason: 'the hung read is said');
      source.holdReads = false;
      unawaited(board.reloadPlanning());
      await _turns();
      expect(source.storyReads, 2, reason: 'the later Reload read again');
    });
  });

  group('a stale revision while a Reload runs reads the board again', () {
    // Harm: a move refused as stale while the planner fetches leaves the older board shown, and every move on it is
    // refused again.
    test('while the planner fetches', () async {
      final (source, board) = await _liveBoard();
      source.refreshHeld = Completer<void>();
      unawaited(board.reloadPlanning());
      await _turns();
      expect(board.fetching, isTrue);
      source.board = {...source.board, 'revision': 'r9'};
      await board.move('S1', 'Finished');
      expect(source.saves, isEmpty, reason: 'nothing was saved');
      expect(board.moveProblem, contains('Planning changed'));
      expect(board.snapshot?.revision, 'r9', reason: 'the board was read again');
      source.refreshHeld!.complete();
      await _turns();
    });

    test('while its read runs', () async {
      final (source, board) = await _liveBoard();
      source.holdReads = true;
      unawaited(board.reloadPlanning());
      await _turns();
      expect(source.heldReads, hasLength(1), reason: 'the Reload\'s read of r1 waits');
      source.holdReads = false;
      source.board = {...source.board, 'revision': 'r9'};
      await board.move('S1', 'Finished');
      expect(board.moveProblem, contains('Planning changed'));
      expect(board.snapshot?.revision, 'r9', reason: 'the board was read again');
      source.heldReads.single.complete();
      await _turns();
      expect(board.snapshot?.revision, 'r9', reason: 'the Reload\'s older read never replaces it');
    });
  });

  group('while a Reload opens the watch anew, the board shown takes', () {
    // Harm: a board whose watch opens anew refuses a move of each kind, or saves it against another revision.
    test('an issue move', () async {
      final (source, board) = await _downBoard(data: _wide());
      await _reloadHeld(source, board);
      await board.moveIssue('I1', 'Review');
      expect(source.saves.single.$3, 'r1', reason: 'saved against the revision shown');
      source.refreshHeld!.complete();
    });

    test('a change of a story\'s list', () async {
      final (source, board) = await _downBoard(data: _wide());
      await _reloadHeld(source, board);
      await board.hangIssue('repo#900', 'S1');
      expect(source.lists.single.$3, 'r1', reason: 'saved against the revision shown');
      source.refreshHeld!.complete();
    });

    // Harm: the story's page refuses issue moves the board takes.
    test('an issue move from the story\'s page', () async {
      final (source, board) = await _downBoard(data: _wide());
      await _reloadHeld(source, board);
      final detail = PlanningDetail(board, 'S1', null);
      addTearDown(detail.dispose);
      await _turns();
      expect(detail.moveUnavailable, isNull);
      source.refreshHeld!.complete();
    });
  });

  group('a move of another card made while a save is out', () {
    /// The ids and revisions of every save, in order.
    List<(String, String)> saved(_Source source) => [for (final save in source.saves) (save.$2.single.id, save.$3)];

    // Harm: a move made while a save is out is lost, saved twice or saved against the revision the first save
    // replaced; a second move of the card being saved goes out beside it.
    test('is queued, shown pending, and saved once after it against the revision it left', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      expect(board.queuedMoves, ['S2'], reason: 'taken and queued');
      expect(board.movingWords('S1'), 'Saving…');
      expect(board.movingWords('S2'), 'Waiting for the moves before it…', reason: 'only queued, nothing sent');
      expect(board.pending.keys, containsAll(['S1', 'S2']), reason: 'both show as pending');
      expect(board.displayedState(board.stories.last), 'Finished', reason: 'shown at once where it goes');
      expect(source.saves, isEmpty, reason: 'S1\'s save is out and nothing else went');
      source.saveHeld!.complete();
      await _turns();
      expect(saved(source), [('S1', 'r1'), ('S2', 'r2')], reason: 'saved once, against the revision S1 left');
      expect(board.pending, isEmpty);
      expect(_stateOf(board, 'S1'), 'Finished');
      expect(_stateOf(board, 'S2'), 'Finished');
      await _turns();
      expect(source.saves, hasLength(2), reason: 'never saved again');
    });

    // Harm: a queued move the planner refuses stops the moves queued behind it, or goes back without a word.
    test('refused by the planner shows its words on its card and the queue goes on', () async {
      final (source, board) = await _liveBoard(data: _wide());
      source.saveHeld = Completer<void>();
      source.notAllowed.add('S2');
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      await board.moveIssue('repo#900', 'Review');
      expect(board.queuedMoves, ['S2', 'issue repo#900']);
      source.saveHeld!.complete();
      await _turns();
      expect(board.refusalOf('S2'), contains('may not go there'), reason: 'the card says why');
      expect(board.displayedState(board.stories.last), 'Review', reason: 'S2 went back');
      expect(saved(source), [('S1', 'r1'), ('repo#900', 'r2')], reason: 'the next one was saved');
      expect(board.pending, isEmpty);
    });

    // Harm: a queued move refused as stale makes every move behind it stale too, or one is saved against the
    // revision the planner moved past.
    test('refused as stale reads the board again, and the next one is saved against it', () async {
      final (source, board) = await _liveBoard(data: _wide());
      source.saveHeld = Completer<void>();
      source.movesOnAtPreviewOf.add('S2');
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      await board.moveIssue('repo#900', 'Review');
      source.saveHeld!.complete();
      await _turns();
      expect(board.refusalOf('S2'), contains('Planning changed'), reason: 'the card says why');
      expect(saved(source), [('S1', 'r1'), ('repo#900', 'r9')], reason: 'saved against the board read again');
      expect(board.pending, isEmpty);
    });

    // Harm: a move into a closed state is saved from the queue without the reason the store keeps.
    test('into a closed state asks its question when its turn comes', () async {
      final (source, board) = await _liveBoard(data: {..._counted(), 'reasons': true});
      source.saveHeld = Completer<void>();
      // An issue's move into an open state asks no reason, so the close behind it is the one asked about.
      unawaited(board.moveIssue('I1', 'Review'));
      await _turns();
      await board.move('S2', 'Finished');
      source.saveHeld!.complete();
      await _turns();
      expect(board.question?.key, 'S2', reason: 'asked when its turn came');
      expect(source.saves, hasLength(1), reason: 'not saved before the answer');
      await board.confirmMove();
      await _turns();
      expect(saved(source), [('I1', 'r1'), ('S2', 'r2')]);
    });

    // Harm: a move queued on one project is saved on another, or stays pending there.
    test('is dropped when another project is chosen', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      unawaited(board.changeProject(source, 'other'));
      await _turns();
      expect(board.queuedMoves, isEmpty, reason: 'dropped at the switch');
      source.saveHeld!.complete();
      await _turns();
      expect(board.project, 'other');
      source.send();
      await _turns();
      expect(saved(source), [('S1', 'r1')], reason: 'nothing of the queue was saved');
      expect(source.saves.where((save) => save.$2.single.id == 'S2'), isEmpty, reason: 'S2 was never saved');
      expect(source.saves.where((save) => save.$1 == 'other'), isEmpty);
      expect(board.pending, isEmpty);
    });

    // Harm: a closed board keeps a queued card pending, or saves it.
    test('is dropped when the board is closed', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      board.dispose();
      expect(board.pending.keys, ['S1'], reason: 'only the save that is out stays');
      source.saveHeld!.complete();
      await _turns();
      expect(source.saves, hasLength(1));
      expect(source.saves.where((save) => save.$2.single.id == 'S2'), isEmpty, reason: 'S2 was never saved');
    });

    // Harm: a queued move is lost when the watch is lost, or saved on a board that takes no moves.
    test('waits for the reopen when the watch is lost', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      source.watches.last.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
      await _turns();
      source.saveHeld!.complete();
      await _turns();
      expect(saved(source), [('S1', 'r1')], reason: 'the queue waits while no watch answers');
      expect(board.queuedMoves, ['S2'], reason: 'still queued');
      expect(board.movingWords('S2'), 'Waiting for planning to be live', reason: 'its card says what it waits for');
      unawaited(board.reloadPlanning());
      await _turns();
      expect(saved(source), [('S1', 'r1'), ('S2', 'r2')], reason: 'saved once the board takes moves again');
    });
  });

  group('the last reading of the queue', () {
    /// The id, destination and revision of every save, in order.
    List<(String, String, String)> sent(_Source source) => [
      for (final save in source.saves) (save.$2.single.id, save.$2.single.to, save.$3),
    ];

    // Harm: a move made on a board that changed during the gesture is queued and saved against a later revision.
    test('a move made on an older board than the one shown is not queued', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished', revision: 'r0');
      expect(board.queuedMoves, isEmpty, reason: 'refused, not queued');
      expect(board.refusalOf('S2'), contains('planning changed'));
      source.saveHeld!.complete();
      await _turns();
      expect(sent(source), [('S1', 'Finished', 'r1')], reason: 'S2 was never saved');
    });

    // Harm: a queued move is saved from a state the person never saw: a story's close moved its issue, and the
    // issue's move queued from its old state reopens it.
    test('a queued move whose card the save before it moved is refused, not saved', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.moveIssue('I1', 'Review');
      expect(board.queuedMoves, ['issue I1']);
      source.saveHeld!.complete();
      await _turns();
      expect(sent(source), [('S1', 'Finished', 'r1')], reason: 'I1 was not reopened');
      expect(board.refusalOf('issue I1'), contains('Planning changed: I1 now stands in Finished'));
      expect(board.moveProblem, contains('I1 now stands in Finished'));
      expect(_stateOf(board, 'S1'), 'Finished');
      expect(board.pending, isEmpty);
    });

    // Harm: a second move of a card whose save is out is refused, though the operator wants it to wait for that save.
    test('a card\'s next move waits for its own save and goes from where that save left it', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      // Shown closed, the card is offered its reopen.
      await board.move('S1', 'Backlog');
      expect(board.queuedMoves, ['S1'], reason: 'its next move waits');
      expect(board.displayedState(board.stories.first), 'Backlog', reason: 'the newest destination is shown');
      expect(board.movingWords('S1'), 'Saving…', reason: 'its first move is being saved');
      source.saveHeld!.complete();
      await _turns();
      expect(sent(source), [('S1', 'Finished', 'r1'), ('S1', 'Backlog', 'r2')], reason: 'one after the other');
      expect(_stateOf(board, 'S1'), 'Backlog');
      expect(board.pending, isEmpty);
    });

    // Harm: a card's next move is saved though the move it was made behind was refused, from a state never reached.
    test('a card\'s next move goes, with words, when its first save is refused', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      source.refusedAtSave['S1'] = 'MoveNotAllowed';
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S1', 'Backlog');
      source.saveHeld!.complete();
      await _turns();
      expect(sent(source), [('S1', 'Finished', 'r1')], reason: 'the next move was not saved');
      expect(board.refusalOf('S1'), contains('Its next move to Backlog was not made.'));
      expect(board.moveProblem, contains('Its next move to Backlog was not made.'));
      expect(board.pending, isEmpty);
      expect(board.queuedMoves, isEmpty);
    });

    // Harm: a card moved twice while nothing of its move was sent is saved twice, or with the older destination.
    test('a second move of a card only queued replaces its queued move', () async {
      final (source, board) = await _liveBoard();
      source.saveHeld = Completer<void>();
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.move('S2', 'Finished');
      await board.move('S2', 'Backlog');
      expect(board.queuedMoves, ['S2'], reason: 'one queued move for the card');
      expect(board.displayedState(board.stories.last), 'Backlog');
      source.saveHeld!.complete();
      await _turns();
      expect(sent(source), [('S1', 'Finished', 'r1'), ('S2', 'Backlog', 'r2')]);
    });

    // Harm: a question dropped by the provider's reconnect leaves its save held, and the board takes no move again.
    test('a question dropped when the provider reconnects ends its hold, and a later move is saved', () async {
      final source = _Source({..._counted(), 'reasons': true});
      final client = FakePlanningClient([provider('m', source)]);
      final boards = PlanningBoards(client);
      addTearDown(boards.dispose);
      final board = boards.selected!;
      source.send();
      await _turns();
      await board.move('S2', 'Finished');
      expect(board.question?.key, 'S2', reason: 'the close asks its reason');
      client.providers = [provider('m', source, connected: false)];
      await _turns();
      client.providers = [provider('m', source)];
      await _turns();
      expect(board.question, isNull);
      expect(board.moveProblem, contains('S2 was not moved'), reason: 'the dropped move is said');
      source.send();
      await _turns();
      expect(board.live, isTrue, reason: 'the watch opened anew');
      await board.moveIssue('I1', 'Review');
      expect(sent(source), [('I1', 'Review', 'r1')], reason: 'the later move was saved');
    });

    // Harm: a queued issue move refused at its save leaves no words, as the next queued move clears them.
    test('a queued issue move refused at its save keeps its words past the next queued move', () async {
      final (source, board) = await _liveBoard(data: _wide());
      source.saveHeld = Completer<void>();
      source.refusedAtSave['repo#900'] = 'MoveNotAllowed';
      unawaited(board.move('S1', 'Finished'));
      await _turns();
      await board.moveIssue('repo#900', 'Review');
      await board.move('S2', 'Finished');
      source.saveHeld!.complete();
      await _turns();
      expect(board.refusalOf('issue repo#900'), contains('was refused at its save'), reason: 'on its row');
      expect(board.moveProblem, contains('was refused at its save'), reason: 'the next save did not clear it');
      expect(sent(source).last, ('S2', 'Finished', 'r2'), reason: 'the queue went on');
    });

    // Harm: a card's refusal stands on a card the board shows elsewhere since, or goes with another card's change.
    test('a card\'s refusal goes once the board shows the card elsewhere', () async {
      final (source, board) = await _liveBoard();
      source.notAllowed.add('S2');
      await board.move('S2', 'Finished');
      expect(board.refusalOf('S2'), contains('may not go there'));
      source.board = {...source.board, 'revision': 'r4'};
      source.send();
      await _turns();
      expect(board.refusalOf('S2'), isNotNull, reason: 'the card still stands where it was refused');
      final stories = (source.board['stories'] as List).cast<Map<String, dynamic>>();
      source.board = {
        ...source.board,
        'revision': 'r5',
        'stories': [
          stories.first,
          {...stories.last, 'state': 'Backlog'},
        ],
      };
      source.send();
      await _turns();
      expect(board.refusalOf('S2'), isNull, reason: 'the board shows it elsewhere now');
    });

    // Harm: a move queued while a story action waits is saved beside it, against the revision the action replaces.
    test('a move made while a story action waits is queued until the action ends', () async {
      final (source, board) = await _liveBoard();
      source.priorityHeld = Completer<void>();
      unawaited(board.setPriority('S2', 'Low'));
      await _turns();
      await board.move('S1', 'Finished');
      expect(board.queuedMoves, ['S1'], reason: 'taken while the action waits');
      expect(source.previews, isEmpty, reason: 'nothing sent beside the action');
      source.priorityHeld!.complete();
      await _turns();
      expect(board.actionBusy, isFalse);
      expect(source.previews, isNotEmpty, reason: 'previewed once the action ended');
      expect(board.queuedMoves, isEmpty);
    });

    for (final name in ['StaleRevision', 'PreviewChanged']) {
      // Harm: a queued move meeting a changed board at its save is saved unseen, or stops the queue behind it.
      test('a queued move refused at its save with $name is asked anew, and the queue goes on after', () async {
        final (source, board) = await _liveBoard(data: _wide());
        source.saveHeld = Completer<void>();
        source.refusedAtSave['S2'] = name;
        unawaited(board.move('S1', 'Finished'));
        await _turns();
        await board.move('S2', 'Finished');
        await board.moveIssue('repo#900', 'Review');
        source.saveHeld!.complete();
        await _turns();
        expect(board.question?.key, 'S2', reason: 'asked anew on the board read again');
        expect(board.queuedMoves, ['issue repo#900'], reason: 'the next one waits for the answer');
        board.cancelMove();
        await _turns();
        expect(sent(source).last, ('repo#900', 'Review', 'r2'), reason: 'the queue went on');
      });
    }
  });
}
