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

  // Harm: a planner older than the given state's answer breaks the board, or a given state read as derived.
  test('a story reads its given state and misfits, and none from a planner that sends neither', () {
    final given = PlanStory.from({
      'id': 'ST1',
      'state': 'Review',
      'stateGiven': true,
      'misfits': ['PL1'],
    });
    expect(given.stateGiven, isTrue, reason: 'the given state is read');
    expect(given.misfits, ['PL1'], reason: 'the misfit is read');
    final older = PlanStory.from({'id': 'ST1', 'state': 'Review'});
    expect(older.stateGiven, isFalse, reason: 'an older planner says nothing given');
    expect(older.misfits, isNull, reason: 'and its misfits unknown, not none');
  });

  // Harm: after a save, a misfit shown with an issue's old state, or a given mark lost until the board is read again.
  test('a save keeps a given state, and its misfits only while nothing of the story moved', () {
    PlanStory story(List<Map<String, dynamic>> issues) => PlanStory.from({
      'id': 'ST1',
      'state': 'Review',
      'stateGiven': true,
      'misfits': issues.isEmpty ? <String>[] : ['PL1'],
      'issues': issues,
    });
    final issues = [
      {'id': 'PL1', 'state': 'Backlog'},
      {'id': 'PL2', 'state': 'Review'},
    ];
    final untouched = PlanStory.withMoves(story(issues), {'PL9': 'Review'}, null);
    expect(untouched.stateGiven, isTrue, reason: 'another story\'s move leaves the given state');
    expect(untouched.misfits, ['PL1'], reason: 'and its misfit');
    final moved = PlanStory.withMoves(story(issues), {'PL1': 'Review'}, null);
    expect(moved.stateGiven, isTrue, reason: 'an issue\'s move leaves the given state to the planner');
    expect(moved.misfits, isNull, reason: 'the misfit read before the move is unknown until the board is read');
    final derived = PlanStory.withMoves(
      PlanStory.from({'id': 'ST2', 'state': 'Backlog', 'issues': <Object>[]}),
      const {},
      'Review',
    );
    expect(derived.stateGiven, isTrue, reason: 'a story without issues saved to a destination states it');
  });
}
