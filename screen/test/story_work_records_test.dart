import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: absent findings look like an explicit clean reading and enable approval.
  test('absent or malformed findings remain unreported', () {
    for (final reply in <Map<String, dynamic>>[
      {},
      {'findings': null},
      {'findings': 'none'},
      {
        'findings': [false],
      },
      {
        'findings': [
          {'severity': 'BLOCKING'},
        ],
      },
      {
        'findings': [
          {'text': 'An unclassified finding.'},
        ],
      },
    ]) {
      expect(PlanningIssueDetail.from({'issue': reply}).findingsReported, isFalse, reason: '$reply');
    }
    final clean = PlanningIssueDetail.from({
      'issue': {'findings': <Map<String, dynamic>>[]},
    });
    expect(clean.findingsReported, isTrue);
    expect(clean.findings, isEmpty);
  });

  // Harm: a blocking finding is hidden behind less urgent or future severity values.
  test('blocking findings lead without losing unknown severities or source order', () {
    final detail = PlanningIssueDetail.from({
      'issue': {
        'findings': [
          {'severity': 'FUTURE', 'text': 'a'},
          {'severity': 'BLOCKING', 'text': 'b'},
          {'severity': 'NOTE', 'text': 'c'},
          {'severity': 'BLOCKING', 'text': 'd'},
        ],
      },
    });
    expect(detail.findingsReported, isTrue);
    expect(detail.blockingFirst.map((each) => each.text), ['b', 'd', 'a', 'c']);
    expect(detail.blockingFirst.last.severity, 'NOTE');
  });

  // Harm: optional overrides erase daemon defaults or changed choices never reach the wire.
  test('start serializes changed choices and omits unspecified overrides', () {
    const request = StoryWorkRequest(
      project: 'demo',
      story: 'S1',
      to: 'Building',
      mode: 'AGENT',
      revision: 'r1',
      subAgents: [StoryWorkChoice(repository: 'repo', name: 'reader', choice: 'CHANGE', agent: 'agent', model: '')],
    );
    expect(request.toMap(), {'project': 'demo', 'story': 'S1', 'to': 'Building', 'mode': 'AGENT', 'revision': 'r1'});
  });
}
