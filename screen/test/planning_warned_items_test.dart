import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, String state) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': <String>[],
};

void main() {
  // Harm: a planner that serves a story whose state cannot be told, or an issue in a state the workflow lacks,
  // takes the whole board away from a screen built before those were served.
  testWidgets('a story without a state and an issue outside the workflow leave the board standing', (tester) async {
    tester.view
      ..physicalSize = const Size(1920, 2000)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = MockPlanningSource()
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
          {
            'id': 'S1',
            'title': 'No state told',
            'state': '',
            'stateGiven': false,
            'priority': 'High',
            'issues': [_issue('I1', 'Parked')],
          },
          {
            'id': 'S2',
            'title': 'One issue parked by hand',
            'state': 'Ready',
            'priority': 'High',
            'issues': [_issue('I2', 'Parked'), _issue('I3', 'Ready')],
          },
        ],
        'revision': 'r1',
      };
    final client = MockPlanningClient(
      () => [
        PlanningProvider(name: 'local', connected: true, projects: ['demo'], source: backend),
      ],
      currentProvider: () => 'local',
    );
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.end();
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);

    expect(tester.takeException(), isNull, reason: 'the board is built');
    expect(find.text('One issue parked by hand'), findsWidgets, reason: 'the story in a known state is shown');
  });
}
