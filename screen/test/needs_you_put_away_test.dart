import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// A board whose one story holds two raised issues; [reason] and [state] are those of the issue `Broken`.
Map<String, dynamic> _board({String reason = 'FAILED', String state = 'Building', bool raised = true}) => {
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Reading', 'kind': 'WORKING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Building', 'Reading'],
    },
  ],
  'stories': [
    {
      'id': 'Held',
      'title': 'A held story',
      'state': 'Building',
      'issues': [
        {
          'id': 'Broken',
          'repository': 'repo',
          'state': state,
          'needsYou': raised,
          'attention': raised ? reason : 'NONE',
        },
        {'id': 'Unpushed', 'repository': 'repo', 'state': 'Building', 'needsYou': true, 'attention': 'UNPUSHED'},
      ],
      'work': {
        'atWork': false,
        'tasks': <Map<String, dynamic>>[],
        'issues': [
          if (raised) {'issue': 'Broken', 'reason': reason, 'needsYou': true},
          {'issue': 'Unpushed', 'reason': 'UNPUSHED', 'needsYou': true},
        ],
      },
    },
  ],
  'revision': 'r1',
};

void main() {
  // Harm: a row put away never comes back when its issue needs the person anew, or comes back at once and buries
  // the rows that still wait.
  for (final (change, changed, again) in [
    ('reason', _board(reason: 'STOPPED'), null),
    ('state', _board(state: 'Reading'), null),
    ('settling', _board(raised: false), _board()),
  ]) {
    testWidgets('a row put away stays out while its reason and state stand and returns when its $change changes', (
      tester,
    ) async {
      final backend = MockPlanningSource()..boards['checkout'] = _board();
      final client = MockPlanningClient.of(backend, projects: ['checkout']);
      final boards = PlanningBoards(client);
      addTearDown(() async {
        boards.dispose();
        client.dispose();
        await backend.end();
      });
      await settle(tester);
      List<String?> raised() => [for (final row in boards.needsYou) row.attention.issue?.id];
      expect(raised(), containsAll(['Broken', 'Unpushed']), reason: 'both issues are raised at first');

      final row = boards.needsYou.firstWhere((row) => row.attention.issue?.id == 'Broken');
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: StoryWorkAttentionView(
                row: row.attention,
                resolved: false,
                machine: row.machine,
                onPutAway: (kept) => boards.putAway = {...boards.putAway, kept},
              ),
            ),
          ),
        ),
      );
      await settle(tester);
      await tester.tap(find.byKey(const Key('story-work-put-away Broken')));
      await settle(tester);
      expect(raised(), ['Unpushed'], reason: 'the row put away is left out, the other stays');

      backend.push('checkout', _board());
      await settle(tester);
      expect(raised(), ['Unpushed'], reason: 'an unchanged reason and state keep it away');

      backend.push('checkout', changed);
      await settle(tester);
      if (again != null) {
        // A row no longer raised is forgotten, so the same reason raised anew is seen.
        backend.push('checkout', again);
        await settle(tester);
      }
      expect(raised(), contains('Broken'), reason: 'a changed $change shows the row again');
    });
  }
}
