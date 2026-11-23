import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

/// A provider whose save answers as the planner does, its reply alone, and whose story read after it fails, so the
/// board shows what it took from the reply.
class _ReplyOnlySource extends FakePlanningSource {
  _ReplyOnlySource(super.board);

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    Map<String, String> seen, {
    required String digest,
    bool confirm = false,
  }) async {
    saves.add((project, moves, board['revision'] as String));
    storiesFailure = const PlanningRefusal('Unavailable');
    return PlanningMoveResult.from({'revision': 'r2', 'moved': <String>[]});
  }
}

Map<String, dynamic> _closedBoard() {
  final board = smallBoard();
  final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
  final second = stories[1];
  return {
    ...board,
    'stories': [
      stories[0],
      {
        ...second,
        'state': 'Finished',
        'closedAt': '2026-03-04T05:06:07Z',
        'issues': [
          {...(second['issues'] as List).cast<Map<String, dynamic>>().single, 'state': 'Finished'},
        ],
      },
    ],
  };
}

void main() {
  // Harm: a reopened story still shows Finished, with the day its test passed, until the board is read again.
  test('a story reopened takes the state its confirmed reopen names and drops its closed day', () async {
    final source = _ReplyOnlySource(_closedBoard());
    final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
    addTearDown(boards.dispose);
    await pumpEventQueue();
    final board = boards.selected!;
    expect(board.snapshot!.stories.firstWhere((story) => story.id == 'S2').closedAt, isNotNull);

    await board.move('S2', 'Backlog');
    await pumpEventQueue();

    expect(source.saves.single.$2.single.to, 'Backlog', reason: 'the reopen was saved');
    final story = board.snapshot!.stories.firstWhere((story) => story.id == 'S2');
    expect(story.state, 'Backlog', reason: 'the state its reopen names');
    expect(story.closedAt, isNull, reason: 'open again, with no day its test passed');
  });
}
