/// The planning records as the planning service reports them; an absent field stays absent.
library;

import 'package:flutter/foundation.dart' show immutable;

import 'story_work_order.dart';
import 'work_reply.dart';

String _planText(Map<String, dynamic> map, String key) => map[key] is String ? map[key] as String : '';
List<String> _planStrings(Object? value) =>
    value is List ? value.whereType<String>().where((text) => text.trim().isNotEmpty).toList() : const [];
bool _planMapsReported(Object? value) => value is List && value.every((entry) => entry is Map<String, dynamic>);
bool _planStringsReported(Object? value) =>
    value is List && value.every((entry) => entry is String && entry.trim().isNotEmpty);
List<T> _planList<T>(Object? value, T Function(Map<String, dynamic>) read) =>
    value is List ? value.whereType<Map<String, dynamic>>().map(read).toList() : <T>[];

/// Removes directional and control characters only for display; wire records remain unchanged.
String planningShown(String text, {bool multiline = true}) => text.replaceAll(
  RegExp(
    multiline
        ? r'[\x00-\x08\x0b-\x1f\x7f-\x9f\u2028\u2029\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]'
        : r'[\x00-\x1f\x7f-\x9f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069\u2028\u2029]',
  ),
  '',
);

/// One state, with the machine's kind preserved even when unknown.
class PlanState {
  /// Reads the configured state and preserves unknown kinds for display.
  PlanState.from(Map<String, dynamic> map) : name = _planText(map, 'name'), kind = _planText(map, 'kind');

  /// The name reported by the daemon.
  final String name;

  /// The kind reported by the daemon.
  final String kind;
}

/// A configured column and the names of its states.
class PlanPhase {
  /// Reads a phase in its reported order.
  PlanPhase.from(Map<String, dynamic> map) : name = _planText(map, 'name'), states = _planStrings(map['states']);

  /// The column's name.
  final String name;

  /// Its states, in order.
  final List<String> states;
}

/// Work reported for a story, without inferring whether anyone is working.
class PlanWork {
  /// Reads the complete durable work record.
  PlanWork.from(Map<String, dynamic> map)
    : run = map['run'] is String ? map['run'] as String : null,
      _tasksReported = _planMapsReported(map['tasks']),
      tasks = _planList(map['tasks'], PlanWorkTask.from),
      coordinator = map['coordinator'] is String ? map['coordinator'] as String : null,
      since = _planText(map, 'since'),
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      repositories = _planList(map['repositories'], PlanRepositoryWork.from),
      comment = _planText(map, 'comment'),
      readingRounds = map['readingRounds'] is int ? map['readingRounds'] as int : null,
      issueReadingRounds = _planList(map['issueReadingRounds'], PlanIssueReadingRounds.from),
      issues = _planList(map['issues'], PlanWorkIssue.from);

  /// The run the planner drew when the work started, compared only for equality; null from a planner without it.
  final String? run;

  /// The tasks reported by the daemon.
  final List<PlanWorkTask> tasks;

  /// The coordinator reported by the daemon.
  final String? coordinator;

  /// The since reported by the daemon.
  final String since;

  /// The atWork reported by the daemon.
  final bool? atWork;

  /// The repositories reported by the daemon.
  final List<PlanRepositoryWork> repositories;

  /// The comment reported by the daemon.
  final String comment;

  /// The readingRounds reported by the daemon.
  final int? readingRounds;

  /// The issueReadingRounds reported by the daemon.
  final List<PlanIssueReadingRounds> issueReadingRounds;

  /// The issues reported by the daemon.
  final List<PlanWorkIssue> issues;

  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => tasks
      .map((task) => task.ended)
      .whereType<WorkEnded>()
      .where((end) => end.finished != true || (end.unpushed ?? 0) > 0 || end.declaredStop)
      .firstOrNull;

  /// The reported value presented to the window.
  bool get tasksReported => _tasksReported;

  /// The _tasksReported reported by the daemon.
  final bool _tasksReported;

  /// The reported value presented to the window.
  String get mode => tasks.firstOrNull?.mode ?? '';

  /// The reported value presented to the window.
  String get agent => tasks.firstOrNull?.agent ?? '';

  /// The reported value presented to the window.
  String get model => tasks.firstOrNull?.model ?? '';
}

/// One recorded runtime participant; identity is never inferred from its name.
class PlanWorkTask {
  /// Reads the fields reported by the daemon.
  PlanWorkTask.from(Map<String, dynamic> map)
    : task = _planText(map, 'task'),
      containerId = _planText(map, 'containerId'),
      machine = map['machine'] is String ? map['machine'] as String : null,
      repository = _planText(map, 'repository'),
      issues = _planStrings(map['issues']),
      state = _planText(map, 'state'),
      done = _planText(map, 'done'),
      mode = _planText(map, 'mode'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      role = _planText(map, 'role'),
      since = _planText(map, 'since'),
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      ended = map['ended'] is Map<String, dynamic> ? WorkEnded.from(map['ended'] as Map<String, dynamic>) : null;

  /// The task, containerId, repository, state, done, mode, agent, model, role, since reported by the daemon.
  final String task, containerId, repository, state, done, mode, agent, model, role, since;

  /// The machine name as reported, preserved verbatim.
  final String? machine;

  /// The issues reported by the daemon.
  final List<String> issues;

  /// The atWork reported by the daemon.
  final bool? atWork;

  /// The recorded outcome, absent while a task has no outcome.
  final WorkEnded? ended;
}

/// A saved per-issue reading allowance.
class PlanIssueReadingRounds {
  /// Reads the fields reported by the daemon.
  PlanIssueReadingRounds.from(Map<String, dynamic> map)
    : issue = _planText(map, 'issue'),
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null;

  /// The issue reported by the daemon.
  final String issue;

