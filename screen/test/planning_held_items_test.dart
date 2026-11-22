import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

const _outside = 'Its state is none of the workflow\'s states, so no work starts on it.';
const _twice = 'Its id names another item too, so it can be neither worked nor moved until one id is changed.';
const _parent = 'Its parent is outside the project and is not shown.';
const _priority = 'It has no priority, so it is sorted as Medium.';

Map<String, dynamic> _warning(String kind, String text, {required bool holds}) => {
  'kind': kind,
  'text': text,
  'holds': holds,
};

Map<String, dynamic> _issue(String id, String state, {List<Object>? warnings}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'worker': 'MANUAL',
  'blockedBy': <String>[],
  'warnings': ?warnings,
};

Map<String, dynamic> _story(String id, String state, {List<Object>? issues, List<Object>? warnings}) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'stateGiven': true,
  'priority': 'High',
  'issues': issues ?? <Object>[],
  'warnings': ?warnings,
};

/// A live board in the states Ready, Building and Finished, with the stories S1 named twice, S2 parked by hand, S3
/// parked by hand and named twice, S4 without a priority and S5 sound; S5 holds the issues I1 to I3.
Future<PlanningBoard> _board() async {
  final source = MockPlanningSource()
    ..boards['demo'] = {
      'states': [
        {'name': 'Ready', 'kind': 'WAITING'},
        {'name': 'Building', 'kind': 'WORKING'},
        {'name': 'Finished', 'kind': 'CLOSED'},
      ],
      'phases': [
        {
          'name': 'Work',
          'states': ['Ready', 'Building', 'Finished'],
        },
      ],
      'stories': [
        _story('S1', 'Ready', warnings: [_warning('IdTwice', _twice, holds: true)]),
        _story('S2', 'Parked', warnings: [_warning('StateOutsideWorkflow', _outside, holds: true)]),
        _story(
          'S3',
          'Parked',
          warnings: [_warning('StateOutsideWorkflow', _outside, holds: true), _warning('IdTwice', _twice, holds: true)],
        ),
        _story('S4', 'Ready', warnings: [_warning('NoPriority', _priority, holds: false)]),
        _story(
          'S5',
          'Ready',
          issues: [
            _issue('I1', 'Ready', warnings: [_warning('ParentOutsideProject', _parent, holds: true)]),
            _issue('I2', 'Parked', warnings: [_warning('StateOutsideWorkflow', _outside, holds: true)]),
            _issue('I3', 'Ready'),
          ],
        ),
      ],
      'revision': 'r1',
    };
  final board = PlanningBoard(source, 'demo');
  addTearDown(() async {
    board.dispose();
    await source.end();
  });
  await pumpEventQueue();
  expect(board.takesMoves, isTrue, reason: 'the board is live and complete');
  return board;
}

PlanStory _of(PlanningBoard board, String id) => board.stories.firstWhere((story) => story.id == id);

PlanIssue _issueOf(PlanningBoard board, String id) =>
    board.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == id);

List<String> _into(PlanningBoard board, bool Function(PlanState) enters) => [
  for (final state in board.states)
    if (enters(state)) state.name,
];

void main() {
  // Harm: Start work is offered on a story the planner refuses to work, so the person waits on a refusal; a warning
  // that holds nothing must not take Start away.
  test(
    'a story a warning holds offers no Start, in the warning\'s words; one whose warning holds nothing does',
    () async {
      final board = await _board();

      expect(board.startUnavailable(_of(board, 'S1')), 'the planner holds it: $_twice');
      expect(board.startUnavailable(_of(board, 'S4')), isNull, reason: 'a missing priority holds nothing back');
      expect(board.startUnavailable(_of(board, 'S5')), isNull);
    },
  );

  // Harm: a priority is offered on a story the planner refuses to edit.
  test('a story a warning holds offers no priority; one whose warning holds nothing does', () async {
    final board = await _board();

    expect(board.priorityUnavailable('S1'), 'the planner holds it: $_twice');
    expect(board.priorityUnavailable('S4'), isNull);
  });

  // Harm: a held story is dropped into a state the planner refuses, or one parked by hand cannot be set right by the
  // one move the planner takes.
  test('a held story moves only out of a state the workflow lacks, into a waiting or closed state', () async {
    final board = await _board();

    expect(_into(board, (state) => board.storyMayEnter(_of(board, 'S1'), state)), isEmpty, reason: 'named twice');
    expect(_into(board, (state) => board.storyMayEnter(_of(board, 'S2'), state)), [
      'Ready',
      'Finished',
    ], reason: 'out of its unknown state, never into work');
    expect(
      _into(board, (state) => board.storyMayEnter(_of(board, 'S3'), state)),
      isEmpty,
      reason: 'a second holding warning holds the move too',
    );
    expect(_into(board, (state) => board.storyMayEnter(_of(board, 'S5'), state)), ['Ready', 'Building', 'Finished']);
    expect(
      [
        for (final id in ['S1', 'S2', 'S3', 'S5']) board.heldInPlace(_of(board, id).state, _of(board, id).warnings),
      ],
      [true, false, true, false],
      reason: 'which cards take no drag',
    );

    await board.move('S1', 'Finished', revision: 'r1');

    expect(board.moveProblem, 'S1 was not moved: the planner holds it: $_twice');
    expect(_of(board, 'S1').state, 'Ready');
  });

  // Harm: the issue rows' move offers a held issue states the planner refuses.
  test('a held issue moves only out of a state the workflow lacks, into a waiting or closed state', () async {
    final board = await _board();

    expect(_into(board, (state) => board.issueMayEnter(_issueOf(board, 'I1'), state)), isEmpty);
    expect(_into(board, (state) => board.issueMayEnter(_issueOf(board, 'I2'), state)), ['Ready', 'Finished']);
    expect(_into(board, (state) => board.issueMayEnter(_issueOf(board, 'I3'), state)), [
      'Ready',
      'Building',
      'Finished',
    ]);
  });
}
