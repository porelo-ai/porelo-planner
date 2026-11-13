import 'dart:async';

import 'package:flutter/foundation.dart';

import 'client.dart';
import 'issue_filter.dart';
import 'models.dart';
import 'hooks.dart';
import 'run_attention.dart';
import 'task_runs.dart';
import 'story_work.dart';

/// The priority limits offered above a board.
enum PlanningPriority {
  /// All reported priorities, including unknown ones.
  all('All priorities', null),

  /// Highest alone.
  highest('Highest only', 0),

  /// Highest and High.
  high('High and above', 1),

  /// Highest, High and Medium.
  medium('Medium and above', 2);

  const PlanningPriority(this.label, this.maximum);

  /// The label shown in the filter.
  final String label;

  /// The greatest rank included, or null for all.
  final int? maximum;
}

/// View choices retained for one project independently of its serving machine.
class PlanningPreferences {
  /// The priority limit.
  PlanningPriority priority = PlanningPriority.all;

  /// The search over identifier and title.
  String search = '';

  /// The last vertical board position, retained across provider replacements.
  double verticalOffset = 0;

  /// The last horizontal board position, retained across provider replacements.
  double horizontalOffset = 0;

  /// Whether closed states are shown.
  bool showClosed = false;

  /// Phase names folded to marks and counts, as in the design.
  final Set<String> foldedPhases = {'Ideas', 'Done'};

  /// State names folded to their heading; every box starts open.
  final Set<String> foldedStates = {};

  /// Stories whose issue lists are unfolded.
  final Set<String> expandedStories = {};

  /// Stories folded to their number, priority and size.
  final Set<String> foldedStories = {};

  /// Stories whose page shows its description folded to its heading.
  final Set<String> foldedDescriptions = {};

  final Map<String, PlanningIssueFilter> _issueLists = {};

  /// The search and sort of [story]'s issue list on its page, kept across leaving the page.
  PlanningIssueFilter issueList(String story) => _issueLists.putIfAbsent(story, PlanningIssueFilter.new);
}

/// An unsaved destination, kept separately from the daemon's story state.
class PendingPlanningMove {
  /// Records the original state for the unsaved-move summary.
  PendingPlanningMove(this.from, this.to, {this.isIssue = false});

  /// Whether this destination belongs to an issue rather than a story.
  final bool isIssue;

  /// The state when staging began.
  String from;

  /// The requested destination.
  final String to;

  /// The daemon's refusal, if this move failed.
  String? problem;

  /// Whether a save of this move went unanswered in time and may still land.
  bool unanswered = false;
}

class _StoryStart {
  bool pending = true;
  bool confirmed = false;
}

/// A project's board, replaced only by whole replies from its machine.
class PlanningBoard extends ChangeNotifier {
  /// Starts a watch with independently retained view choices.
  PlanningBoard(
    this.backend,
    this.project, {
    PlanningPreferences? preferences,
    // Past the planner's own bounds on a save: 30 s for the store's lock and a few Git calls of 60 s each.
    this.unansweredWait = const Duration(minutes: 5),
  }) : preferences = preferences ?? PlanningPreferences() {
    _listen();
  }

  /// The machine supplying the board.
  PlanningSource backend;

  /// The project name sent to that machine.
  String project;

  /// Choices belonging to the project.
  final PlanningPreferences preferences;

  /// How long moves of a save answered too late may wait to show on the board before they are told as not saved.
  final Duration unansweredWait;

  Timer? _unansweredDeadline;

  /// The last whole reply, retained visibly stale after a failure.
  PlanningSnapshot? snapshot;

  /// The daemon's error, or an ended-watch explanation.
  String? problem;

  /// Whether the daemon explicitly lacks this feature.
  bool unsupported = false;

  /// Whether changes are still arriving.
  bool live = false;

  /// Whether the watch is recovering, until its next board is accepted.
  bool catchingUp = false;
  VoidCallback? _unfollowReconnect;
  final Map<String, PendingPlanningMove> _pending = {};

  /// Unsaved moves in staging order.
  Map<String, PendingPlanningMove> get pending => Map.unmodifiable(_pending);

  /// Whether a save or reload is in progress.
  bool saving = false;

  /// Save, reload, or drop feedback, using daemon details on failures.
  String? moveProblem;

  /// The refused save, including typed details needed after a partial push.
  PlanningRefusal? moveRefusal;

  /// Confirmation or revision-change feedback from the shared save.
  String? moveNotice;

  (PlanningSource, String)? _queuedProject;

  /// A board streamed during a save or reload, its order, and what its acceptance tells of the watch.
  (PlanningSnapshot, int, VoidCallback)? _deferredSnapshot;
  int _replyOrder = 0;
  int _snapshotOrder = 0;
  StreamSubscription<PlanningSnapshot>? _watching;
  bool _disposed = false;

  /// Whether this board has released its listeners and watch.
  bool get disposed => _disposed;

  int _generation = 0;

  /// Invalidates retained actions even if a watch returns to the same source and project.
  int get generation => _generation;

  static final _storyStarts = Expando<Map<(String, String), _StoryStart>>();

  Map<(String, String), _StoryStart> _startsOn(PlanningSource source) => _storyStarts[source] ??= {};

  /// Prevents separate forms from requesting concurrent orders for the same story.
  Object? beginStoryStart(PlanStory story, {PlanningPassOver? passOver}) {
    if (startUnavailable(story, passOver: passOver) != null) return null;
    final token = _StoryStart();
    _startsOn(backend)[(project, story.id)] = token;
    _changed();
    return token;
  }

  /// An old completion must not release a newer form's reservation.
  void endStoryStart(PlanningSource source, String project, String story, Object token, {required bool accepted}) {
    final key = (project, story);
    final starts = _startsOn(source);
    final start = starts[key];
    if (start == null || !identical(start, token)) return;
    start.pending = false;
    if (!accepted || start.confirmed) starts.remove(key);
    _changed();
  }

  /// Window hooks retained for follow-up tasks and stopping this board's runs.
  PlanningStartTask? startTask;
  PlanningTaskChanges? taskChanges;

  /// What a start from a Needs you row of this board asks of the window; null until the window handed it.
  PlanningStartHooks? startHooks;

  /// All forms and task reports share the same origin and stop epoch.
  PlanningRun runOf(String story) {
    final run = PlanningRun.on(backend, project, story);
    if (startTask != null) run.startTask = startTask;
    if (taskChanges != null) run.changes = taskChanges;
    return run;
  }

  void _acknowledgeStoryStart(PlanStory story) {
    final key = (project, story.id);
    final starts = _startsOn(backend);
    final start = starts[key];
    if (start == null) return;
    // After the order ended, the planner refuses another while a recorded run is active.
    if (!start.pending) {
      starts.remove(key);
    } else if (workOf(story)?.atWork == true) {
      start.confirmed = true;
    }
  }

  /// Whether this source has released its notifier and subscriptions.
  bool get isDisposed => _disposed;

  /// Subscribes only while this source exists; cancellation never touches a disposed notifier.
  VoidCallback follow(VoidCallback listener) {
    if (_disposed) return () {};
    addListener(listener);
    return () {
      if (!_disposed) removeListener(listener);
    };
  }