  /// The rounds reported by the daemon.
  final int? rounds;
}

/// A durable issue result, including the reason for its stop.
class PlanWorkIssue {
  /// Reads the fields reported by the daemon.
  PlanWorkIssue.from(Map<String, dynamic> map)
    : issue = _planText(map, 'issue'),
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null,
      roundsMost = validRound(map['roundsMost'], minimum: 1) ? map['roundsMost'] as int : null,
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      needsYou = map['needsYou'] is bool ? map['needsYou'] as bool : null,
      readingStopped = map['readingStopped'] is bool ? map['readingStopped'] as bool : null,
      findings = _planList(map['findings'], PlanFinding.from),
      reason = _planText(map, 'reason');

  /// The issue, reason reported by the daemon.
  final String issue, reason;

  /// The rounds, roundsMost reported by the daemon.
  final int? rounds, roundsMost;

  /// The atWork, needsYou, readingStopped reported by the daemon.
  final bool? atWork, needsYou, readingStopped;

  /// The findings reported by the daemon.
  final List<PlanFinding> findings;
}

/// Who works an issue: an agent through the tasks the planner starts, or a person by hand.
enum IssueWorker {
  /// An agent, through the tasks the planner starts.
  agent('AGENT'),

  /// A person, by hand.
  manual('MANUAL');

  const IssueWorker(this.wire);

  /// The planner's name for it.
  final String wire;

  /// The worker the planner names, or null for anything else.
  static IssueWorker? read(Object? value) => values.where((worker) => worker.wire == value).firstOrNull;
}

/// One issue belonging to a story.
class PlanIssue {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanIssue.withState(PlanIssue source, this.state)
    : id = source.id,
      repository = source.repository,
      title = source.title,
      size = source.size,
      blockedBy = source.blockedBy,
      blockedByReported = source.blockedByReported,
      link = source.link,
      parent = source.parent,
      rounds = source.rounds,
      roundsMost = source.roundsMost,
      readingStopped = source.readingStopped,
      atWork = source.atWork,
      needsYou = source.needsYou,
      noRunHere = source.noRunHere,
      attention = source.attention,
      worker = source.worker,
      workerMalformed = source.workerMalformed,
      findings = source.findings,
      whatMustBeTrue = source.whatMustBeTrue,
      subAgents = source.subAgents;

  /// Reads fields leniently.
  PlanIssue.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      repository = _planText(map, 'repository'),
      title = _planText(map, 'title'),
      state = _planText(map, 'state'),
      size = _planText(map, 'size'),
      blockedBy = _planStrings(map['blockedBy']),
      blockedByReported = _planStringsReported(map['blockedBy']),
      link = _planText(map, 'link'),
      parent = map['parent'] is String && (map['parent'] as String).isNotEmpty ? map['parent'] as String : null,
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null,
      roundsMost = validRound(map['roundsMost'], minimum: 1) ? map['roundsMost'] as int : null,
      readingStopped = map['readingStopped'] is bool ? map['readingStopped'] as bool : null,
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      needsYou = map['needsYou'] is bool ? map['needsYou'] as bool : null,
      noRunHere = map['noRunHere'] == true,
      attention = map['attention'] is String ? map['attention'] as String : '',
      worker = IssueWorker.read(map['worker']),
      workerMalformed = map.containsKey('worker') && IssueWorker.read(map['worker']) == null,
      findings = _planList(map['findings'], PlanFinding.from),
      whatMustBeTrue = map['whatMustBeTrue'] is String ? map['whatMustBeTrue'] as String : null,
      subAgents = map['subAgents'] is List ? _planList(map['subAgents'], PlanWorker.from) : null;

  /// The issue's identifier.
  final String id;

  /// Its repository.
  final String repository;

  /// Its title.
  final String title;

  /// Its reported state.
  final String state;

  /// Its size, empty when unsaid.
  final String size;

  /// The identifiers blocking it.
  final List<String> blockedBy;

  /// Whether blockers were actually reported.
  final bool blockedByReported;

  /// The atWork, needsYou reported by the daemon.
  final bool? atWork, needsYou;

  /// Whether an agent's issue stands in a working state with no run of the planner, so it is worked elsewhere.
  final bool noRunHere;

  /// Why the planner raised it, the run's reason; empty when unsaid and `NONE` when not raised.
  final String attention;

  /// Who works it, or null when the planner named no worker it describes.
  final IssueWorker? worker;

  /// Whether the planner named a worker that is neither `AGENT` nor `MANUAL`; an older planner names none at all.
  final bool workerMalformed;

  /// The findings reported by the daemon.
  final List<PlanFinding> findings;

  /// The whatMustBeTrue reported by the daemon.
  final String? whatMustBeTrue;

  /// The subAgents reported by the daemon.
  final List<PlanWorker>? subAgents;

  /// Its web link.
  final String link;

  /// The issue it is a sub-issue of, or null for a head issue.
  final String? parent;

  /// Whether a person moves it by hand, into and through the working states too.
  bool get byHand => worker == IssueWorker.manual;

  /// The reading round as reported, or null when unsaid.
  final int? rounds;

  /// The machine's reading round limit, or null when unsaid.
  final int? roundsMost;

  /// Whether the machine stopped this issue in Reading, or null when unsaid.
  final bool? readingStopped;
}

/// One story, whose state is always the machine's answer.
class PlanStory {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanStory.withMoves(PlanStory source, Map<String, String> moves, String? destination)
    : id = source.id,
      title = source.title,
      state = destination ?? source.state,
      // A story without issues saved to a destination states it, as the planner writes it.
      stateGiven = destination != null || source.stateGiven,
      // Misfits read before a move of the story or its issues are unknown until the board read after the save.
      misfits = destination != null || source.issues.any((issue) => moves.containsKey(issue.id))
          ? null
          : source.misfits,
      priority = source.priority,
      size = source.size,
      description = source.description,
      issues = [for (final issue in source.issues) PlanIssue.withState(issue, moves[issue.id] ?? issue.state)],
      issuesReported = source.issuesReported,
      link = source.link,
      work = source.work,
      workReported = source.workReported;

