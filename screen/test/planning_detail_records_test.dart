import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: omitted or malformed detail fields masquerade as a clean reading or completed work.
  test('detail records distinguish absent reports from explicit empty reports', () {
    final missing = PlanIssueDetail.from({});
    expect(missing.issue, isNull);
    expect(missing.criteriaReported, isFalse);
    expect(missing.findingsReported, isFalse);
    expect(missing.subAgentsReported, isFalse);
    expect(PlanStoryDetail.from({'story': false}).story, isNull);
    expect(PlanIssueList.from({'issues': 'wrong'}).reported, isFalse);
    expect(PlanIssueList.from({'issues': <dynamic>[]}).reported, isTrue);
    final empty = PlanIssueDetail.from({
      'issue': {'whatMustBeTrue': '', 'findings': <dynamic>[], 'subAgents': <dynamic>[]},
    });
    expect(empty.criteriaReported, isTrue);
    expect(empty.findingsReported, isTrue);
    expect(empty.subAgentsReported, isTrue);
    expect(empty.findings, isEmpty);
    expect(PlanIssue.from({}).blockedByReported, isFalse);
    expect(PlanIssue.from({'blockedBy': <String>[]}).blockedByReported, isTrue);
  });

  // Harm: malformed entries are filtered into an apparently clean reading or empty work list.
  test('malformed list entries cannot confirm a complete empty report', () {
    final malformed = PlanIssueDetail.from({
      'issue': {
        'findings': [false],
        'whatMustBeTrue': [9],
        'subAgents': [false],
      },
    });
    expect(malformed.findingsReported, isFalse);
    expect(malformed.criteriaReported, isFalse);
    expect(malformed.subAgentsReported, isFalse);
    expect(
      PlanIssueList.from({
        'issues': [false],
      }).reported,
      isFalse,
    );
    expect(
      PlanStory.from({
        'issues': [false],
      }).issuesReported,
      isFalse,
    );
    final partial = PlanIssueDetail.from({
      'issue': {
        'findings': [
          false,
          {'number': 1, 'severity': 'BLOCKING', 'text': 'Still visible'},
        ],
      },
    });
    expect(partial.findingsReported, isFalse);
    expect(partial.findings.single.text, 'Still visible');
  });

  // Harm: malformed blockers are shown as confirmation that work is unblocked.
  test('blockers are reported only when every entry is a nonempty string', () {
    for (final value in <Object?>[
      null,
      false,
      [false],
      [''],
      ['  '],
      ['parent', false],
    ]) {
      expect(PlanIssue.from({'blockedBy': value}).blockedByReported, isFalse, reason: 'Malformed blockers: $value');
    }
    final empty = PlanIssue.from({'blockedBy': <String>[]});
    expect(empty.blockedByReported, isTrue);
    expect(empty.blockedBy, isEmpty);
    final valid = PlanIssue.from({
      'blockedBy': ['parent', 'other'],
    });
    expect(valid.blockedByReported, isTrue);
    expect(valid.blockedBy, ['parent', 'other']);
    expect(
      PlanIssueDetail.from({
        'issue': {
          'whatMustBeTrue': [''],
        },
      }).criteriaReported,
      isFalse,
    );
  });

  // Harm: findings lose their locations or an unknown severity crashes the issue screen.
  test('findings preserve numbers locations and unknown severity without invented values', () {
    final absent = PlanFinding.from({'number': '3', 'file': false, 'line': 3.5});
    expect(absent.number, isNull);
    expect(absent.file, isNull);
    expect(absent.line, isNull);
    expect(absent.severity, isEmpty);
    final finding = PlanFinding.from({
      'number': 4,
      'severity': 'LATER',
      'text': 'Check this',
      'file': 'lib/a.dart',
      'line': 0,
    });
    expect(finding.number, 4);
    expect(finding.severity, 'LATER');
    expect(finding.text, 'Check this');
    expect(finding.file, 'lib/a.dart');
    expect(finding.line, 0);
    expect(PlanFinding.from({'line': 7}).file, isNull);
    expect(PlanFinding.from({'file': 'lib/a.dart'}).line, isNull);
  });

  // Harm: empty optional strings render invalid locations, blockers or task links.
  test('empty optional locations and list entries remain absent', () {
    expect(PlanFinding.from({'file': '', 'line': 42}).file, isNull);
    expect(PlanFinding.from({'file': ''}).file, isNull);
    final issue = PlanIssue.from({
      'blockedBy': ['', '  ', 'parent'],
    });
    expect(issue.blockedBy, ['parent']);
    expect(issue.blockedByReported, isFalse);
    expect(
      PlanWork.from({
        'tasks': [
          false,
          {'task': 'task'},
        ],
      }).tasks.map((task) => task.task),
      ['task'],
    );
  });

  // Harm: a blocking finding is buried behind notes or readers lose the report order.
  test('blocking findings precede notes and unknown severities in stable report order', () {
    final reply = PlanIssueDetail.from({
      'issue': {
        'findings': [
          {'number': 5, 'severity': 'NOTE'},
          {'number': 9, 'severity': 'BLOCKING'},
          {'number': 3, 'severity': 'LATER'},
          {'number': 8, 'severity': 'BLOCKING'},
          {'number': 2, 'severity': 'NOTE'},
        ],
      },
    });
    expect(reply.orderedFindings.map((finding) => finding.number), [9, 8, 5, 2, 3]);
    expect(reply.findings.map((finding) => finding.number), [5, 9, 3, 8, 2]);
  });

  // Harm: issue ownership or source text is lost and navigation reaches the wrong item.
  test('standalone records preserve source text revisions ownership and sub-agent fields', () {
    final story = PlanStoryDetail.from({
      'story': {'id': 'S2', 'description': '# Source'},
      'revision': 'story-rev',
    });
    expect(story.story!.id, 'S2');
    expect(story.revision, 'story-rev');
    expect(story.story!.description, '# Source');
    final issue = PlanIssueDetail.from({
      'revision': 'issue-rev',
      'issue': {
        'id': 'I2',
        'repository': 'frontend',
        'whatMustBeTrue': 'First\nSecond',
        'subAgents': [
          {'name': 'Reader', 'role': 'READER', 'agent': 'Agent', 'model': 'Model', 'brief': 'Read only'},
        ],
      },
    });
    expect(issue.issue!.id, 'I2');
    expect(issue.issue!.repository, 'frontend');
    expect(issue.revision, 'issue-rev');
    expect(issue.whatMustBeTrue, ['First\nSecond']);
    final agent = issue.subAgents.single;
    expect(
      [agent.name, agent.role, agent.agent, agent.model, agent.brief],
      ['Reader', 'READER', 'Agent', 'Model', 'Read only'],
    );
    expect(PlanWorker.from({'model': false}).model, isEmpty);
  });
  // Harm: the stand-in overwrites the daemon fixture's reported or absent issue owner.
  test('the stand-in preserves an explicit issue owner and explicit absence', () {
    final fixture = <String, dynamic>{
      'stories': [
        {
          'id': 'S2',
          'issues': [
            {'id': 'I2', 'story': 'S3'},
            {'id': 'I3', 'story': null},
            {'id': 'I4'},
          ],
        },
      ],
    };
    expect(mockPlanningIssue(fixture, 'I2')!['story'], 'S3');
    expect(mockPlanningIssue(fixture, 'I3')!['story'], isNull);
    expect(mockPlanningIssue(fixture, 'I4')!.containsKey('story'), isFalse);
  });
  // Harm: move serialization changes the selected issue, target or revision confirmation.
  test('issue move records preserve the exact wire fields and confirmation', () {
    const move = PlanningIssueMove('sample', 'Review');
    expect(move.toMap(), {'issue': 'sample', 'to': 'Review'});
    expect(PlanningMoveResult.from({'revision': 'next', 'ignored': true}).revision, 'next');
    expect(PlanningMoveResult.from({}).revision, isEmpty);
    expect(PlanningMoveResult.from({'revision': false}).revision, isEmpty);
  });

  // Harm: a rejected batch partially changes the store or a stale revision overwrites newer work.
  test('the move stand-in is atomic and uses the exact refusal contracts', () {
    final board = <String, dynamic>{
      'revision': 'r1',
      'states': [
        {'name': 'Ready', 'kind': 'WAITING'},
        {'name': 'Working', 'kind': 'WORKING'},
        {'name': 'Done', 'kind': 'CLOSED'},
      ],
      'stories': [
        {
          'id': 'story',
          'issues': [
            {'id': 'first', 'state': 'Working'},
            {'id': 'second', 'state': 'Working'},
          ],
        },
      ],
    };
    expect(
      () => applyPlanningMove(board, {
        'moves': [
          {'issue': 'first', 'to': 'Done'},
        ],
        'revision': 'old',
      }),
      throwsA(
        isA<PlanningRefusal>().having((error) => error.name, 'name', 'StaleRevision').having(
          (error) => error.parameters,
          'parameters',
          {'revision': 'r1'},
        ),
      ),
    );
    expect(
      () => applyPlanningMove(board, {
        'moves': [
          {'issue': 'first', 'to': 'Done'},
          {'issue': 'second', 'to': 'Working'},
        ],
        'revision': 'r1',
      }),
      throwsA(
        isA<PlanningRefusal>().having((error) => error.name, 'name', 'EnteredOnlyByWork').having(
          (error) => error.parameters,
          'parameters',
          {'item': 'second', 'to': 'Working'},
        ),
      ),
    );
    expect(mockPlanningIssue(board, 'first')!['state'], 'Working');
    expect(
      () => applyPlanningMove(board, {
        'moves': [
          {'issue': 'first', 'to': 'unknown'},
        ],
        'revision': 'r1',
      }),
      throwsA(
        isA<PlanningRefusal>().having((error) => error.name, 'name', 'StateNotAllowed').having(
          (error) => error.parameters.keys.toSet(),
          'fields',
          {'item', 'from', 'to', 'reason'},
        ),
      ),
    );
    expect(
      () => applyPlanningMove(board, {
        'moves': [
          {'issue': 'missing', 'to': 'Done'},
        ],
        'revision': 'r1',
      }),
      throwsA(
        isA<PlanningRefusal>().having((error) => error.name, 'name', 'NoSuchIssue').having(
          (error) => error.parameters.keys.toSet(),
          'fields',
          {'issue'},
        ),
      ),
    );
    final moved = applyPlanningMove(board, {
      'moves': [
        {'issue': 'first', 'to': 'Done'},
      ],
      'revision': 'r1',
    });
    expect(mockPlanningIssue(moved, 'first')!['state'], 'Done');
    expect(mockPlanningIssue(moved, 'second')!['state'], 'Working');
    expect(mockPlanningIssue(board, 'first')!['state'], 'Working');
    expect(moved['revision'], isNot('r1'));
  });
}
