import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// The agents a test's machine offers a start, and what it could not read.
class FakeAgents implements PlanningAgents {
  /// Offers [agents], two by default as a machine with two agents installed does.
  FakeAgents([this.agents = const [PlanningAgent('an-agent'), PlanningAgent('other-agent')]]);

  /// The agents offered.
  final List<PlanningAgent> agents;

  /// What could not be read, by agent, in the window's words.
  Map<String, String> failures = {};

  /// The sources asked, in order.
  final List<PlanningSource> asked = [];

  @override
  Future<PlanningAgentList> on(PlanningSource source) async {
    asked.add(source);
    return PlanningAgentList(agents, failures);
  }
}

/// A start form whose start is the stand-in's own and whose agents are [agents], two by default.
StoryWork planningStoryWork(
  PlanningBoard board,
  PlanStory story,
  String to, {
  Map<PlanningBoard, String>? machines,
  List<String> unavailableMachines = const [],
  PlanningAgents? agents,
  PlanningStartTask? startTask,
}) => StoryWork(
  board,
  story,
  to,
  startTask: startTask ?? mockStartTask,
  agentChoices: agents ?? FakeAgents(),
  machines: machines,
  unavailableMachines: unavailableMachines,
);

/// Lets a frame and its animations finish, as the window's scenarios settle.
Future<void> settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
  await tester.pump(const Duration(milliseconds: 350));
}

/// The window's place as a test holds it: where the screen stands, and every move it asked for.
class TestHost extends ChangeNotifier implements PlanningHost {
  PlanningPlace _place = const PlanningPlace();

  /// The places the screen went to, in order.
  final List<PlanningPlace> history = [];

  /// The links the screen opened.
  final List<Uri> opened = [];

  @override
  PlanningPlace get place => _place;

  @override
  void go(PlanningPlace place) {
    history.add(place);
    _place = place;
    notifyListeners();
  }

  @override
  Future<void> openLink(Uri address) async => opened.add(address);

  @override
  void openNeedsYou() {}
}

/// A window that opens no task and holds none back.
class NoTasks implements PlanningTaskChanges {
  /// Opens no task.
  const NoTasks();

  @override
  String? unavailable(PlanningBoard board, String task) => null;

  @override
  void open(PlanningBoard board, String task) {}

  @override
  Future<void> started(
    PlanningSource source,
    String project,
    String story,
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
  ) async {}
}

/// The planning screen over [boards], starting on the stand-in and choosing from [agents].
PlanningScreen planningView({required PlanningBoards boards, PlanningHost? host, PlanningAgents? agents}) =>
    PlanningScreen(
      boards: boards,
      host: host ?? TestHost(),
      startTask: mockStartTask,
      taskChanges: const NoTasks(),
      agents: agents ?? FakeAgents(),
    );