  void _listen() {
    final generation = ++_generation;
    bool current() => !_disposed && generation == _generation;
    var ended = false;
    var reconnectVersion = 0;
    final source = backend;
    final name = project;
    final reconnect = source is PlanningReconnect ? source as PlanningReconnect : null;
    try {
      final watch = source.watchStories(name);
      // A watch that tells its own reconnect is followed alone; another watch of the project may reconnect meanwhile.
      final (Listenable?, bool Function()) told = switch (watch) {
        PlanningWatch(:final reconnecting) => (reconnecting, () => reconnecting.value),
        _ when reconnect != null => (reconnect, () => reconnect.isReconnecting(name)),
        _ => (null, () => false),
      };
      final (follows, reconnecting) = told;
      void changed() {
        if (!current() || !reconnecting()) return;
        reconnectVersion++;
        catchingUp = true;
        live = false;
        notifyListeners();
      }

      if (follows != null) {
        follows.addListener(changed);
        _unfollowReconnect = () => follows.removeListener(changed);
      }
      _watching = watch.listen(
        (reply) async {
          final receivedVersion = reconnectVersion;
          final recovering = reconnecting();
          final order = ++_replyOrder;
          try {
            final prior = snapshot;
            final names = prior?.states.map((state) => state.name).toSet() ?? <String>{};
            final unknown = reply.stories.any(
              (story) => !names.contains(story.state) || story.issues.any((issue) => !names.contains(issue.state)),
            );
            // A whole board brings its own workflow, which a push elsewhere may have changed.
            final workflow = reply.complete && (reply.states.isNotEmpty || reply.faults.isNotEmpty)
                ? reply
                : prior == null || unknown
                ? await backend.planningWorkflow(project)
                : prior;
            if (!current()) return;
            final combined = _checked(PlanningSnapshot.fromParts(workflow, reply));
            void settle() {
              if (!current()) return;
              problem = workflow.faults.isEmpty ? null : planningShown(workflow.faults.join('\n'));
              unsupported = false;
              if (!ended && receivedVersion == reconnectVersion && !recovering) catchingUp = false;
              live = !ended && !catchingUp;
            }

            if (saving) {
              // Held back, the board is not yet shown, so the watch stays stale until it is.
              // A board that read its workflow first may come after a newer one.
              if (order > (_deferredSnapshot?.$2 ?? 0)) _deferredSnapshot = (combined, order, settle);
            } else {
              _accept(combined, order: order);
              settle();
            }
            notifyListeners();
          } catch (error) {
            if (!current()) return;
            problem = planningFailureWords(error);
            live = false;
            notifyListeners();
          }
        },
        onError: (Object error) {
          if (!current()) return;
          _generation++;
          catchingUp = false;
          _stopFollowingReconnect();
          final watching = _watching;
          _watching = null;
          unsupported = planningUnsupported(error);
          problem = unsupported ? null : planningFailureWords(error);
          live = false;
          notifyListeners();
          // A retry can start without waiting again on this terminal subscription's cancellation.
          unawaited(watching?.cancel());
        },
        onDone: () {
          if (!current()) return;
          _watching = null;
          ended = true;
          live = false;
          catchingUp = false;
          _stopFollowingReconnect();
          notifyListeners();
        },
      );
      // Told only once the watch stands, so a listener that closes or rebinds the board cancels this watch.
      changed();
    } catch (error) {
      _stopFollowingReconnect();
      catchingUp = false;
      unsupported = planningUnsupported(error);
      problem = unsupported ? null : planningFailureWords(error);
      live = false;
    }
  }

  void _stopFollowingReconnect() {
    _unfollowReconnect?.call();
    _unfollowReconnect = null;
  }

  /// Story work read from this exact provider, retained while its watch is stale.
  final Map<String, StoryWorkStatus> storyWork = {};

  /// Failures to read story work, retained in the daemon’s words.
  final Map<String, String> workProblems = {};

  /// The last story action’s refusal, when one was reported.
  String? actionProblem;

  /// Methods this machine explicitly cannot perform yet.
  final Map<String, String> unavailableActions = {};

  /// Whether a story action is awaiting its answer.
  bool actionBusy = false;

  final Map<String, PlanningSnapshot> _workSnapshots = {};

  /// Stories whose older daemon cannot supply missing watch fields.
  final Set<String> workUnsupported = {};

  /// The latest explicit call for this snapshot, otherwise the work from its watch.
  PlanWork? workOf(PlanStory story) =>
      identical(_workSnapshots[story.id], snapshot) ? storyWork[story.id]?.work : story.work;

  /// Task outcomes are authoritative only after a complete work read.
  WorkEnded? endedOf(PlanStory story) => workOf(story)?.ended;

  bool _needsWorkRead(PlanStory story) =>
      state(story.state)?.kind == 'WORKING' &&
      (!story.workReported || story.work != null && !story.work!.tasksReported);

  /// Whether an ended outcome is current enough to resolve a retained attention row.
  bool endedCurrent(PlanStory story) =>
      !_needsWorkRead(story) ||
      identical(_workSnapshots[story.id], snapshot) &&
          !workProblems.containsKey(story.id) &&
          !workUnsupported.contains(story.id);

  /// Why a story cannot start, preserving unknown work as unknown; a row's [passOver] lets its own reason through.
  String? startUnavailable(
    PlanStory story, {
    Object? ownStart,
    List<PlanningTaskStarted> ownTasks = const [],
    PlanningPassOver? passOver,
  }) => startHold(story, ownStart: ownStart, ownTasks: ownTasks, passOver: passOver)?.words;

  /// Why a story cannot start, with the run's attention when that is what holds it.
  PlanningStartHold? startHold(
    PlanStory story, {
    Object? ownStart,
    List<PlanningTaskStarted> ownTasks = const [],
    PlanningPassOver? passOver,
  }) {
    return switch (_startUnavailable(story, ownStart: ownStart, ownTasks: ownTasks, passOver: passOver)) {
      final PlanningRunAttention attention => PlanningStartHold(attention.words, attention: attention),
      final String words => PlanningStartHold(words),
      _ => null,
    };
  }

  /// What in the story's run needs a person: its end and each issue with its reason, or null when nothing does.
  PlanningRunAttention? runAttention(PlanStory story) {
    final work = workOf(story);
    final reported = <String, PlanWorkIssue>{for (final issue in work?.issues ?? <PlanWorkIssue>[]) issue.issue: issue};
    final issues = [
      for (final id in <String>{
        for (final issue in story.issues)
          if (issue.needsYou == true || issue.readingStopped == true) issue.id,
        for (final issue in work?.issues ?? <PlanWorkIssue>[])
          if (planReasonNeedsAttention(issue.reason) || issue.needsYou == true || issue.readingStopped == true)
            issue.issue,
      })
        _issueAttention(id, reported[id], story.issues.where((issue) => issue.id == id).firstOrNull),
    ];
    if (work?.ended == null && issues.isEmpty) return null;
    return PlanningRunAttention(ended: work?.ended != null, issues: issues);
  }

  static PlanningIssueAttention _issueAttention(String id, PlanWorkIssue? work, PlanIssue? issue) =>
      PlanningIssueAttention(
        id,
        reason: work?.reason ?? '',
        readingStopped: work?.readingStopped == true || issue?.readingStopped == true,
        needsYou: work?.needsYou == true || issue?.needsYou == true,
      );

  /// Whether [passOver] names the run and revision shown and exactly the reason that holds the start: its issue's,
  /// with no other issue raised for another reason and no task at work.
  bool _passes(PlanStory story, PlanningRunAttention attention, PlanningPassOver? passOver) {
    final work = workOf(story);
    if (passOver == null ||
        work == null ||
        work.atWork == true ||
        work.run != passOver.run ||
        snapshot?.revision != passOver.revision ||
        !PlanningPassOver.reasons.contains(passOver.reason)) {
      return false;
    }
    return attention.issues.any((issue) => issue.issue == passOver.issue && issue.reason == passOver.reason) &&
        attention.issues.every((issue) => issue.reason == passOver.reason && !issue.readingStopped);
  }

  /// Why a run cannot be stopped now, or null when it can: one at work, ended with reasons or halted can.
  String? stopUnavailable(PlanStory story) {
    if (unavailableActions['StopStoryWork'] case final reason?) return reason;
    if (_disposed || !live) return 'the planning board is not live';
    if (actionBusy) return 'waiting for the planner';
    final work = workOf(story);
    if (work == null) return 'no run was reported';
    final open =
        work.atWork == true ||
        work.issues.any((issue) => planReasonNeedsAttention(issue.reason) && issue.reason != 'STOPPED');
    if (!open) return 'no running or raised work was reported';
    // Recording a stop while the agent goes on running is worse than offering none.
    if (taskChanges == null) return 'the window has not handed the planning screen the hook that stops tasks';
    return null;
  }

