import 'package:flutter/widgets.dart';

import 'client.dart';
import 'planning_board.dart';
import 'story_work_order.dart';

/// Keeps Start on the provider and story that supplied these checked arguments.
typedef PlanningStartTask = Future<PlanningTaskStarted> Function(
  PlanningSource source,
  String project,
  String story,
  PlanningTaskOrder task,
);

/// Runtime identities come from Porelo's Start, never from a prepared order.
class PlanningTaskStarted {
  const PlanningTaskStarted({required this.task, required this.container});

  final String task;
  final String container;
}

/// What the screen shows of a run, and what the window does with it.
abstract interface class PlanningTaskChanges {
  /// Why the window cannot open [task] of [board]'s project now, or null when it can.
  String? unavailable(PlanningBoard board, String task);

  /// Opens [task] of [board]'s project in the window.
  void open(PlanningBoard board, String task);

  /// Keeps confirmed starts attached to their originating provider, including partial orders.
  Future<void> started(
    PlanningSource source,
    String project,
    String story,
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
  );

  /// Stops these runtime tasks on their originating source without reporting task events.
  ///
  /// Completion means the window has requested each stop. A task may arrive after cancellation when its Start
  /// was already in flight; the planner voided its offer, so it cannot accept any report for that task.
  Future<void> stop(PlanningSource source, String project, String story, List<PlanningTaskStarted> tasks);
}

/// An agent a start may choose, with what the window shows for it.
class PlanningAgent {
  /// Takes the agent's name and, when the window has them, what it shows in a choice, why it cannot work on the
  /// machine the agents were listed for, its models and its effort levels.
  const PlanningAgent(this.name, {this.choice, this.unready, this.models = const [], this.modelsWhy, this.efforts});

  /// The agent's name, as a start names it.
  final String name;

  /// What a choice shows for it, or null for its name alone.
  final Widget? choice;

  /// Why the agent cannot work on that machine, in the window's words, or null when it can or the window cannot say.
  final String? unready;

  /// The models the window knows for the agent there, to choose from; a model may still be typed.
  final List<PlanningModel> models;

  /// Why the window could not list the agent's models there, in its words, or null when it could.
  final String? modelsWhy;

  /// The effort levels the agent takes, or null when the window does not know them; empty when it takes none.
  final List<String>? efforts;
}

/// A model an agent may start with.
class PlanningModel {
  /// Takes the model's id, as a start names it, and the words a person knows it by.
  const PlanningModel(this.id, {this.label});

  /// The model's id, as a start names it.
  final String id;

  /// The model's readable name, or null for its id alone.
  final String? label;
}

/// One step of a wizard: its panel of choices, whether it lets the person go on, and its lines on the review.
class PlanningWizardStep {
  /// Takes the step's title, its panel, its check and its review lines; [shown] false passes it over, though its
  /// lines stay on the review.
  const PlanningWizardStep({
    required this.title,
    required this.panel,
    required this.check,
    required this.summary,
    this.words = '',
    this.shown = _always,
  });

  /// The step's title.
  final String title;

  /// What the step asks, in a sentence, or empty.
  final String words;

  /// The step's own fields; the wizard draws its frame around them.
  final WidgetBuilder panel;

  /// Why the person cannot go on from this step, or null when they can.
  final String? Function() check;

  /// The step's choices in words, one line each, as the review lists them.
  final List<String> Function() summary;

  /// Whether the step has anything to choose now.
  final bool Function() shown;
}

bool _always() => true;

/// How the window shows a wizard of the screen's steps with its own wizard frame.
abstract interface class PlanningWizards {
  /// Shows [steps] and a review with a Change per step, redrawn on each of [changes], and ends with the button
  /// [startLabel] names, read on each redraw, which runs [start] and closes when it answers true; answers whether it
  /// was started. Cancel is offered while [canCancel] answers true.
  Future<bool> show(
    BuildContext context, {
    required String title,
    required List<PlanningWizardStep> steps,
    required Listenable changes,
    required String Function() startLabel,
    required Future<bool> Function() start,
    required bool Function() canCancel,
  });
}

/// The agents a provider offers, and why any could not be asked.
class PlanningAgentList {
  /// Takes the agents and the failures, each named by what failed.
  const PlanningAgentList(this.agents, [this.failures = const {}]);

  /// The agents offered.
  final List<PlanningAgent> agents;

  /// What could not be asked, by name, in the window's words.
  final Map<String, String> failures;
}

/// The agents and models a start may choose. A model is typed as it is named; agents come from the window.
abstract interface class PlanningAgents {
  /// The agents [source]'s provider offers a start.
  Future<PlanningAgentList> on(PlanningSource source);
}

