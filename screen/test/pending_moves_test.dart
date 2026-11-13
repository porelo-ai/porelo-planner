import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

/// A provider whose saves wait until a test lets them answer.
class _HeldSource extends FakePlanningSource {
  _HeldSource(super.board, {super.label});

  Completer<void> gate = Completer<void>();

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    String revision, {
    required String digest,
    bool confirm = false,
  }) async {
    await gate.future;
    return super.saveMoves(project, moves, revision, digest: digest, confirm: confirm);
  }
}

void main() {
  // Harm: leaving the planning place while a move's save is still out loses it without asking first.
  test('a move whose save is out holds a leave until it is answered', () async {
    final source = _HeldSource(smallBoard());
    final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
    addTearDown(boards.dispose);
    await pumpEventQueue();
    final board = boards.selected!;
    expect(board.live, isTrue, reason: 'the fake answered the watch');
    expect(boards.hasPendingMoves, isFalse, reason: 'nothing is saved yet');

    final moving = board.move('S1', 'Review');
    await pumpEventQueue();

    expect(board.pending.keys, ['S1']);
    expect(boards.hasPendingMoves, isTrue, reason: 'its save is still out');
    source.gate.complete();
    await moving;
    await pumpEventQueue();
    expect(source.saves.single.$2.single.to, 'Review');
    expect(boards.hasPendingMoves, isFalse, reason: 'the provider confirmed the move');
  });

  // Harm: a save out on a provider that is no longer selected is lost on leaving without a question.
  test('a save out on a provider not shown still holds the leave', () async {
    final local = FakePlanningSource(smallBoard(), label: 'local');
    final other = _HeldSource(smallBoard(), label: 'other');
    final client = FakePlanningClient([provider('local', local), provider('other', other)]);
    final boards = PlanningBoards(client);
    addTearDown(boards.dispose);
    await pumpEventQueue();
    final hidden = boards.at('other', 'demo')!;
    expect(boards.selected, isNot(same(hidden)));

    final moving = hidden.move('S2', 'Backlog');
    await pumpEventQueue();

    expect(boards.hasPendingMoves, isTrue);
    other.gate.complete();
    await moving;
    expect(boards.hasPendingMoves, isFalse);
  });
}
