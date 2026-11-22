import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// A source whose fetches the test answers one by one and whose every watch it holds.
class _Source extends Fake implements PlanningSource {
  final fetches = <Completer<String>>[];
  final watches = <StreamController<PlanningSnapshot>>[];

  @override
  Future<String> refreshProject(String project) {
    final fetch = Completer<String>();
    fetches.add(fetch);
    return fetch.future;
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>(sync: true);
    watches.add(watch);
    return watch.stream;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async => _board(const []);

  @override
  Future<PlanningSnapshot> stories(String project) async => _board(const []);
}

PlanningSnapshot _board(List<String> faults) => PlanningSnapshot.from({
  'revision': 'r1',
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Review', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Review'],
    },
  ],
  'faults': faults,
  'stories': [
    {'id': 'S1', 'state': 'Ready', 'title': 'A story', 'priority': 'High'},
  ],
});

Future<void> _turn() => Future<void>.delayed(Duration.zero);

/// A board whose watch is open and live, but whose board has a fault.
Future<(_Source, PlanningBoard)> _faultyBoard() async {
  final source = _Source();
  final board = PlanningBoard(source, 'demo');
  addTearDown(board.dispose);
  source.watches.single.add(_board(['a fault']));
  await _turn();
  expect(board.live, isTrue, reason: 'the watch answered');
  expect(board.problem, isNotNull, reason: 'the board has a fault');
  return (source, board);
}

/// A board whose watch failed, so it is no longer live.
Future<(_Source, PlanningBoard)> _downBoard() async {
  final (source, board) = await _faultyBoard();
  source.watches.single.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
  await _turn();
  expect(board.live, isFalse, reason: 'the watch failed');
  return (source, board);
}

void main() {
  // Harm: a second press sends the planner two fetches, or a board with a fault is locked while the planner fetches.
  test('Reload planning on a live board asks one fetch and keeps the board taking moves', () async {
    final (source, board) = await _faultyBoard();
    unawaited(board.reloadPlanning());
    await _turn();
    expect(board.fetching, isTrue, reason: 'the planner fetches');
    expect(board.saving, isFalse, reason: 'the fetch holds no move');
    expect(board.takesMoves, isTrue, reason: 'the board shown keeps taking moves');
    await board.reloadPlanning();
    expect(source.fetches, hasLength(1), reason: 'a second press asks for no second fetch');
    source.fetches.single.complete('r2');
    await _turn();
    expect(source.watches, hasLength(1), reason: 'a live watch brings the board read, so it is not opened anew');
    expect(board.live, isTrue, reason: 'the board stays live');
  });

  // Harm: the one button leaves planning down once its watch failed, or locks the board shown while it opens anew.
  test('Reload planning opens a failed watch anew and keeps the board taking moves meanwhile', () async {
    final (source, board) = await _downBoard();
    expect(board.takesMoves, isFalse, reason: 'a failed watch takes no move before a Reload');
    unawaited(board.reloadPlanning());
    await _turn();
    expect(board.saving, isFalse, reason: 'the fetch holds no move');
    expect(board.takesMoves, isTrue, reason: 'the board shown takes moves while the Reload runs');
    source.fetches.single.complete('r2');
    await _turn();
    expect(source.watches, hasLength(2), reason: 'the watch was opened anew');
    expect(board.reopening, isTrue, reason: 'until the new watch answers');
    expect(board.takesMoves, isTrue, reason: 'still taking moves');
    source.watches.last.add(_board(const []));
    await _turn();
    expect(board.live, isTrue, reason: 'planning is live again');
    expect(board.reopening, isFalse, reason: 'the new watch answered');
  });

  // Harm: a refusal that no longer holds stays above a board that recovered, and the person distrusts a sound board.
  test('a refused fetch is said and goes once a later Reload planning fetches', () async {
    final (source, board) = await _faultyBoard();
    unawaited(board.reloadPlanning());
    await _turn();
    source.fetches.single.completeError(const PlanningRefusal('Unreachable', {}, 'The remote cannot be reached.'));
    await _turn();
    expect(board.moveProblem, 'The remote cannot be reached.', reason: 'the refused fetch is said');
    unawaited(board.reloadPlanning());
    await _turn();
    source.fetches.last.complete('r2');
    await _turn();
    expect(board.moveProblem, isNull, reason: 'the fetch that worked took the refusal away');
  });

  // Harm: a refused fetch of a failed watch leaves planning down, with no word why.
  test('a refused fetch of a failed watch is said and the watch is opened anew all the same', () async {
    final (source, board) = await _downBoard();
    unawaited(board.reloadPlanning());
    await _turn();
    source.fetches.single.completeError(const PlanningRefusal('Unreachable', {}, 'The remote cannot be reached.'));
    await _turn();
    expect(source.watches, hasLength(2), reason: 'the watch was opened anew');
    expect(board.moveProblem, 'The remote cannot be reached.', reason: 'the refused fetch is said');
  });
}
