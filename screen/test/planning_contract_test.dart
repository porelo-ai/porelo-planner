import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: a daemon outcome disappears because the client reads obsolete fields.
  test('task outcomes remain available on story work', () {
    final story = PlanStory.from({
      'work': {
        'atWork': false,
        'tasks': [
          {
            'task': 'story-repo',
            'ended': {
              'finished': false,
              'unpushed': 0,
              'why': 'Need access',
              'answer': 'Please grant access',
              'at': 'instant',
            },
          },
        ],
      },
    });
    expect(story.ended?.reason, contains('Need access'));
  });

  // Harm: nested acceptance text and findings are lost in the issue screen.
  test('issue details read the served nested record', () {
    final detail = PlanIssueDetail.from({
      'issue': {
        'id': 'item',
        'whatMustBeTrue': 'Preserve the data.',
        'findings': [
          {'number': 1, 'severity': 'BLOCKING', 'text': 'Data lost'},
        ],
        'subAgents': <Object>[],
      },
      'revision': 'r1',
    });
    expect(detail.whatMustBeTrue, ['Preserve the data.']);
    expect(detail.findings.single.text, 'Data lost');
  });

  // Harm: a story's tested day read from an open or rejected story, or a board that fails on a story without one.
  test('a story reads the day it was closed, and none when the planner sends none', () {
    final closed = PlanStory.from({'id': 'ST1', 'state': 'Finished', 'closedAt': '2026-03-04T05:06:07Z'});
    expect(closed.closedAt, DateTime.utc(2026, 3, 4, 5, 6, 7), reason: 'the tested day is read');
    expect(PlanStory.from({'id': 'ST1', 'state': 'Review', 'closedAt': null}).closedAt, isNull, reason: 'open');
    expect(PlanStory.from({'id': 'ST1', 'state': 'Review'}).closedAt, isNull, reason: 'none sent');
  });

  // Harm: a rejected story whose issue is held back is saved, yet the window says the planner did not confirm it.
  test('a story closed carries no issue held back, as the planner carries none', () {
    final states = [
      for (final (name, kind) in [
        ('Backlog', 'WAITING'),
        ('Review', 'WAITING'),
        ('Done', 'CLOSED'),
        ('Cancelled', 'CLOSED'),
      ])
        PlanState.from({'name': name, 'kind': kind}),
    ];
    final story = PlanStory.from({
      'id': 'ST1',
      'state': 'Backlog',
      'issues': [
        {'id': 'PL1', 'state': 'Backlog'},
        {
          'id': 'PL2',
          'state': 'Backlog',
          'warnings': [
            {'kind': 'UnknownWorker', 'text': 'Its worker is unknown.', 'holds': true},
          ],
        },
        {'id': 'PL3', 'state': 'Backlog', 'parent': 'PL2'},
        {'id': 'PL4', 'state': 'Review'},
      ],
    });
    expect(story.closedIssues(states, const [], 'Cancelled')!.map((issue) => issue.id), [
      'PL1',
      'PL4',
    ], reason: 'neither the held issue nor the one under it');
    expect(
      story.closedIssues(states, const [], 'Done')!.map((issue) => issue.id),
      isEmpty,
      reason: 'the first closed state completes, whatever the later one is named',
    );
  });

  // Harm: after a save, a reopened story still showing the day it was closed until the board is read again.
  test('a save keeps the closed day of a story it left alone and drops it from one it moved', () {
    final story = PlanStory.from({
      'id': 'ST1',
      'state': 'Finished',
      'closedAt': '2026-03-04T05:06:07Z',
      'issues': [
        {'id': 'PL1', 'state': 'Finished'},
      ],
    });
    expect(PlanStory.withMoves(story, {'PL9': 'Review'}, null).closedAt, isNotNull, reason: 'left alone');
    expect(PlanStory.withMoves(story, const {}, 'Backlog').closedAt, isNull, reason: 'reopened');
  });
}
