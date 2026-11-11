import 'package:flutter/material.dart';
import 'package:flutter/widgets.dart' as widgets;
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// A board of a few hundred stories: rebuilding every card on each change took up to 200 ms a frame.
void main() {
  testWidgets('folding one state rebuilds no card of another state', (tester) async {
    tester.view
      ..physicalSize = const Size(1920, 1080)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = MockPlanningSource();
    backend.boards['checkout'] = {
      'states': [
        {'name': 'Ready', 'kind': 'WAITING'},
        {'name': 'Building', 'kind': 'WORKING'},
        {'name': 'Review', 'kind': 'WAITING'},
      ],
      'phases': [
        {
          'name': 'Work',
          'states': ['Ready', 'Building', 'Review'],
        },
      ],
      'stories': [
        for (var i = 0; i < 300; i++)
          {
            'id': 'Story$i',
            'title': 'Story number $i',
            'state': ['Ready', 'Building', 'Review'][i % 3],
            'issues': <Map<String, dynamic>>[],
          },
      ],
      'revision': 'initial',
    };
    final client = MockPlanningClient(
      () => [
        PlanningProvider(name: 'local', connected: true, projects: ['checkout'], source: backend),
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
    expect(find.byKey(const Key('planning-card Story0')), findsOneWidget, reason: 'the board is shown');

    final rebuilt = <String>[];
    var seen = 0;
    final before = widgets.debugOnRebuildDirtyWidget;
    widgets.debugOnRebuildDirtyWidget = (element, builtOnce) {
      seen++;
      if (element.widget case Draggable<String>(:final data?)) rebuilt.add(data);
    };
    addTearDown(() => widgets.debugOnRebuildDirtyWidget = before);
    // The column is taller than the view; its button is pressed as a click on it would.
    tester.widget<IconButton>(find.byKey(const Key('planning-fold-state Review'))).onPressed!();
    await tester.pump();
    widgets.debugOnRebuildDirtyWidget = before;

    expect(seen, isNonZero, reason: 'the fold rebuilds its column, so the count is read');
    final others = rebuilt.where((id) => int.parse(id.substring('Story'.length)) % 3 != 2).toList();
    expect(others, isEmpty, reason: 'a card whose story did not change is not built again; ${others.length} were');
  });
}