  /// A reason in words, or the run's attention when that is what holds the start.
  Object? _startUnavailable(
    PlanStory story, {
    Object? ownStart,
    List<PlanningTaskStarted> ownTasks = const [],
    PlanningPassOver? passOver,
  }) {
    final reported = stories.where((each) => each.id == story.id).firstOrNull;
    if (reported == null) return 'the story is no longer reported';
    story = reported;
    if (_startsOn(backend).containsKey((project, story.id)) &&
        !identical(_startsOn(backend)[(project, story.id)], ownStart)) {
      return 'a story start is awaiting confirmation';
    }
    if (unavailableActions['StartStoryWork'] ?? unavailableActions['ProposeStoryWork'] case final reason?) {
      return reason;
    }
    if (_disposed || !live || snapshot?.complete != true) return 'the planning board is not live and complete';
    if (snapshot!.revision.isEmpty) return 'the planning revision was not reported';
    final kind = state(story.state)?.kind;
    if (!{'WAITING', 'WORKING', 'CLOSED'}.contains(kind)) return 'the story state does not permit starting work';
    if (_needsWorkRead(story) && !identical(_workSnapshots[story.id], snapshot)) {
      return workProblems[story.id] ??
          (workUnsupported.contains(story.id)
              ? 'the planner does not offer story work status'
              : 'work status not reported');
    }
    if (_needsWorkRead(story)) {
      if (workProblems[story.id] case final problem?) return problem;
      if (workUnsupported.contains(story.id)) return 'the planner does not offer story work status';
    }
    final work = workOf(story);
    if (runAttention(story) case final attention? when !_passes(story, attention, passOver)) return attention;
    if (work == null && (kind == 'WAITING' || kind == 'CLOSED' || workedByHand(story))) return null;
    return switch (work?.atWork) {
      false => null,
      true =>
        ownStart != null &&
                work!.tasks.any((task) => task.atWork == true) &&
                work.tasks
                    .where((task) => task.atWork == true)
                    .every((task) => ownTasks.any((own) => own.task == task.task && own.container == task.containerId))
            ? null
            : 'work is already running',
      null => 'work status not reported',
    };
  }

  /// Reports an action error and redraws every consumer of this board.
  void reportActionProblem(Object error) {
    if (_disposed) return;
    actionProblem = planningFailureWords(error);
    _changed();
  }

  Future<void> _readWork(PlanningSnapshot reply, int generation) async {
    final source = backend;
    final name = project;
    bool current() => !_disposed && generation == _generation && identical(snapshot, reply);
    for (final story in reply.stories) {
      if (!current()) return;
      storyWork.remove(story.id);
      _workSnapshots.remove(story.id);
      if (!_needsWorkRead(story)) {
        workProblems.remove(story.id);
        workUnsupported.remove(story.id);
        _acknowledgeStoryStart(story);
        continue;
      }
      try {
        final answer = await source.getStoryWork(name, story.id);
        if (!current()) return;
        if (answer.work != null && !answer.work!.tasksReported) {
          throw const FormatException('The planner did not report the work tasks.');
        }
        runOf(story.id).observe(answer.work);
        storyWork[story.id] = answer;
        _workSnapshots[story.id] = reply;
        _acknowledgeStoryStart(story);
        workProblems.remove(story.id);
        workUnsupported.remove(story.id);
      } catch (error) {
        if (!current()) return;
        storyWork.remove(story.id);
        _workSnapshots.remove(story.id);
        if (planningUnsupported(error)) {
          workUnsupported.add(story.id);
          workProblems.remove(story.id);
        } else {
          workProblems[story.id] = planningFailureWords(error);
        }
      }
      _changed();
    }
    if (!current()) return;
    final ids = reply.stories.map((story) => story.id).toSet();
    storyWork.removeWhere((id, _) => !ids.contains(id));
    _workSnapshots.removeWhere((id, _) => !ids.contains(id));
    workProblems.removeWhere((id, _) => !ids.contains(id));
    workUnsupported.removeWhere((id) => !ids.contains(id));
    _changed();
  }

  /// Whether a discussion of an idea is being asked for or handed to Start.
  bool discussing = false;