  /// Reads fields leniently and preserves unknown priorities.
  PlanStory.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      title = _planText(map, 'title'),
      state = _planText(map, 'state'),
      stateGiven = map['stateGiven'] == true,
      misfits = map['misfits'] is List ? _planStrings(map['misfits']) : null,
      priority = map['priority'] is String ? map['priority'] as String : null,
      size = _planText(map, 'size'),
      description = _planText(map, 'description'),
      issues = _planList(map['issues'], PlanIssue.from),
      issuesReported = _planMapsReported(map['issues']),
      link = _planText(map, 'link'),
      work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null,
      workReported = map.containsKey('work');

  /// Its identifier.
  final String id;

  /// Its title.
  final String title;

  /// Its reported state.
  final String state;

  /// Whether its state was given to it rather than derived from its issues; false when unsaid.
  final bool stateGiven;

  /// The ids of its issues that do not fit its given state, in its order; empty when they fit, null when unknown.
  final List<String>? misfits;

  /// Its reported priority, empty when unsaid.
  final String? priority;

  /// Its reported size, empty when unsaid or when it has none.
  final String size;

  /// Full story text from GetStory.
  final String description;

  /// Its issues, empty when unsaid.
  final List<PlanIssue> issues;

  /// Whether the daemon actually sent an issue list.
  final bool issuesReported;

  /// Its web link.
  final String link;

  /// Whether the watch supplied work status, including an explicitly absent work record.
  final bool workReported;

  /// Whether the watch supplied the ended outcome, including an explicitly cleared outcome.
  bool get endedReported => workReported;

  /// Its work, when reported.
  /// The work record returned by the daemon.
  final PlanWork? work;

  /// Why the story stopped before reaching its done state.
  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => work?.ended;

  /// The issues a move of this story changes, as the daemon chooses them: those in the earliest open state in
  /// workflow order, or in the earliest closed state when all are closed. Null when that cannot be told: the issues
  /// were not reported, or one stands in a state outside the workflow.
  List<PlanIssue>? leastAdvancedIssues(List<PlanState> states) {
    int place(PlanIssue issue) => states.indexWhere((state) => state.name == issue.state);
    if (!issuesReported || issues.any((issue) => place(issue) < 0)) return null;
    if (issues.isEmpty) return const [];
    final open = issues.where((issue) => states[place(issue)].kind != 'CLOSED').toList();
    final candidates = open.isEmpty ? issues : open;
    final earliest = candidates.map(place).reduce((a, b) => a < b ? a : b);
    return [
      for (final issue in candidates)
        if (place(issue) == earliest) issue,
    ];
  }

  /// The number in Review or Finished, when the list and every issue state were reported.
  int? get completedIssues => issuesReported && issues.every((issue) => issue.state.isNotEmpty)
      ? issues.where((issue) => issue.state == 'Review' || issue.state == 'Finished').length
      : null;
}

/// A workflow or a whole board reply; absent fields stay empty.
class PlanningSnapshot {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanningSnapshot.saved(
    PlanningSnapshot source,
    this.revision,
    Map<String, String> moves,
    Map<String, String> destinations,
  ) : states = source.states,
      phases = source.phases,
      work = source.work,
      faults = source.faults,
      repositories = source.repositories,
      repositoriesReported = source.repositoriesReported,
      statesReported = source.statesReported,
      phasesReported = source.phasesReported,
      storiesReported = source.storiesReported,
      stories = [for (final story in source.stories) PlanStory.withMoves(story, moves, destinations[story.id])];

  /// Reads a snapshot without deriving story states.
  PlanningSnapshot.from(Map<String, dynamic> map)
    : work = _planList(map['work'], PlanWorkRule.from),
      faults = _planStrings(map['faults']),
      repositories = _planStrings(map['repositories']),
      repositoriesReported = _planStringsReported(map['repositories']),
      states = _planList(map['states'], PlanState.from),
      phases = _planList(map['phases'], PlanPhase.from),
      stories = _planList(map['stories'], PlanStory.from),
      revision = _planText(map, 'revision'),
      statesReported = _planMapsReported(map['states']),
      phasesReported = _planMapsReported(map['phases']),
      storiesReported = _planMapsReported(map['stories']);

  /// Combines the separate workflow and story reads without deriving any states.
  PlanningSnapshot.fromParts(PlanningSnapshot workflow, PlanningSnapshot board)
    : work = workflow.work,
      faults = workflow.faults,
      repositories = board.repositoriesReported ? board.repositories : workflow.repositories,
      repositoriesReported = board.repositoriesReported || workflow.repositoriesReported,
      states = workflow.states,
      phases = workflow.phases,
      stories = board.stories,
      revision = board.revision,
      statesReported = workflow.statesReported,
      phasesReported = workflow.phasesReported,
      storiesReported = board.storiesReported;

  /// The work reported by the daemon.
  final List<PlanWorkRule> work;

  /// The faults reported by the daemon.
  final List<String> faults;

  /// The project's repositories, its own first, as a whole board names them; empty when none were reported.
  final List<String> repositories;

  /// Whether the planner sent the repository list, which a streamed whole board must carry.
  final bool repositoriesReported;

  /// Whether the daemon sent the state list.
  final bool statesReported;

  /// Whether the daemon sent the phase list.
  final bool phasesReported;

  /// Whether the daemon sent the story list.
  final bool storiesReported;

  /// Whether this is a complete whole-board reply.
  bool get complete => statesReported && phasesReported && storiesReported;

  /// The workflow's state order.
  final List<PlanState> states;

  /// The workflow's phase order.
  final List<PlanPhase> phases;

  /// The complete story list when present.
  final List<PlanStory> stories;

  /// The daemon's opaque revision.
  final String revision;
}

/// One destination in an atomic planning save.
sealed class PlanningMove {
  /// Allows story and issue destinations in the same reviewed batch.
  const PlanningMove();

  /// The work identifier.
  String get id;

  /// The requested state.
  String get to;

