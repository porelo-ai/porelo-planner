import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'hook_log.dart';

/// How the window answers the screen's Start.
enum StartAnswer {
  /// Porelo started the task and named it and its container.
  started,

  /// The start failed, with the words the form holds.
  refused,

  /// No answer ever comes.
  never,
}

/// A provider as the window lists it, entered by hand.
@immutable
class ProviderEntry {
  /// Takes its name, whether it answers and its projects.
  const ProviderEntry(this.name, {this.connected = true, this.projects = const ['demo']});

  /// The provider's name.
  final String name;

  /// Whether it answers now.
  final bool connected;

  /// The projects it lists.
  final List<String> projects;
}

/// A task the screen's Start hook named and the window reported started, so events can be fired for it.
@immutable
class StartedTask {
  /// Takes where it was started, its order and what the Start answered.
  const StartedTask(this.source, this.project, this.story, this.order, this.started);

  /// The provider it was started on.
  final PlanningSource source;

  /// Its project and story.
  final String project, story;

  /// The planner's order.
  final PlanningTaskOrder order;

  /// The task and container the Start named.
  final PlanningTaskStarted started;
}

/// Everything the window hands the screen, set by hand, and every call the screen makes toward it, logged.
class WindowStandIn extends ChangeNotifier {
  /// Starts against the in-package fake, logging with [clock].
  WindowStandIn({DateTime Function()? clock, MockPlanningSource? fake})
    : log = HookLog(clock: clock),
      fake = fake ?? MockPlanningSource('fake') {
    _source = this.fake;
  }

  /// The log of every call between the screen and the window.
  final HookLog log;

  /// The in-package fake, which needs no process.
  final MockPlanningSource fake;

  late PlanningSource _source;

  /// The source every provider serves from: the fake or a planner started with `--mock`.
  PlanningSource get source => _source;

  /// Whether the source is the in-package fake, the only one whose board events can be fired by hand.
  bool get onFake => identical(_source, fake);

  set source(PlanningSource value) {
    // A planner's source holds an HTTP client of its own, closed with it.
    final old = _source;
    if (!identical(old, value) && old is HttpPlanningSource) old.close();
    _source = value;
    started.clear();
    notifyListeners();
  }

  List<ProviderEntry> _providers = const [ProviderEntry('mock')];

  /// The providers, with their projects and connection.
  List<ProviderEntry> get providers => _providers;
  set providers(List<ProviderEntry> value) {
    _providers = List.unmodifiable(value);
    notifyListeners();
  }

  String? _currentProvider = 'mock';

  /// The provider the window has chosen.
  String? get currentProvider => _currentProvider;
  set currentProvider(String? value) {
    _currentProvider = value;
    notifyListeners();
  }

  String? _chosenProject;

  /// The project chosen elsewhere in the window.
  String? get chosenProject => _chosenProject;
  set chosenProject(String? value) {
    _chosenProject = value;
    notifyListeners();
  }

  /// The agents a start may choose.
  List<String> agents = const ['an-agent', 'other-agent'];

  /// How the window answers a Start.
  StartAnswer startAnswer = StartAnswer.started;

  /// The words of a refused Start.
  String startRefusal = 'No machine can take the task now.';

  /// Why a task cannot be opened, or null when it can.
  String? openUnavailable;

  /// The tasks the window reported started, the newest last.
  final List<StartedTask> started = [];

  int _tasks = 0;

  /// Keeps tasks the window reported started, so events can be fired for them.
  void addStarted(List<StartedTask> tasks) {
    started.addAll(tasks);
    notifyListeners();
  }

  /// Tells the forms that a setting changed.
  void changed() => notifyListeners();

  /// The providers as the screen's client lists them, each serving from [source].
  List<PlanningProvider> get planningProviders => [
    for (final entry in _providers)
      PlanningProvider(name: entry.name, connected: entry.connected, projects: entry.projects, source: _source),
  ];

  /// The window's Start: logs the call and answers as [startAnswer] says.
  Future<PlanningTaskStarted> startTask(
    PlanningSource source,
    String project,
    String story,
    PlanningTaskOrder task,
  ) async {
    log.add('startTask', {'source': source.label, 'project': project, 'story': story, ...orderFields(task)});
    switch (startAnswer) {
      case StartAnswer.refused:
        throw PlanningRefusal('NotStartedByWindow', const {}, startRefusal);
      case StartAnswer.never:
        return Completer<PlanningTaskStarted>().future;
      case StartAnswer.started:
        // The fake binds the task to its story as the planner does once STARTED is reported.
        if (source case final MockPlanningSource fake) return fake.simulateStart(project, story, task);
        _tasks++;
        return PlanningTaskStarted(task: 'app-task-$_tasks', container: 'app-container-$_tasks');
    }
  }