  /// Asks the planner for a discussion of [idea] and hands its order to [start] with an empty story.
  ///
  /// The task belongs to no story: no run is started or told of it, and no task event is reported for it.
  Future<void> discussIdea(String idea, PlanningStartTask start) async {
    if (_disposed || discussing) return;
    final source = backend;
    final name = project;
    final generation = _generation;
    discussing = true;
    actionProblem = null;
    _changed();
    try {
      final task = await source.discussIdea(name, idea);
      // A board closed or switched meanwhile starts nothing the person no longer sees.
      if (_disposed || generation != _generation) return;
      await start(source, name, '', task);
    } catch (error) {
      // The person cancelled the Start: their own choice, no fault, and the discussion stays offered.
      if (planningRefused(error, 'NotStarted')) return;
      if (!_disposed && generation == _generation) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['DiscussIdea'] = planningFailureWords(error);
        }
      }
    } finally {
      if (!_disposed && generation == _generation) {
        discussing = false;
        _changed();
      }
    }
  }

  /// Stops work without moving the story locally, naming [run], the run shown when the person asked to stop.
  Future<void> stopWork(
    String story, {
    required String? run,
    required String revision,
    required PlanningSource source,
    required String sourceProject,
  }) async {
    if (_disposed || actionBusy) return;
    if (!live ||
        !identical(backend, source) ||
        project != sourceProject ||
        snapshot?.complete != true ||
        !stories.any((each) => each.id == story && stopUnavailable(each) == null)) {
      actionProblem = 'The board changed while Stop work was open. Open it again for the current run.';
      _changed();
      return;
    }
    final tasks = runOf(story)..cancel();
    actionBusy = true;
    actionProblem = null;
    final generation = _generation;
    final name = sourceProject;
    _changed();
    try {
      final answer = await source.stopStoryWork(name, story, run: run);
      tasks.observe(answer.work);
      if (!_disposed && generation == _generation) applyWork(story, answer);
      await tasks.changes?.stop(source, name, story, [
        for (final task in answer.work?.tasks ?? <PlanWorkTask>[])
          if (task.atWork == true) PlanningTaskStarted(task: task.task, container: task.containerId),
      ]);
      if (!_disposed && generation == _generation) await reload();
    } catch (error) {
      if (!_disposed && generation == _generation) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['StopStoryWork'] = planningFailureWords(error);
        }
      }
    } finally {
      if (!_disposed && generation == _generation) {
        actionBusy = false;
        _changed();
      }
    }
  }

  /// The project's repositories, its own first, as the last whole board named them; empty before one did.
  List<String> get repositories => _repositories;

  // Kept apart from the snapshot: a board read again by its workflow and stories names no repositories.
  List<String> _repositories = const [];

  /// Why a new issue cannot be added now, or null when it can.
  String? get newIssueUnavailable =>
      unavailableActions['CreateIssue'] ??
      (_disposed || !live
          ? 'the planning board is not live'
          : snapshot == null || snapshot!.revision.trim().isEmpty
          ? 'the planning revision was not reported'
          : actionBusy || saving
          ? 'waiting for the planner'
          : repositories.isEmpty
          ? 'no repository was reported'
          : null);

  /// Adds an idea as a new issue against the revision the board shows; answers its id, or null after a refusal,
  /// whose words stand in [actionProblem] as every board action's do.
  Future<String?> createIssue(
    String repository,
    String title,
    String mustBeTrue,
    String? size, {
    IssueWorker worker = IssueWorker.agent,
    String? task,
  }) async {
    if (newIssueUnavailable case final reason?) {
      actionProblem = 'No new issue: $reason.';
      _changed();
      return null;
    }
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    final generation = _generation;
    actionBusy = true;
    actionProblem = null;
    _changed();
    try {
      return (await source.createIssue(
        name,
        repository,
        title,
        mustBeTrue,
        size,
        revision,
        worker: worker,
        task: task,
      )).id;
    } catch (error) {
      if (!_disposed && generation == _generation) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['CreateIssue'] = planningFailureWords(error);
        }
      }
      return null;
    } finally {
      if (!_disposed && generation == _generation) {
        actionBusy = false;
        _changed();
      }
    }
  }

  /// Priorities chosen and not yet carried by the board, by story.
  Map<String, String> get pendingPriorities => {
    for (final entry in _priorities.entries) entry.key: entry.value.priority,
  };

  final Map<String, _PendingPriority> _priorities = {};

  /// Why [story]'s priority cannot be changed now, or null when it can.
  String? priorityUnavailable(String story) =>
      unavailableActions['SetPriority'] ??
      (_disposed || !live
          ? 'the planning board is not live'
          : snapshot == null || snapshot!.revision.trim().isEmpty
          ? 'the planning revision was not reported'
          : actionBusy || saving
          ? 'waiting for the planner'
          : story.isEmpty || !snapshot!.stories.any((reported) => reported.id == story)
          ? 'the story is not on the board'
          : null);

  /// Sets [story]'s priority, one of [planningPriorities], against the revision the board shows; the choice stays
  /// pending until a board carries it. Answers whether the planner took it; a refusal's words stand in
  /// [actionProblem] as every board action's do.
  Future<bool> setPriority(String story, String priority) async {
    if (priorityUnavailable(story) case final reason?) {
      actionProblem = 'No priority: $reason.';
      _changed();
      return false;
    }
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    final generation = _generation;
    final pending = _PendingPriority(priority, revision);
    _priorities[story] = pending;
    actionBusy = true;
    actionProblem = null;
    _changed();
    try {
      await source.setPriority(name, story, priority, revision);
      if (!_disposed && generation == _generation && identical(_priorities[story], pending)) {
        pending.answered = true;
        // A board that arrived before the answer may carry it already.
        if (snapshot case final current?) _reconcilePriorities(current);
      }
      return true;
    } catch (error) {
      if (!_disposed && generation == _generation) {
        if (identical(_priorities[story], pending)) _priorities.remove(story);
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['SetPriority'] = planningFailureWords(error);
        }
      }
      return false;
    } finally {
      if (!_disposed && generation == _generation) {
        actionBusy = false;
        _changed();
      }
    }
  }

  /// Drops each pending priority the board carries, or whose save was answered and a newer board has come since.
  void _reconcilePriorities(PlanningSnapshot reply) {
    if (!reply.complete || reply.revision.isEmpty) return;
    _priorities.removeWhere((story, pending) {
      final reported = reply.stories.where((candidate) => candidate.id == story).firstOrNull;
      return reported == null ||
          reported.priority == pending.priority ||
          pending.answered && reply.revision != pending.revision;
    });
  }

  /// Why who works [issue] cannot be changed now, or null when it can.
  String? workerUnavailable(PlanIssue issue) =>
      unavailableActions['SetWorker'] ??
      (issue.parent != null
          ? 'a sub-issue is worked as its parent ${issue.parent} is'
          : issue.worker == null
          ? 'the planner did not report who works it'
          : _disposed || !live
          ? 'the planning board is not live'
          : snapshot?.complete != true
          ? 'the planning board is not complete'
          : snapshot!.revision.trim().isEmpty
          ? 'the planning revision was not reported'
          : actionBusy || saving
          ? 'waiting for the planner'
          : _staged(issue)
          ? 'it or a sub-issue has a move to save or undo first'
          : null);

  /// Whether [issue] or one of its sub-issues has a staged move, which a worker change would make the save refuse.
  bool _staged(PlanIssue issue) {
    final staged = pendingIssues.keys.toSet();
    return staged.contains(issue.id) ||
        stories.expand((story) => story.issues).any((each) => each.parent == issue.id && staged.contains(each.id));
  }

  /// Sets who works [issue] against the revision the board shows; answers the planner's words for a refusal, which
  /// also stand in [actionProblem], or null once it is saved.
  Future<String?> setWorker(PlanIssue issue, IssueWorker worker) async {
    if (workerUnavailable(issue) case final reason?) {
      actionProblem = 'No worker change: $reason.';
      _changed();
      return actionProblem;
    }
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    final generation = _generation;
    actionBusy = true;
    actionProblem = null;
    _changed();
    try {
      await source.setWorker(name, issue.id, worker, revision);
      return null;
    } catch (error) {
      if (!_disposed && generation == _generation) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['SetWorker'] = planningFailureWords(error);
        }
      }
      return planningFailureWords(error);
    } finally {
      if (!_disposed && generation == _generation) {
        actionBusy = false;
        _changed();
      }
    }
  }

  /// Applies a run reply immediately, independently of a subsequent board reload.
  void applyWork(String story, StoryWorkStatus status) {
    if (_disposed) return;
    storyWork[story] = status;
    if (snapshot case final current?) _workSnapshots[story] = current;
    _changed();
  }

  /// Replaces the source, canceling its silent watch before accepting another project's replies.
  Future<void> changeProject(PlanningSource next, String name) async {
    if (_disposed) return;
    if (saving) {
      _queuedProject = (next, name);
      return;
    }
    final sameProject = identical(backend, next) && project == name;
    // Story ids repeat across projects, so a fold made in one must not fold a card of another.
    if (project != name) preferences.foldedStories.clear();
    final generation = ++_generation;
    _stopFollowingReconnect();
    catchingUp = false;
    live = false;
    final watching = _watching;
    _watching = null;
    if (watching != null) await watching.cancel();
    if (_disposed || generation != _generation) return;
    backend = next;
    project = name;
    if (!sameProject) {
      snapshot = null;
      _repositories = const [];
      _pending.clear();
      _priorities.clear();
      moveProblem = null;
      storyWork.clear();
      workProblems.clear();
      _workSnapshots.clear();
      workUnsupported.clear();
      unavailableActions.clear();
    }
    actionBusy = false;
    discussing = false;
    problem = null;
    unsupported = false;
    live = false;
    _listen();
    _changed();
  }

  /// Restarts this project's watch after a failure, retaining its last snapshot and view choices.
  Future<void> retry() => changeProject(backend, project);

  /// The displayed destination, with failed moves kept in their reported state.
  String displayedState(PlanStory story) {
    final move = _pending[story.id];
    final destination = move == null ? null : state(move.to);
    return move != null &&
            move.problem == null &&
            destination != null &&
            phases.any((phase) => phase.states.contains(move.to))
        ? move.to
        : story.state;
  }

  /// Stages a waiting or closed destination without sending any command.
  void move(String id, String to) {
    if (_disposed || saving || snapshot?.complete != true || !live) return;
    final story = stories.where((story) => story.id == id).firstOrNull;
    final target = state(to);
    if (story == null || target == null) return;
    if (!{'WAITING', 'CLOSED'}.contains(target.kind)) return;
    if (snapshot!.revision.isEmpty) {
      moveProblem = 'The planner did not report a planning revision. Reload planning before moving stories.';
      _changed();
      return;
    }
    if (to == story.state) {
      undo(id);
      return;
    }
    _pending[id] = PendingPlanningMove(story.state, to);
    moveNotice = null;
    _changed();
  }

  /// Issue destinations in the same pending batch as the story moves.
  Map<String, PendingPlanningMove> get pendingIssues => {
    for (final entry in _pending.entries)
      if (entry.value.isIssue) entry.key.substring('issue '.length): entry.value,
  };

  /// Whether a person may move [issue] into [target]: a waiting or closed state, or a working one for an issue a
  /// person works, which no task enters for it.
  static bool issueMayEnter(PlanIssue issue, PlanState? target) =>
      target != null && {'WAITING', 'CLOSED', if (issue.byHand) 'WORKING'}.contains(target.kind);

  /// Whether every issue of [story] in a working state is one a person works, so no run is missing for it.
  bool workedByHand(PlanStory story) {
    final working = story.issues.where((issue) => state(issue.state)?.kind == 'WORKING');
    return working.isNotEmpty && working.every((issue) => issue.byHand);
  }

  /// Stages one issue without changing its reported state or any sibling.
  ///
  /// [shown] is the state the person saw when choosing, or [reported]'s; [undo] takes the issue's draft back.
  void moveIssue(String id, String to, {PlanIssue? reported, String? shown, bool undo = false}) {
    if (_disposed) return;
    if (saving) return refuseMove(id, 'planning is saving; choose again once it is saved.');
    final key = 'issue $id';
    if (undo) return this.undo(key);
    if (snapshot?.complete != true || !live || snapshot!.revision.isEmpty) {
      return refuseMove(id, 'the board is not live and complete.');
    }
    // Judged on the board's issue: the page's copy may be older or newer in any field.
    final onBoard = stories.expand((story) => story.issues).where((issue) => issue.id == id).firstOrNull;
    // Only a board that reports every story's issues can say an issue is gone; otherwise the page's copy stands.
    final issue = onBoard ?? (stories.every((story) => story.issuesReported) || reported?.id != id ? null : reported);
    if (issue == null) return refuseMove(id, 'it is no longer on the board.');
    if (issue.state.isEmpty) return refuseMove(id, 'its state was not reported.');
    // A menu shows the issue as it stood when it opened; a task may have moved it since.
    final seen = shown ?? (reported?.id == id ? reported!.state : null);
    if (seen != null && seen != issue.state) return refuseMove(id, 'it now stands in ${issue.state}; choose again.');
    final target = state(to);
    if (to == issue.state) return this.undo(key);
    if (!issueMayEnter(issue, target)) return refuseMove(id, 'it may not enter $to from ${issue.state}.');
    _pending[key] = PendingPlanningMove(issue.state, to, isIssue: true);
    moveProblem = null;
    moveNotice = null;
    _changed();
  }

  /// Says why a move of issue [id] was not staged, where the board and the story's page show it.
  void refuseMove(String id, String why) {
    moveProblem = '$id was not moved: $why';
    moveNotice = null;
    _changed();
  }

  String? _reportedState(PlanningSnapshot reply, String key, PendingPlanningMove move) => move.isIssue
      ? reply.stories.expand((story) => story.issues).where((issue) => 'issue ${issue.id}' == key).firstOrNull?.state
      : reply.stories.where((story) => story.id == key).firstOrNull?.state;

  /// Drops one unsaved move without touching the daemon.
  void undo(String id) {
    if (_disposed || saving) return;
    _pending.remove(id);
    if (_pending.isEmpty) moveProblem = null;
    _changed();
  }

  /// Drops all unsaved moves without touching the daemon.
  void undoAll() {
    if (_disposed || saving) return;
    _pending.clear();
    moveProblem = null;
    moveNotice = null;
    _changed();
  }

  PlanningSnapshot _checked(PlanningSnapshot reply) {
    if (!reply.complete || reply.revision.isEmpty) {
      throw StateError('The planner did not report a complete planning board and revision.');
    }
    return reply;
  }

  Future<(PlanningSnapshot, int)> _read() async {
    final order = _readStarts();
    final workflow = await backend.planningWorkflow(project);
    if (_disposed) throw StateError('The planning board was closed.');
    final reply = await backend.stories(project);
    return (_checked(PlanningSnapshot.fromParts(workflow, reply)), order);
  }

  /// Numbers a read when it starts: a board streamed while it waits is newer than its answer may be, and the stream
  /// tells every revision in order, so it is never held on an older one.
  int _readStarts() => ++_replyOrder;

  /// Shows the newer of a read's board and one streamed while the read waited, so pending moves are reconciled only
  /// against the board that stays.
  void _acceptNewest(PlanningSnapshot reply, int order, {bool reconcile = true}) {
    final deferred = _deferredSnapshot;
    _deferredSnapshot = null;
    if (deferred != null && deferred.$2 > order) {
      _accept(deferred.$1, order: deferred.$2, reconcile: reconcile);
    } else {
      _accept(reply, order: order, reconcile: reconcile);
    }
    // The board shown is at least as new as the one the watch brought, so the watch has caught up.
    deferred?.$3();
  }

  void _accept(PlanningSnapshot reply, {required int order, bool reconcile = true}) {
    if (order <= _snapshotOrder) return;
    snapshot = reply;
    // A story gone from the board loses its fold, so one saved anew under its id starts open.
    if (reply.storiesReported) {
      final shown = {for (final story in reply.stories) story.id};
      preferences.foldedStories.removeWhere((id) => !shown.contains(id));
    }
    if (reply.repositoriesReported) _repositories = List.unmodifiable(reply.repositories);
    for (final story in reply.stories) {
      runOf(story.id).observe(story.work);
    }
    _snapshotOrder = order;
    storyWork.clear();
    _workSnapshots.clear();
    workProblems.clear();
    workUnsupported.clear();
    unawaited(_readWork(reply, _generation));
    _reconcilePriorities(reply);
    if (!reply.complete || reply.revision.isEmpty) return;
    final reconciled = <String>[];
    final landed = <PendingPlanningMove>[];
    for (final entry in _pending.entries.toList()) {
      final reported = _reportedState(reply, entry.key, entry.value);
      if (!reconcile) {
        if (reported != null) entry.value.from = reported;
        continue;
      }
      if (reported == null && entry.value.isIssue && reply.stories.any((story) => !story.issuesReported)) {
        continue;
      }
      if (reported == null) {
        _pending.remove(entry.key);
        reconciled.add(planningShown('${entry.key} is no longer reported by the planner.', multiline: false));
      } else if (reported == entry.value.to) {
        _pending.remove(entry.key);
        if (entry.value.unanswered) {
          landed.add(entry.value);
        } else {
          reconciled.add(planningShown('${entry.key} was moved to ${entry.value.to} elsewhere.', multiline: false));
        }
      } else {
        entry.value.from = reported;
      }
    }
    if (!_pending.values.any((move) => move.unanswered)) {
      _unansweredDeadline?.cancel();
      _unansweredDeadline = null;
      if (landed.isNotEmpty) moveNotice = _savedNotice(landed);
    }
    if (reconciled.isNotEmpty) {
      final detail = reconciled.join('\n');
      moveProblem = moveProblem == null ? detail : '$moveProblem\n$detail';
    }
  }

  void _awaitUnanswered() {
    if (_disposed || _unansweredDeadline != null || !_pending.values.any((move) => move.unanswered)) return;
    _unansweredDeadline = Timer(unansweredWait, _unansweredTooLong);
  }

  void _unansweredTooLong() {
    _unansweredDeadline = null;
    // A save running now arms the deadline again when it ends.
    if (_disposed || saving) return;
    final late = _pending.values.where((move) => move.unanswered).toList();
    if (late.isEmpty) return;
    const words = 'The save was not seen on the planning board. Save again.';
    for (final move in late) {
      move.unanswered = false;
      move.problem = words;
    }
    moveNotice = null;
    moveProblem = moveProblem == null ? words : '$moveProblem\n$words';
    _changed();
  }

  void _forgetUnanswered(Iterable<String> keys) {
    for (final key in keys) {
      _pending[key]?.unanswered = false;
    }
  }

  static String _savedNotice(Iterable<PendingPlanningMove> moves) =>
      moves.every((move) => move.isIssue) ? 'Issue changes saved.' : 'Planning changes saved.';

  void _finishOperation({bool reconcile = true}) {
    if (_disposed) return;
    saving = false;
    final deferred = _deferredSnapshot;
    _deferredSnapshot = null;
    if (deferred != null) {
      _accept(deferred.$1, order: deferred.$2, reconcile: reconcile);
      deferred.$3();
    }
    _changed();
    final queued = _queuedProject;
    _queuedProject = null;
    if (queued != null) unawaited(changeProject(queued.$1, queued.$2));
  }

  /// Reloads and reconciles pending destinations while retaining their individual refusals; with [fetch], the
  /// provider first fetches the repositories, so the board read is what they hold now, not what it last polled.
  Future<void> reload({bool fetch = false}) async {
    if (_disposed || saving) return;
    saving = true;
    final generation = _generation;
    _changed();
    try {
      if (fetch) await backend.refreshProject(project);
      if (_disposed || generation != _generation) return;
      final reply = await _read();
      if (_disposed || generation != _generation) return;
      moveProblem = null;
      _acceptNewest(reply.$1, reply.$2);
      final faults = snapshot?.faults ?? const <String>[];
      problem = faults.isEmpty ? null : planningShown(faults.join('\n'));
    } catch (error) {
      if (!_disposed && generation == _generation) moveProblem = planningFailureWords(error);
    } finally {
      _finishOperation();
    }
  }

  /// Saves all pending story and issue destinations atomically and applies only the daemon's complete answer.
  Future<void> save() async {
    if (_disposed || saving || _pending.isEmpty || !live || unavailableActions.containsKey('SaveMoves')) return;
    saving = true;
    _unansweredDeadline?.cancel();
    _unansweredDeadline = null;
    final generation = _generation;
    final revision = snapshot?.revision ?? '';
    final batch = Map.of(_pending);
    final moves = <PlanningMove>[
      for (final entry in batch.entries)
        if (!entry.value.isIssue) PlanningStoryMove(entry.key, entry.value.to),
      for (final entry in batch.entries)
        if (entry.value.isIssue) PlanningIssueMove(entry.key.substring('issue '.length), entry.value.to),
    ];
    moveProblem = null;
    moveNotice = null;
    moveRefusal = null;
    for (final move in _pending.values) {
      move.problem = null;
    }
    _changed();
    var refused = false;
    try {
      _checked(snapshot!);
      final reply = await backend.saveMoves(project, moves, revision);
      if (_disposed || generation != _generation) return;
      if (!reply.complete || reply.revision.isEmpty) {
        throw const FormatException('The planner did not report the saved revision and moved issues.');
      }
      final previous = snapshot!;
      final destinations = <String, String>{};
      final storyDestinations = <String, String>{};
      // In the order sent: story moves first, so an explicit issue move made after them wins.
      final ordered = [
        ...batch.entries.where((entry) => !entry.value.isIssue),
        ...batch.entries.where((entry) => entry.value.isIssue),
      ];
      for (final entry in ordered) {
        final move = entry.value;
        final id = move.isIssue ? entry.key.substring('issue '.length) : entry.key;
        final story = previous.stories.where((story) => story.id == id).firstOrNull;
        final expected = move.isIssue
            ? [id]
            : story?.leastAdvancedIssues(previous.states)?.map((issue) => issue.id).toList();
        if (expected != null && expected.every(reply.moved.contains)) {
          for (final issue in expected) {
            destinations[issue] = move.to;
          }
          // A story with issues stands where the daemon computes it, read again below.
          if (!move.isIssue && expected.isEmpty) storyDestinations[id] = move.to;
          _pending.remove(entry.key);
        } else {
          _pending[entry.key]?.problem = 'The planner did not confirm this destination.';
        }
      }
      _accept(
        PlanningSnapshot.saved(previous, reply.revision, destinations, storyDestinations),
        // Numbered after the save: a board streamed while it waited was read before the save was made.
        order: _readStarts(),
        reconcile: false,
      );
      if (_pending.isEmpty) {
        moveNotice = _savedNotice(batch.values);
      }
      _changed();
      // SaveMoves does not return computed story states; read them from the store.
      final latestOrder = _readStarts();
      final PlanningSnapshot latest;
      try {
        latest = _checked(PlanningSnapshot.fromParts(snapshot!, await backend.stories(project)));
      } catch (error) {
        if (!_disposed && generation == _generation) {
          moveProblem = 'Saved, but planning could not be read again: ${planningFailureWords(error)}';
        }
        return;
      }
      if (_disposed || generation != _generation) return;
      _acceptNewest(latest, latestOrder);
    } on PlanningRefusal catch (error) {
      if (_disposed || generation != _generation) return;
      if (error.name == 'TimedOut') {
        // Only the screen stopped waiting; the planner finishes the save, and the streamed board shows when it landed.
        for (final key in batch.keys) {
          _pending[key]?.unanswered = true;
        }
        moveNotice = 'Still saving. The moves show as saved once planning shows them.';
        return;
      }
      refused = true;
      // Refused, the move cannot land anymore; a board showing it later shows another person's move.
      if (error.name != 'StaleRevision') _forgetUnanswered(batch.keys);
      moveRefusal = error;
      final detail = planningFailureWords(error);
      moveProblem = detail;
      for (final key in batch.keys) {
        _pending[key]?.problem = detail;
      }
      if (error.name == 'NotYetBuilt' || error.name == 'NotOffered') unavailableActions['SaveMoves'] = detail;
      if (error.name == 'StaleRevision' || error.name == 'PartlySaved') {
        if (error.name == 'StaleRevision') moveNotice = 'Planning changed. Review the pending moves and save again.';
        try {
          final reply = await _read();
          if (!_disposed && generation == _generation) {
            if (error.name == 'StaleRevision') {
              for (final move in _pending.values) {
                move.problem = null;
              }
              moveProblem = null;
              moveRefusal = null;
              refused = false;
              moveNotice = null;
              _acceptNewest(reply.$1, reply.$2);
              // A save that landed late has already told itself saved.
              moveNotice = _pending.isEmpty
                  ? moveNotice ?? 'The pending moves were made elsewhere.'
                  : 'Planning changed. Review the pending moves and save again.';
            } else {
              _acceptNewest(reply.$1, reply.$2, reconcile: false);
            }
          }
        } catch (readError) {
          if (!_disposed && generation == _generation) moveProblem = '$detail\n${planningFailureWords(readError)}';
        }
      }
    } catch (error) {
      if (!_disposed && generation == _generation) {
        refused = true;
        _forgetUnanswered(batch.keys);
        moveProblem = planningFailureWords(error);
        for (final key in batch.keys) {
          _pending[key]?.problem = moveProblem;
        }
      }
    } finally {
      _finishOperation(reconcile: !refused);
      _awaitUnanswered();
    }
  }

  /// The reported states, without inventing any workflow.
  List<PlanState> get states => snapshot?.states ?? const [];

  /// The reported phases.
  List<PlanPhase> get phases => snapshot?.phases ?? const [];

  /// The reported stories, independent of the filters.
  List<PlanStory> get stories => snapshot?.stories ?? const [];

  /// Stories with reported active tasks; lost working records keep the count unknown.
  int? get workingCount {
    if (snapshot?.complete != true || !live || stories.any((story) => state(story.state)?.kind.isEmpty ?? true)) {
      return null;
    }
    for (final story in stories) {
      final work = workOf(story);
      if (work == null && state(story.state)?.kind == 'WORKING' && !workedByHand(story) ||
          work != null && (!work.tasksReported || work.tasks.any((task) => task.atWork == null))) {
        return null;
      }
    }
    return stories.where((story) => workOf(story)?.tasks.any((task) => task.atWork == true) == true).length;
  }

  /// The reported state with this name, if any.
  PlanState? state(String name) => states.where((state) => state.name == name).firstOrNull;

  /// Whether a state is shown under the closed-state choice.
  bool visible(PlanState state) => preferences.showClosed || state.kind != 'CLOSED';

  /// Visible states of a phase, working first and otherwise in workflow order.
  List<PlanState> statesIn(PlanPhase phase) {
    final listed = states.where((state) => phase.states.contains(state.name) && visible(state)).toList();
    return [...listed.where((state) => state.kind == 'WORKING'), ...listed.where((state) => state.kind != 'WORKING')];
  }

  /// The number of closed stories before filtering.
  int? get closedCount => snapshot?.complete != true || stories.any((story) => state(story.state)?.kind.isEmpty ?? true)
      ? null
      : stories.where((story) => state(story.state)?.kind == 'CLOSED').length;

  /// The priority rank, with unknown or missing values after known ones.
  static int rank(String? priority) => switch (priority) {
    'Highest' => 0,
    'High' => 1,
    'Medium' => 2,
    'Low' => 3,
    'Lowest' => 4,
    _ => 5,
  };
  static final _trailingNumber = RegExp(r'\d+$');

  /// Each story's words for the search and its number for the order, made once per story read.
  static final _keys = Expando<(String, String, int?)>('story keys');

  static (String, String, int?) _keysOf(PlanStory story) => _keys[story] ??= (
    story.id.toLowerCase(),
    story.title.toLowerCase(),
    int.tryParse(_trailingNumber.firstMatch(story.id)?.group(0) ?? ''),
  );

  /// Each story's issues with their words for the search, made once per story read.
  static final _issueKeys = Expando<List<(String, String, String)>>('issue keys');

  static List<(String, String, String)> _issueKeysOf(PlanStory story) => _issueKeys[story] ??= [
    for (final issue in story.issues) (issue.id, issue.id.toLowerCase(), issue.title.toLowerCase()),
  ];

  static Iterable<String> _issuesFound(PlanStory story, String query) => query.isEmpty
      ? const []
      : _issueKeysOf(story).where((keys) => keys.$2.contains(query) || keys.$3.contains(query)).map((keys) => keys.$1);

  bool _matches(PlanStory story, String query) {
    final keys = _keysOf(story);
    return (preferences.priority.maximum == null || rank(story.priority) <= preferences.priority.maximum!) &&
        (query.isEmpty || keys.$1.contains(query) || keys.$2.contains(query) || _issuesFound(story, query).isNotEmpty);
  }

  /// What the found issues were collected from, and the issues.
  (List<Object?>, Set<String>)? _found;

  List<PlanStory> _sort(Iterable<PlanStory> stories) {
    final query = preferences.search.trim().toLowerCase();
    return stories.where((story) => _matches(story, query)).toList()..sort((a, b) {
      final priority = rank(a.priority).compareTo(rank(b.priority));
      if (priority != 0) return priority;
      final left = _keysOf(a).$3, right = _keysOf(b).$3;
      return left != null && right != null && left != right ? left.compareTo(right) : a.id.compareTo(b.id);
    });
  }

  /// What the columns were sorted from, and the columns; a board of hundreds of stories is sorted once per change,
  /// not once per column and frame.
  (List<Object?>, Map<String, List<PlanStory>>)? _columns;

  /// The filtered, sorted stories of exactly this reported state.
  List<PlanStory> cards(String name) {
    final inputs = <Object?>[
      snapshot,
      preferences.search,
      preferences.priority,
      for (final move in _pending.entries) ...[move.key, move.value.to, move.value.problem],
    ];
    final columns = _columns;
    if (columns != null && listEquals(columns.$1, inputs)) return columns.$2[name] ?? const [];
    final grouped = <String, List<PlanStory>>{};
    for (final story in stories) {
      (grouped[displayedState(story)] ??= []).add(story);
    }
    final sorted = {for (final entry in grouped.entries) entry.key: List<PlanStory>.unmodifiable(_sort(entry.value))};
    _columns = (inputs, sorted);
    return sorted[name] ?? const [];
  }

  /// Stories whose state is missing from the column configuration, kept visible with an explanation.
  List<PlanStory> get unplaced => _sort(
    stories.where(
      (story) =>
          state(displayedState(story)) == null || !phases.any((phase) => phase.states.contains(displayedState(story))),
    ),
  );

  /// Updates the priority filter.
  void filter(PlanningPriority priority) {
    preferences.priority = priority;
    _changed();
  }

  /// The ids of the issues the current search finds in the stories it keeps, empty without a search.
  Set<String> get foundIssues {
    final inputs = <Object?>[snapshot, preferences.search, preferences.priority];
    final found = _found;
    if (found != null && listEquals(found.$1, inputs)) return found.$2;
    final query = preferences.search.trim().toLowerCase();
    final issues = Set<String>.unmodifiable({
      for (final story in stories)
        if (_matches(story, query)) ..._issuesFound(story, query),
    });
    _found = (inputs, issues);
    return issues;
  }

  /// Updates the search.
  void search(String query) {
    preferences.search = query;
    _changed();
  }

  /// Toggles closed states.
  void toggleClosed() {
    preferences.showClosed = !preferences.showClosed;
    _changed();
  }

  /// Toggles one phase.
  void foldPhase(String name) {
    _toggle(preferences.foldedPhases, name);
  }

  /// Toggles one state.
  void foldState(String name) {
    _toggle(preferences.foldedStates, name);
  }

  /// Folds or unfolds one story's card; an id the board does not show is ignored.
  void foldStory(String id) {
    if (!stories.any((story) => story.id == id)) return;
    _toggle(preferences.foldedStories, id);
  }

  /// Toggles one story's issue list.
  void expand(String id) {
    _toggle(preferences.expandedStories, id);
  }

  void _toggle(Set<String> set, String name) {
    if (!set.remove(name)) set.add(name);
    _changed();
  }

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _unansweredDeadline?.cancel();
    live = false;
    catchingUp = false;
    _stopFollowingReconnect();
    _generation++;
    unawaited(_watching?.cancel());
    super.dispose();
  }
}

