import 'package:flutter/material.dart';
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
            // One story at work, the rest waiting, so cards of Ready stand in view below it.
            'state': i == 0 ? 'Building' : ['Ready', 'Review'][i % 2],
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
    final ready = boards.selected!.cards('Ready').first.id;
    expect(find.byKey(Key('planning-card $ready')), findsOneWidget, reason: 'a card of Ready is in view');

    // A card built again is a new widget; one taken from the board's cache is the very same.
    Map<String, Widget> shown() => {
      for (final element in find.byWidgetPredicate((widget) => widget.key is ValueKey<String>).evaluate())
        if ((element.widget.key! as ValueKey<String>).value case final key when key.startsWith('planning-card '))
          key: element.widget,
    };
    final before = shown();
    expect(before, contains('planning-card $ready'), reason: 'the cards in view are read');
    tester.widget<IconButton>(find.byKey(const Key('planning-fold-state Building'))).onPressed!();
    await tester.pump();
    final after = shown();

    expect(find.byKey(const Key('planning-card Story0')), findsNothing, reason: 'the folded state shows no card');
    final rebuilt = [
      for (final entry in before.entries)
        if (after[entry.key] case final card? when !identical(card, entry.value)) entry.key,
    ];
    expect(rebuilt, isEmpty, reason: 'a card whose story did not change is not built again; ${rebuilt.length} were');
  });
}
