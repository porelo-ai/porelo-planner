import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

void main() {
  // Harm: leaving the planning place drops an unsaved move without asking first.
  test('one unsaved move holds a leave until it is saved', () async {
    final source = FakePlanningSource(smallBoard());
    final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
    addTearDown(boards.dispose);
    await pumpEventQueue();
    final board = boards.selected!;
    expect(board.live, isTrue, reason: 'the fake answered the watch');
    expect(boards.hasPendingMoves, isFalse, reason: 'nothing is staged yet');

    board.move('S1', 'Review');

    expect(board.pending.keys, ['S1']);
    expect(boards.hasPendingMoves, isTrue, reason: 'one move is staged and not saved');
    await board.save();
    await pumpEventQueue();
    expect(source.saves.single.$2.single.to, 'Review');
    expect(boards.hasPendingMoves, isFalse, reason: 'the provider confirmed the move');
  });

  // Harm: a move staged on a provider that is no longer selected is lost on leaving without a question.
  test('an unsaved move on a provider not shown still holds the leave', () async {
    final local = FakePlanningSource(smallBoard(), label: 'local');
    final other = FakePlanningSource(smallBoard(), label: 'other');
    final client = FakePlanningClient([provider('local', local), provider('other', other)]);
    final boards = PlanningBoards(client);
    addTearDown(boards.dispose);
    await pumpEventQueue();
    final hidden = boards.at('other', 'demo')!;
    expect(boards.selected, isNot(same(hidden)));

    hidden.move('S2', 'Backlog');

    expect(boards.hasPendingMoves, isTrue);
    hidden.undoAll();
    expect(boards.hasPendingMoves, isFalse);
  });
}