/// Keeps boards for watched projects and selects an available planning provider.
class PlanningBoards extends ChangeNotifier {
  /// Observes the client's providers without opening anything of its own; with [startTask] and [agents] each board
  /// it makes can start work from its Needs you rows, on every board of the same project.
  PlanningBoards(this.client, {PlanningStartTask? startTask, PlanningAgents? agents}) {
    if (startTask != null && agents != null) {
      startHooks = PlanningStartHooks(
        startTask: startTask,
        agents: agents,
        machines: machinesOf,
        unavailableMachines: unavailableMachinesOf,
      );
    }
    client.addListener(_sync);
    _sync();
  }

  /// The providers of planning and what the window tells them.
  final PlanningClient client;

  /// What a start from a Needs you row asks of the window, handed to each board this makes; null without it.
  PlanningStartHooks? startHooks;

  /// Every board of [board]'s project, by its machine.
  Map<PlanningBoard, String> machinesOf(PlanningBoard board) => {
    for (final source in sources.where((source) => source.project == board.project)) source: machineOf(source),
  };

  /// The machines known now that have not supplied [board]'s project.
  List<String> unavailableMachinesOf(PlanningBoard board) => [
    for (final machine in providerNames)
      if (!sources.any((source) => source.project == board.project && machineOf(source) == machine)) machine,
  ];
  final Map<(String, String), PlanningBoard> _boards = {};
  final Map<String, PlanningPreferences> _preferences = {};
  String? _selected;
  bool _disposed = false;
  final Map<String, bool> _connected = {};

