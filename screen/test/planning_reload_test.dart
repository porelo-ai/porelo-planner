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

void main() {
  // Harm: a second press or a move while the fetch runs sends the planner two fetches or moves a story on the board
  // the reload is about to replace.
  test('Reload planning holds the board while the planner fetches', () async {
    final (source, board) = await _faultyBoard();
    unawaited(board.reloadPlanning());
    await _turn();
    expect(board.saving, isTrue, reason: 'the board is busy while it fetches');
    await board.reloadPlanning();
    expect(source.fetches, hasLength(1), reason: 'a second press asks for no second fetch');
    await board.move('S1', 'Review');
    expect(board.pending, isEmpty, reason: 'no move starts on the board being replaced');
    source.fetches.single.complete('r2');
    await _turn();
    expect(board.saving, isFalse, reason: 'the board is free once the fetch is done');
    expect(source.watches, hasLength(2), reason: 'the watch was opened anew');
  });

  // Harm: the watch fails while the fetch runs, and the one button leaves planning down.
  test('Reload planning opens the watch anew even when the old one fails meanwhile', () async {
    final (source, board) = await _faultyBoard();
    unawaited(board.reloadPlanning());
    await _turn();
    source.watches.first.addError(const PlanningRefusal('Unreachable', {}, 'The planner stopped answering.'));
    await _turn();
    source.fetches.single.complete('r2');
    await _turn();
    expect(source.watches, hasLength(2), reason: 'the watch was opened anew');
    source.watches.last.add(_board(const []));
    await _turn();
    expect(board.live, isTrue, reason: 'planning is live again');
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
}
