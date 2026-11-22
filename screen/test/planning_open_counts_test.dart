import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

Map<String, dynamic> _open({required int review, required int left, Object? minutes = 42.5}) => {
  'states': [
    {'name': 'Backlog', 'issues': left},
    {'name': 'Review', 'issues': review},
  ],
  'blocked': 0,
  'sizes': [
    {'name': 'S', 'issues': left},
  ],
  'unsized': 0,
  'left': left,
  'minutes': minutes,
  'unestimated': 0,
  'review': review,
};

/// One work, from Backlog through Building to Review, where a story waits for the person's review.
const _work = [
  {
    'state': 'Building',
    'from': ['Backlog'],
    'done': 'Review',
    'per': 'story',
    'modes': ['UNATTENDED'],
    'order': 'Build {story}',
    'models': {'codex': 'gpt'},
  },
];

/// A board with as many stories in Review, where its work ends, as [open] says issues wait for review, since only
/// a story as a whole needs the person's review.
Map<String, dynamic> _board(Map<String, dynamic>? open) => {
  'revision': 'r1',
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Building', 'Review', 'Finished'],
    },
  ],
  'work': _work,
  'faults': <Object>[],
  'repositories': ['repo'],
  'stories': [
    for (var n = 0; n < ((open?['review'] as int?) ?? 0); n++)
      {'id': 'R$n', 'title': 'A story', 'state': 'Review', 'priority': 'High', 'issues': <Object>[]},
  ],
  'open': ?open,
};

void main() {
  // Harm: a Needs you count of 0 shown while nothing is known, which reads as nothing waiting.
  test('the counts stay unknown until a board says them, and a malformed one is no count', () {
    expect(PlanOpenWork.read(null), isNull, reason: 'no figures sent');
    expect(PlanOpenWork.read({..._open(review: 1, left: 2), 'review': -1}), isNull, reason: 'a negative count');
    expect(PlanOpenWork.read({..._open(review: 1, left: 2), 'states': 'many'}), isNull, reason: 'no list of states');
    final read = PlanOpenWork.read(_open(review: 1, left: 2))!;
    expect(read.review, 1, reason: 'the review count as sent');
    expect(read.states, {'Backlog': 2, 'Review': 1}, reason: 'the states in order');
    expect(read.minutes, 42.5, reason: 'the minutes left');
    expect(PlanOpenWork.read(_open(review: 1, left: 2, minutes: null))!.minutes, isNull, reason: 'no minutes known');
  });

  // Harm: Needs you promising reviews for issues in review under a story still being built, though only a story as
  // a whole can be tested.
  test('the window counts the stories standing in review, never the issues in review', () async {
    final building = {
      ..._board(_open(review: 2, left: 3)),
      'stories': [
        {'id': 'B1', 'title': 'A story', 'state': 'Building', 'priority': 'High', 'issues': <Object>[]},
        {'id': 'R1', 'title': 'A story', 'state': 'Review', 'priority': 'High', 'issues': <Object>[]},
      ],
    };
    final local = MockPlanningSource()..boards['alpha'] = building;
    final boards = PlanningBoards(FakePlanningClient([provider('local', local, project: 'alpha')]));
    addTearDown(() async {
      boards.dispose();
      await local.end();
    });
    await pumpEventQueue();

    expect(boards.review.value, 1, reason: 'R1 alone stands in review; the two issues in review do not count');
  });

  // Harm: a wrong Needs you or Open work count, one project counted twice from two machines, or a partial or old sum
  // shown as the whole.
  test('the boards add up each project once and stay unknown while any project is not known or not live', () async {
    final local = MockPlanningSource()
      ..boards['alpha'] = _board(_open(review: 2, left: 3))
      ..boards['beta'] = _board(_open(review: 1, left: 4));
    final remote = MockPlanningSource()..boards['alpha'] = _board(_open(review: 2, left: 3));
    final client = FakePlanningClient([
      provider('local', local, project: 'alpha'),
      provider('local2', local, project: 'beta'),
      provider('remote', remote, project: 'alpha'),
    ]);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      await local.end();
      await remote.end();
    });

    expect(boards.review.value, isNull, reason: 'nothing read yet');
    await pumpEventQueue();

    expect(boards.review.value, 3, reason: 'alpha once and beta');
    expect(boards.open.value, 7, reason: 'the issues left to build of alpha once and beta');

    local.fail(StateError('gone'));
    await pumpEventQueue();

    expect(boards.review.value, isNull, reason: 'beta is served by the machine that stopped answering alone');
    expect(boards.open.value, isNull, reason: 'its last figures no longer say what is open');
  });

  test('a project without figures leaves the counts unknown', () async {
    final local = MockPlanningSource()..boards['alpha'] = _board(_open(review: 2, left: 3));
    final silent = MockPlanningSource()..boards['gamma'] = _board(null);
    final boards = PlanningBoards(
      FakePlanningClient([provider('local', local, project: 'alpha'), provider('silent', silent, project: 'gamma')]),
    );
    addTearDown(() async {
      boards.dispose();
      await local.end();
      await silent.end();
    });
    await pumpEventQueue();

    expect(boards.review.value, isNull, reason: 'gamma said nothing, so 2 would be a partial sum');
  });

  // Harm: a count once known turns into "?" while a move is saved, so every move blanks the window's figures.
  test('the counts known before a move stay while it is saved', () async {
    // S2 of the small board stands in Review.
    final source = _HeldSource({...smallBoard(), 'work': _work, 'open': _open(review: 1, left: 2)});
    final boards = PlanningBoards(FakePlanningClient([provider('local', source)]));
    addTearDown(boards.dispose);
    await pumpEventQueue();
    expect(boards.review.value, 1, reason: 'the figures as read');

    final moving = boards.selected!.move('S1', 'Review');
    await pumpEventQueue();

    expect(boards.review.value, 1, reason: 'the figures known before the move, until the read after it');
    source.gate.complete();
    await moving;
  });

  // Harm: a moved issue still showing minutes left, or a story's known figures blanked by a save.
  test('a saved move keeps every story\'s figures and drops the moved issue\'s minutes until the next read', () {
    final board = smallBoard();
    final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
    final first = stories.first;
    final issue = (first['issues'] as List).first as Map<String, dynamic>;
    issue['minutesLeft'] = 30;
    final other = {...first, 'id': 'S9', 'issues': <Object>[], 'open': _open(review: 0, left: 1)};
    board['stories'] = [
      {...first, 'open': _open(review: 0, left: 1)},
      other,
    ];
    final read = PlanningSnapshot.from(board);
    expect(read.stories.first.issues.first.minutesLeft, 30, reason: 'the minutes as read');

    final saved = PlanningSnapshot.saved(read, 'r2', {issue['id'] as String: 'Finished'}, const {});

    expect(saved.stories.first.issues.first.minutesLeft, isNull, reason: 'the moved issue');
    expect(saved.stories.first.open?.left, 1, reason: 'the moved story keeps its figures until the next read');
    expect(saved.stories.last.open?.left, 1, reason: 'a story none of whose issues moved');
  });
}

/// A provider whose saves wait until a test lets them answer.
class _HeldSource extends FakePlanningSource {
  _HeldSource(super.board);

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
