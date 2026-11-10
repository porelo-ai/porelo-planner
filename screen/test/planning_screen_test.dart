import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

class _Host extends ChangeNotifier implements PlanningHost {
  PlanningPlace _place = const PlanningPlace();
  final List<PlanningPlace> history = [];

  @override
  PlanningPlace get place => _place;

  @override
  void go(PlanningPlace place) {
    history.add(place);
    _place = place;
    notifyListeners();
  }

  @override
  Future<void> openLink(Uri address) async {}

  @override
  void openNeedsYou() {}
}

class _Tasks implements PlanningTaskChanges {
  @override
  String? unavailable(PlanningBoard board, String task) => 'not known here';

  @override
  void open(PlanningBoard board, String task) {}

  @override
  Future<void> started(StoryWork work) async {}
}

class _Agents implements PlanningAgents {
  @override
  Future<PlanningAgentList> on(PlanningSource source) async => const PlanningAgentList([PlanningAgent('agent')]);
}

void main() {
  // Harm: opening a story bypasses the window's history, so Back and the leave question miss it.
  testWidgets('opening a story asks the window to go there, on the provider that supplied it', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 800)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final boards = PlanningBoards(FakePlanningClient([provider('local', FakePlanningSource(smallBoard()))]));
    addTearDown(boards.dispose);
    final host = _Host();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlanningScreen(
            boards: boards,
            host: host,
            startTask: (source, request) async => StoryWorkStarted.from({}),
            taskChanges: _Tasks(),
            agents: _Agents(),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('First story'), findsOneWidget);

    await tester.tap(find.byKey(const Key('planning-story S2')));
    await tester.pump();

    expect(host.history, [const PlanningPlace(project: 'demo', source: 'local', story: 'S2')]);
  });

  // Harm: a save the window lists in its palette runs although nothing is staged, or is missing once something is.
  test('the save action the window lists follows the staged moves', () async {
    final boards = PlanningBoards(FakePlanningClient([provider('local', FakePlanningSource(smallBoard()))]));
    addTearDown(boards.dispose);
    await pumpEventQueue();
    PlanningAction save() =>
        planningActions(boards, _Host()).singleWhere((action) => action.id == 'planning.save/demo');
    expect(save().unavailable, 'No planning moves are pending.');

    boards.selected!.move('S1', 'Review');

    expect(save().available, isTrue);
  });
}