  /// The wire fields identifying the kind of work.
  Map<String, dynamic> toMap();
}

/// One story destination in an atomic board save.
class PlanningStoryMove extends PlanningMove {
  /// Keeps the daemon's story identifier and destination.
  const PlanningStoryMove(this.story, this.to);

  /// The story to move.
  final String story;

  /// The requested state.
  @override
  /// The to reported by the daemon.
  final String to;

  @override
  /// The reported value presented to the window.
  String get id => story;

  /// The wire fields.
  @override
  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {'story': story, 'to': to};
}

/// One issue destination sent as part of an atomic issue move.
class PlanningIssueMove extends PlanningMove {
  /// Keeps the daemon's issue identifier and destination.
  const PlanningIssueMove(this.issue, this.to);

  /// The issue to move.
  final String issue;

  /// The destination state.
  @override
  /// The to reported by the daemon.
  final String to;

  @override
  /// The reported value presented to the window.
  String get id => issue;

  /// The wire fields.
  @override
  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {'issue': issue, 'to': to};
}

/// The daemon's confirmation, without inventing a missing result or revision.
class PlanningMoveResult {
  /// Reads the fields reported by the daemon.
  PlanningMoveResult.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      moved = _planStrings(map['moved']),
      complete = _planStringsReported(map['moved']),
      stopped = _planList(map['stopped'], PlanningStoppedRun.from);

  /// Each run a confirmed save stopped, whose tasks the window ends; empty when it stopped none.
  final List<PlanningStoppedRun> stopped;

  /// The revision reported by the daemon.
  final String revision;

  /// The moved reported by the daemon.
  final List<String> moved;

  /// The complete reported by the daemon.
  final bool complete;
}

/// A run a confirmed save of moves stopped, with each task at work the window must end.
class PlanningStoppedRun {
  /// Reads one entry of a save's answer.
  PlanningStoppedRun.from(Map<String, dynamic> map)
    : story = _planText(map, 'story'),
      run = _planText(map, 'run'),
      tasks = [
        if (map['tasks'] case final List<dynamic> tasks)
          for (final task in tasks.whereType<Map<String, dynamic>>())
            (task: _planText(task, 'task'), container: _planText(task, 'containerId')),
      ];

  /// The run's story.
  final String story;

  /// The run's id.
  final String run;

  /// Each task at work, with the container it runs in.
  final List<({String task, String container})> tasks;
}

/// An issue a move carries, or one a warning names, with its state now and after the move.
class PlanningMovedIssue {
  /// Takes what the planner answered.
  const PlanningMovedIssue(this.id, this.repository, this.from, this.to);

  /// Reads one entry of a preview.
  PlanningMovedIssue.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      repository = _planText(map, 'repository'),
      from = _planText(map, 'from'),
      to = _planText(map, 'to');

  /// The issue's id.
  final String id;

  /// The repository that holds it.
  final String repository;

  /// Its state now.
  final String from;

  /// Its state after the move.
  final String to;
}

/// One harm a move can do, which the person confirms or cancels before it is saved.
class PlanningMoveWarning {
  /// Takes a warning's parts; [local] marks one the window adds of its own knowledge, which no digest covers.
  const PlanningMoveWarning(
    this.kind,
    this.item, {
    this.from = '',
    this.to = '',
    this.run,
    this.tasks = const [],
    this.offers = const [],
    this.issues = const [],
    this.skipped = const [],
    this.local = false,
  });

  /// Reads one warning of a preview.
  PlanningMoveWarning.from(Map<String, dynamic> map)
    : kind = _planText(map, 'kind'),
      item = _planText(map, 'item'),
      from = _planText(map, 'from'),
      to = _planText(map, 'to'),
      run = map['run'] is String ? map['run'] as String : null,
      tasks = _planStrings(map['tasks']),
      offers = _planStrings(map['offers']),
      issues = [
        if (map['issues'] case final List<dynamic> issues)
          for (final issue in issues.whereType<Map<String, dynamic>>()) PlanningMovedIssue.from(issue),
      ],
      skipped = _planStrings(map['skipped']),
      local = false;

  /// Running agent tasks a stop ends before the move.
  static const runningWork = 'RUNNING_WORK';

  /// Issues a story's move carries along from a working state or another repository.
  static const issuesAlong = 'ISSUES_ALONG';

  /// A move forward past waiting states.
  static const skipsStates = 'SKIPS_STATES';

  /// A story that would stand where its issues do not fit.
  static const storyMismatch = 'STORY_MISMATCH';

  /// The kind, one of the constants above; another is shown as it is named.
  final String kind;

  /// The story or issue it is about.
  final String item;

  /// The item's state now.
  final String from;

  /// The state the move sets.
  final String to;

  /// The run whose tasks [runningWork] names, or null.
  final String? run;

  /// The tasks at work a stop ends.
  final List<String> tasks;

  /// The offers not yet started a stop voids.
  final List<String> offers;

  /// The issues it names.
  final List<PlanningMovedIssue> issues;

  /// The waiting states a move forward passes.
  final List<String> skipped;

  /// Whether the window added it of its own knowledge.
  final bool local;
}

/// What a move would do, answered before it is saved, with the digest its save sends back.
class PlanningMovePreview {
  /// Takes a preview's parts.
  const PlanningMovePreview({
    required this.revision,
    required this.digest,
    this.issues = const [],
    this.warnings = const [],
  });

  /// Reads a preview as the planner answers it.
  PlanningMovePreview.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      digest = _planText(map, 'digest'),
      issues = [
        if (map['issues'] case final List<dynamic> issues)
          for (final issue in issues.whereType<Map<String, dynamic>>()) PlanningMovedIssue.from(issue),
      ],
      warnings = [
        if (map['warnings'] case final List<dynamic> warnings)
          for (final warning in warnings.whereType<Map<String, dynamic>>()) PlanningMoveWarning.from(warning),
      ];

  /// The revision the preview was read at.
  final String revision;

  /// What the save sends back, so it saves only what was shown.
  final String digest;

