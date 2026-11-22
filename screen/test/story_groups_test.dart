import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/story_groups.dart';

PlanIssue _issue(String id, String state, {List<String> blockedBy = const []}) => PlanIssue.from({
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': blockedBy,
});

final _states = [
  for (final (name, kind) in [
    ('Backlog', 'WAITING'),
    ('Implementing', 'WORKING'),
    ('Review', 'WAITING'),
    ('Finished', 'CLOSED'),
    ('Rejected', 'CLOSED'),
  ])
    PlanState.from({'name': name, 'kind': kind}),
];

final _work = [
  PlanWorkRule.from({
    'state': 'Implementing',
    'from': ['Backlog'],
    'done': 'Review',
  }),
];

/// Each issue's group, by its id.
Map<String, String> _groupOf(List<StoryIssueGroup> groups) => {
  for (final group in groups)
    for (final row in group.rows) row.issue.id: group.key,
};

void main() {
  // Harm: an issue shown ready to start while it waits on open work in another story or in no story, or one held as
  // blocked by work that is finished or that the board does not hold.
  test('an open blocker anywhere on the board blocks, a closed or absent one gives the issue its own state', () {
    final groups = groupStoryIssues(
      issues: [
        _issue('A1', 'Backlog', blockedBy: ['B1']),
        _issue('A2', 'Backlog', blockedBy: ['N1']),
        _issue('A3', 'Implementing', blockedBy: ['B2']),
        _issue('A4', 'Backlog', blockedBy: ['GONE']),
      ],
      board: [_issue('B1', 'Implementing'), _issue('B2', 'Finished'), _issue('N1', 'Backlog')],
      states: _states,
      work: _work,
    );

    expect(_groupOf(groups), {
      'A1': 'blocked Blocked',
      'A2': 'blocked Blocked',
      'A3': 'working Implementing',
      'A4': 'waiting Backlog',
    }, reason: 'B1 and N1 are open, B2 is closed and GONE is not on the board');
    final blocked = groups.singleWhere((group) => group.kind == StoryGroupKind.blocked);
    expect(
      [for (final row in blocked.rows) row.blockers.map((each) => each.id).toList()],
      [
        ['B1'],
        ['N1'],
      ],
      reason: 'each blocked issue waits on its open blockers alone',
    );
  });

  // Harm: an issue in a state the workflow lacks vanishes from its story's page, review is read from a fixed state
  // name instead of the work rules, or a finished issue's old blocker pulls it out of its closed group.
  test('a misfit goes to Other, review follows the work rules, and every closed state folds', () {
    final issues = [
      _issue('A1', 'Parked'),
      _issue('A2', 'Review', blockedBy: ['B1']),
      _issue('A3', 'Finished', blockedBy: ['B1']),
      _issue('A4', 'Rejected'),
      _issue('A5', ''),
    ];
    final board = [_issue('B1', 'Backlog')];

    final groups = groupStoryIssues(issues: issues, board: board, states: _states, work: _work);
    expect(_groupOf(groups), {
      'A1': 'other Other',
      'A2': 'review Review',
      'A3': 'closed Finished',
      'A4': 'closed Rejected',
      'A5': 'other Other',
    }, reason: 'what waits for review or is closed is no longer left to build, so nothing blocks it');
    expect(
      [for (final group in groups) (group.kind, group.foldedByDefault)],
      [
        (StoryGroupKind.review, false),
        (StoryGroupKind.other, false),
        (StoryGroupKind.closed, true),
        (StoryGroupKind.closed, true),
      ],
      reason: 'the groups stand in their order and only the closed ones fold',
    );

    final noRules = groupStoryIssues(issues: issues, board: board, states: _states, work: const []);
    expect(
      _groupOf(noRules)['A2'],
      'blocked Blocked',
      reason: 'with no work rule ending in it, Review is an ordinary waiting state, left to build',
    );
  });

  // Harm: a story whose open work is not known reads as having nothing left, or one with nothing left as unknown.
  test('the head figures stay unknown without a count, and a count of nothing left stays 0', () {
    final unknown = StoryHeadFigures(null, _states, _work);
    expect(
      [unknown.left, unknown.total, unknown.minutes, unknown.sizes, unknown.bar],
      everyElement(isNull),
      reason: 'no count, so no figure',
    );

    final done = StoryHeadFigures(
      PlanOpenWork.read({
        'states': [
          {'name': 'Backlog', 'issues': 0},
          {'name': 'Review', 'issues': 1},
          {'name': 'Finished', 'issues': 2},
        ],
        'blocked': 0,
        'sizes': <Object>[],
        'unsized': 0,
        'left': 0,
        'minutes': null,
        'unestimated': 0,
        'review': 1,
      }),
      _states,
      _work,
    );
    expect((done.left, done.total), (0, 3), reason: 'nothing left of three');
    expect(done.sizes, isEmpty, reason: 'no open size');
    expect(done.bar, {
      StoryGroupKind.review: 1,
      StoryGroupKind.closed: 2,
    }, reason: 'the bar holds every counted issue by its group');
  });
}
