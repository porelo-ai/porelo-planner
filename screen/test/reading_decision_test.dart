import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  late MockPlanningSource backend;
  late PlanningBoard board;
  late ReadingDecision reading;
  late String issue;
  setUp(() async {
    backend = MockPlanningSource();
    final snapshot = mockPlanningBoard();
    final story = (snapshot['stories'] as List).cast<Map<String, dynamic>>().firstWhere(
      (story) => story['id'] == 'ST291',
    );
    final stopped = (story['issues'] as List).first as Map<String, dynamic>;
    issue = stopped['id'] as String;
    stopped.addAll({'state': 'Reading', 'rounds': 3, 'roundsMost': 3, 'readingStopped': true});
    backend.boards['checkout'] = snapshot;
    backend.work.findings[issue] = [
      {'number': 1, 'severity': 'NOTE', 'text': 'A small concern.'},
      {'number': 2, 'severity': 'BLOCKING', 'text': 'A credential would escape.'},
    ];
    board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
    final shown = board.stories.firstWhere((story) => story.id == 'ST291');
    reading = ReadingDecision(PlanningAttention(board, shown, issue: shown.issues.first));
    await reading.load();
  });
  tearDown(() async {
    reading.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a reading decision targets the wrong issue, round or revision.
  test('all three decisions preserve the exact issue round and revision', () async {
    backend.work.deferEcho = true;
    for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK']) {
      if (reading.submitted) {
        reading.dispose();
        final story = board.stories.firstWhere((story) => story.id == 'ST291');
        reading = ReadingDecision(PlanningAttention(board, story, issue: story.issues.first));
        await reading.load();
      }
      await reading.resolve(choice, comment: choice == 'REWORK' ? 'Keep the credential inside.' : null);
    }
    final calls = backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading').map((call) => call.$2).toList();
    expect(calls, [
      for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK'])
        {
          'project': 'checkout',
          'story': 'ST291',
          'issue': issue,
          'round': 3,
          'choice': choice,
          'revision': 'design-1',
          // ResolveIssueReading takes the story and a comment that is never omitted.
          'comment': choice == 'REWORK' ? 'Keep the credential inside.' : '',
        },
    ]);
  });

  // Harm: accepting a reading conceals its findings or sends a duplicate before confirmation.
  test('findings put blocking first and a decision waits for board confirmation', () async {
    backend.work.deferEcho = true;
    expect(reading.detail!.blockingFirst.map((finding) => finding.severity), ['BLOCKING', 'NOTE']);
    await reading.resolve('REVIEW_ANYWAY');
    await reading.resolve('REVIEW_ANYWAY');
    expect(backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading'), hasLength(1));
    expect(board.stories.firstWhere((story) => story.id == 'ST291').issues.first.readingStopped, isTrue);
    expect(reading.canResolve, isFalse);
  });

  // Harm: empty rework instructions are submitted or refusal detail is lost.
  test('rework without blocking findings requires text and a refusal remains actionable', () async {
    backend.work.findings[issue] = [
      {'number': 1, 'severity': 'NOTE', 'text': 'A small concern.'},
    ];
    await reading.load();
    await reading.resolve('REWORK', comment: '  ');
    expect(
      backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading'),
      isEmpty,
      reason: 'notes alone give a rework nothing to fix',
    );
    backend.work.refusal = 'The round changed on this machine.';
    await reading.resolve('REWORK', comment: 'Keep the credential inside.');
    expect(reading.problem, 'The round changed on this machine.');
    expect(reading.submitted, isFalse);
    expect(reading.canResolve, isFalse);
    backend.work.refusal = null;
    await reading.load();
    expect(reading.canResolve, isTrue);
  });

  // Harm: a rework the person sent without words is dropped, or runs in settings they did not choose.
  test('a rework with blocking findings needs no words and sends the chosen settings', () async {
    backend.work.deferEcho = true;
    await reading.resolve('REWORK', comment: '', rework: const PlanningRework('AGENT', 'claude', 'opus'));
    final sent = backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading').map((call) => call.$2);
    expect(sent, hasLength(1), reason: 'the blocking findings go with the rework');
    expect(
      [sent.single['mode'], sent.single['agent'], sent.single['model']],
      ['AGENT', 'claude', 'opus'],
      reason: 'the planner gets the settings the person chose',
    );
  });

  // Harm: a lost planning watch authorizes an action based on stale findings.
  test('a failed watch disables reading decisions', () async {
    backend.fail(const SocketException('Planning stream lost.'));
    await Future<void>.delayed(Duration.zero);
    expect(reading.canResolve, isFalse);
    await reading.resolve('REVIEW_ANYWAY');
    expect(backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading'), isEmpty);
  });
}
