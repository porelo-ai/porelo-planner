import 'dart:async';

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'story_work_order.dart';

/// The planning service refused a call, with its own name for the refusal and what it sent with it.
///
/// A refusal is an answer, not a fault: `ItemChanged`, `NotYetBuilt` or a refused move each reach the screen
/// with their name intact, so the screen can say what the service said.
class PlanningRefusal implements Exception {
  /// Takes the refusal's short name, such as `ItemChanged`, and its parameters as sent.
  const PlanningRefusal(this.name, [this.parameters = const {}, this.description = '', this.notOffered = false]);

  /// The refusal's short name, such as `ItemChanged` or `NotYetBuilt`.
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

  /// The work of a task that ended while it still waited at Porelo's gate has landed.
  landed,
}

/// Refuses a report whose landings do not fit its event: only [PlanningTaskEvent.landed] carries them, at least one.
void checkLanded(PlanningTaskEvent event, List<PlanningLanding> landed) {
  if (event == PlanningTaskEvent.landed ? landed.isEmpty : landed.isNotEmpty) {
    throw ArgumentError.value(landed, 'landed', 'Only a landed report names where the work landed');
  }
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
    List<PlanningLanding> landed = const [],
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

/// A watch of one project's boards that tells its own reconnect, apart from any other watch of the same project.
///
/// [PlanningSource.watchStories] may answer one; a board follows [reconnecting] rather than
/// [PlanningReconnect.isReconnecting], which is true while any watch of the project reconnects.
class PlanningWatch extends StreamView<PlanningSnapshot> {
  /// Takes the watch's boards, whether this watch is recovering from a lost connection, and how far the planner's
  /// read of the board is where it tells it.
  PlanningWatch(
    super.stream,
    this.reconnecting, {
    ValueListenable<PlanningLoad?>? loading,
    this.tellsReads = false,
    ValueListenable<PlanningRefusal?>? readFailed,
  }) : loading = loading ?? ValueNotifier<PlanningLoad?>(null),
       readFailed = readFailed ?? ValueNotifier<PlanningRefusal?>(null);

  /// Why the provider's last read after the first board failed, while the board shown stands; null once a board came.
  final ValueListenable<PlanningRefusal?> readFailed;

  /// Whether the provider tells every whole read of the board on this watch, to its end, a Reload's included: its
  /// refresh then answers before the read, and the read's progress and end come only here.
  final bool tellsReads;

  /// Whether this watch is recovering from a lost connection and has not yet received its next board.
  final ValueListenable<bool> reconnecting;

  /// How far the planner's read of the board is while it reads, as it last told it; null once a board came, or where
  /// the planner tells nothing of its read.
  final ValueListenable<PlanningLoad?> loading;
}

/// How far the planner's read of a board is, as its stream tells it while it reads.
class PlanningLoad {
  /// Takes what the planner told; [since] and [readAt] are instants, [read] and [total] counts of items.
  const PlanningLoad({
    required this.since,
    this.lastRead = '',
    this.read,
    this.total,
    this.readAt,
    this.place,
    this.look = false,
    this.waitsFor,
  });

  /// Reads one `load` event's data; null where it is not a read under way.
  static PlanningLoad? from(Object? json) {
    if (json is! Map<String, dynamic> || json['state'] != 'reading') return null;
    final since = json['since'] is String ? DateTime.tryParse(json['since'] as String) : null;
    if (since == null) return null;
    int? count(Object? value) => value is int && value >= 0 ? value : null;
    final lastRead = json['lastRead'] is String ? (json['lastRead'] as String).trim() : '';
    return PlanningLoad(
      since: since,
      lastRead: lastRead.length > 200 ? '${lastRead.substring(0, 200)}…' : lastRead,
      read: count(json['read']),
      total: count(json['total']),
      readAt: json['readAt'] is String ? DateTime.tryParse(json['readAt'] as String) : null,
      place: switch (json['from']) {
        final String from when _placeName.hasMatch(from) => from,
        _ => null,
      },
      // A kind or a wait this window does not know is taken as a whole read that waits for nothing.
      look: json['kind'] == 'look',
      waitsFor: json['waitsFor'] == 'save' ? 'save' : null,
    );
  }

  /// A place's name as the planner gives it: a word or a few, never a path or an address.
  static final _placeName = RegExp(r'^[A-Za-z][A-Za-z0-9 .-]{0,39}$');

  /// When the read began.
  final DateTime since;

  /// What the planner read last, in its words; empty where it named nothing.
  final String lastRead;

  /// How many items it read so far, and how many it expects, where it said so.
  final int? read;
  final int? total;

  /// When it read the last item, where it said so.
  final DateTime? readAt;

  /// Where the planner reads from, in its words ("GitHub"), where it said so.
  final String? place;

  /// Whether the planner only looks at the board's item list for what changed, not reads the board whole.
  final bool look;

  /// What the read waits for now, `save` while a save of the planner holds the project; null while it reads.
  final String? waitsFor;

  /// The share of the read done, from 0 to 1, where the planner told how many items it expects; null where not yet.
  double? get done => switch ((read, total)) {
    (final read?, final total?) when total > 0 => (read / total).clamp(0, 1).toDouble(),
    _ => null,
  };

  /// The read in one sentence for a person: how many entries of how many, once the planner told the total.
  String get words {
    final what = look ? 'Looking for changes' : 'Loading the planning';
    final head = place == null ? what : '$what ${look ? 'on' : 'from'} $place';
    final wait = waitsFor == 'save' ? ', waiting for a save to finish' : '';
    return switch ((read, total)) {
      (final read?, final total?) when total > 0 => '$head: ${_grouped(read)} of ${_grouped(total)} entries$wait',
      _ => wait.isEmpty ? '$head…' : '$head$wait…',
    };
  }

  /// [value] with a comma between each three digits, as "1,384".
  static String _grouped(int value) => value.toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',');
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

  /// Has the provider fetch [project]'s repositories now instead of at its next poll; answers the revision after it,
  /// or null for a tracked project that holds no board yet, whose read goes on and whose board comes on the watch.
  Future<String?> refreshProject(String project);

  /// Answers what [moves] would do against the board as the planner holds it, the items they change with their
  /// versions and what they can do harm by, writing nothing.
  Future<PlanningMovePreview> previewMoves(String project, List<PlanningMove> moves);

  /// Saves [moves] in one atomic call, only as the preview answered by [digest] showed them, sending its targets back
  /// as [seen]; [confirm] lets a story stand where its issues do not fit, as that preview warned.
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    Map<String, String> seen, {
    required String digest,
    bool confirm = false,
  });

  /// Answers what [correction] of a warning would write against the board as the planner holds it and the items it
  /// changes with their versions, writing nothing. A Move is previewed by [previewMoves]
  /// instead, as any move of the issue.
  Future<PlanningCorrectionPreview> previewCorrection(String project, PlanningCorrection correction);

  /// Saves [correction] only as the preview answered by [digest] showed it, sending its targets back as [seen]; a
  /// correction writes no state, so it stops no run.
  Future<PlanningCorrectionSaved> saveCorrection(
    String project,
    PlanningCorrection correction,
    Map<String, String> seen, {
    required String digest,
  });

  /// Adds an idea as a new issue in state New of [repository], against the versions [seen]; [size] is one of
  /// `XS` to `XL`, or null when it is not known yet; [task] names the Porelo task that proposed it, null when a person
  /// did.
  Future<PlanningIssueAdded> createIssue(
    String project,
    String repository,
    String title,
    String mustBeTrue,
    String? size,
    Map<String, String> seen, {
    IssueWorker worker = IssueWorker.agent,
    String? task,
  });

  /// Sets who works [issue], against the versions [seen]; a sub-issue is refused, as it takes its parent's.
  Future<PlanningWorkerSaved> setWorker(String project, String issue, IssueWorker worker, Map<String, String> seen);

  /// Sets [story]'s priority, one of [planningPriorities], against the versions [seen]; saved as a move is, it
  /// answers the board's revision after the save, the same one when the story had that priority already.
  Future<String> setPriority(String project, String story, String priority, Map<String, String> seen);

  /// Sets the topic labels [item], a story when [story] holds and an issue otherwise, carries after the save, each of
  /// [planningTopicLabels], against the versions [seen]; a label of the person's own on it stays. It answers the
  /// board's revision after the save, the same one when the item carried those labels already.
  Future<String> setLabels(
    String project,
    String item,
    List<String> labels,
    Map<String, String> seen, {
    required bool story,
  });

  /// Reads one story and its source text.
  Future<PlanStoryDetail> getStory(String project, String story);

  /// Reads exactly the issues of one story.
  Future<PlanIssueList> listStoryIssues(String project, String story);

  /// Reads the board's issues in no story at one revision.
  Future<PlanIssueList> listIssues(String project);

  /// Replaces a story's whole issue list, preserving its supplied order, against the versions [seen].
  Future<String> setStoryIssues(String project, String story, List<String> issues, Map<String, String> seen);

  /// Keeps [directory], a path [planningDirectory] takes, as where the planner keeps local [project]'s stories and
  /// issues, or the default directory again for null, against the versions [seen], which name no target; answers
  /// where it keeps them now. A directory the planner cannot use is refused as `ItemsDirectoryRefused` in one of its
  /// fixed phrases, naming it in `parameters['directory']` where it follows the rule, and a project not kept locally
  /// as not offered.
  Future<PlanningTracker> setItemsDirectory(String project, String? directory, Map<String, String> seen);

  /// Adds an issue the board offers as made outside the project to the project, against the versions [seen];
  /// answers the board's revision after the save.
  Future<String> addUnlistedIssue(String project, String issue, Map<String, String> seen);

  /// Sends a save of the board's held saves that is resendable again, as its id [save] names it; answers the board's
  /// revision once it is written. A save that runs, was sent again or is gone is refused, as is one the planner
  /// does not resend.
  Future<String> resendSave(String project, String save);

  /// Drops a save of the board's held saves, as its id [save] names it, writing nothing; one that runs, was sent again
  /// or is gone is refused.
  Future<void> dropSave(String project, String save);

  /// Reads one issue and its last reader report.
  Future<PlanIssueDetail> getIssue(String project, String issue);

  /// Reads the proposed tasks and participants for a working state; with [issues], for those of the story's issues
  /// alone.
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues});

  /// Prepares a checked order without starting tasks or recording running work.
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request);

  /// Offers one DISCUSSION task over [project]'s repositories for [idea], the person's first words.
  ///
  /// The offer belongs to no story: the planner records no run for it and takes no task events for it. The window
  /// starts it with the offer as Start's caller key; the planner reads the reports of the task Porelo recorded with
  /// that key and saves the idea it reports, which the changes stream then shows like any save.
  Future<PlanningTaskOrder> discussIdea(String project, String idea);

  /// Reads current work and any ended outcome for a story.
  Future<StoryWorkStatus> getStoryWork(String project, String story);

  /// Stops the story's work without changing its planning state; with [run], only while that run stands.
  Future<StoryWorkStatus> stopStoryWork(String project, String story, {String? run});

  /// Reports a bound task and returns checked follow-up offers without starting them.
  Future<TaskEventResolution> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
    List<PlanningLanding> landed = const [],
  });

  /// Reads the issue's last findings.
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue);

  /// Sends a decision for one stopped reading round, bound to the versions [seen]; only a rework takes [rework], and
  /// without it the planner takes the Reworking rule's first mode and model.
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    Map<String, String> seen, {
    String? comment,
    PlanningRework? rework,
  });
}