  /// Every issue the move carries.
  final List<PlanningMovedIssue> issues;

  /// What the move can do harm by; empty when it is saved without asking.
  final List<PlanningMoveWarning> warnings;

  /// A copy with [more] warnings the window adds of its own knowledge.
  PlanningMovePreview withWarnings(List<PlanningMoveWarning> more) => more.isEmpty
      ? this
      : PlanningMovePreview(revision: revision, digest: digest, issues: issues, warnings: [...warnings, ...more]);
}

/// A new issue the planner wrote: its id and the board's revision after the save.
class PlanningIssueAdded {
  /// Takes what the planner answered.
  const PlanningIssueAdded({required this.id, required this.revision});

  /// The id the planner gave it.
  final String id;

  /// The board's revision after the save.
  final String revision;
}

/// What the planner answered a saved worker.
class PlanningWorkerSaved {
  /// Takes what the planner answered.
  const PlanningWorkerSaved({required this.revision});

  /// The board's revision after the save.
  final String revision;
}

/// Whether an issue's durable [reason] asks for a person: `NONE` does not, nor `MANUAL`, an issue a person works.
bool planReasonNeedsAttention(String reason) => reason.isNotEmpty && reason != 'NONE' && reason != 'MANUAL';

/// One finding from the reader's last report, preserving unknown severities.
class PlanFinding {
  /// Reads optional locations without manufacturing a file or line.
  PlanFinding.from(Map<String, dynamic> map)
    : number = map['number'] is int ? map['number'] as int : null,
      severity = _planText(map, 'severity'),
      text = _planText(map, 'text'),
      file = map['file'] is String && (map['file'] as String).isNotEmpty ? map['file'] as String : null,
      line = map['line'] is int ? map['line'] as int : null;

  /// The reported number, absent when malformed or unsaid.
  final int? number;

  /// BLOCKING, NOTE, an unknown value, or empty when unsaid.
  final String severity;

  /// The reader's words.
  final String text;

  /// The file, if given.
  final String? file;

  /// The line, if given.
  final int? line;
}

/// A sub-agent reported for an issue.
class PlanWorker {
  /// Preserves each field independently.
  PlanWorker.from(Map<String, dynamic> map)
    : name = _planText(map, 'name'),
      role = _planText(map, 'role'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      brief = _planText(map, 'brief'),
      effort = map['effort'] is String ? map['effort'] as String : null,
      chosenBy = map['chosenBy'] is String ? map['chosenBy'] as String : null;

  /// The effort proposed, null for none.
  final String? effort;

  /// Where the rule's choice of agent, model and effort came from, as the planner says it; null when unsaid.
  final String? chosenBy;

  /// Its name.
  final String name;

  /// Its role.
  final String role;

  /// Its agent.
  final String agent;

  /// Its model.
  final String model;

  /// Its brief.
  final String brief;
}

/// The standalone story and its source text.
class PlanStoryDetail {
  /// Reads the fields reported by the daemon.
  PlanStoryDetail.from(Map<String, dynamic> map)
    : story = map['story'] is Map<String, dynamic> ? PlanStory.from(map['story'] as Map<String, dynamic>) : null,
      revision = _planText(map, 'revision');

  /// The full story returned by the daemon.
  final PlanStory? story;

  /// The revision reported by the daemon.
  final String revision;
}

/// A separately read issue list; an omitted list is never an empty list.
class PlanIssueList {
  /// Reads the list and its presence.
  PlanIssueList.from(Map<String, dynamic> map)
    : issues = _planList(map['issues'], PlanIssue.from),
      reported = _planMapsReported(map['issues']),
      supplied = map['issues'] != null,
      revision = _planText(map, 'revision');

  /// The revision reported by the daemon.
  final String revision;

  /// The issues supplied.
  final List<PlanIssue> issues;

  /// Whether the daemon supplied a complete list.
  final bool reported;

  /// Whether any issue-list payload was supplied.
  final bool supplied;
}

/// The standalone issue, its sub-issues, acceptance criteria, last findings and sub-agents.
class PlanIssueDetail {
  /// Reads additive fields leniently without inventing absent reports.
  factory PlanIssueDetail.from(Map<String, dynamic> map) => PlanIssueDetail._(
    map['issue'] is Map<String, dynamic> ? map['issue'] as Map<String, dynamic> : <String, dynamic>{},
    _planText(map, 'revision'),
  );
  PlanIssueDetail._(Map<String, dynamic> map, this.revision)
    : issue = map.isEmpty ? null : PlanIssue.from(map),
      whatMustBeTrue = map['whatMustBeTrue'] is String ? [map['whatMustBeTrue'] as String] : [],
      criteriaReported = map['whatMustBeTrue'] is String,
      criteriaSupplied = map['whatMustBeTrue'] != null,
      findings = _planList(map['findings'], PlanFinding.from),
      findingsReported = _planMapsReported(map['findings']),
      findingsSupplied = map['findings'] != null,
      subAgents = _planList(map['subAgents'], PlanWorker.from),
      subAgentsReported = _planMapsReported(map['subAgents']),
      subAgentsSupplied = map['subAgents'] != null,
      subIssues = _planStrings(map['subIssues']),
      subIssuesReported = _planStringsReported(map['subIssues']),
      text = _planText(map, 'text'),
      textReported = map['text'] is String;

  /// The revision reported by the daemon.
  final String revision;

  /// The issue's text, empty when its file is gone.
  final String text;

  /// Whether the planner sent the text, an empty one included.
  final bool textReported;

  /// The issue when supplied.
  /// The issue record returned by the daemon.
  final PlanIssue? issue;

  /// Its acceptance criteria.
  final List<String> whatMustBeTrue;

  /// Whether acceptance criteria were supplied completely.
  final bool criteriaReported;

  /// Whether any acceptance criteria payload was supplied.
  final bool criteriaSupplied;

  /// The reader's last findings, in the reported order.
  final List<PlanFinding> findings;

