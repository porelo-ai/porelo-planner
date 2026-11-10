import 'dart:async';

import 'package:flutter/foundation.dart';

import 'client.dart';
import 'models.dart';
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

  /// State names folded to marks and counts.
  final Set<String> foldedStates = {'In analysis'};

  /// Stories whose issue lists are unfolded.
  final Set<String> expandedStories = {};
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
}

/// A project's board, replaced only by whole replies from its machine.
class PlanningBoard extends ChangeNotifier {
  /// Starts a watch with independently retained view choices.
  PlanningBoard(this.backend, this.project, {PlanningPreferences? preferences})
    : preferences = preferences ?? PlanningPreferences() {
    _listen();
  }

  /// The machine supplying the board.
  PlanningSource backend;

  /// The project name sent to that machine.
  String project;

  /// Choices belonging to the project.
  final PlanningPreferences preferences;

  /// The last whole reply, retained visibly stale after a failure.
  PlanningSnapshot? snapshot;

  /// The daemon's error, or an ended-watch explanation.
  String? problem;

  /// Whether the daemon explicitly lacks this feature.
  bool unsupported = false;

  /// Whether changes are still arriving.
  bool live = false;
  final Map<String, PendingPlanningMove> _pending = {};

  /// Unsaved moves in staging order.
  Map<String, PendingPlanningMove> get pending => Map.unmodifiable(_pending);

  /// Whether a save or reload is in progress.
  bool saving = false;

  /// Save, reload, or drop feedback, using daemon details on failures.
  String? moveProblem;

  /// Confirmation or revision-change feedback from the shared save.
  String? moveNotice;

  (PlanningSource, String)? _queuedProject;
  (PlanningSnapshot, int)? _deferredSnapshot;
  int _replyOrder = 0;
  int _snapshotOrder = 0;
  StreamSubscription<PlanningSnapshot>? _watching;
  bool _disposed = false;

  /// Whether this board has released its listeners and watch.
  bool get disposed => _disposed;