  void _sync() {
    if (_disposed) return;
    if (client case final PlanningTaskReports reports) reports.retainPlanningSources();
    final providers = client.providers;
    final wanted = <(String, String), PlanningSource>{
      for (final provider in providers)
        for (final project in provider.projects)
          if (provider.connected || _boards.containsKey((provider.name, project)))
            (provider.name, project): provider.source,
    };
    for (final key in _boards.keys.toList()) {
      if (!wanted.containsKey(key) || !identical(_boards[key]!.backend, wanted[key])) {
        final removed = _boards.remove(key)!;
        removed.removeListener(_changed);
        removed.dispose();
      }
    }
    for (final entry in wanted.entries) {
      if (_boards[entry.key] case final existing?) {
        final connected = providers.any((provider) => provider.name == entry.key.$1 && provider.connected);
        // A board that failed, such as for a repository not yet named, tries again with each change.
        if (connected && (!existing.live && existing.problem != null || _connected[entry.key.$1] == false)) {
          unawaited(existing.changeProject(entry.value, entry.key.$2));
        }
        continue;
      }
      final board = PlanningBoard(
        entry.value,
        entry.key.$2,
        preferences: _preferences.putIfAbsent(entry.key.$2, PlanningPreferences.new),
      );
      board.startHooks = startHooks;
      board.addListener(_changed);
      _boards[entry.key] = board;
    }
    _connected
      ..clear()
      ..addEntries(providers.map((provider) => MapEntry(provider.name, provider.connected)));
    _changed();
  }