  /// Whether the findings report is complete.
  final bool findingsReported;

  /// Whether any findings payload was supplied.
  final bool findingsSupplied;

  /// The reported sub-agents.
  final List<PlanWorker> subAgents;

  /// Whether the sub-agent list is complete.
  final bool subAgentsReported;

  /// Whether any sub-agent payload was supplied.
  final bool subAgentsSupplied;

  /// The sub-issue identifiers from the standalone issue read, in reported order.
  final List<String> subIssues;

  /// Whether the planner supplied every sub-issue identifier, including an explicitly empty list.
  final bool subIssuesReported;

  /// BLOCKING first, NOTE next, then unknown severities; report order within each group.
  List<PlanFinding> get orderedFindings => [
    ...findings.where((finding) => finding.severity == 'BLOCKING'),
    ...findings.where((finding) => finding.severity == 'NOTE'),
    ...findings.where((finding) => !{'BLOCKING', 'NOTE'}.contains(finding.severity)),
  ];
}

/// The machine's reason for an ended story run.
class WorkEnded {
  /// Reads the fields reported by the daemon.
  WorkEnded.from(Map<String, dynamic> map)
    : at = _planText(map, 'at'),
      finished = map['finished'] is bool ? map['finished'] as bool : null,
      unpushed = map['unpushed'] is int ? map['unpushed'] as int : null,
      why = _planText(map, 'why'),
      answer = _planText(map, 'answer'),
      outcome = map['outcome'] is String ? map['outcome'] as String : null;

  /// The original end record, without mapping the agent’s result.
  final String at, why, answer;
  final bool? finished;
  final int? unpushed;

  /// The outcome the task declared, absent when it declared none.
  final String? outcome;

  /// Whether the task declared its work blocked or failed, which no finished run overrides.
  bool get declaredStop => outcome == 'BLOCKED' || outcome == 'FAILED';

  /// The reported value presented to the window.
  String get reason => [why, answer].where((text) => text.isNotEmpty).join('\n');
}

/// One proposed participant, including the reader.
class PlanAssignment {
  /// Reads the fields reported by the daemon.
  PlanAssignment.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      issue = _planText(map, 'issue'),
      name = _planText(map, 'name'),
      kind = _planText(map, 'kind'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      workingState = _planText(map, 'workingState'),
      brief = _planText(map, 'brief'),
      choice = _planText(map, 'choice'),
      effort = map['effort'] is String ? map['effort'] as String : null,
      chosenBy = map['chosenBy'] is String ? map['chosenBy'] as String : null;

  /// The effort proposed, null for none.
  final String? effort;

  /// Where the rule's choice of agent, model and effort came from, as the planner says it; null when unsaid.
  final String? chosenBy;

  /// The id, issue, name, role, agent, model, workingState, brief, choice reported by the daemon.
  final String id, issue, name, kind, agent, model, workingState, brief, choice;

  /// The assignment kind, presented beside worker roles.
  String get role => kind;

  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {
    'id': id,
    'issue': issue,
    'name': name,
    'kind': kind,
    'agent': agent,
    'model': model,
    'workingState': workingState,
    'brief': brief,
    'choice': choice,
    'effort': ?effort,
  };
}

/// A repository's task and its proposed participants.
class PlanRepositoryWork {
  /// Reads the fields reported by the daemon.
  PlanRepositoryWork.from(Map<String, dynamic> map)
    : repository = _planText(map, 'repository'),
      issues = _planStrings(map['issues']),
      main = PlanWorker.from(map['main'] is Map<String, dynamic> ? map['main'] as Map<String, dynamic> : {}),
      subAgents = _planList(map['subAgents'], PlanAssignment.from);

  /// The repository reported by the daemon.
  final String repository;

  /// The issues reported by the daemon.
  final List<String> issues;

  /// The required main worker for this repository.
  final PlanWorker main;

  /// The subAgents reported by the daemon.
  final List<PlanAssignment> subAgents;

  /// The reported value presented to the window.
  String get agent => main.agent;

  /// The reported value presented to the window.
  String get model => main.model;

  /// The reported value presented to the window.
  String get task => main.name;

  /// The reported value presented to the window.
  String get workOrder => main.brief;

  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {
    'repository': repository,
    'issues': issues,
    'main': {
      'name': main.name,
      'role': main.role,
      'agent': main.agent,
      'model': main.model,
      'brief': main.brief,
      'effort': ?main.effort,
    },
    'subAgents': subAgents.map((sub) => sub.toMap()).toList(),
  };
}

/// An issue whose reading limit can be overridden at start.
class StoryWorkIssue {
  /// Reads the machine’s reported fields.
  StoryWorkIssue.from(Map<String, dynamic> map) : id = _planText(map, 'id'), repository = _planText(map, 'repository');

  /// The issue identifier supplied by the machine.
  final String id;

  /// The repository name supplied by the machine.
  final String repository;
}

/// The complete proposal for a start; missing limits are never invented.
class StoryWorkProposal {
  /// Reads the fields reported by the daemon.
  StoryWorkProposal.from(Map<String, dynamic> map)
    : modes = _planStrings(map['modes']),
      repositories = _planList(map['repositories'], PlanRepositoryWork.from),
      coordinator = _planText(map, 'coordinator'),
      comment = _planText(map, 'comment'),
      readingRounds = map['readingRounds'] is int ? map['readingRounds'] as int : null,
      issueReadingRounds = _planList(map['issueReadingRounds'], PlanIssueReadingRounds.from),
      revision = _planText(map, 'revision'),
      manual = _planStrings(map['manual']);

  /// The modes reported by the daemon.
  final List<String> modes;

  /// The repositories reported by the daemon.
  final List<PlanRepositoryWork> repositories;

  /// The coordinator, comment, revision reported by the daemon.
  final String coordinator, comment, revision;

  /// The readingRounds reported by the daemon.
  final int? readingRounds;

  /// The issueReadingRounds reported by the daemon.
  final List<PlanIssueReadingRounds> issueReadingRounds;

