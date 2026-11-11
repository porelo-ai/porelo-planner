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
}