/// How a rework runs: one of the Reworking rule's modes, and one of its agents with that agent's model.
@immutable
class PlanningRework {
  /// Takes the mode, the agent and its model.
  const PlanningRework(this.mode, this.agent, this.model);

  /// The mode, the agent and the agent's model.
  final String mode, agent, model;

  /// The three as the planner's reading request names them.
  Map<String, String> get wire => {'mode': mode, 'agent': agent, 'model': model};

  @override
  bool operator ==(Object other) =>
      other is PlanningRework && other.mode == mode && other.agent == agent && other.model == model;

  @override
  int get hashCode => Object.hash(mode, agent, model);
}

/// The priorities a story may have, the highest first, as the planner names them.
const planningPriorities = ['Highest', 'High', 'Medium', 'Low', 'Lowest'];

/// The topic labels every repository of a project carries, the only labels the planner sets on an item.
const planningTopicLabels = ['documentation', 'security', 'tests', 'build', 'dependencies', 'performance', 'usability'];

/// The topic label [label] is, or null when it is none: a forge matches a label's name in any case, so `Tests` is the
/// topic label `tests`.
String? planningTopicLabelOf(String label) {
  final folded = label.toLowerCase();
  return planningTopicLabels.contains(folded) ? folded : null;
}

/// Whether [error] says the provider does not offer the call at all.
bool planningUnsupported(Object error) => error is PlanningUnsupported || error is PlanningRefusal && error.notOffered;

/// Whether [error] is the provider's refusal named [name].
bool planningRefused(Object error, String name) => error is PlanningRefusal && error.name == name;