  /// Fires a task's event as the window reports it, through the same client call.
  Future<void> fire(
    PlanningClient client,
    StartedTask task,
    PlanningTaskEvent event, {
    required String machine,
    PlanningTaskEnded? ended,
  }) async {
    log.add('window.taskEvent', {
      'project': task.project,
      'task': task.started.task,
      'event': event.name,
      'offer': task.order.offer,
      'container': task.started.container,
      'machine': machine,
      if (ended != null) 'ended': ended.toMap(),
    });
    try {
      await client.taskEvent(
        task.project,
        task.started.task,
        event,
        offer: task.order.offer ?? '',
        container: task.started.container,
        machine: machine,
        ended: ended,
      );
    } catch (error) {
      log.add('window.taskEvent refused', {'error': '$error'});
    }
  }
}

/// A planner order's fields, as the log shows them.
Map<String, Object?> orderFields(PlanningTaskOrder task) => {
  'offer': task.offer,
  'repository': task.repository,
  'issues': task.issues,
  'role': task.role,
  'agent': task.agent,
  'model': task.model,
  'mode': task.mode,
  'prompt': task.prompt,
};

/// The window's place, moved by the screen and by hand; the screen's moves are logged.
class RecordingHost extends ChangeNotifier implements PlanningHost {
  /// Logs into [log].
  RecordingHost(this.log);

  /// Where the calls are written.
  final HookLog log;

  PlanningPlace _place = const PlanningPlace();

  @override
  PlanningPlace get place => _place;

  @override
  void go(PlanningPlace place) {
    log.add('host.go', _placeFields(place));
    _place = place;
    notifyListeners();
  }

  /// Moves the screen as the window would, without a call of the screen's.
  void setPlace(PlanningPlace place) {
    _place = place;
    notifyListeners();
  }

  @override
  Future<void> openLink(Uri address) async => log.add('host.openLink', {'address': '$address'});

  @override
  void openNeedsYou() => log.add('host.openNeedsYou', const {});
}

Map<String, Object?> _placeFields(PlanningPlace place) => {
  'project': place.project,
  'source': place.source,
  'story': place.story,
  'issue': place.issue,
};

/// What the window does with a run: every call logged, the started tasks kept for events fired by hand.
class RecordingTasks implements PlanningTaskChanges {
  /// Logs into [window]'s log.
  RecordingTasks(this.window);

  /// The window whose log and started tasks this keeps.
  final WindowStandIn window;

  // Asked on every frame that shows a task, so it is answered from the form and not logged.
  @override
  String? unavailable(PlanningBoard board, String task) => window.openUnavailable;

  @override
  void open(PlanningBoard board, String task) => window.log.add('taskChanges.open', {'task': task});

  @override
  Future<void> started(
    PlanningSource source,
    String project,
    String story,
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
  ) async {
    window.log.add('taskChanges.started', {
      'source': source.label,
      'project': project,
      'story': story,
      'tasks': [
        for (final (order, started) in tasks)
          {'task': started.task, 'container': started.container, ...orderFields(order)},
      ],
    });
    window.addStarted([for (final (order, started) in tasks) StartedTask(source, project, story, order, started)]);
  }

  @override
  Future<void> stop(PlanningSource source, String project, String story, List<PlanningTaskStarted> tasks) async =>
      window.log.add('taskChanges.stop', {
        'source': source.label,
        'project': project,
        'story': story,
        'tasks': [
          for (final task in tasks) {'task': task.task, 'container': task.container},
        ],
      });
}

/// The agents a start may choose, as the form lists them; each ask is logged.
class RecordingAgents implements PlanningAgents {
  /// Answers from [window]'s form.
  RecordingAgents(this.window);

  /// The window whose agents and log this uses.
  final WindowStandIn window;

  @override
  Future<PlanningAgentList> on(PlanningSource source) async {
    window.log.add('agents.on', {'source': source.label});
    return PlanningAgentList([for (final agent in window.agents) PlanningAgent(agent)]);
  }
}
