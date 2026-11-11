import 'package:flutter/foundation.dart';

import 'models.dart';
import 'story_work_order.dart';

/// The planning service refused a call, with its own name for the refusal and what it sent with it.
///
/// A refusal is an answer, not a fault: `StaleRevision`, `NotYetBuilt` or a refused move each reach the screen
/// with their name intact, so the screen can say what the service said.
class PlanningRefusal implements Exception {
  /// Takes the refusal's short name, such as `StaleRevision`, and its parameters as sent.
  const PlanningRefusal(this.name, [this.parameters = const {}, this.description = '', this.notOffered = false]);

  /// The refusal's short name, such as `StaleRevision` or `NotYetBuilt`.
  final String name;

  /// Whatever the service sent with it.
  final Map<String, dynamic> parameters;

  /// The service's own full wording of the refusal, empty when it gave none.
  final String description;

  /// Whether the service says it has no such call at all, the signal to leave that one feature out.
  final bool notOffered;

  @override
  String toString() => description.isNotEmpty
      ? description
      : parameters.isEmpty
      ? name
      : '$name $parameters';
}

/// The planning service does not offer what was asked: an older service, or one without planning.
class PlanningUnsupported implements Exception {
  /// Takes the service's words for what it lacks.
  const PlanningUnsupported(this.words);

  /// What the service lacks, in words.
  final String words;

  @override
  String toString() => words;
}

/// One place that serves planning, with the projects it lists.
///
/// The window today knows several machines, each of which may plan the same project; a planning service of its own
/// is one provider.
class PlanningProvider {
  /// Takes the provider's stable name, whether it answers now, its projects and its source.
  const PlanningProvider({required this.name, required this.connected, required this.projects, required this.source});

  /// The provider's stable name, shown beside its answers.
  final String name;

  /// Whether it answers now.
  final bool connected;

  /// The projects it lists.
  final List<String> projects;

  /// Where its planning is read and written.
  final PlanningSource source;
}

/// How a task the window started for a story changed.
enum PlanningTaskEvent {
  /// The task started.
  started,

  /// The task ended by itself.
  ended,

  /// The task was stopped.
  stopped,
}

/// Where the planning screen reads and writes: the providers of planning and what the window tells them.
///
/// It notifies its listeners when a provider, its projects or its connection change.
abstract interface class PlanningClient implements Listenable {
  /// Every provider known now, connected or not.
  List<PlanningProvider> get providers;

  /// The provider the window has chosen, preferred whenever it offers planning.
  String? get currentProvider;

  /// The project the window has chosen elsewhere, preferred when no project was chosen here.
  String? get chosenProject;

  /// Reports a change of a task the window started for [project].
  Future<void> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
  });
}

/// Optional watch status for sources that reconnect without ending their board stream.
///
/// Listeners are notified when [isReconnecting] changes for any project. A lost watch stays reconnecting until
/// a complete board arrives, including at an unchanged revision, or until that watch ends or is canceled.
/// Sources without this interface continue to report failures and completion through [PlanningSource.watchStories].
abstract interface class PlanningReconnect implements Listenable {
  /// Whether a watch of [project] is recovering from a lost connection and has not yet received its next board.
  bool isReconnecting(String project);
}

/// One provider's planning calls, each answering with the provider's own records.
///
/// A refusal arrives as a [PlanningRefusal], a call the provider does not offer as a [PlanningUnsupported].
abstract interface class PlanningSource {
  /// The provider's name for a person, shown beside its answers.
  String get label;

  /// Watches whole-board replacements, including the first reply immediately.
  Stream<PlanningSnapshot> watchStories(String project);

  /// Reads a project's workflow.
  Future<PlanningSnapshot> planningWorkflow(String project);

  /// Reads a project's stories and revision.
  Future<PlanningSnapshot> stories(String project);

  /// Has the provider fetch [project]'s repositories now instead of at its next poll; answers the revision after it.
  Future<String> refreshProject(String project);

  /// Saves the entire reviewed batch in one atomic call.
  Future<PlanningMoveResult> saveMoves(String project, List<PlanningMove> moves, String revision);

  /// Adds an idea as a new issue in state Idea of [repository], against the board at [revision]; [size] is one of
  /// `XS` to `XL`, or null when it is not known yet.
  Future<PlanningIssueAdded> createIssue(
    String project,
    String repository,
    String title,
    String mustBeTrue,
    String? size,
    String revision,
  );

  /// Reads one story and its source text.
  Future<PlanStoryDetail> getStory(String project, String story);

  /// Reads exactly the issues of one story.
  Future<PlanIssueList> listStoryIssues(String project, String story);

  /// Reads one issue and its last reader report.
  Future<PlanIssueDetail> getIssue(String project, String issue);

  /// Reads the proposed tasks and participants for a working state.
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to);

  /// Prepares a checked order without starting tasks or recording running work.
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request);

  /// Offers one DISCUSSION task over [project]'s repositories for [idea], the person's first words.
  ///
  /// The offer belongs to no story: the planner records no run for it and takes no task events for it.
  Future<PlanningTaskOrder> discussIdea(String project, String idea);

  /// Reads current work and any ended outcome for a story.
  Future<StoryWorkStatus> getStoryWork(String project, String story);

  /// Stops the story's work without changing its planning state.
  Future<StoryWorkStatus> stopStoryWork(String project, String story);

  /// Reports a bound task and returns checked follow-up offers without starting them.
  Future<TaskEventResolution> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
  });

  /// Reads the issue's last findings.
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue);

  /// Sends a revision-bound decision for one stopped reading round.
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    String revision, {
    String? comment,
  });
}

/// Whether [error] says the provider does not offer the call at all.
bool planningUnsupported(Object error) => error is PlanningUnsupported || error is PlanningRefusal && error.notOffered;

/// Whether [error] is the provider's refusal named [name].
bool planningRefused(Object error, String name) => error is PlanningRefusal && error.name == name;