  void _changed() {
    if (_disposed) return;
    _forgetSettled();
    notifyListeners();
  }

  /// Boards holding unsaved work, including providers no longer selected.
  Iterable<PlanningBoard> get pendingBoards =>
      _boards.values.where((board) => board.pending.isNotEmpty || board.saving);

  /// Whether any board holds an unsaved move or a save still waiting for its answer, so a leave is asked first.
  bool get hasPendingMoves => pendingBoards.isNotEmpty;

  /// The provider names known now, for saying which did not supply a project.
  List<String> get providerNames => [for (final provider in client.providers) provider.name];

  Set<PlanningPutAway> _putAway = const {};

  /// The issue rows the window keeps put away; [needsYou] leaves each out while its reason and state stand.
  Set<PlanningPutAway> get putAway => _putAway;

  set putAway(Set<PlanningPutAway> rows) {
    _putAway = Set.unmodifiable(rows);
    _changed();
  }

  /// Forgets each row put away whose issue no longer needs the person, so the same reason raised anew is seen.
  ///
  /// The window reads [putAway] back after a change to keep only what still stands.
  void _forgetSettled() {
    if (_putAway.isEmpty) return;
    final raised = <(String, String, String)>{
      for (final board in sources)
        for (final story in board.stories)
          for (final row in storyAttention(board, story))
            if (row.issue case final issue?) (machineOf(board), board.project, issue.id),
    };
    final standing = {
      for (final kept in _putAway)
        if (raised.contains((kept.machine, kept.project, kept.issue))) kept,
    };
    if (standing.length != _putAway.length) _putAway = Set.unmodifiable(standing);
  }

