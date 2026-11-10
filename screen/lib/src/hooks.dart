import 'package:flutter/widgets.dart';

import 'client.dart';
import 'models.dart';
import 'planning_board.dart';
import 'story_work.dart';

/// How a start of story work reaches the window: [source] is the provider the person chose, [request] every choice.
///
/// It answers with the start as the provider confirmed it; a refusal arrives as a [PlanningRefusal].
typedef PlanningStartTask = Future<StoryWorkStarted> Function(PlanningSource source, StoryWorkRequest request);

/// What the screen shows of a run, and what the window does with it.
abstract interface class PlanningTaskChanges {
  /// Why the window cannot open [task] of [board]'s project now, or null when it can.
  String? unavailable(PlanningBoard board, String task);

  /// Opens [task] of [board]'s project in the window.
  void open(PlanningBoard board, String task);

  /// Takes a start the provider confirmed, to show its run where the window shows runs.
  Future<void> started(StoryWork work);
}

/// An agent a start may choose, with what the window shows for it.
class PlanningAgent {
  /// Takes the agent's name and, when the window has one, what it shows in a choice.
  const PlanningAgent(this.name, {this.choice});

  /// The agent's name, as a start names it.
  final String name;

  /// What a choice shows for it, or null for its name alone.
  final Widget? choice;
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

/// Where the screen stands: the board, a story, or one of its issues, on the provider that supplied it.
@immutable
class PlanningPlace {
  /// Takes the place; all absent is the board of the chosen project.
  const PlanningPlace({this.project, this.source, this.story, this.issue});

  /// The project's name.
  final String? project;

  /// The provider's name supplying a story or issue.
  final String? source;

  /// The story opened, or null for the board.
  final String? story;

  /// The issue opened under [story], or null for the story itself.
  final String? issue;

  @override
  bool operator ==(Object other) =>
      other is PlanningPlace &&
      other.project == project &&
      other.source == source &&
      other.story == story &&
      other.issue == issue;

  @override
  int get hashCode => Object.hash(project, source, story, issue);

  @override
  String toString() => 'PlanningPlace(project: $project, source: $source, story: $story, issue: $issue)';
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