  int _generation = 0;

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
    try {
      _watching = backend
          .watchStories(project)
          .listen(
            (reply) async {
              final order = ++_replyOrder;
              try {
                final prior = snapshot;
                final names = prior?.states.map((state) => state.name).toSet() ?? <String>{};
                final unknown = reply.stories.any(
                  (story) => !names.contains(story.state) || story.issues.any((issue) => !names.contains(issue.state)),
                );
                final workflow = prior == null || unknown ? await backend.planningWorkflow(project) : prior;
                if (!current()) return;
                final combined = _checked(PlanningSnapshot.fromParts(workflow, reply));
                if (saving) {
                  _deferredSnapshot = (combined, order);
                } else {
                  _accept(combined, order: order);
                }
                problem = workflow.faults.isEmpty ? null : workflow.faults.join('\n');
                unsupported = false;
                live = true;
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
              // A final snapshot is a complete answer on daemons without subscriptions.
              notifyListeners();
            },
          );
    } catch (error) {
      unsupported = planningUnsupported(error);
      problem = unsupported ? null : planningFailureWords(error);
      live = false;
    }
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

  /// Work from the watch, or the fallback only when that field was not supplied.
  PlanWork? workOf(PlanStory story) => story.work != null && story.work!.tasksReported
      ? story.work
      : identical(_workSnapshots[story.id], snapshot)
      ? storyWork[story.id]?.work ?? story.work
      : story.work;

  /// Task outcomes are authoritative only after a complete work read.
  WorkEnded? endedOf(PlanStory story) => !_needsWorkRead(story)
      ? story.work?.ended
      : identical(_workSnapshots[story.id], snapshot)
      ? storyWork[story.id]?.ended
      : null;

  bool _needsWorkRead(PlanStory story) =>
      state(story.state)?.kind == 'WORKING' &&
      (!story.workReported || story.work != null && !story.work!.tasksReported);

  /// Whether an ended outcome is current enough to resolve a retained attention row.
  bool endedCurrent(PlanStory story) =>
      !_needsWorkRead(story) ||
      identical(_workSnapshots[story.id], snapshot) &&
          !workProblems.containsKey(story.id) &&
          !workUnsupported.contains(story.id);

  /// Why a story cannot start, preserving unknown work as unknown.
  String? startUnavailable(PlanStory story) {
    if (unavailableActions['StartStoryWork'] ?? unavailableActions['ProposeStoryWork'] case final reason?) {
      return reason;
    }
    if (_disposed || !live || snapshot?.complete != true) return 'the planning board is not live and complete';
    if (snapshot!.revision.isEmpty) return 'the planning revision was not reported';
    final kind = state(story.state)?.kind;
    if (!{'WAITING', 'WORKING', 'CLOSED'}.contains(kind)) return 'the story state does not permit starting work';
    if (story.work == null && (kind == 'WAITING' || kind == 'CLOSED')) return null;
    if (_needsWorkRead(story) && !identical(_workSnapshots[story.id], snapshot)) {
      return workProblems[story.id] ??
          (workUnsupported.contains(story.id)
              ? 'the machine does not offer story work status'
              : 'work status not reported');
    }
    if (_needsWorkRead(story)) {
      if (workProblems[story.id] case final problem?) return problem;
      if (workUnsupported.contains(story.id)) return 'the machine does not offer story work status';
    }
    return switch (workOf(story)?.atWork) {
      false => null,
      true => 'work is already running',
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
        continue;
      }
      try {
        final answer = await source.getStoryWork(name, story.id);
        if (!current()) return;
        if (answer.work != null && !answer.work!.tasksReported) {
          throw const FormatException('The machine did not report the work tasks.');
        }
        storyWork[story.id] = answer;
        _workSnapshots[story.id] = reply;
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

  /// Stops work without moving the story locally.
  Future<void> stopWork(
    String story, {
    required String revision,
    required PlanningSource source,
    required String sourceProject,
  }) async {
    if (_disposed || actionBusy) return;
    if (!live ||
        revision.isEmpty ||
        !identical(backend, source) ||
        project != sourceProject ||
        snapshot?.complete != true ||
        snapshot?.revision != revision ||
        !stories.any((each) => each.id == story && workOf(each)?.atWork == true)) {
      actionProblem = 'The board changed while Stop work was open. Open it again for the current run.';
      _changed();
      return;
    }
    actionBusy = true;
    actionProblem = null;
    final generation = _generation;
    final name = sourceProject;
    _changed();
    try {
      await source.stopStoryWork(name, story);
      if (!_disposed && generation == _generation) await reload();
    } catch (error) {
      if (!_disposed && generation == _generation) {
        reportActionProblem(error);
        if (planningRefused(error, 'NotYetBuilt')) {
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

  /// Replaces the source, canceling its silent watch before accepting another project's replies.
  Future<void> changeProject(PlanningSource next, String name) async {
    if (_disposed) return;
    if (saving) {
      _queuedProject = (next, name);
      return;
    }
    final sameProject = identical(backend, next) && project == name;
    final generation = ++_generation;
    final watching = _watching;
    _watching = null;
    if (watching != null) await watching.cancel();
    if (_disposed || generation != _generation) return;
    backend = next;
    project = name;
    if (!sameProject) {
      snapshot = null;
      _pending.clear();
      moveProblem = null;
      storyWork.clear();
      workProblems.clear();
      _workSnapshots.clear();
      workUnsupported.clear();
      unavailableActions.clear();
    }
    actionBusy = false;
    problem = null;
    unsupported = false;
    live = false;
    _listen();
    notifyListeners();
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
      moveProblem = 'The machine did not report a planning revision. Reload planning before moving stories.';
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

  /// Stages one issue without changing its reported state or any sibling.
  void moveIssue(String id, String to, {PlanIssue? reported}) {
    if (_disposed || saving || snapshot?.complete != true || !live || snapshot!.revision.isEmpty) return;
    final issue = reported?.id == id
        ? reported
        : stories.expand((story) => story.issues).where((issue) => issue.id == id).firstOrNull;
    final target = state(to);
    if (issue == null || issue.state.isEmpty) return;
    final key = 'issue $id';
    if (to == issue.state) {
      undo(key);
      return;
    }
    if (target == null || !{'WAITING', 'CLOSED'}.contains(target.kind)) return;
    _pending[key] = PendingPlanningMove(issue.state, to, isIssue: true);
    moveProblem = null;
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
      throw StateError('The machine did not report a complete planning board and revision.');
    }
    return reply;
  }

  Future<(PlanningSnapshot, int)> _read() async {
    final workflow = await backend.planningWorkflow(project);
    if (_disposed) throw StateError('The planning board was closed.');
    final reply = await backend.stories(project);
    final order = ++_replyOrder;
    return (_checked(PlanningSnapshot.fromParts(workflow, reply)), order);
  }

  void _accept(PlanningSnapshot reply, {required int order, bool reconcile = true}) {
    if (order <= _snapshotOrder) return;
    snapshot = reply;
    _snapshotOrder = order;
    storyWork.clear();
    _workSnapshots.clear();
    workProblems.clear();
    workUnsupported.clear();
    unawaited(_readWork(reply, _generation));
    if (!reconcile || !reply.complete || reply.revision.isEmpty) return;
    final reconciled = <String>[];
    for (final entry in _pending.entries.toList()) {
      final reported = _reportedState(reply, entry.key, entry.value);
      if (reported == null && entry.value.isIssue && reply.stories.any((story) => !story.issuesReported)) {
        continue;
      }
      if (reported == null) {
        _pending.remove(entry.key);
        reconciled.add('${entry.key} is no longer reported by the machine.');
      } else if (reported == entry.value.to) {
        _pending.remove(entry.key);
        reconciled.add('${entry.key} was moved to ${entry.value.to} elsewhere.');
      } else {
        entry.value.from = reported;
      }
    }
    if (reconciled.isNotEmpty) {
      final detail = reconciled.join(' ');
      moveProblem = moveProblem == null ? detail : '$moveProblem $detail';
    }
  }

  void _finishOperation() {
    if (_disposed) return;
    saving = false;
    final deferred = _deferredSnapshot;
    _deferredSnapshot = null;
    if (deferred != null && deferred.$1.complete && deferred.$1.revision.isNotEmpty && deferred.$2 > _snapshotOrder) {
      _accept(deferred.$1, order: deferred.$2);
    }
    _changed();
    final queued = _queuedProject;
    _queuedProject = null;
    if (queued != null) unawaited(changeProject(queued.$1, queued.$2));
  }

  /// Reloads and reconciles pending destinations while retaining their individual refusals.
  Future<void> reload() async {
    if (_disposed || saving) return;
    saving = true;
    final generation = _generation;
    _changed();
    try {
      final reply = await _read();
      if (_disposed || generation != _generation) return;
      moveProblem = null;
      _accept(reply.$1, order: reply.$2);
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
    for (final move in _pending.values) {
      move.problem = null;
    }
    _changed();
    try {
      _checked(snapshot!);
      final reply = await backend.saveMoves(project, moves, revision);
      if (_disposed || generation != _generation) return;
      if (!reply.complete || reply.revision.isEmpty) {
        throw const FormatException('The machine did not report the saved revision and moved issues.');
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
          _pending[entry.key]?.problem = 'The machine did not confirm this destination.';
        }
      }
      _accept(
        PlanningSnapshot.saved(previous, reply.revision, destinations, storyDestinations),
        order: ++_replyOrder,
        reconcile: false,
      );
      if (_pending.isEmpty) {
        moveNotice = batch.values.every((move) => move.isIssue) ? 'Issue changes saved.' : 'Planning changes saved.';
      }
      _changed();
      // SaveMoves does not return computed story states; read them from the store.
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
      _accept(latest, order: ++_replyOrder);
    } on PlanningRefusal catch (error) {
      if (_disposed || generation != _generation) return;
      final detail = planningFailureWords(error);
      final item = error.parameters['item'] ?? error.parameters['issue'] ?? error.parameters['story'];
      final named = item is String && batch.containsKey(item)
          ? item
          : item is String
          ? 'issue $item'
          : null;
      final affected = named is String && batch.containsKey(named) ? [named] : batch.keys;
      if (error.name != 'StaleRevision') {
        for (final key in affected) {
          _pending[key]?.problem = detail;
        }
      }
      if (error.name == 'NotYetBuilt') unavailableActions['SaveMoves'] = detail;
      if (error.name == 'StaleRevision') {
        moveProblem = detail;
        moveNotice = 'Planning changed. Review the pending moves and save again.';
        try {
          final reply = await _read();
          if (!_disposed && generation == _generation) {
            for (final move in _pending.values) {
              move.problem = null;
            }
            moveProblem = null;
            _accept(reply.$1, order: reply.$2);
            moveNotice = _pending.isEmpty
                ? 'The pending moves were made elsewhere.'
                : 'Planning changed. Review the pending moves and save again.';
          }
        } catch (readError) {
          if (!_disposed && generation == _generation) moveProblem = '$detail ${planningFailureWords(readError)}';
        }
      }
    } catch (error) {
      if (!_disposed && generation == _generation) {
        moveProblem = planningFailureWords(error);
        for (final key in batch.keys) {
          _pending[key]?.problem = moveProblem;
        }
      }
    } finally {
      _finishOperation();
    }
  }

  /// The reported states, without inventing any workflow.
  List<PlanState> get states => snapshot?.states ?? const [];

  /// The reported phases.
  List<PlanPhase> get phases => snapshot?.phases ?? const [];

  /// The reported stories, independent of the filters.
  List<PlanStory> get stories => snapshot?.stories ?? const [];

  /// Stories in states the daemon calls WORKING, or null before a snapshot or after losing the watch.
  int? get workingCount =>
      snapshot?.complete != true || !live || stories.any((story) => state(story.state)?.kind.isEmpty ?? true)
      ? null
      : stories.where((story) => state(story.state)?.kind == 'WORKING').length;

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
    _ => 4,
  };
  bool _matches(PlanStory story) {
    final query = preferences.search.trim().toLowerCase();
    return (preferences.priority.maximum == null || rank(story.priority) <= preferences.priority.maximum!) &&
        (query.isEmpty || story.id.toLowerCase().contains(query) || story.title.toLowerCase().contains(query));
  }

  List<PlanStory> _sort(Iterable<PlanStory> stories) => stories.where(_matches).toList()
    ..sort((a, b) {
      final priority = rank(a.priority).compareTo(rank(b.priority));
      if (priority != 0) return priority;
      int? number(String id) => int.tryParse(RegExp(r'\d+$').firstMatch(id)?.group(0) ?? '');
      final left = number(a.id), right = number(b.id);
      return left != null && right != null && left != right ? left.compareTo(right) : a.id.compareTo(b.id);
    });

  /// The filtered, sorted stories of exactly this reported state.
  List<PlanStory> cards(String name) => _sort(stories.where((story) => displayedState(story) == name));

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
    live = false;
    _generation++;
    unawaited(_watching?.cancel());
    super.dispose();
  }
}

/// Keeps boards for watched projects and selects an available planning provider.
class PlanningBoards extends ChangeNotifier {
  /// Observes the client's providers without opening anything of its own.
  PlanningBoards(this.client) {
    client.addListener(_sync);
    _sync();
  }

  /// The providers of planning and what the window tells them.
  final PlanningClient client;
  final Map<(String, String), PlanningBoard> _boards = {};
  final Map<String, PlanningPreferences> _preferences = {};
  String? _selected;
  bool _disposed = false;
  final Map<String, bool> _connected = {};

  void _sync() {
    if (_disposed) return;
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
        if (connected && _connected[entry.key.$1] == false) {
          unawaited(existing.changeProject(entry.value, entry.key.$2));
        }
        continue;
      }
      final board = PlanningBoard(
        entry.value,
        entry.key.$2,
        preferences: _preferences.putIfAbsent(entry.key.$2, PlanningPreferences.new),
      );
      board.addListener(_changed);
      _boards[entry.key] = board;
    }
    _connected
      ..clear()
      ..addEntries(providers.map((provider) => MapEntry(provider.name, provider.connected)));
    _changed();
  }

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  /// Boards holding unsaved work, including providers no longer selected.
  Iterable<PlanningBoard> get pendingBoards =>
      _boards.values.where((board) => board.pending.isNotEmpty || board.saving);

  /// Whether any board holds an unsaved move or a save still waiting for its answer, so a leave is asked first.
  bool get hasPendingMoves => pendingBoards.isNotEmpty;

  /// The provider names known now, for saying which did not supply a project.
  List<String> get providerNames => [for (final provider in client.providers) provider.name];

  /// Ended story runs and stopped readings that need a person, from every source including stale ones.
  List<PlanningNeedsYou> get needsYou => [
    for (final board in sources)
      for (final story in board.stories) ...[
        if (board.endedOf(story) case final ended?)
          PlanningNeedsYou(
            'story ended ${machineOf(board)}/${board.project}/${story.id}',
            machineOf(board),
            PlanningAttention(board, story, ended: ended),
          ),
        for (final issue in story.issues.where((issue) => issue.readingStopped == true))
          PlanningNeedsYou(
            'reading ${machineOf(board)}/${board.project}/${issue.id}',
            machineOf(board),
            PlanningAttention(board, story, issue: issue),
          ),
      ],
  ];

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

/// Preserves a daemon refusal in its own detail words.
String planningFailureWords(Object error) {
  if (error is PlanningRefusal) {
    if (error.name == 'NotYetBuilt') return 'This machine does not offer ${error.parameters['method']} yet.';
    final words = [
      for (final key in ['detail', 'message', 'reason'])
        if (error.parameters[key] is String && (error.parameters[key] as String).trim().isNotEmpty)
          error.parameters[key] as String,
    ].firstOrNull;
    final retry = error.parameters['retryAfter'];
    if (words != null) return '$words${retry is int && retry > 0 ? ' — try again in $retry s' : ''}';
    if (error.name == 'PlanningAdapterError') {
      final diagnostic = '${error.parameters['error']}: ${error.parameters['parameters']}';
      final safe = diagnostic.replaceAll(RegExp(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]'), '');
      return safe.length > 4096 ? safe.substring(0, 4096) : safe;
    }
    if (error.name == 'EnteredOnlyByWork') return '${error.parameters['to']} can be entered only by work.';
    return '${error.name}: ${error.parameters}';
  }
  if (error is StateError) return error.message.toString();
  if (error is FormatException) return error.message;
  return '$error';
}