  /// Ended story runs and stopped readings that need a person, from every source including stale ones, without
  /// the rows the window keeps put away.
  List<PlanningNeedsYou> get needsYou => [
    for (final board in sources)
      for (final story in board.stories)
        for (final row in storyAttention(board, story))
          if (!_putAway.contains(row.putAwayOn(machineOf(board))))
            PlanningNeedsYou(
              row.issue == null
                  ? 'story ended ${machineOf(board)}/${board.project}/${story.id}'
                  : 'reading ${machineOf(board)}/${board.project}/${row.issue!.id}',
              machineOf(board),
              row,
            ),
  ];

  /// The rows of one story that need a person, from its own board: the run's end, then each issue.
  static List<PlanningAttention> storyAttention(PlanningBoard board, PlanStory story) => [
    if (board.endedOf(story) case final ended?) PlanningAttention(board, story, ended: ended),
    for (final issue in attentionIssues(board, story))
      PlanningAttention(
        board,
        story,
        issue: issue,
        ended: issueEnd(board, story, issue.id),
        reason: board.workOf(story)?.issues.where((item) => item.issue == issue.id).firstOrNull?.reason ?? '',
      ),
  ];

  /// Every durable issue reason remains visible, including successful readers whose findings could not save.
  static List<PlanIssue> attentionIssues(PlanningBoard board, PlanStory story) {
    final work = board.workOf(story);
    final ids = <String>{
      for (final issue in story.issues)
        if (issue.readingStopped == true || issue.needsYou == true) issue.id,
      for (final issue in work?.issues ?? <PlanWorkIssue>[])
        if (planReasonNeedsAttention(issue.reason) || issue.needsYou == true || issue.readingStopped == true)
          issue.issue,
    };
    return [for (final id in ids) _attentionIssue(story, work, id)];
  }

  static PlanIssue _attentionIssue(PlanStory story, PlanWork? work, String id) {
    final issue = story.issues.where((issue) => issue.id == id).firstOrNull;
    final result = work?.issues.where((issue) => issue.issue == id).firstOrNull;
    return PlanIssue.from({
      'id': id,
      'repository': issue?.repository ?? '',
      'rounds': result?.rounds ?? issue?.rounds,
      'roundsMost': result?.roundsMost ?? issue?.roundsMost,
      'readingStopped': result?.readingStopped ?? issue?.readingStopped,
      'state': ?issue?.state,
      'attention': ?issue?.attention,
      'worker': ?issue?.worker?.wire,
    });
  }

  /// Pairs the issue with its latest raw task result, even when finished is true.
  static WorkEnded? issueEnd(PlanningBoard board, PlanStory story, String issue) => board
      .workOf(story)
      ?.tasks
      .reversed
      .where((task) => task.issues.contains(issue) && task.ended != null)
      .firstOrNull
      ?.ended;

  /// The machine identity owning this exact source.
  String machineOf(PlanningBoard board) =>
      _boards.entries.where((entry) => identical(entry.value, board)).firstOrNull?.key.$1 ?? board.backend.label;

  /// Every source, including stale boards, for machine-scoped attention rows.
  List<PlanningBoard> get sources => List.unmodifiable(_boards.values);

  /// The project names with at least one provider that has not refused planning.
  List<String> get projects =>
      _boards.entries.where((entry) => !entry.value.unsupported).map((entry) => entry.key.$2).toSet().toList();

  /// The selected board, preferring the current machine whenever it offers planning.
  PlanningBoard? get selected {
    final available = projects;
    final name = available.contains(_selected)
        ? _selected
        : available.contains(client.chosenProject)
        ? client.chosenProject
        : available.firstOrNull;
    if (name == null) {
      return _boards.entries.where((entry) => entry.key.$1 == client.currentProvider).firstOrNull?.value ??
          _boards.values.firstOrNull;
    }
    return _provider(name);
  }

  PlanningBoard? _provider(String name) {
    final candidates = _boards.values.where((board) => board.project == name && !board.unsupported).toList();
    final preferred = _boards[(client.currentProvider, name)];
    bool current(PlanningBoard board) => board.live && board.snapshot?.complete == true;
    bool holdsPending(PlanningBoard board) => board.pending.isNotEmpty || board.saving;
    if (preferred != null && !preferred.unsupported && holdsPending(preferred)) return preferred;
    final pending = candidates.where(holdsPending).firstOrNull;
    if (pending != null) return pending;
    if (preferred != null && !preferred.unsupported && current(preferred)) return preferred;
    return candidates.where(current).firstOrNull ??
        (preferred != null && !preferred.unsupported ? preferred : candidates.firstOrNull);
  }

  /// The stable owner of a board, independent of the current machine selection.
  String? sourceOf(PlanningBoard board) =>
      _boards.entries.where((entry) => identical(entry.value, board)).firstOrNull?.key.$1;

  /// A detail destination stays on its original project and machine.
  PlanningBoard? at(String? source, String? project) => _boards[(source, project)];

  /// Errors from other providers of the selected project, retained even while a live provider supplies it.
  List<String> get providerProblems => problemsFor(selected);

  /// Errors from other providers of the board actually on screen, including a pinned destination.
  List<String> problemsFor(PlanningBoard? shown) => [
    for (final board in _boards.values)
      if (!identical(board, shown) && board.project == shown?.project && board.problem != null)
        '${board.backend.label}: ${board.problem}',
  ];

  /// Selects a project without resetting its view choices.
  void select(String name) {
    _selected = name;
    _changed();
  }

  /// The total from one planning provider per project, unknown while any included board is stale or unread.
  int? get workingCount {
    var count = 0;
    if (_boards.isEmpty || projects.isEmpty) return null;
    for (final project in projects) {
      final working = _provider(project)?.workingCount;
      if (working == null) return null;
      count += working;
    }
    return count;
  }

  @override
  void dispose() {
    _disposed = true;
    client.removeListener(_sync);
    for (final board in _boards.values) {
      board.removeListener(_changed);
      board.dispose();
    }
    _boards.clear();
    super.dispose();
  }
}

/// Preserves a daemon refusal in its own detail words, on one line and at most 4096 characters long.
String planningFailureWords(Object error) {
  final safe = planningShown(_failureWords(error), multiline: false);
  return safe.length > 4096 ? safe.substring(0, 4096) : safe;
}

String _failureWords(Object error) {
  if (error is PlanningRefusal) {
    if (error.name == 'NotYetBuilt') return 'The planner does not offer ${error.parameters['method']} yet.';
    final words = [
      for (final key in ['detail', 'message', 'reason'])
        if (error.parameters[key] is String && (error.parameters[key] as String).trim().isNotEmpty)
          error.parameters[key] as String,
    ].firstOrNull;
    final retry = error.parameters['retryAfter'];
    if (words != null) return '$words${retry is int && retry > 0 ? ' — try again in $retry s' : ''}';
    if (error.name == 'PlanningAdapterError') {
      return '${error.parameters['error']}: ${error.parameters['parameters']}';
    }
    if (error.name == 'EnteredOnlyByWork') return '${error.parameters['to']} can be entered only by work.';
    if (error.name == 'PartlySaved') {
      final pushed = error.parameters['pushed'];
      final failed = error.parameters['failed'];
      return '${error.description.trim()}${pushed is String ? ' Pushed: $pushed.' : ''}'
          '${failed is String ? ' Not pushed: $failed.' : ''}';
    }
    // The planner's refusals carry their sentence here alone.
    if (error.description.trim().isNotEmpty) return error.description;
    return '${error.name}: ${error.parameters}';
  }
  if (error is StateError) return error.message.toString();
  if (error is FormatException) return error.message;
  return '$error';
}

/// A priority chosen for a story, the revision it was chosen against, and whether the planner answered it.
class _PendingPriority {
  _PendingPriority(this.priority, this.revision);

  final String priority;

  final String revision;

  bool answered = false;
}