  /// The story's open issues a person works, which no task of the proposal names; empty from an older planner.
  final List<String> manual;

  /// The proposed issue identities grouped by repository.
  List<StoryWorkIssue> get issues => [
    for (final repo in repositories)
      for (final issue in repo.issues) StoryWorkIssue.from({'id': issue, 'repository': repo.repository}),
  ];
}

/// The daemon's words describing one way of working.

/// A proposed coordinator and whether it is started or told about the story.

/// The main agent and model chosen for one repository.
class StoryWorkMainAgent {
  /// Keeps the repository identity and explicit main-agent choice.
  const StoryWorkMainAgent({required this.repository, required this.agent, required this.model, this.effort});

  /// The repository the choice applies to.
  final String repository;

  /// The chosen agent identifier.
  final String agent;

  /// The chosen model identifier.
  final String model;

  /// The chosen effort, null for none.
  final String? effort;
}

/// The explicit choice for one proposed sub-agent.
class StoryWorkChoice {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkChoice({
    required this.repository,
    required this.name,
    required this.choice,
    this.agent,
    this.model,
    this.effort,
  });

  /// The repository name supplied by the machine.
  final String repository;

  /// The proposed participant’s name within its repository.
  final String name;

  /// The explicit KEEP, CHANGE or DROP choice.
  final String choice;

  /// The agent identifier named by the machine or selected for a replacement.
  final String? agent;

  /// The model identifier, including an explicitly empty default.
  final String? model;

  /// The effort chosen for a replacement, null for none.
  final String? effort;
}

/// A revision-bound start decision.
class StoryWorkRequest {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkRequest({
    required this.project,
    required this.story,
    required this.to,
    required this.mode,
    required this.revision,
    required this.subAgents,
    this.repositories = const [],
    this.proposedRepositories = const [],
    this.comment,
    this.readingRounds,
    this.issueReadingRounds = const {},
    this.issues,
    this.replaces,
    this.passOver,
  });

  /// The story's issues the work is for, or null for the story's own work.
  final List<String>? issues;

  /// The run this Start replaces, as the board showed it, or null where the board showed none.
  final String? replaces;

  /// The reason of one issue this Start answers with a new run, or null for a Start from the story.
  final PlanningPassOver? passOver;

  /// The project name sent to the machine.
  final String project;

  /// The story identifier reported by the board.
  final String story;

  /// The target working state named by the workflow.
  final String to;

  /// The explicitly selected wire mode.
  final String mode;

  /// The opaque revision used to bind this decision.
  final String revision;

  /// The proposed participants, or the explicit choices sent at start.
  final List<StoryWorkChoice> subAgents;

  /// The chosen main agent and model for every proposed repository.
  final List<StoryWorkMainAgent> repositories;

  /// The proposedRepositories reported by the daemon.
  final List<PlanRepositoryWork> proposedRepositories;

  /// The explanation for changed or dropped participants.
  final String? comment;

  /// The proposed or selected overall reading limit, null when unsaid.
  final int? readingRounds;

  /// Explicit issue limits, omitted on the wire when there are no overrides.
  final Map<String, int> issueReadingRounds;

  Map<String, dynamic> _selectedRepository(PlanRepositoryWork repo) {
    final main = repositories.where((each) => each.repository == repo.repository).firstOrNull;
    final worker = {
      ...(repo.toMap()['main'] as Map<String, dynamic>),
      if (main != null) 'agent': main.agent,
      if (main != null) 'model': main.model,
    };
    // The effort chosen is sent back as chosen; none is no effort, never the proposal's.
    if (main != null) {
      worker.remove('effort');
      if (main.effort != null) worker['effort'] = main.effort;
    }
    return {
      ...repo.toMap(),
      'main': worker,
      'subAgents': [for (final sub in repo.subAgents) _selectedAssignment(repo.repository, sub)],
    };
  }

  Map<String, dynamic> _selectedAssignment(String repository, PlanAssignment sub) {
    final choice = subAgents.where((each) => each.repository == repository && each.name == sub.id).firstOrNull;
    final selected = {
      ...sub.toMap(),
      'choice': sub.role != 'BUILDER' ? 'DROP' : choice?.choice ?? sub.choice,
      if (choice?.choice == 'CHANGE') 'agent': choice!.agent,
      if (choice?.choice == 'CHANGE') 'model': choice!.model,
    };
    if (choice?.choice == 'CHANGE') {
      selected.remove('effort');
      if (choice!.effort != null) selected['effort'] = choice.effort;
    }
    return selected;
  }

  /// Serializes the exact fields sent to the daemon, omitting absent overrides.
  Map<String, dynamic> toMap() => {
    'project': project,
    'story': story,
    'to': to,
    'mode': mode,
    'revision': revision,
    if (proposedRepositories.isNotEmpty)
      'repositories': [for (final repo in proposedRepositories) _selectedRepository(repo)],
    'comment': ?comment,
    if (readingRounds != null) 'readingRounds': readingRounds,
    if (issueReadingRounds.isNotEmpty)
      'issueReadingRounds': [
        for (final each in issueReadingRounds.entries) {'issue': each.key, 'rounds': each.value},
      ],
    'issues': ?issues,
    'replaces': ?replaces,
    if (passOver case final passOver?) 'passOver': passOver.toMap(),
  };
}

/// The reason of one issue a Start from its row answers, as the row showed it.
@immutable
class PlanningPassOver {
  /// Takes the issue, its reason, the board's revision and the run the row showed.
  const PlanningPassOver({required this.issue, required this.reason, required this.revision, required this.run});

  /// The reasons a row's Start may pass over; a blocked task said it cannot go on unless something changes.
  static const reasons = {'NEXT_STEP_UNAVAILABLE', 'SAVE_REFUSED', 'STOPPED', 'FAILED'};

  /// The issue whose row it is.
  final String issue;

  /// The reason the row showed.
  final String reason;

  /// The board's revision the row showed.
  final String revision;