/// What a start from a Needs you row asks of the window, held by the row's board before the screen was ever shown.
class PlanningStartHooks {
  /// Takes how a start reaches the window, the agents it may choose, the machines it may run on and, when the window
  /// has one, the wizard that shows its steps.
  const PlanningStartHooks({
    required this.startTask,
    required this.agents,
    required this.machines,
    required this.unavailableMachines,
    this.wizards,
  });

  /// How a start of story work reaches the window.
  final PlanningStartTask startTask;

  /// The agents a start may choose.
  final PlanningAgents agents;

  /// The machines a start from a board may choose, each with the name the person knows it by, the board among them.
  final Map<PlanningBoard, String> Function(PlanningBoard board) machines;

  /// The machines that have not supplied a board's project, by name.
  final List<String> Function(PlanningBoard board) unavailableMachines;

  /// The window's wizard that shows Start work's steps, or null for the screen's own frame.
  final PlanningWizards? wizards;
}

/// Which list the planning screen shows of a project: its board, or its stories by what is left of them.
enum PlanningView {
  /// The board, its stories by state in columns.
  board,

  /// The stories by what is left to build of them.
  openWork,
}

/// Which stories the board shows by their open work; each value keeps the stories with at least one issue of it.
///
/// A state group gathers the workflow's states by what they mean, since a workflow names its states itself; a group
/// the workflow has no state for cannot be used, and the screen then falls back to no filter.
enum PlanningFilter {
  /// Stories with issues left to build.
  openWork,

  /// Stories with issues being built, in a working state of building.
  implementing,

  /// Stories with issues in analysis or estimation.
  analysis,

  /// Stories with issues an open issue blocks.
  blocked,

  /// Stories with issues waiting for the person's review, as the work rules define review.
  review,

  /// Stories with issues in the backlog.
  backlog,

  /// Stories with estimated issues.
  estimated,

  /// Stories with issues that are ideas.
  idea,
}

/// Where the screen stands: the board, a story, or one of its issues, on the provider that supplied it, with the
/// view and the board's filter it was reached from.
@immutable
class PlanningPlace {
  /// Takes the place; all absent is the board of the chosen project, unfiltered.
  const PlanningPlace({this.project, this.source, this.story, this.issue, this.view = PlanningView.board, this.filter});

  /// The project's name.
  final String? project;

  /// The provider's name supplying a story or issue.
  final String? source;

  /// The story opened, or null for the view.
  final String? story;

  /// The issue opened under [story], or null for the story itself.
  final String? issue;

  /// The view shown; on a story or an issue, the view it was opened from, to which the way back returns.
  final PlanningView view;

  /// The board's filter, or null to show every story; kept on a story or an issue for the way back.
  final PlanningFilter? filter;

  /// This place with the fields given changed; a nullable field is given as a function, so it can be cleared.
  PlanningPlace copyWith({
    ValueGetter<String?>? project,
    ValueGetter<String?>? source,
    ValueGetter<String?>? story,
    ValueGetter<String?>? issue,
    PlanningView? view,
    ValueGetter<PlanningFilter?>? filter,
  }) => PlanningPlace(
    project: project == null ? this.project : project(),
    source: source == null ? this.source : source(),
    story: story == null ? this.story : story(),
    issue: issue == null ? this.issue : issue(),
    view: view ?? this.view,
    filter: filter == null ? this.filter : filter(),
  );

  @override
  bool operator ==(Object other) =>
      other is PlanningPlace &&
      other.project == project &&
      other.source == source &&
      other.story == story &&
      other.issue == issue &&
      other.view == view &&
      other.filter == filter;

  @override
  int get hashCode => Object.hash(project, source, story, issue, view, filter);

  @override
  String toString() =>
      'PlanningPlace(project: $project, source: $source, story: $story, issue: $issue, view: ${view.name}, '
      'filter: ${filter?.name})';
}

/// What the window does for the screen: where it stands, how it moves and what it opens.
///
/// It notifies its listeners when [place] changes.
abstract interface class PlanningHost implements Listenable {
  /// Where the screen stands now.
  PlanningPlace get place;

  /// Moves the screen to [place], kept in the window's history.
  void go(PlanningPlace place);

  /// Opens a web address in the person's browser.
  Future<void> openLink(Uri address);

  /// Opens Needs you, where a stopped reading is decided.
  void openNeedsYou();
}

/// What a window may also do for the screen: put the person's forge token into the vault of the machine whose
/// planner refused the board for it.
///
/// Optional: the screen finds it by `host is PlanningForgeAccess` and offers nothing to a host without it.
abstract interface class PlanningForgeAccess {
  /// Puts the person's token for [address], a forge's https origin, into the vault of [provider], the machine whose
  /// planner refused with `NoForgeAccess`, asking the person for it where none is kept there.
  ///
  /// Answers whether a token now stands there; the screen reads the board again only after `true`.
  Future<bool> handOnForgeToken({required String provider, required String address});
}
