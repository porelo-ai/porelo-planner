import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

class _SilentPlanning extends MockPlanningSource {
  final waiting = StreamController<PlanningSnapshot>();
  @override
  Stream<PlanningSnapshot> watchStories(String project) => waiting.stream;
}

void main() {
  // Harm: a silent watch leaves an endless animation, preventing a waiting page or its test from settling.
  testWidgets('a planning page waiting for its first answer has no endless animation', (tester) async {
    final backend = _SilentPlanning();
    final client = MockPlanningClient.of(backend, projects: ['checkout']);
    final boards = PlanningBoards(client);
    addTearDown(() async {
      boards.dispose();
      client.dispose();
      await backend.waiting.close();
      await backend.end();
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: planningView(boards: boards)),
      ),
    );
    await settle(tester);
    expect(find.text('Waiting for the machine’s planning answer.'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
  });
}