  /// The run the row showed.
  final String run;

  /// The fields sent to the planner.
  Map<String, dynamic> toMap() => {'issue': issue, 'reason': reason, 'revision': revision, 'run': run};

  @override
  bool operator ==(Object other) =>
      other is PlanningPassOver &&
      other.issue == issue &&
      other.reason == reason &&
      other.revision == revision &&
      other.run == run;

  @override
  int get hashCode => Object.hash(issue, reason, revision, run);
}

/// The work and any ended outcome returned by GetStoryWork.
class StoryWorkStatus {
  /// Reads the fields reported by the daemon.
  StoryWorkStatus.from(Map<String, dynamic> map)
    : work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null;

  /// The work record returned by the daemon.
  final PlanWork? work;

  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => work?.ended;
}

/// The findings returned by GetIssue, separate from the compact board summary.
class PlanningIssueDetail {
  /// Reads the machine’s reported fields.
  factory PlanningIssueDetail.from(Map<String, dynamic> map) => PlanningIssueDetail._(
    map['issue'] is Map<String, dynamic> ? map['issue'] as Map<String, dynamic> : {},
    _planText(map, 'revision'),
  );
  PlanningIssueDetail._(Map<String, dynamic> map, this.revision)
    : issue = PlanIssue.from(map),
      findings = _planList(map['findings'], PlanFinding.from),
      findingsReported = reportedFindings(map['findings']);

  /// The exact revision and issue whose findings were read.
  final String revision;
  final PlanIssue issue;

  /// Whether the reply supplied a complete, correctly shaped findings list.
  final bool findingsReported;

  /// The last findings returned by the machine.
  final List<PlanFinding> findings;

  /// Findings in reported order within blocking and remaining severities.
  List<PlanFinding> get blockingFirst => [
    ...findings.where((each) => each.severity == 'BLOCKING'),
    ...findings.where((each) => each.severity != 'BLOCKING'),
  ];
}

/// Signed work settings for a working state.
class PlanWorkRule {
  /// Reads the fields reported by the daemon.
  PlanWorkRule.from(Map<String, dynamic> map)
    : state = _planText(map, 'state'),
      from = _planStrings(map['from']),
      done = _planText(map, 'done'),
      then = map['then'] is String ? map['then'] as String : null,
      blocked = map['blocked'] is String ? map['blocked'] as String : null,
      per = _planText(map, 'per'),
      modes = _planStrings(map['modes']),
      order = _planText(map, 'order'),
      models = {
        if (map['models'] case final Map<String, dynamic> models)
          for (final entry in models.entries)
            if (entry.value case final String model) entry.key: model,
      };

  /// The state, done, per, order reported by the daemon.
  final String state, done, per, order;

  /// The then, blocked reported by the daemon.
  final String? then, blocked;

  /// The from, modes reported by the daemon.
  final List<String> from, modes;

  /// Each agent's model, in the rule's order of preference.
  final Map<String, String> models;
}

/// The authoritative outcome of a reading decision.
class ReadingResolution {
  /// Reads the fields reported by the daemon.
  ReadingResolution.from(Map<String, dynamic> map)
    : issue = map['issue'] is Map<String, dynamic> ? PlanIssue.from(map['issue'] as Map<String, dynamic>) : null,
      work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null,
      revision = _planText(map, 'revision'),
      tasks = readFollowUps(map, readWork(map['work']));

  /// Checks the exact issue and role authorized by the reading choice before Start.
  void checkDecision(String decidedIssue, String choice) {
    final role = switch (choice) {
      'ONE_MORE_ROUND' => 'READER',
      'REWORK' => 'BUILDER',
      _ => null,
    };
    if (issue?.id != decidedIssue ||
        issue?.readingStopped != false ||
        tasks.any((task) => task.role != role || task.issues.single != decidedIssue)) {
      throw const FormatException('The reading answer does not confirm this decision.');
    }
  }

  /// All checked follow-up Start arguments.
  final List<PlanningTaskOrder> tasks;

  /// The issue record returned by the daemon.
  final PlanIssue? issue;

  /// The work record returned by the daemon.
  final PlanWork? work;

  /// The revision reported by the daemon.
  final String revision;
}

/// Where a task's work landed, copied unchanged from Porelo's task row; the planner checks it.
class PlanningLanding {
  const PlanningLanding({required this.branch, required this.ref, required this.remote, required this.commit});

  /// The branch, the ref Porelo serves it under, the name a reader sees after a plain fetch, and the commit.
  final String branch, ref, remote, commit;

  /// The exact fields Porelo supplied.
  Map<String, dynamic> toMap() => {'branch': branch, 'ref': ref, 'remote': remote, 'commit': commit};
}

/// Porelo's original end record, sent without interpreting or rewriting any field.
class PlanningTaskEnded {
  const PlanningTaskEnded({
    required this.at,
    required this.finished,
    required this.why,
    required this.answer,
    required this.unpushed,
    this.landed = const [],
    this.outcome,
  });

  final String at, why, answer;
  final bool finished;
  final int unpushed;

  /// The outcome the task declared, Porelo's `DONE`, `BLOCKED` or `FAILED`; null when it declared none.
  final String? outcome;

  /// Where the work landed; empty while it still waits at Porelo's gate.
  final List<PlanningLanding> landed;

  /// The exact fields Porelo supplied.
  Map<String, dynamic> toMap() => {
    'at': at,
    'finished': finished,
    'why': why,
    'answer': answer,
    'unpushed': unpushed,
    if (landed.isNotEmpty) 'landed': [for (final landing in landed) landing.toMap()],
    if (outcome != null) 'outcome': outcome,
  };
}

/// A checked task report answer, including every still-valid follow-up offer.
class TaskEventResolution {
  TaskEventResolution.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      work = readWork(map['work']),
      tasks = readFollowUps(map, readWork(map['work']));

  final String revision;
  final PlanWork work;
  final List<PlanningTaskOrder> tasks;
}
