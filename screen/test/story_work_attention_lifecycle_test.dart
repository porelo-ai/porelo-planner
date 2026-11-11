import 'dart:io';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _snapshot(int round, {String issue = 'item'}) => {
  'states': <Map<String, dynamic>>[],
  'phases': <Map<String, dynamic>>[],
  'revision': 'round-$round',
  'stories': [
    {
      'id': 'story',
      'title': 'Read this work',
      'work': {'atWork': false},
      'ended': null,
      'issues': [
        {'id': issue, 'repository': 'repo', 'readingStopped': true, 'rounds': round},
      ],
    },
  ],
};
void _desktop(WidgetTester tester) {
  tester.view.physicalSize = const Size(1280, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

PlanningAttention _row(PlanningBoard board) =>
    PlanningAttention(board, board.stories.single, issue: board.stories.single.issues.single);

class _ReadingBackend extends MockPlanningSource {
  _ReadingBackend();
  Completer<PlanningIssueDetail>? heldFindings;
  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) =>
      heldFindings?.future ?? super.getPlanningIssue(project, issue);
}

void main() {
  // Harm: a retained row crashes when opened after its provider was disposed.
  testWidgets('a retained reading row renders without attaching to its disposed source', (tester) async {
    _desktop(tester);
    final backend = MockPlanningSource()..boards['demo'] = _snapshot(3);
    final board = PlanningBoard(backend, 'demo');
    await settle(tester);
    final row = _row(board);
    backend.work.findings['item'] = [
      {'number': 1, 'severity': 'BLOCKING', 'text': 'Keep the key inside.'},
    ];
    board.dispose();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StoryWorkAttentionView(row: row, resolved: false, machine: 'the source machine'),
        ),
      ),
    );
    await settle(tester);
    expect(find.textContaining('the source machine'), findsOneWidget);
    expect(find.textContaining('item · repo'), findsOneWidget);
    expect(find.text('BLOCKING: Keep the key inside.'), findsOneWidget);
    for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK']) {
      final button = find.byKey(Key('reading-resolve item $choice'));
      expect(button, findsOneWidget);
      expect(tester.widget<OutlinedButton>(button).onPressed, isNull);
    }
    expect(find.text('The planning source is no longer watched.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await backend.end();
  });

  // Harm: a Rework comment acts on a disposed decision after the machine advances its reading round.
  testWidgets('a comment opened for an old round is not sent after its decision changes', (tester) async {
    _desktop(tester);
    final backend = MockPlanningSource()..boards['demo'] = _snapshot(3);
    final board = PlanningBoard(backend, 'demo');
    await settle(tester);
    final row = ValueNotifier(_row(board));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ValueListenableBuilder<PlanningAttention>(
            valueListenable: row,
            builder: (context, value, _) =>
                StoryWorkAttentionView(row: value, resolved: false, machine: 'the source machine'),
          ),
        ),
      ),
    );
    await settle(tester);
    await tester.tap(find.byKey(const Key('reading-resolve item REWORK')));
    await settle(tester);
    backend.push('demo', _snapshot(4));
    await settle(tester);
    row.value = _row(board);
    await settle(tester);
    await tester.enterText(find.byKey(const Key('reading-rework-comment')), 'Instructions for the old round.');
    await tester.tap(find.byKey(const Key('reading-rework-send')));
    await settle(tester);
    expect(backend.work.calls.where((call) => call.$1 == 'ResolveIssueReading'), isEmpty);
    expect(find.textContaining('reading 4 of'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    row.dispose();
    board.dispose();
    await backend.end();
  });
  // Harm: an unrelated board refresh clears a refusal and silently authorizes the old reading.
  testWidgets('same-round revision changes preserve refusal and require an explicit refresh', (tester) async {
    _desktop(tester);
    final backend = MockPlanningSource()..boards['demo'] = _snapshot(3);
    final board = PlanningBoard(backend, 'demo');
    await settle(tester);
    final row = ValueNotifier(_row(board));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ValueListenableBuilder<PlanningAttention>(
            valueListenable: row,
            builder: (context, value, _) =>
                StoryWorkAttentionView(row: value, resolved: false, machine: 'the source machine'),
          ),
        ),
      ),
    );
    await settle(tester);
    backend.work.refusal = 'The round is not ready.';
    await tester.tap(find.byKey(const Key('reading-resolve item REVIEW_ANYWAY')));
    await settle(tester);
    expect(find.text('The round is not ready.'), findsOneWidget);
    backend.work.refusal = null;
    backend.push('demo', _snapshot(3)..['revision'] = 'unrelated-change');
    await settle(tester);
    row.value = _row(board);
    await settle(tester);
    expect(find.text('The round is not ready.'), findsOneWidget);
    expect(
      tester.widget<OutlinedButton>(find.byKey(const Key('reading-resolve item REVIEW_ANYWAY'))).onPressed,
      isNull,
    );
    expect(find.byKey(const Key('reading-refresh item')), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    row.dispose();
    board.dispose();
    await backend.end();
  });
  // Harm: opening or refreshing a disconnected reading hides its last blocking findings.
  testWidgets('offline findings are read and remain visible during a failed refresh', (tester) async {
    _desktop(tester);
    final backend = _ReadingBackend()..boards['demo'] = _snapshot(3);
    backend.work.findings['item'] = [
      {'number': 1, 'severity': 'BLOCKING', 'text': 'Keep the key inside.'},
    ];
    final board = PlanningBoard(backend, 'demo');
    await settle(tester);
    backend.fail(const SocketException('The watch is disconnected.'));
    await settle(tester);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StoryWorkAttentionView(row: _row(board), resolved: false, machine: 'the source machine'),
        ),
      ),
    );
    await settle(tester);
    expect(find.text('BLOCKING: Keep the key inside.'), findsOneWidget);
    expect(tester.widget<OutlinedButton>(find.byKey(const Key('reading-resolve item REWORK'))).onPressed, isNull);
    backend.heldFindings = Completer<PlanningIssueDetail>();
    await tester.tap(find.byKey(const Key('reading-refresh item')));
    await tester.pump();
    expect(find.text('BLOCKING: Keep the key inside.'), findsOneWidget);
    backend.heldFindings!.completeError(
      mockRefusal('org.fuin.porelo.Tasks1.Refused', {'detail': 'The findings cannot be refreshed.'}),
    );
    await settle(tester);
    expect(find.text('BLOCKING: Keep the key inside.'), findsOneWidget);
    expect(find.text('The findings cannot be refreshed.'), findsOneWidget);
    expect(find.text('The board changed or is no longer live.'), findsOneWidget);
    backend.heldFindings = Completer<PlanningIssueDetail>();
    await tester.tap(find.byKey(const Key('reading-refresh item')));
    await tester.pump();
    backend.heldFindings!.complete(PlanningIssueDetail.from({}));
    await settle(tester);
    expect(find.text('BLOCKING: Keep the key inside.'), findsOneWidget);
    expect(find.byKey(const Key('reading-error item')), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    board.dispose();
    await backend.end();
  });

  // Harm: a row reused for another issue shows the first issue's findings and resolves the wrong issue.
  testWidgets('another issue in the same round loads its own findings', (tester) async {
    _desktop(tester);
    final backend = MockPlanningSource()..boards['demo'] = _snapshot(3);
    backend.work.findings['item'] = [
      {'number': 1, 'severity': 'BLOCKING', 'text': 'First issue finding.'},
    ];
    backend.work.findings['other'] = [
      {'number': 1, 'severity': 'NOTE', 'text': 'Other issue finding.'},
    ];
    final board = PlanningBoard(backend, 'demo');
    await settle(tester);
    final row = ValueNotifier(_row(board));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ValueListenableBuilder<PlanningAttention>(
            valueListenable: row,
            builder: (context, value, _) =>
                StoryWorkAttentionView(row: value, resolved: false, machine: 'the source machine'),
          ),
        ),
      ),
    );
    await settle(tester);
    expect(find.text('BLOCKING: First issue finding.'), findsOneWidget);
    backend.push('demo', _snapshot(3, issue: 'other')..['revision'] = 'other-issue');
    await settle(tester);
    row.value = _row(board);
    await settle(tester);
    expect(find.text('NOTE: Other issue finding.'), findsOneWidget);
    expect(find.text('BLOCKING: First issue finding.'), findsNothing);
    expect(
      tester.widget<OutlinedButton>(find.byKey(const Key('reading-resolve other REVIEW_ANYWAY'))).onPressed,
      isNotNull,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    row.dispose();
    board.dispose();
    await backend.end();
  });
}
