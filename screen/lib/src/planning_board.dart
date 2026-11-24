import 'dart:async';

import 'package:flutter/foundation.dart';

import 'client.dart';
import 'issue_filter.dart';
import 'open_work_filter.dart';
import 'models.dart';
import 'planning_limits.dart';
import 'hooks.dart';
import 'run_attention.dart';
import 'seconds.dart';
import 'task_runs.dart';
import 'story_work.dart';
import 'ui/open_work_figures.dart';

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

  /// Each phase's last vertical position by phase name, retained across provider replacements; each phase scrolls on
  /// its own.
  final Map<String, double> phaseOffsets = {};

  /// Each column at the board's end for a state no phase shows, its last vertical position by the state's value;
  /// apart from [phaseOffsets], so no phase's name can stand for one of them.
  final Map<String, double> unplacedOffsets = {};

  /// The last horizontal board position, retained across provider replacements.
  double horizontalOffset = 0;

  /// Phase names folded to marks and counts: only Done, which holds the closed stories, so a first open shows every
  /// open story's card whole.
  final Set<String> foldedPhases = {'Done'};

  /// State names folded to their heading; every box starts open.
  final Set<String> foldedStates = {};

  /// Stories whose issue lists are unfolded.
  final Set<String> expandedStories = {};

  /// States whose card of issues in no story is unfolded.
  final Set<String> expandedNoStoryStates = {};

  /// States whose card shows all issues in no story rather than the first twenty.
  final Set<String> allNoStoryStates = {};

  /// Stories whose page shows its description folded to its heading.
  final Set<String> foldedDescriptions = {};

  final Map<String, PlanningIssueFilter> _issueLists = {};

  /// The search and sort of [story]'s issue list on its page, kept across leaving the page.
  PlanningIssueFilter issueList(String story) => _issueLists.putIfAbsent(story, PlanningIssueFilter.new);

  /// The filter and sort of Open work, kept across leaving it.
  final PlanningOpenWorkFilter openWork = PlanningOpenWorkFilter();
}

/// The one move in flight - previewed, asked about, saved or unanswered - kept apart from the planner's state, so its
/// card stands at its target until the answer.
class PendingPlanningMove {
  /// Records the state the person saw and the target.
  PendingPlanningMove(
    this.from,
    this.to, {
    this.isIssue = false,
    this.inNoStory = false,
    this.corrects,
    this.version = '',
  });

  /// The warning this move sets right as its correction offers, whose preview is always asked about; null for a move
  /// the person made on the board.
  final PlanWarning? corrects;

  /// Whether this destination belongs to an issue rather than a story.
  final bool isIssue;

  /// Whether this issue was read separately from stories, so its absence needs that list at the same revision.
  final bool inNoStory;

  /// The state the person saw when the move began.
  String from;

  /// The version of the moved item the move was judged on, as the person saw it; empty when none was shown.
  final String version;

  /// The requested destination.
  final String to;

  /// Whether a save of this move went unanswered in time and may still land.
  bool unanswered = false;
}

class _StoryStart {
  bool pending = true;
  bool confirmed = false;
}

/// A move whose preview warned, or one into a closed state whose store keeps a reason: it waits for the person's
/// Confirm or Cancel, and nothing is saved before.
class PlanningMoveQuestion {
  /// Takes the move, its preview and the revision the preview was answered for; [asksReason] offers the reason, which
  /// starts as the one the move carries, so a move asked about again keeps what was typed.
  PlanningMoveQuestion(
    this.key,
    this.move,
    this.preview,
    this.revision, {
    this.again = false,
    this.asksReason = false,
    this.corrects,
  }) : reason = ValueNotifier(move.reason ?? '');

  /// The warning the move sets right as its correction offers, or null for a move the person made on the board.
  final PlanWarning? corrects;

  /// Whether the person may give a reason why the item is closed, which may stay empty.
  final bool asksReason;

  /// The reason as typed, trimmed when it is sent.
  final ValueNotifier<String> reason;

  /// Why the typed reason cannot be sent, as the planner would refuse it; null when it can.
  String? get reasonRefused => asksReason ? planningReasonRefusal(reason.value) : null;

  /// The move's key in [PlanningBoard.pending].
  final String key;

  /// The move as it is saved.
  final PlanningMove move;

  /// The preview shown, with the warnings the window added.
  final PlanningMovePreview preview;

  /// The revision the save goes against.
  final String revision;

  /// Whether planning changed since the person confirmed it, so it is asked anew.
  final bool again;

  /// What the person confirms.
  List<PlanningMoveWarning> get warnings => preview.warnings;
}

/// A correction other than a move whose preview waits for the person's Confirm or Cancel; nothing is saved before.
class PlanningCorrectionQuestion {
  /// Takes the correction, its preview and the warning it sets right.
  const PlanningCorrectionQuestion(this.correction, this.preview, this.warning);

  /// The correction as it is saved.
  final PlanningCorrection correction;

  /// What the planner answered it would write.
  final PlanningCorrectionPreview preview;

  /// The warning it sets right, as the board showed it.
  final PlanWarning warning;
}

/// A project's board, replaced only by whole replies from its machine.
class PlanningBoard extends ChangeNotifier {
  /// Starts a watch with independently retained view choices.
  ///
  /// [unansweredWait] and [readWait] are their defaults times [limits]' `services` unless given.
  PlanningBoard(
    this.backend,
    this.project, {
    PlanningPreferences? preferences,
    this.limits = PlanningLimits.ones,
    Duration? unansweredWait,
    Duration? readWait,
  }) : preferences = preferences ?? PlanningPreferences(),
       unansweredWait = unansweredWait ?? limits.servicesOf(defaultUnansweredWait),
       readWait = readWait ?? limits.servicesOf(defaultReadWait) {
    _listen();
  }

  /// How long moves of a save answered too late wait by default, before the factor `services`: past the planner's
  /// own bounds on a save, 30 s for the store's lock and a few Git calls of 60 s each.
  static const defaultUnansweredWait = Duration(minutes: 5);

  /// How long a read beside the moves may take by default, before the factor `services`: the planner's own bound on
  /// a read, which the HTTP source also keeps.
  static const defaultReadWait = Duration(seconds: 30);

  /// The factors this board's limits follow.
  final PlanningLimits limits;

  /// The machine supplying the board.
  PlanningSource backend;

  /// The project name sent to that machine.
  String project;

  /// Choices belonging to the project.
  final PlanningPreferences preferences;

  /// How long moves of a save answered too late may wait to show on the board before they are told as not saved.
  final Duration unansweredWait;

  /// How long a read beside the moves may take before it is told as failed, so a hang holds no Reload for good.
  final Duration readWait;

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

  /// Whether a Reload opens the watch anew, until its first board or its end: the board shown meanwhile keeps
  /// taking moves, each saved against the revision it shows.
  bool reopening = false;

  /// A Reload's reopen asked for while a save ran, a move was asked about or a story action waited, opened once that
  /// ends.
  bool _reopenQueued = false;

  /// Whether a Reload's read of the story list runs; it holds no move.
  bool _reloading = false;

  /// Counts every save that landed, so a watch's first board read before one of them is read again after it.
  int _savesLanded = 0;

  /// Counts every move or story-list change asked for and every move refused, so a Reload's read clears no word of
  /// one made while it read.
  int _moveTurns = 0;

  /// Whether the board shown takes moves: a complete board whose watch is live, or one a Reload opens anew.
  bool get takesMoves => snapshot?.complete == true && (live || reopening);

  /// How far the planner's read of the board is while it reads, as its watch tells it; null otherwise.
  PlanningLoad? loading;

  /// Why the planner's last read of the whole board failed, in words, while the board it had stands; null once a
  /// board came.
  String? readProblem;

  /// The refusal behind [readProblem], kept whole so a part of the screen can answer it; null with it.
  PlanningRefusal? readRefusal;

  /// Whether a Reload's read of the whole board runs at the planner; a change made meanwhile is saved at once.
  bool fetching = false;

  /// Whether the planner only looks for changes on a live board: told as a line, never as a read of the board.
  bool get lookingLive => live && loading?.look == true;

  /// Whether Reload waits: a save, a Reload or the planner's read of the board runs; a look on a live board does not
  /// hold it.
  bool get reloadHeld => saving || fetching || loading != null && !lookingLive;

  /// How long a Reload's bar stands after the planner answered, until its watch tells the read behind the answer.
  @visibleForTesting
  static Duration fetchTold = const Duration(seconds: 10);
  Timer? _fetchWait;

  /// Whether the watch tells a Reload's read, which then runs behind the refresh's answer.
  bool _tellsReads = false;

  /// Ends a Reload's own bar: the watch told the read, or a board, or it told nothing in time.
  void _endFetch() {
    _fetchWait?.cancel();
    _fetchWait = null;
    if (!fetching) return;
    fetching = false;
    if (!_disposed) notifyListeners();
  }

  VoidCallback? _unfollowReconnect;
  final Map<String, PendingPlanningMove> _pending = {};

  /// The moves in flight by card: the one previewed, asked about or saved, those answered too late, and those
  /// queued; where a card's next move waits behind its own save, that next move, whose destination is shown.
  Map<String, PendingPlanningMove> get pending =>
      Map.unmodifiable({..._pending, for (final queued in _queue) queued.key: queued.shown});

  /// The move [key]'s card shows: its queued next move, else its move in flight.
  PendingPlanningMove? _shownMove(String key) =>
      _queue.where((queued) => queued.key == key).lastOrNull?.shown ?? _pending[key];

  /// Moves made while a save was out, in the order made, at most one per card; each is previewed and saved once the
  /// save before it answered and was read again, against the revision it left. A card's next move made while its own
  /// save is out waits here too, from the state that save goes to. Their cards show them as pending meanwhile.
  final List<_QueuedMove> _queue = [];

  /// The keys of the queued moves, in their order.
  List<String> get queuedMoves => [for (final queued in _queue) queued.key];

  /// Whether [key]'s card has only a queued move, with nothing of it sent yet.
  bool _queuedOnly(String key) => _queue.any((queued) => queued.key == key && identical(_pending[key], queued.shown));

  bool get _anyUnanswered => _pending.values.any((move) => move.unanswered);

  /// Reads after a save not yet ended; the queue waits for them, so a queued move is judged on the board they read.
  int _readsAfterSave = 0;

  /// The planner's words for the last move of a card it refused, by the card's key, with the state the card stood in
  /// then; kept until the card moves again or the board shows it elsewhere.
  final Map<String, ({String words, String? state})> _cardRefusals = {};

  /// Why the last move of [key]'s card was refused, or null.
  String? refusalOf(String key) => _cardRefusals[key]?.words;

  /// What [key]'s card says of its move: being saved, waiting in the queue, or waiting for the watch; null without
  /// one.
  String? movingWords(String key) {
    if (_pending[key] == null && !_queue.any((queued) => queued.key == key)) return null;
    if (_pending[key] != null && !_queuedOnly(key)) return 'Saving…';
    return takesMoves ? 'Waiting for the moves before it…' : 'Waiting for planning to be live';
  }

  /// The state [key]'s card stands in on [reply]: a story's, or an issue's on it or in no story; null where unknown.
  String? _stateOfKey(PlanningSnapshot reply, String key) => key.startsWith('issue ')
      ? [
          ...reply.stories.expand((story) => story.issues),
          if (_noStory != null && _noStory!.revision == reply.revision) ..._noStory!.issues,
        ].where((issue) => 'issue ${issue.id}' == key).firstOrNull?.state
      : reply.stories.where((story) => story.id == key).firstOrNull?.state;

  void _addProblem(String words) {
    final now = moveProblem;
    if (now != null && now.split('\n').contains(words)) return;
    moveProblem = now == null ? words : '$now\n$words';
  }

  void _addNotice(String words) {
    final now = moveNotice;
    if (now != null && now.split('\n').contains(words)) return;
    moveNotice = now == null ? words : '$now\n$words';
  }

  /// Takes back [key]'s refused move and says [words] on its card; a next move queued behind it goes too, said.
  /// Answers the words said.
  String _refuseCard(String key, String words) {
    _pending.remove(key);
    var said = words;
    final next = _queue.where((queued) => queued.key == key).lastOrNull;
    if (next != null) {
      _queue.removeWhere((queued) => queued.key == key);
      said = '$words\n${planningShown('Its next move to ${next.shown.to} was not made.')}';
    }
    _cardRefusals[key] = (words: said, state: snapshot == null ? null : _stateOfKey(snapshot!, key));
    return said;
  }

  /// Drops every queued move and its pending card; nothing of it is saved.
  void _dropQueue() {
    for (final queued in _queue) {
      if (identical(_pending[queued.key], queued.shown)) _pending.remove(queued.key);
    }
    _queue.clear();
  }

  /// Starts the next queued move once nothing holds the moves, the board takes them, no story action, project switch
  /// or reopen waits to run first, no save answered too late may still land, and every read after a save ended. A
  /// move whose card no longer stands where it was made from is refused, and the queue goes on.
  void _drainQueue() {
    if (_disposed || _queue.isEmpty || holdsMoves || !takesMoves || question != null || actionBusy) return;
    if (_queuedProject != null || _reopenQueued || snapshot!.revision.isEmpty) return;
    if (_anyUnanswered || _readsAfterSave > 0) return;
    final next = _queue.first;
    if (!_sameBoard(next.source, next.name)) {
      _queue.removeAt(0);
      if (identical(_pending[next.key], next.shown)) _pending.remove(next.key);
      _changed();
      return _drainQueue();
    }
    final shown = next.shown;
    final now = _stateOfKey(snapshot!, next.key);
    // An issue in no story is judged once its list is read at the board's revision.
    if (now == null && shown.isIssue && _noStory?.revision != snapshot!.revision) return;
    _queue.removeAt(0);
    _pending[next.key] = shown;
    final moved = now != shown.from;
    if (moved) {
      // Never saved from a state the person did not see.
      _addProblem(
        _refuseCard(
          next.key,
          planningShown('Planning changed: ${next.move.id} now stands in ${now ?? 'no state'}; it was not moved.'),
        ),
      );
      _changed();
      return _drainQueue();
    }
    _dropCorrection();
    unawaited(_startMove(next.key, next.move, snapshot!.revision, drained: true));
  }

  /// Whether a move, a save or a reload is in progress.
  bool saving = false;

  /// The move whose warnings wait for the person's choice, or null.
  PlanningMoveQuestion? question;

  /// Save, reload, or drop feedback, using daemon details on failures.
  String? moveProblem;

  /// The refused save, including typed details needed after a partial push.
  PlanningRefusal? moveRefusal;

  /// The last refusal, retained until a new board for reads or the next action for saves.
  PlanningRefusal? refusal;

  /// The order of the last board received when [refusal] was kept; a board received later clears a read's refusal.
  int _refusalOrder = 0;

  /// Whether [refusal] answered the person's own action, which a board read cannot undo, so it waits for the next one.
  bool _actionRefusal = false;

  void _keepRefusal(Object error, {bool action = false}) {
    if (_actionRefusal && !action) return;
    refusal = error is PlanningRefusal ? error : null;
    _refusalOrder = _replyOrder;
    _actionRefusal = action;
  }

  void _clearRefusal({bool action = false}) {
    if (_actionRefusal && !action) return;
    _actionRefusal = false;
    refusal = null;
  }

  /// The refusals a read that progresses proves wrong: the planner answers, and it reads the forge with the person's
  /// access.
  static const _disprovedByProgress = {
    'TimedOut',
    'PlannerUnreachable',
    'PlannerSilent',
    'NoAddress',
    'NoForgeAccess',
    'Unreachable',
  };

  /// The words a Reload's refused fetch left in [moveProblem], so a read's progress can take them back.
  String? _reloadProblem;

  /// Takes back, at a read's progress, every line an earlier step left that the progress proves wrong, an action's
  /// too, and at a read's start the failure of the read before; every other refusal stays.
  void _progressed({required bool began}) {
    final kept = refusal;
    if (kept != null && _disprovedByProgress.contains(kept.name)) {
      _actionRefusal = false;
      refusal = null;
    }
    if (_reloadProblem != null && moveProblem == _reloadProblem) moveProblem = null;
    _reloadProblem = null;
    if (began) {
      readProblem = null;
      readRefusal = null;
    }
  }

  void _beginAction() {
    // A read's refusal, such as missing forge access, holds until a board read shows it mended.
    if (_actionRefusal) _clearRefusal(action: true);
    actionProblem = null;
    moveNotice = null;
  }

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

  /// Whether the window marked a row of Needs you seen, handed in by the boards that made this board.
  bool Function(PlanningAttention row)? rowSeen;

  /// Whether the window marked seen every row of [story] that needs a person, its run's end among them, so its card
  /// no longer says so.
  bool attentionSeen(PlanStory story) {
    final seen = rowSeen;
    if (seen == null) return false;
    final rows = PlanningBoards.storyAttention(this, story);
    return rows.isNotEmpty && rows.every(seen);
  }

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
    // A save that lands after the watch opened may be missing from its first board, which the planner read before.
    final landedAtOpen = _savesLanded;
    var firstBoard = true;
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

      // The planner's read of the board, said while it reads, so a long first read shows how far it is.
      final progress = watch is PlanningWatch ? watch.loading : null;
      _tellsReads = watch is PlanningWatch && watch.tellsReads;
      void read() {
        if (!current() || loading == progress?.value) return;
        final began = loading == null;
        loading = progress?.value;
        if (loading != null) _progressed(began: began);
        // The read behind a Reload is told now: its bar is the watch's from here. A look the Reload may wait behind
        // is not that read.
        if (loading?.look != true) _endFetch();
        notifyListeners();
      }

      // A read that failed after the first board: said over the board, which stands, until the next one.
      final failed = watch is PlanningWatch ? watch.readFailed : null;
      void readFailed() {
        if (!current()) return;
        final refusal = failed?.value;
        readRefusal = refusal;
        readProblem = refusal == null
            ? null
            : planningShown('The planner could not read the board again: ${planningFailureWords(refusal)}');
        if (refusal != null) _endFetch();
        notifyListeners();
      }

      progress?.addListener(read);
      failed?.addListener(readFailed);
      if (follows != null) follows.addListener(changed);
      _unfollowReconnect = () {
        progress?.removeListener(read);
        failed?.removeListener(readFailed);
        follows?.removeListener(changed);
      };
      _watching = watch.listen(
        (reply) async {
          _endFetch();
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
              if (order > _refusalOrder) _clearRefusal();
              problem = workflow.faults.isEmpty ? null : planningShown(workflow.faults.join('\n'));
              unsupported = false;
              if (!ended && receivedVersion == reconnectVersion && !recovering) catchingUp = false;
              live = !ended && !catchingUp;
              reopening = false;
              if (firstBoard) {
                firstBoard = false;
                // Read again after it, so the save shows over the older board, numbered later than it.
                if (_savesLanded != landedAtOpen) unawaited(_readAfterSave(source, name));
              }
            }

            if (saving) {
              // Held back, the board is not yet shown, so the watch stays stale until it is.
              // A board that read its workflow first may come after a newer one.
              if (order > (_deferredSnapshot?.$2 ?? 0)) _deferredSnapshot = (combined, order, settle);
            } else {
              _accept(combined, order: order);
              settle();
              // Moves queued while the watch was lost go on once it answers.
              _drainQueue();
            }
            notifyListeners();
          } catch (error) {
            if (!current()) return;
            _keepRefusal(error);
            problem = planningFailureWords(error);
            live = false;
            reopening = false;
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
          _keepRefusal(error);
          problem = unsupported ? null : planningFailureWords(error);
          live = false;
          reopening = false;
          notifyListeners();
          // A retry can start without waiting again on this terminal subscription's cancellation.
          unawaited(watching?.cancel());
        },
        onDone: () {
          if (!current()) return;
          _watching = null;
          ended = true;
          live = false;
          reopening = false;
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
      _keepRefusal(error);
      unsupported = planningUnsupported(error);
      problem = unsupported ? null : planningFailureWords(error);
      live = false;
      reopening = false;
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

  /// Whether [passOver] names the run shown and exactly the reason that holds the start: its issue's, with no other
  /// issue raised for another reason and no task at work; the versions the start sends bind it to the issue shown.
  bool _passes(PlanStory story, PlanningRunAttention attention, PlanningPassOver? passOver) {
    final work = workOf(story);
    if (passOver == null ||
        work == null ||
        work.atWork == true ||
        work.run != passOver.run ||
        !PlanningPassOver.reasons.contains(passOver.reason)) {
      return false;
    }
    return attention.issues.any((issue) => issue.issue == passOver.issue && issue.reason == passOver.reason) &&
        attention.issues.every((issue) => issue.reason == passOver.reason && !issue.readingStopped);
  }

  /// Why a run cannot be stopped now, or null when it can: one at work, ended with reasons or halted can.
  String? stopUnavailable(PlanStory story) {
    if (unavailableActions['StopStoryWork'] case final reason?) return reason;
    // Unlike a move, kept on a live watch: a stop ends the tasks of the run shown, which a board whose watch opens
    // anew may show at work after it ended or another started.
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
    if (heldBy(story.warnings) case final words?) return words;
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
    _keepRefusal(error, action: true);
    actionProblem = planningFailureWords(error);
    _changed();
  }

  /// Says why [story]'s start, which goes on after its form closed, was refused or failed; the person's own cancel of
  /// a start in the window is no fault, as for an idea's discussion.
  void reportStartProblem(String story, Object error) {
    if (_disposed || planningRefused(error, 'NotStarted')) return;
    _keepRefusal(error, action: true);
    actionProblem = 'Start work on ${planningShown(story, multiline: false)}: ${planningFailureWords(error)}';
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
    discussing = true;
    _beginAction();
    _changed();
    try {
      final task = await source.discussIdea(name, idea);
      // A board closed or switched meanwhile starts nothing the person no longer sees.
      if (!_sameBoard(source, name)) return;
      await start(source, name, '', task);
    } catch (error) {
      // The person cancelled the Start: their own choice, no fault, and the discussion stays offered.
      if (planningRefused(error, 'NotStarted')) return;
      if (_sameBoard(source, name)) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['DiscussIdea'] = planningFailureWords(error);
        }
      }
    } finally {
      // A watch that failed or opened anew meanwhile leaves the board the discussion was asked on.
      if (_sameBoard(source, name)) {
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
    _beginAction();
    final name = sourceProject;
    _changed();
    try {
      final answer = await source.stopStoryWork(name, story, run: run);
      tasks.observe(answer.work);
      if (_sameBoard(source, name)) applyWork(story, answer);
      await tasks.changes?.stop(source, name, story, [
        for (final task in answer.work?.tasks ?? <PlanWorkTask>[])
          if (task.atWork == true) PlanningTaskStarted(task: task.task, container: task.containerId),
      ]);
      if (_sameBoard(source, name)) await reload();
    } catch (error) {
      if (_sameBoard(source, name)) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['StopStoryWork'] = planningFailureWords(error);
        }
      }
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// The versions the board shows of [ids], by id, from its stories, their issues, the issues in no story read at its
  /// revision and the issues made outside the project; an id it does not show is left out, so the planner names it as
  /// missing rather than taking it as seen.
  Map<String, String> seenOf(Iterable<String> ids) {
    final wanted = ids.toSet();
    final shown = snapshot;
    final versions = shown == null ? <String, String>{} : shown.versionsOf(wanted);
    final list = _noStory;
    if (list != null && shown != null && list.revision == shown.revision) {
      for (final issue in list.issues) {
        if (wanted.contains(issue.id) && issue.version.isNotEmpty) versions.putIfAbsent(issue.id, () => issue.version);
      }
    }
    return versions;
  }

  /// The versions the board shows of [story], each of its issues and their sub-issues, and of [more], by id: what a
  /// decision over the story's work saw.
  Map<String, String> seenOfStory(String story, [Iterable<String> more = const []]) {
    final issues = snapshot?.stories.where((each) => each.id == story).firstOrNull?.issues ?? const <PlanIssue>[];
    final ids = {for (final issue in issues) issue.id};
    return seenOf({
      story,
      ...ids,
      for (final each in stories.expand((each) => each.issues))
        if (ids.contains(each.parent)) each.id,
      ...more,
    });
  }

  /// The project's repositories, its own first, as the last whole board named them; empty before one did.
  List<String> get repositories => _repositories;

  // Kept apart from the snapshot: a board read again by its workflow and stories names no repositories.
  List<String> _repositories = const [];

  /// What the last whole board found wrong in the project's repositories themselves; empty before one named it.
  List<PlanRepositoryWarning> get repositoryWarnings => _repositoryWarnings;

  // Kept apart from the snapshot as the repositories are, which a story list does not name either.
  List<PlanRepositoryWarning> _repositoryWarnings = const [];

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
    actionBusy = true;
    _beginAction();
    _changed();
    try {
      // A new issue in no story changes no item that stands, so it names no versions.
      return (await source.createIssue(
        name,
        repository,
        title,
        mustBeTrue,
        size,
        const {},
        worker: worker,
        task: task,
      )).id;
    } catch (error) {
      if (_sameBoard(source, name)) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['CreateIssue'] = planningFailureWords(error);
        }
      }
      return null;
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  Future<bool> _saveChoice({
    required String id,
    required String? seen,
    required String revision,
    required PlanningSource source,
    required String name,
    required ({String? value})? Function(PlanningSnapshot) valueOn,
    required Future<void> Function(Map<String, String>) save,
  }) async {
    try {
      await save(seenOf([id]));
      return true;
    } catch (error, stack) {
      if (!planningRefused(error, 'ItemChanged')) rethrow;
      if (!_sameBoard(source, name)) return false;
      if (snapshot?.revision == revision) {
        final (PlanningSnapshot, int) reply;
        try {
          reply = await _read();
        } catch (_) {
          // The board read's own failure reaches the person through the watch; the action stays refused as stale.
          Error.throwWithStackTrace(error, stack);
        }
        if (!_sameBoard(source, name)) return false;
        _acceptNewest(reply.$1, reply.$2);
      }
      if (!_sameBoard(source, name)) return false;
      final current = snapshot;
      if (current == null || !current.complete || current.revision.isEmpty || current.revision == revision) {
        rethrow;
      }
      final found = valueOn(current);
      if (found == null) {
        reportActionProblem('$id is no longer on the board; choose again.');
        return false;
      }
      if (found.value != seen) {
        reportActionProblem('$id changed meanwhile to ${found.value ?? 'none'}; choose again.');
        return false;
      }
      // Changed elsewhere but not in what was chosen: saved again over the version now shown.
      await save(seenOf([id]));
      return true;
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
          : heldBy(snapshot!.stories.firstWhere((reported) => reported.id == story).warnings));

  /// Sets [story]'s priority, one of [planningPriorities], against the revision the board shows; the choice stays
  /// pending until a board carries it. Answers whether the planner took it; a refusal's words stand in
  /// [actionProblem] as every board action's do.
  Future<bool> setPriority(String story, String priority) async {
    if (priorityUnavailable(story) case final reason?) {
      actionProblem = 'No priority: $reason.';
      _changed();
      return false;
    }
    _beginAction();
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    final seen = snapshot!.stories.firstWhere((each) => each.id == story).priority;
    final pending = _PendingPriority(priority, revision);
    _priorities[story] = pending;
    actionBusy = true;
    _changed();
    try {
      final saved = await _saveChoice(
        id: story,
        seen: seen,
        revision: revision,
        source: source,
        name: name,
        valueOn: (board) => switch (board.stories.where((each) => each.id == story).firstOrNull) {
          final found? => (value: found.priority),
          null => null,
        },
        save: (seen) async {
          pending.revision = snapshot?.revision ?? revision;
          await source.setPriority(name, story, priority, seen);
        },
      );
      if (!saved) {
        if (_sameBoard(source, name) && identical(_priorities[story], pending)) _priorities.remove(story);
        return false;
      }
      if (_sameBoard(source, name) && identical(_priorities[story], pending)) {
        pending.answered = true;
        // A board that arrived before the answer may carry it already.
        if (snapshot case final current?) _reconcilePriorities(current);
      }
      return true;
    } catch (error) {
      if (_sameBoard(source, name)) {
        if (identical(_priorities[story], pending)) _priorities.remove(story);
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['SetPriority'] = planningFailureWords(error);
        }
      }
      return false;
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// The saves the planner keeps for the person on this board, as the last whole board named them; empty when none.
  List<PlanHeldSave> get heldSaves => snapshot?.heldSaves ?? const [];

  /// Why the held save [save] cannot be resent or dropped now, or null when it can.
  String? heldSaveUnavailable(String save) => _disposed || !live
      ? 'the planning board is not live'
      : actionBusy || saving
      ? 'waiting for the planner'
      : !heldSaves.any((held) => held.id == save)
      ? 'the save is no longer held'
      : null;

  /// Sends the held save [save] again; answers whether the planner wrote it. The board that follows no longer holds
  /// it; a refusal's words stand in [actionProblem].
  Future<bool> resendSave(String save) async {
    final held = heldSaves.where((each) => each.id == save).firstOrNull;
    if (held != null && !held.resendable) {
      actionProblem = 'The save was not resent: the planner does not resend it.';
      _changed();
      return false;
    }
    return _heldSaveCall(save, 'resent', (source, name) => source.resendSave(name, save));
  }

  /// Drops the held save [save], writing nothing; answers whether the planner dropped it.
  Future<bool> dropSave(String save) => _heldSaveCall(save, 'dropped', (source, name) => source.dropSave(name, save));

  Future<bool> _heldSaveCall(String save, String done, Future<void> Function(PlanningSource, String) call) async {
    if (heldSaveUnavailable(save) case final reason?) {
      actionProblem = 'The save was not $done: $reason.';
      _changed();
      return false;
    }
    _beginAction();
    final source = backend;
    final name = project;
    actionBusy = true;
    _changed();
    try {
      await call(source, name);
      return true;
    } catch (error) {
      if (_sameBoard(source, name)) reportActionProblem(error);
      return false;
    } finally {
      if (_sameBoard(source, name)) _endAction();
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

  /// Why the labels of [item], a story when [story] holds and an issue otherwise, cannot be changed now, or null when
  /// they can.
  String? labelsUnavailable(String item, {required bool story}) =>
      unavailableActions['SetLabels'] ??
      (_disposed || !live
          ? 'the planning board is not live'
          : snapshot?.complete != true
          ? 'the planning board is not complete'
          : snapshot!.revision.trim().isEmpty
          ? 'the planning revision was not reported'
          : actionBusy || saving
          ? 'waiting for the planner'
          : switch (_labelled(snapshot!, item, story: story)) {
              null => story ? 'the story is not on the board' : 'the issue is not on the board',
              final found => heldBy(found.warnings),
            });

  /// The labels and warnings of [item] on [board], a story's when [story] holds; null when the board does not show it.
  ({List<String> labels, List<PlanWarning> warnings})? _labelled(
    PlanningSnapshot board,
    String item, {
    required bool story,
  }) {
    if (story) {
      final found = board.stories.where((each) => each.id == item).firstOrNull;
      return found == null ? null : (labels: found.labels, warnings: found.warnings);
    }
    final found = [
      ...board.stories.expand((each) => each.issues),
      ...noStory,
    ].where((each) => each.id == item).firstOrNull;
    return found == null ? null : (labels: found.labels, warnings: found.warnings);
  }

  /// The topic labels among [labels], in any case, in the order of [planningTopicLabels], as one value to compare.
  static String _topicOf(List<String> labels) {
    final carried = {for (final label in labels) ?planningTopicLabelOf(label)};
    return [
      for (final label in planningTopicLabels)
        if (carried.contains(label)) label,
    ].join(',');
  }

  /// Sets the topic labels [item] carries after the save, a story's when [story] holds, against the revision the board
  /// shows; a label of the person's own on it stays. Answers the planner's words for a refusal, which also stand in
  /// [actionProblem], or null once it is saved.
  Future<String?> setLabels(String item, List<String> labels, {required bool story}) async {
    if (labelsUnavailable(item, story: story) case final reason?) {
      actionProblem = 'No labels change: $reason.';
      _changed();
      return actionProblem;
    }
    _beginAction();
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    final chosen = [
      for (final label in planningTopicLabels)
        if (labels.contains(label)) label,
    ];
    actionBusy = true;
    _changed();
    try {
      final saved = await _saveChoice(
        id: item,
        seen: _topicOf(_labelled(snapshot!, item, story: story)!.labels),
        revision: revision,
        source: source,
        name: name,
        valueOn: (board) => switch (_labelled(board, item, story: story)) {
          final found? => (value: _topicOf(found.labels)),
          null => null,
        },
        save: (seen) async {
          await source.setLabels(name, item, chosen, seen, story: story);
        },
      );
      if (!saved) return actionProblem ?? 'The board changed before the labels could be saved.';
      return null;
    } catch (error) {
      if (_sameBoard(source, name)) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['SetLabels'] = planningFailureWords(error);
        }
      }
      return planningFailureWords(error);
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// Why who works [issue] cannot be changed now, or null when it can.
  String? workerUnavailable(PlanIssue issue) =>
      unavailableActions['SetWorker'] ??
      (issue.parent != null
          ? 'a sub-issue is worked as its parent ${issue.parent} is'
          : issue.worker == null
          ? 'the planner did not report who works it'
          // Unlike a move, kept on a live watch: who works an issue decides whether a run takes it, and a board whose
          // watch opens anew may show a run that ended or miss one that started.
          : _disposed || !live
          ? 'the planning board is not live'
          : snapshot?.complete != true
          ? 'the planning board is not complete'
          : snapshot!.revision.trim().isEmpty
          ? 'the planning revision was not reported'
          : actionBusy || saving
          ? 'waiting for the planner'
          : _moving(issue)
          ? 'it or a sub-issue has a move being saved'
          : null);

  /// Whether [issue] or one of its sub-issues has a move being saved, which a worker change would make the save refuse.
  bool _moving(PlanIssue issue) {
    final moving = pendingIssues.keys.toSet();
    return moving.contains(issue.id) ||
        [
          ...stories.expand((story) => story.issues),
          ...noStory,
        ].any((each) => each.parent == issue.id && moving.contains(each.id));
  }

  /// Sets who works [issue] against the revision the board shows; answers the planner's words for a refusal, which
  /// also stand in [actionProblem], or null once it is saved.
  Future<String?> setWorker(PlanIssue issue, IssueWorker worker) async {
    if (workerUnavailable(issue) case final reason?) {
      actionProblem = 'No worker change: $reason.';
      _changed();
      return actionProblem;
    }
    _beginAction();
    final source = backend;
    final name = project;
    final revision = snapshot!.revision;
    actionBusy = true;
    _changed();
    try {
      final saved = await _saveChoice(
        id: issue.id,
        seen: issue.worker?.wire,
        revision: revision,
        source: source,
        name: name,
        valueOn: (board) =>
            switch (board.stories.expand((story) => story.issues).where((each) => each.id == issue.id).firstOrNull) {
              final found? => (value: found.worker?.wire),
              null => null,
            },
        save: (seen) async {
          await source.setWorker(name, issue.id, worker, seen);
        },
      );
      if (!saved) return actionProblem ?? 'The board changed before the worker could be saved.';
      return null;
    } catch (error) {
      if (_sameBoard(source, name)) {
        reportActionProblem(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          unavailableActions['SetWorker'] = planningFailureWords(error);
        }
      }
      return planningFailureWords(error);
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// Ends a story action's wait for the planner, on the board it was asked on whatever its watch did meanwhile; a
  /// Reload's reopen asked for during it opens now.
  void _endAction() {
    actionBusy = false;
    _changed();
    if (_reopenQueued) unawaited(_reopen());
    // Moves made while it waited are saved against the board it left.
    _drainQueue();
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
    // A move still asked about was never confirmed, so it goes with the project it was shown for, said, and the save it
    // held ends, so the switch runs now.
    if (question case final asked?) {
      _dropQuestion();
      _addProblem(planningShown('${asked.move.id} was not moved: planning was opened anew before it was confirmed.'));
      _reopenQueued = false;
      _finishOperation(drain: false);
    }
    // Moves queued on this board are never saved against another one.
    if (!identical(backend, next) || project != name) _dropQueue();
    if (saving) {
      _queuedProject = (next, name);
      return;
    }
    final sameProject = identical(backend, next) && project == name;
    // Story ids repeat across projects, so a fold made in one must not fold a card of another.
    if (project != name) {
      preferences.expandedNoStoryStates.clear();
      preferences.allNoStoryStates.clear();
    }
    final generation = ++_generation;
    _stopFollowingReconnect();
    catchingUp = false;
    live = false;
    reopening = false;
    _reopenQueued = false;
    final watching = _watching;
    _watching = null;
    if (watching != null) await watching.cancel();
    if (_disposed || generation != _generation) return;
    backend = next;
    project = name;
    _noStory = null;
    noStoryProblem = null;
    if (!sameProject) {
      snapshot = null;
      _repositories = const [];
      _repositoryWarnings = const [];
      _pending.clear();
      _priorities.clear();
      moveProblem = null;
      storyWork.clear();
      workProblems.clear();
      _workSnapshots.clear();
      workUnsupported.clear();
      unavailableActions.clear();
      _clearRefusal(action: true);
      actionProblem = null;
      _queue.clear();
      _cardRefusals.clear();
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

  /// Reads the board again from the planner's store, without a fetch, once a save under way has ended: the project's
  /// store was opened anew on another directory, whose board stands in for the one shown. A watch that ended, as on a
  /// directory refused, is opened anew; a live one is read beside. Nothing is read once another board is shown.
  Future<void> readAgain() async {
    final source = backend;
    final name = project;
    while (saving && !_disposed && _sameBoard(source, name)) {
      await _nextChange();
    }
    if (_disposed || !_sameBoard(source, name)) return;
    if (live) return reload();
    return retry();
  }

  Future<void> _nextChange() {
    final changed = Completer<void>();
    void once() {
      removeListener(once);
      if (!changed.isCompleted) changed.complete();
    }

    addListener(once);
    return changed.future;
  }

  /// Brings the board to what the repositories hold now, whatever its state: the planner fetches them, and a stream
  /// that is down or a board whose watch failed has its watch opened anew, whose first answer is the board read anew.
  /// The board shown keeps taking moves meanwhile.
  Future<void> reloadPlanning() async {
    if (_disposed || saving || fetching) return;
    _beginAction();
    if (live) return reload(fetch: true);
    final source = backend;
    final name = project;
    String? refused;
    Object? refusedError;
    // Taken at once, so a move made while the planner fetches is saved against the board shown.
    reopening = snapshot?.complete == true;
    fetching = true;
    _changed();
    // Moves queued while the watch was lost go on against the board shown.
    _drainQueue();
    try {
      await source.refreshProject(name);
    } catch (error) {
      // The watch is opened anew all the same; a fetch the planner refused is said, not taken for a fresh board.
      refused = planningFailureWords(error);
      refusedError = error;
    } finally {
      fetching = false;
    }
    if (!_sameBoard(source, name)) return;
    moveProblem = refused;
    // A fetch that timed out while the read it began goes on is taken back by that read's progress.
    _reloadProblem = refusedError is PlanningRefusal && _disprovedByProgress.contains(refusedError.name)
        ? refused
        : null;
    if (_queuedProject != null) {
      // A project chosen meanwhile is opened by the save's end instead.
      reopening = false;
      _changed();
      return;
    }
    await _reopen();
    // Kept past the new watch's start, so a board it reads afterwards is what clears it.
    if (refusedError != null && _sameBoard(source, name)) {
      _keepRefusal(refusedError);
      _changed();
    }
  }

  /// Opens this project's watch anew and keeps everything shown: the board, its moves in flight, its figures and the
  /// view's choices. While a save runs, a move is asked about or a story action waits for the planner, it waits for
  /// their end, so their answer reaches the board they were made on.
  Future<void> _reopen() async {
    if (_disposed) return;
    if (saving || question != null || actionBusy) {
      _reopenQueued = true;
      _changed();
      return;
    }
    _reopenQueued = false;
    reopening = snapshot?.complete == true;
    final generation = ++_generation;
    _stopFollowingReconnect();
    catchingUp = false;
    live = false;
    final watching = _watching;
    _watching = null;
    if (watching != null) await watching.cancel();
    if (_disposed || generation != _generation) return;
    problem = null;
    unsupported = false;
    _listen();
    _changed();
    _drainQueue();
  }

  /// The displayed destination: the move in flight's target until its answer.
  String displayedState(PlanStory story) {
    final move = _shownMove(story.id);
    final destination = move == null ? null : state(move.to);
    return move != null && destination != null && phases.any((phase) => phase.states.contains(move.to))
        ? move.to
        : story.state;
  }

  /// Moves a story to a waiting or closed state at once: previewed, then saved, or first asked about when the preview
  /// warns.
  ///
  /// [revision] is the board's revision when the gesture began; a board that changed during the gesture moves nothing,
  /// since the person chose on items that may stand otherwise now.
  Future<void> move(String id, String to, {String? revision}) async {
    if (_disposed || !takesMoves) return;
    _dropCorrection();
    final story = stories.where((story) => story.id == id).firstOrNull;
    final target = state(to);
    if (story == null || target == null) return;
    if (!{'WAITING', 'CLOSED'}.contains(target.kind)) return;
    if (!storyMayEnter(story, target)) {
      // A move the board offers no drop for is not sent; one a warning holds is said.
      if (heldBy(story.warnings) case final held?) return refuseMove(id, held);
      return;
    }
    if (snapshot!.revision.isEmpty) {
      moveProblem = 'The planner did not report a planning revision. Reload planning before moving stories.';
      _changed();
      return;
    }
    // Against where the card is shown: a move back while its move is out is its next move.
    if (to == displayedState(story) || _oneAtATime(id, queues: true)) return;
    await _moveNow(id, PlanningStoryMove(id, to), PendingPlanningMove(story.state, to), revision: revision);
  }

  /// The issue move in flight, by issue id.
  Map<String, PendingPlanningMove> get pendingIssues => {
    for (final entry in pending.entries)
      if (entry.value.isIssue) entry.key.substring('issue '.length): entry.value,
  };

  /// Whether a person may move [issue] into [target]: a waiting or closed state, or a working one for an issue a
  /// person works, or for an agent's issue no run of this planner works, which shows work started elsewhere.
  /// An issue a warning holds may only leave a state the workflow does not have, as [storyMayEnter] says.
  bool issueMayEnter(PlanIssue issue, PlanState? target) =>
      target != null &&
      (PlanWarning.holding(issue.warnings)
          ? _heldMayEnter(issue.state, issue.warnings, target)
          : {'WAITING', 'CLOSED', if (issue.byHand || !runHolds(issue)) 'WORKING'}.contains(target.kind));

  /// Whether a person may drop [story] on [target]. An open story follows its issues: a closed state closes it, a
  /// working one starts its work, and no other open state is offered. A closed story is only reopened, into a waiting
  /// state. A story a warning holds may only leave a state the workflow does not have, into a closed state, which
  /// sets it right; the planner refuses it every other move.
  bool storyMayEnter(PlanStory story, PlanState? target) {
    if (target == null) return false;
    if (PlanWarning.holding(story.warnings)) {
      return _heldMayEnter(story.state, story.warnings, target) && target.kind == 'CLOSED';
    }
    // Judged where the card is shown, so a card whose close is still out is offered its reopen.
    return state(displayedState(story))?.kind == 'CLOSED'
        ? target.kind == 'WAITING'
        : {'WORKING', 'CLOSED'}.contains(target.kind);
  }

  /// Whether an item standing in [from] that one of its [warnings] holds may still enter [target]: only into a waiting
  /// or closed state, since a working one would start its work, and only out of a state the workflow does not have
  /// that its one holding warning is about; a second holding warning, such as an id named twice, holds the move too.
  bool _heldMayEnter(String from, List<PlanWarning> warnings, PlanState target) =>
      _leaves(from, warnings) && {'WAITING', 'CLOSED'}.contains(target.kind);

  /// Whether an item standing in [from] may move out of it although its [warnings] hold it: its state is none of the
  /// workflow's, and only one warning holds it, the one about that state.
  bool _leaves(String from, List<PlanWarning> warnings) =>
      state(from) == null && warnings.where((warning) => warning.holds).length == 1;

  /// Whether an item standing in [from] is held where it stands: one of its [warnings] holds it, and no move out of a
  /// state the workflow does not have is open to it, so it takes no drag.
  bool heldInPlace(String from, List<PlanWarning> warnings) =>
      PlanWarning.holding(warnings) && !_leaves(from, warnings);

  /// Why the planner holds back an item with [warnings], in the words of those that hold it; null when none does.
  static String? heldBy(List<PlanWarning> warnings) {
    final words = [
      for (final warning in warnings)
        if (warning.holds) planningShown(warning.text.isEmpty ? warning.kind : warning.text, multiline: false),
    ];
    return words.isEmpty ? null : 'the planner holds it: ${words.join(' ')}';
  }

  /// Whether a run of this planner works [issue] now: at work on it, or a run of a story holding it or its parent
  /// with a task at work or not ended. A story whose work was not reported counts as one, so no move goes beside a
  /// run the screen cannot see; the planner's refusal is the last word on offers the screen does not show.
  bool runHolds(PlanIssue issue) =>
      issue.atWork == true ||
      stories.any(
        (story) =>
            (!story.workReported || _working(workOf(story))) &&
            story.issues.any((each) => each.id == issue.id || issue.parent != null && each.id == issue.parent),
      );

  static bool _working(PlanWork? work) =>
      work != null && (work.atWork != false || work.tasks.any((task) => task.ended == null));

  /// Whether every issue of [story] in a working state is one a person works, so no run is missing for it.
  bool workedByHand(PlanStory story) {
    final working = story.issues.where((issue) => state(issue.state)?.kind == 'WORKING');
    return working.isNotEmpty && working.every((issue) => issue.byHand);
  }

  /// Moves one issue at once, as [move] moves a story.
  ///
  /// [shown] is the state the person saw when choosing, or [reported]'s; [revision] is the board's when the choice was
  /// offered.
  Future<void> moveIssue(String id, String to, {PlanIssue? reported, String? shown, String? revision}) async {
    if (_disposed) return;
    _dropCorrection();
    if (_oneAtATime(id, queues: true)) return;
    if (!takesMoves || snapshot!.revision.isEmpty) {
      return refuseMove(id, 'the board is not live and complete.');
    }
    // Judged on the board's issue: the page's copy may be older or newer in any field.
    final onBoard = [
      ...stories.expand((story) => story.issues),
      ...noStory,
    ].where((issue) => issue.id == id).firstOrNull;
    // Absence is known only after both parts of the board have been read at its revision.
    final complete = stories.every((story) => story.issuesReported) && _noStory?.revision == snapshot?.revision;
    final issue = onBoard ?? (complete || reported?.id != id ? null : reported);
    if (issue == null) return refuseMove(id, 'it is no longer on the board.');
    if (issue.state.isEmpty) return refuseMove(id, 'its state was not reported.');
    // A menu shows the issue as it stood when it opened; a task may have moved it since.
    final seen = shown ?? (reported?.id == id ? reported!.state : null);
    if (seen != null && seen != issue.state) return refuseMove(id, 'it now stands in ${issue.state}; choose again.');
    final target = state(to);
    if (to == (_shownMove('issue $id')?.to ?? issue.state)) return;
    if (!issueMayEnter(issue, target)) {
      return refuseMove(
        id,
        PlanWarning.holding(issue.warnings) ? heldBy(issue.warnings)! : 'it may not enter $to from ${issue.state}.',
      );
    }
    await _moveNow(
      'issue $id',
      PlanningIssueMove(id, to),
      PendingPlanningMove(
        issue.state,
        to,
        isIssue: true,
        inNoStory: noStory.any((each) => each.id == id),
        version: issue.version,
      ),
      revision: revision,
    );
  }

  /// The correction [warnings] offer that the window knows, with the warning offering it; null when none does.
  static (PlanWarning, PlanCorrection)? offeredCorrection(List<PlanWarning> warnings, {required bool story}) {
    for (final warning in warnings) {
      final correction = warning.correction;
      if (correction == null) continue;
      // A Move sets an issue's state, a Priority a story's; any other is offered as none.
      if (correction.kind == (story ? PlanCorrection.priority : PlanCorrection.move)) return (warning, correction);
    }
    return null;
  }

  /// Why no correction can be asked for now, or null when one can: one change is asked about or saved at a time.
  String? get correctionUnavailable => _disposed || !takesMoves || snapshot!.revision.isEmpty
      ? 'the board is not live and complete'
      : holdsMoves ||
            _queue.isNotEmpty ||
            _anyUnanswered ||
            actionBusy ||
            question != null ||
            correctionQuestion != null
      ? 'another change is being saved; set it right once it is saved'
      : null;

  /// Moves issue [id] into [to] to set its warning of kind [warning] right, as that warning's correction offers on the
  /// board shown: its preview is always asked about, and nothing is saved before the person confirms. [shown] is the
  /// state the person saw when choosing, [revision] the board's then.
  Future<void> correctIssue(String id, String warning, String to, {String? shown, String? revision}) async {
    if (_disposed) return;
    if (correctionUnavailable case final why?) return refuseMove(id, '$why.');
    final found = [...stories.expand((story) => story.issues), ...noStory].where((issue) => issue.id == id).toList();
    if (found.length != 1) return refuseMove(id, 'it is not on the board once.');
    final issue = found.single;
    if (shown != null && shown != issue.state) return refuseMove(id, 'it now stands in ${issue.state}; choose again.');
    final offered = offeredCorrection(issue.warnings, story: false);
    if (offered == null || offered.$1.kind != warning || !offered.$2.choices.contains(to)) {
      return refuseMove(id, 'the board no longer offers that correction; choose again.');
    }
    if (state(to) == null) return refuseMove(id, 'the workflow has no state $to.');
    await _moveNow(
      'issue $id',
      PlanningIssueMove(id, to),
      PendingPlanningMove(
        issue.state,
        to,
        isIssue: true,
        inNoStory: noStory.any((each) => each.id == id),
        corrects: offered.$1,
      ),
      revision: revision,
    );
  }

  /// The correction other than a move whose preview waits for the person's Confirm or Cancel; null when none does.
  PlanningCorrectionQuestion? correctionQuestion;

  /// Sets story [id]'s warning of kind [warning] right with [choice], as that warning's correction offers on the board
  /// shown: the planner previews it, and the preview waits in [correctionQuestion] for the person's confirm. [revision]
  /// is the board's when the choice was offered; a board changed since is refused. A refusal is said in
  /// [actionProblem], and the board is read again.
  Future<void> correctStory(String id, String warning, String choice, {String? revision}) async {
    if (_disposed) return;
    if (unavailableActions['SaveCorrection'] ?? correctionUnavailable case final why?) {
      return _correctionRefused(id, why);
    }
    if (revision != null && revision != snapshot!.revision) {
      return _correctionRefused(id, 'the board changed since it was offered; choose again');
    }
    final found = stories.where((story) => story.id == id).toList();
    if (found.length != 1) return _correctionRefused(id, 'it is not on the board once');
    final offered = offeredCorrection(found.single.warnings, story: true);
    if (offered == null || offered.$1.kind != warning || !offered.$2.choices.contains(choice)) {
      return _correctionRefused(id, 'the board no longer offers that correction; choose again');
    }
    await _askCorrection(PlanningCorrection(story: id, warning: warning, choice: choice), offered.$1);
  }

  /// Sets repository [repository]'s warning of kind [warning] right, as its Labels correction offers on the board
  /// shown: the planner previews every call it would make on the forge, and the preview waits in
  /// [correctionQuestion] for the person's confirm. A refusal is said in [actionProblem], and the board is read again.
  Future<void> correctRepository(String repository, String warning) async {
    if (_disposed) return;
    if (unavailableActions['SaveCorrection'] ?? correctionUnavailable case final why?) {
      return _correctionRefused(repository, why);
    }
    final found = repositoryWarnings.where((each) => each.repository == repository && each.kind == warning).toList();
    final offered = found.length == 1 ? found.single.correction : null;
    if (offered == null || offered.kind != PlanCorrection.labels) {
      return _correctionRefused(repository, 'the board no longer offers that correction; choose again');
    }
    await _askCorrection(
      PlanningCorrection(repository: repository, warning: warning),
      PlanWarning(warning, found.single.words, correction: offered),
    );
  }

  /// Asks the planner to preview [correction], which sets [warning] right, and keeps the preview in
  /// [correctionQuestion] for the person's confirm; nothing is saved.
  Future<void> _askCorrection(PlanningCorrection correction, PlanWarning warning) async {
    final id = correction.id;
    _beginAction();
    actionBusy = true;
    _changed();
    final source = backend;
    final name = project;
    final asked = snapshot!.revision;
    try {
      final preview = await source.previewCorrection(name, correction);
      if (!_sameBoard(source, name)) return;
      // A preview of another change than the one asked must never be confirmed as this one.
      if (!preview.answers(correction)) {
        throw const PlanningRefusal('BadAnswer', {}, 'The planner did not answer a preview of the correction asked.');
      }
      // The planner previews the items as it holds them; one the board shows at another version changed meanwhile.
      final shown = seenOf(preview.targets.keys);
      final changed = [
        for (final target in preview.targets.entries)
          if (shown[target.key] != target.value) target.key,
      ];
      if (changed.isNotEmpty || snapshot?.revision != asked) {
        throw PlanningRefusal('ItemChanged', {
          'changed': changed.isEmpty ? [id] : changed,
        });
      }
      correctionQuestion = PlanningCorrectionQuestion(correction, preview, warning);
    } catch (error) {
      if (_sameBoard(source, name)) await _correctionFailed(source, name, id, error);
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// Saves the correction [correctionQuestion] holds, sending its preview's targets back as seen with its digest. A
  /// refusal is said in [actionProblem], and the board is read again.
  Future<void> confirmCorrection() async {
    final asked = correctionQuestion;
    if (_disposed || asked == null) return;
    correctionQuestion = null;
    _beginAction();
    actionBusy = true;
    _changed();
    final source = backend;
    final name = project;
    final id = asked.correction.id;
    try {
      await source.saveCorrection(name, asked.correction, asked.preview.targets, digest: asked.preview.digest);
      if (!_sameBoard(source, name)) return;
      _addNotice(planningShown('$id was set right.'));
      _savesLanded++;
      await _readAfterSave(source, name);
    } catch (error) {
      if (_sameBoard(source, name)) await _correctionFailed(source, name, id, error);
    } finally {
      if (_sameBoard(source, name)) _endAction();
    }
  }

  /// Drops the correction asked about; nothing was sent.
  void cancelCorrection() {
    if (_disposed || correctionQuestion == null) return;
    correctionQuestion = null;
    _changed();
  }

  /// Drops the correction asked about, unsaved, before another change goes out: its preview was of the board before.
  void _dropCorrection() {
    if (correctionQuestion == null) return;
    correctionQuestion = null;
    _changed();
  }

  void _correctionRefused(String id, String why) {
    _beginAction();
    actionProblem = planningShown('$id was not set right: $why.');
    _changed();
  }

  /// Says why a correction of [id] was refused, in the screen's words, and reads the board again, since the warning
  /// it set right may have changed or gone.
  Future<void> _correctionFailed(PlanningSource source, String name, String id, Object error) async {
    _keepRefusal(error, action: true);
    final words = planningShown('$id was not set right: ${planningFailureWords(error)}');
    if (planningUnsupported(error)) unavailableActions['SaveCorrection'] = planningFailureWords(error);
    try {
      final reply = await _read();
      if (_sameBoard(source, name)) _acceptNewest(reply.$1, reply.$2);
    } catch (readError) {
      if (_sameBoard(source, name)) {
        actionProblem = '$words\n${planningFailureWords(readError)}';
        _changed();
      }
      return;
    }
    if (!_sameBoard(source, name)) return;
    actionProblem = words;
    _changed();
  }

  PlanIssueList? _noStory;
  int _noStoryRead = 0;
  Future<void>? _readingNoStory;
  bool _catchingUpNoStory = false;

  /// Why the latest list of issues in no story could not be read.
  String? noStoryProblem;

  /// Issues in no story from exactly the board's revision; a newer board hides an earlier list immediately.
  List<PlanIssue> get noStory =>
      _noStory != null && _noStory!.revision == snapshot?.revision ? _noStory!.issues : const [];

  /// Issues in no story in [name], under the board's search and priority filter; they have no story priority.
  List<PlanIssue> noStoryIn(String name) {
    if (preferences.priority.maximum != null && rank(null) > preferences.priority.maximum!) return const [];
    final query = preferences.search.trim().toLowerCase();
    return noStory
        .where(
          (issue) =>
              issue.state == name &&
              (query.isEmpty || issue.id.toLowerCase().contains(query) || issue.title.toLowerCase().contains(query)),
        )
        .toList();
  }

  Future<void> _readNoStory(PlanningSnapshot shown, int generation, {required bool reconcile}) async {
    final mayCatchUp = !_catchingUpNoStory;
    final read = ++_noStoryRead;
    final source = backend;
    final name = project;
    bool current() => !_disposed && generation == _generation && read == _noStoryRead && identical(snapshot, shown);
    noStoryProblem = null;
    try {
      // Revisions are opaque: equality is the only safe proof that both reads describe the same board.
      for (var attempt = 0; attempt < 3; attempt++) {
        final reply = await source.listIssues(name);
        if (!current()) return;
        if (!reply.reported || reply.revision.isEmpty) {
          throw const PlanningRefusal(
            'BadAnswer',
            {},
            'The planner did not report a complete issue list and revision.',
          );
        }
        if (reply.revision != shown.revision) continue;
        _noStory = reply;
        if (reconcile) _reconcileMoves(shown);
        _changed();
        // A queued issue move waits for this list to be judged.
        _drainQueue();
        return;
      }
      if (mayCatchUp && !saving) {
        // A list ahead of the shown board cannot catch up by rereading the list alone.
        _catchingUpNoStory = true;
        try {
          await reload();
        } finally {
          _catchingUpNoStory = false;
        }
        if (!current()) return;
      }
      throw const PlanningRefusal(
        'IssueListBehind',
        {},
        'The issues in no story could not be read at the board revision.',
      );
    } catch (error) {
      if (!current()) return;
      _noStory = null;
      noStoryProblem = planningFailureWords(error);
      _keepRefusal(error);
      _changed();
    }
  }

  Future<void> _waitForNoStory(int generation, {bool reconcile = true}) async {
    while (_current(generation)) {
      // A streamed board held during the save must be included before the caller sees the save finish.
      final deferred = _deferredSnapshot;
      _deferredSnapshot = null;
      if (deferred != null) {
        _accept(deferred.$1, order: deferred.$2, reconcile: reconcile);
        deferred.$3();
      }
      final reading = _readingNoStory;
      if (reading == null) return;
      await reading;
      if (identical(reading, _readingNoStory) && _deferredSnapshot == null) return;
    }
  }

  /// Appends an issue in no story to the story's complete list at the revision currently shown.
  Future<void> hangIssue(String issue, String story, {String? revision}) async {
    if (_disposed) return;
    _dropCorrection();
    if (_oneAtATime(issue) || _oneAtATime(story)) return;
    if (actionBusy) {
      moveProblem = 'Waiting for the planner before changing the story list.';
      _changed();
      return;
    }
    final target = stories.where((each) => each.id == story).firstOrNull;
    if (!takesMoves ||
        snapshot?.complete != true ||
        snapshot!.revision.isEmpty ||
        revision != null && revision != snapshot!.revision ||
        target == null ||
        !target.issuesReported ||
        !noStory.any((each) => each.id == issue)) {
      moveProblem = 'The board or the complete issue list changed. Choose the story again.';
      _changed();
      return;
    }
    final source = backend;
    final name = project;
    final issues = [for (final each in target.issues) each.id, issue];
    // The story and the issue hung in are the items the list changes.
    final seen = seenOf([story, issue]);
    _moveTurns++;
    saving = true;
    moveProblem = null;
    moveRefusal = null;
    moveNotice = null;
    _changed();
    var refused = false;
    var readAfter = false;
    try {
      await source.setStoryIssues(name, story, issues, seen);
      if (!_sameBoard(source, name)) return;
      _savesLanded++;
      moveNotice = 'Issue changes saved.';
      // The story list is read once the save has ended, so a slow read holds no move; the queue waits for it.
      readAfter = true;
      _readsAfterSave++;
    } catch (error) {
      if (!_sameBoard(source, name)) return;
      refused = true;
      moveProblem = planningFailureWords(error);
      if (error is PlanningRefusal && {'ItemChanged', 'BasisIncomplete', 'PartlySaved'}.contains(error.name)) {
        try {
          final reply = await _read();
          if (!_sameBoard(source, name)) return;
          _acceptNewest(reply.$1, reply.$2, reconcile: error.name != 'PartlySaved');
        } catch (readError) {
          if (!_sameBoard(source, name)) return;
          moveProblem = '$moveProblem\n${planningFailureWords(readError)}';
        }
      }
      _keepRefusal(error);
      moveRefusal = error is PlanningRefusal ? error : null;
    } finally {
      if (_sameBoard(source, name)) {
        await _waitForNoStory(_generation, reconcile: !refused);
        if (_sameBoard(source, name)) _finishOperation(reconcile: !refused);
      }
    }
    if (readAfter) {
      if (_sameBoard(source, name)) {
        await _readAfterSave(source, name, counted: true);
      } else {
        _readsAfterSave--;
      }
    }
  }

  /// The issue made outside the project whose add is out, or null; its row offers no second add meanwhile.
  String? listing;

  /// Adds an issue the board offers as made outside the project to the project, at the revision currently shown.
  Future<void> addUnlistedIssue(String issue, {String? revision}) async {
    if (_disposed) return;
    _dropCorrection();
    if (_oneAtATime(issue)) return;
    if (actionBusy || listing != null) {
      moveProblem = 'Waiting for the planner before adding an issue.';
      _changed();
      return;
    }
    if (!takesMoves ||
        snapshot!.revision.isEmpty ||
        revision != null && revision != snapshot!.revision ||
        !unlisted.any((each) => each.id == issue)) {
      moveProblem = '$issue was not added: the board no longer offers it.';
      _changed();
      return;
    }
    final source = backend;
    final name = project;
    final seen = seenOf([issue]);
    final story = unlisted.firstWhere((each) => each.id == issue).story;
    _moveTurns++;
    saving = true;
    listing = issue;
    moveProblem = null;
    moveRefusal = null;
    moveNotice = null;
    _changed();
    var refused = false;
    var readAfter = false;
    try {
      await source.addUnlistedIssue(name, issue, seen);
      if (!_sameBoard(source, name)) return;
      _savesLanded++;
      moveNotice = '$issue added to the project.';
      readAfter = true;
      _readsAfterSave++;
    } catch (error) {
      if (!_sameBoard(source, name)) return;
      refused = true;
      final words = planningFailureWords(error);
      final refusedAs = error is PlanningRefusal ? error.name : null;
      moveProblem = refusedAs != 'PartlySaved'
          ? '$issue was not added: $words'
          : story
          ? '$issue may stand on the project without its priority and rank: $words'
          : '$issue may stand on the project outside the workflow, without its state: $words';
      // The planner streams the board whenever the add changed it or found it changed, so the offer shown follows that;
      // a read of the parts would carry no offer.
      _keepRefusal(error);
      moveRefusal = error is PlanningRefusal ? error : null;
    } finally {
      if (_sameBoard(source, name)) {
        listing = null;
        _finishOperation(reconcile: !refused);
      }
    }
    if (readAfter) {
      if (_sameBoard(source, name)) {
        await _readAfterSave(source, name, counted: true);
      } else {
        _readsAfterSave--;
      }
    }
  }

  /// The open issues of the project's repositories that are no items of the project, which the screen only shows.
  List<PlanUnlistedIssue> get unlisted => snapshot?.unlisted ?? const [];

  /// Each breach of the stories' rules the last whole board named; only shown, it holds no move back.
  List<PlanRuleBreach> get rules => snapshot?.rules ?? const [];

  /// The words of each breach of the stories' rules naming [item]; empty when it breaks none.
  List<String> rulesOf(String item) => [
    for (final breach in rules)
      if (breach.item == item) breach.words,
  ];

  /// Says why a move of [id] was not made, where the board and the story's page show it.
  void refuseMove(String id, String why) {
    _beginAction();
    _moveTurns++;
    moveProblem = '$id was not moved: $why';
    moveNotice = null;
    _changed();
  }

  /// Refuses a change while another is previewed, asked about, saved or unanswered; with [queues], a move is refused
  /// only while its own card's save went unanswered, and is queued otherwise.
  bool _oneAtATime(String id, {bool queues = false}) {
    if (_pending[id]?.unanswered == true || _pending['issue $id']?.unanswered == true) {
      refuseMove(
        id,
        'Saving… its save may still land; move it again once the board shows it, or it is told as not saved.',
      );
      return true;
    }
    // A move of a card whose own move is out waits behind it.
    if (queues) return false;
    if (!holdsMoves) return false;
    refuseMove(id, 'Saving… another move is still being saved; move it again once it is saved.');
    return true;
  }

  /// Whether a save runs and holds every move until its answer. A move whose answer came too late holds only its own
  /// card: every save is bound to the revision it was previewed against, so another move made meanwhile is refused
  /// as stale and asked about anew where that save lands first, never written over it.
  bool get holdsMoves => saving || _pending.entries.any((entry) => !entry.value.unanswered && !_queuedOnly(entry.key));

  /// Whether [id]'s card takes no move now: its save went unanswered and may still land. A card whose save is out
  /// takes its next move, which waits behind that save.
  bool waitsOnItsSave(String id) => _pending[id]?.unanswered == true;

  String? _reportedState(PlanningSnapshot reply, String key, PendingPlanningMove move) => move.isIssue
      ? [
          ...reply.stories.expand((story) => story.issues),
          if (_noStory != null && _noStory!.revision == reply.revision) ..._noStory!.issues,
        ].where((issue) => 'issue ${issue.id}' == key).firstOrNull?.state
      : reply.stories.where((story) => story.id == key).firstOrNull?.state;

  bool _needsNoStory(PlanningSnapshot reply, String key, PendingPlanningMove move) =>
      move.isIssue &&
      (move.inNoStory || !reply.stories.any((story) => story.issues.any((issue) => 'issue ${issue.id}' == key)));

  Future<void> _moveNow(String key, PlanningMove move, PendingPlanningMove shown, {String? revision}) async {
    _beginAction();
    _moveTurns++;
    _cardRefusals.remove(key);
    final inFlight = _pending[key];
    final place = _queue.indexWhere((queued) => queued.key == key);
    final waits = holdsMoves || _queue.isNotEmpty || _anyUnanswered || actionBusy || inFlight != null;
    if (!waits) {
      _pending[key] = shown;
      // The gesture's revision: a board that changed during it is refused instead of being read anew.
      return _startMove(key, move, revision ?? snapshot!.revision);
    }
    // A queued move is saved against a later revision, so one made on a board that changed during it moves nothing.
    if (revision != null && revision != snapshot!.revision) {
      refuseMove(move.id, 'planning changed while it was moved; move it again.');
      _cardRefusals[key] = (words: moveProblem!, state: _stateOfKey(snapshot!, key));
      return;
    }
    // A queued move goes from where the card will stand: a move already queued for it starts from the same state, and
    // one behind its own save from where that save goes.
    final only = place >= 0 && _queuedOnly(key);
    final from = place >= 0 ? _queue[place].shown.from : inFlight?.to ?? shown.from;
    final queued = (
      key: key,
      move: move,
      shown: PendingPlanningMove(
        from,
        shown.to,
        isIssue: shown.isIssue,
        inNoStory: shown.inNoStory,
        corrects: shown.corrects,
        version: shown.version,
      ),
      source: backend,
      name: project,
    );
    if (place >= 0) _queue.removeAt(place);
    if (only) _pending.remove(key);
    if (queued.shown.to != from) {
      // A card's move made while nothing of its last one was sent replaces it in its place.
      _queue.insert(place >= 0 ? place : _queue.length, queued);
      if (inFlight == null || only) _pending[key] = queued.shown;
    }
    _changed();
  }

  /// Previews and saves [move] of [key]'s pending card, made on the board at [revision]; a [drained] one keeps the
  /// words of the moves before it and adds its own.
  Future<void> _startMove(String key, PlanningMove move, String revision, {bool drained = false}) async {
    saving = true;
    if (!drained) {
      moveProblem = null;
      moveNotice = null;
    }
    moveRefusal = null;
    final source = backend;
    final name = project;
    _changed();
    await _preview(key, move, revision, source, name);
  }

  bool _current(int generation) => !_disposed && generation == _generation;

  /// Whether the board still shows [source]'s [name]: a save's end is told to the board it was made on, whatever
  /// watch failed or opened again meanwhile, and never to another project's.
  bool _sameBoard(PlanningSource source, String name) => !_disposed && identical(backend, source) && project == name;

  /// Previews [move] made on the board at [revision]; with no warning it is saved at once, else asked about.
  Future<void> _preview(
    String key,
    PlanningMove move,
    String revision,
    PlanningSource source,
    String name, {
    bool again = false,
  }) async {
    final corrects = _pending[key]?.corrects;
    final PlanningMovePreview preview;
    try {
      // The gesture began on another board than the one shown: what the person chose on may have changed meanwhile.
      if (revision != snapshot?.revision) {
        throw PlanningRefusal('ItemChanged', {
          'changed': [move.id],
        });
      }
      preview = await source.previewMoves(name, [move]);
      // The planner previews the items as it holds them; one the board shows at another version, or does not show,
      // changed while it was moved, so the move is made again on the board read now rather than saved over what the
      // person did not see.
      final shown = seenOf(preview.targets.keys);
      // A move from an issue's page is judged on the page's copy while the board's part holding it is still unread.
      if (_pending[key]?.version case final version? when version.isNotEmpty) shown.putIfAbsent(move.id, () => version);
      final changed = [
        for (final target in preview.targets.entries)
          if (shown[target.key] != target.value) target.key,
      ];
      if (changed.isNotEmpty) throw PlanningRefusal('ItemChanged', {'changed': changed});
    } catch (error) {
      if (!_sameBoard(source, name)) return;
      final stale = error is PlanningRefusal && {'ItemChanged', 'BasisIncomplete'}.contains(error.name);
      final words = _refuseCard(
        key,
        corrects != null
            ? planningShown('${move.id} was not set right: ${planningFailureWords(error)}')
            : stale
            ? planningShown('Planning changed while ${move.id} was moved; nothing was moved. Move it again.')
            : planningShown('${move.id} was not moved: ${planningFailureWords(error)}'),
      );
      final before = moveProblem;
      // A queued move goes on only after the board is read again, so it is not refused as stale in turn.
      final readAgain = stale || corrects != null;
      _finishOperation(drain: !readAgain);
      // The board the gesture began on is gone; the person moves again on the one read now. A refused correction
      // reads the board too, since the warning it set right may have changed or gone.
      if (readAgain) {
        await reload();
        // The words stand on the board read again, where the refusal left the card.
        if (_cardRefusals[key] case final said? when snapshot != null) {
          _cardRefusals[key] = (words: said.words, state: _stateOfKey(snapshot!, key));
        }
        // A read that could not run leaves the queue to go on against the board shown.
        if (!saving) _drainQueue();
      }
      if (!_sameBoard(source, name)) return;
      moveRefusal = error is PlanningRefusal ? error : null;
      _keepRefusal(error, action: true);
      // The board read again clears the words; those of the moves before this one stay.
      if (before != null) _addProblem(before);
      _addProblem(words);
      _changed();
      return;
    }
    if (!_sameBoard(source, name)) return;
    final shown = preview.withWarnings(_localWarnings(move, preview));
    final asksReason = _asksReason(move);
    // A correction's preview is always shown: the person confirms what it would change.
    if (shown.warnings.isEmpty && !again && !asksReason && corrects == null) {
      await _save(key, move, shown, revision, source, name);
      return;
    }
    question = PlanningMoveQuestion(
      key,
      move,
      shown,
      revision,
      again: again,
      asksReason: asksReason,
      corrects: corrects,
    );
    _changed();
  }

  /// Whether a move closes its item on a store that keeps the person's reason, so it is asked about first.
  bool _asksReason(PlanningMove move) => snapshot?.reasons == true && state(move.to)?.kind == 'CLOSED';

  /// A start this window handed to Porelo that the planner does not report yet, a story's own or a follow-up task's,
  /// which no digest covers.
  List<PlanningMoveWarning> _localWarnings(PlanningMove move, PlanningMovePreview preview) {
    final story = switch (move) {
      PlanningStoryMove(:final story) => story,
      PlanningIssueMove(:final issue) =>
        stories.where((each) => each.issues.any((item) => item.id == issue)).firstOrNull?.id,
    };
    if (story == null || !_startsOn(backend).containsKey((project, story)) && !runOf(story).starting) return const [];
    if (preview.warnings.any((warning) => warning.kind == PlanningMoveWarning.runningWork && warning.item == story)) {
      return const [];
    }
    return [PlanningMoveWarning(PlanningMoveWarning.runningWork, story, local: true)];
  }

  /// Saves the move asked about, confirmed: the planner stops the running work it named with the save, after the
  /// preview's digest matched, and the window ends exactly the tasks the save names; a start this window handed on
  /// that the planner does not report yet is cancelled here.
  ///
  /// The reason typed goes with the move, trimmed, or none when it is empty; a question that asked only for the reason
  /// confirms no warning.
  Future<void> confirmMove() async {
    final asked = question;
    if (_disposed || asked == null || asked.reasonRefused != null) return;
    question = null;
    _changed();
    for (final warning in asked.warnings) {
      if (warning.kind == PlanningMoveWarning.runningWork && warning.local) runOf(warning.item).cancel();
    }
    final move = asked.asksReason ? asked.move.withReason(asked.reason.value) : asked.move;
    await _save(
      asked.key,
      move,
      asked.preview,
      asked.revision,
      backend,
      project,
      confirmed: asked.warnings.isNotEmpty || asked.again,
    );
  }

  /// Drops the move asked about; nothing was sent, so its card stands where the planner reports it.
  void cancelMove() {
    if (_disposed || question == null) return;
    _dropQuestion();
    _finishOperation();
  }

  void _dropQuestion() {
    final asked = question;
    if (asked == null) return;
    question = null;
    _pending.remove(asked.key);
    // A next move of the card waited for this one, which is not made.
    if (_queue.any((queued) => queued.key == asked.key)) {
      _addProblem(_refuseCard(asked.key, planningShown('${asked.move.id} was not moved.')));
    }
  }

  /// Ends the tasks of each run a confirmed save stopped, as the story's Stop ends them.
  Future<void> _endStopped(PlanningSource source, String name, List<PlanningStoppedRun> stopped) async {
    for (final each in stopped) {
      final run = runOf(each.story)..cancel();
      await run.changes?.stop(source, name, each.story, [
        for (final task in each.tasks) PlanningTaskStarted(task: task.task, container: task.container),
      ]);
    }
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

  /// Reads the story list beside the moves, with [workflow] the workflow first: numbered when it starts, so a board
  /// streamed or saved meanwhile is never replaced by it, and held back like a streamed board while a save runs.
  /// Answers whether the board read is the one shown now; a read past [readWait] fails as timed out.
  Future<bool> _readBeside(PlanningSource source, String name, {bool workflow = false}) async {
    final order = _readStarts();
    final (flow, reply) =
        await () async {
          final flow = workflow ? await source.planningWorkflow(name) : null;
          return (flow, await source.stories(name));
        }().timeout(
          readWait,
          onTimeout: () =>
              throw PlanningRefusal('TimedOut', const {}, 'the planner did not answer within ${secondsOf(readWait)} s'),
        );
    final shown = snapshot;
    if (!_sameBoard(source, name) || shown == null) return false;
    final read = _checked(PlanningSnapshot.fromParts(flow ?? shown, reply));
    if (saving) {
      if (order > (_deferredSnapshot?.$2 ?? 0)) _deferredSnapshot = (read, order, () {});
      return false;
    }
    _accept(read, order: order);
    return _snapshotOrder == order;
  }

  /// Reads the board again after a save landed; a failed read is said, the save stands. The queue waits for it:
  /// [counted] where the save already counted it, before it ended.
  Future<void> _readAfterSave(PlanningSource source, String name, {bool counted = false}) async {
    if (!counted) _readsAfterSave++;
    try {
      await _readBeside(source, name);
    } catch (error) {
      if (_sameBoard(source, name)) {
        _addProblem('Saved, but planning could not be read again: ${planningFailureWords(error)}');
      }
    } finally {
      _readsAfterSave--;
      if (!_disposed) {
        _changed();
        _drainQueue();
      }
    }
  }

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

  void _accept(PlanningSnapshot accepted, {required int order, bool reconcile = true}) {
    if (order <= _snapshotOrder) return;
    final prior = snapshot;
    // A read of the story list names no breaches; at the revision a whole board named them for, they still stand.
    final reply = !accepted.rulesReported && prior != null && prior.rulesReported && prior.revision == accepted.revision
        ? PlanningSnapshot.ruledBy(accepted, prior)
        : accepted;
    snapshot = reply;
    // A correction previewed on the board before is confirmed on none: the person asks again on this one.
    if (prior?.revision != reply.revision) correctionQuestion = null;
    _readingNoStory = _readNoStory(reply, _generation, reconcile: reconcile);
    if (reply.repositoriesReported) _repositories = List.unmodifiable(reply.repositories);
    if (reply.repositoryWarningsReported) _repositoryWarnings = reply.repositoryWarnings;
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
    // A card the board shows elsewhere than where its move was refused no longer needs the words.
    _cardRefusals.removeWhere((key, refused) {
      final now = _stateOfKey(reply, key);
      return now != null && now != refused.state;
    });
    if (reconcile) _reconcileMoves(reply);
  }

  void _reconcileMoves(PlanningSnapshot reply) {
    if (!reply.complete || reply.revision.isEmpty) return;
    // Only a save answered too late waits on the board: it landed once the board shows it.
    final landed = <PendingPlanningMove>[];
    for (final entry in _pending.entries.toList()) {
      if (!entry.value.unanswered) continue;
      final reported = _reportedState(reply, entry.key, entry.value);
      if (reported == null && entry.value.isIssue && reply.stories.any((story) => !story.issuesReported)) continue;
      if (_needsNoStory(reply, entry.key, entry.value) && _noStory?.revision != reply.revision) continue;
      if (reported == null || reported == entry.value.to) {
        _pending.remove(entry.key);
        if (reported != null) landed.add(entry.value);
      }
    }
    if (!_pending.values.any((move) => move.unanswered)) {
      _unansweredDeadline?.cancel();
      _unansweredDeadline = null;
      if (landed.isNotEmpty) moveNotice = _savedNotice(landed);
      // The queue held behind a save answered too late goes on once it landed.
      _drainQueue();
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
    final late = _pending.entries.where((entry) => entry.value.unanswered).map((entry) => entry.key).toList();
    if (late.isEmpty) return;
    final words = planningShown('The save of ${late.join(', ')} was not seen on the planning board. Move it again.');
    moveNotice = null;
    for (final key in late) {
      final said = _refuseCard(key, words);
      if (said != words) _addProblem(said.substring(words.length + 1));
    }
    _addProblem(words);
    _changed();
    // The queue held behind those saves goes on.
    _drainQueue();
  }

  static String _savedNotice(Iterable<PendingPlanningMove> moves) =>
      moves.every((move) => move.isIssue) ? 'Issue changes saved.' : 'Planning changes saved.';

  /// Ends the save or read under way; with [drain], the next queued move starts.
  void _finishOperation({bool reconcile = true, bool drain = true}) {
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
    if (queued != null) {
      unawaited(changeProject(queued.$1, queued.$2));
      return;
    }
    // A reopen opens first, so the queued moves go on against the board it keeps shown.
    if (_reopenQueued) unawaited(_reopen());
    if (drain) _drainQueue();
  }

  /// Reloads and reconciles pending destinations while retaining their individual refusals; with [fetch], the
  /// provider first fetches the repositories, so the board read is what they hold now, not what it last polled.
  ///
  /// Without [fetch] it also runs while a Reload fetches or reads, as after a refusal for a stale revision, whose
  /// board is newer than the one the Reload's read may bring.
  Future<void> reload({bool fetch = false}) async {
    if (_disposed || saving || fetch && (fetching || _reloading)) return;
    final generation = _generation;
    if (fetch && _tellsReads && snapshot == null) {
      // Before its first board the planner is still reading it: the watch opened anew joins that read and brings the
      // board, where a refresh and a read beside it would wait for the same read and time out.
      await _reopen();
      return;
    }
    if (fetch) {
      final turns = _moveTurns;
      // The planner's read of the whole board holds nothing: a change made meanwhile is saved at once, and the watch
      // brings the board the read ends with.
      fetching = true;
      _changed();
      try {
        await backend.refreshProject(project);
      } catch (error) {
        if (!_disposed && generation == _generation) {
          _keepRefusal(error);
          moveProblem = planningFailureWords(error);
          _reloadProblem = error is PlanningRefusal && _disprovedByProgress.contains(error.name) ? moveProblem : null;
        }
        _endFetch();
        return;
      }
      if (_disposed) return;
      if (_tellsReads) {
        // The planner answers at once and reads behind its answer: the bar stands until the watch tells that read, and
        // the watch brings its board; a read beside it would only bring the board held before the read.
        _fetchWait?.cancel();
        _fetchWait = Timer(fetchTold, _endFetch);
        if (generation == _generation && turns == _moveTurns && !saving && question == null) moveProblem = null;
        _changed();
        return;
      } else {
        // A provider that tells no read on its watch has read the board when it answers.
        _endFetch();
      }
      // A change saved meanwhile reads the board at its own end.
      if (_disposed || generation != _generation || saving) return;
      // The read beside the fetch holds no move: a board it brings late is shown only where it is the newest.
      final source = backend;
      final name = project;
      _reloading = true;
      try {
        // With the workflow, so a mended fault or a changed workflow shows.
        final shown = await _readBeside(source, name, workflow: true);
        if (!shown || !_sameBoard(source, name)) return;
        final faults = snapshot?.faults ?? const <String>[];
        problem = faults.isEmpty ? null : planningShown(faults.join('\n'));
        // A word of a move made or refused since the Reload began stays: the board read may not have seen it.
        if (turns != _moveTurns || saving || question != null) return;
        moveProblem = null;
        _clearRefusal();
      } catch (error) {
        if (_sameBoard(source, name)) {
          _keepRefusal(error);
          moveProblem = planningFailureWords(error);
        }
      } finally {
        _reloading = false;
        _changed();
      }
      return;
    }
    saving = true;
    _changed();
    try {
      if (_disposed || generation != _generation) return;
      final reply = await _read();
      if (_disposed || generation != _generation) return;
      moveProblem = null;
      _clearRefusal();
      _acceptNewest(reply.$1, reply.$2);
      final faults = snapshot?.faults ?? const <String>[];
      problem = faults.isEmpty ? null : planningShown(faults.join('\n'));
    } catch (error) {
      if (!_disposed && generation == _generation) {
        _keepRefusal(error);
        moveProblem = planningFailureWords(error);
      }
    } finally {
      _finishOperation();
    }
  }

  /// Saves the one move against the revision its preview was answered for, sending the preview's digest, and applies
  /// only the planner's complete answer; a board changed meanwhile is read again and the move asked about anew.
  Future<void> _save(
    String key,
    PlanningMove move,
    PlanningMovePreview preview,
    String revision,
    PlanningSource source,
    String name, {
    bool confirmed = false,
  }) async {
    var handedOn = false;
    var refused = false;
    var readAfter = false;
    final shown = _pending[key];
    try {
      final reply = await source.saveMoves(name, [move], preview.targets, digest: preview.digest, confirm: confirmed);
      if (reply.stopped.isNotEmpty) {
        try {
          await _endStopped(source, name, reply.stopped);
        } catch (error) {
          if (_sameBoard(source, name)) {
            moveProblem = planningShown(
              '${move.id} was moved and its work stopped, but its tasks could not be ended: '
              '${planningFailureWords(error)}',
            );
          }
        }
      }
      if (!_sameBoard(source, name)) return;
      if (!reply.complete || reply.revision.isEmpty) {
        throw const FormatException('The planner did not report the saved revision and moved issues.');
      }
      final previous = snapshot!;
      final destinations = <String, String>{};
      final storyDestinations = <String, String>{};
      final story = previous.stories.where((story) => story.id == move.id).firstOrNull;
      final expected = move is PlanningIssueMove
          ? [move.id]
          : story?.closedIssues(previous.states, previous.work, move.to)?.map((issue) => issue.id).toList();
      _pending.remove(key);
      if (expected != null && expected.every(reply.moved.contains)) {
        for (final issue in expected) {
          destinations[issue] = move.to;
        }
        if (move is PlanningStoryMove) {
          // A story closed stands in its closed state; one reopened in the state its confirmed reopen names, with no
          // day its test passed, until the read below brings where its issues give.
          storyDestinations[move.id] = move.to;
        }
        _addNotice(move is PlanningIssueMove ? 'Issue changes saved.' : 'Planning changes saved.');
      } else {
        _addProblem(planningShown('${move.id}: the planner did not confirm this destination.'));
      }
      _accept(
        PlanningSnapshot.saved(previous, reply.revision, destinations, storyDestinations),
        // Numbered after the save: a board streamed while it waited was read before the save was made.
        order: _readStarts(),
        reconcile: false,
      );
      _changed();
      _savesLanded++;
      // SaveMoves does not return computed story states; they are read from the store once the save has ended, and
      // the queue waits for that read.
      readAfter = true;
      _readsAfterSave++;
    } on PlanningRefusal catch (error) {
      if (!_sameBoard(source, name)) return;
      if (error.name == 'TimedOut') {
        // Only the screen stopped waiting; the planner finishes the save, and the streamed board shows when it landed.
        _pending[key]?.unanswered = true;
        _addNotice('Still saving. The move shows as saved once planning shows it.');
        return;
      }
      refused = true;
      final detail = planningFailureWords(error);
      if (error.name == 'ItemChanged' || error.name == 'BasisIncomplete' || error.name == 'PreviewChanged') {
        // Never saved unseen: the board is read again and the move previewed and asked about anew.
        try {
          final reply = await _read();
          if (!_sameBoard(source, name)) return;
          _acceptNewest(reply.$1, reply.$2);
          if (shown != null && _needsNoStory(snapshot!, key, shown)) {
            await _waitForNoStory(_generation);
            if (!_sameBoard(source, name)) return;
            if (_noStory?.revision != snapshot?.revision) {
              throw StateError(noStoryProblem ?? 'The current issue list could not be read.');
            }
          }
          final now = shown == null ? null : _reportedState(snapshot!, key, shown);
          if (shown != null && now == shown.to) {
            // Made elsewhere meanwhile: nothing is left to save, and nothing went wrong.
            _pending.remove(key);
            _addNotice(planningShown('Planning already shows ${move.id} in $now.'));
            return;
          }
          if (shown == null || now != shown.from) {
            _addProblem(
              _refuseCard(
                key,
                planningShown('Planning changed: ${move.id} now stands in ${now ?? 'no state'}; it was not moved.'),
              ),
            );
            return;
          }
          handedOn = true;
          await _preview(key, move, snapshot!.revision, source, name, again: true);
        } catch (readError) {
          if (!_sameBoard(source, name)) return;
          _addProblem(
            _refuseCard(key, planningShown('${move.id} was not moved: $detail\n${planningFailureWords(readError)}')),
          );
        }
        return;
      }
      moveRefusal = error;
      _keepRefusal(error, action: true);
      final corrects = shown?.corrects != null;
      _addProblem(
        _refuseCard(
          key,
          planningShown(corrects ? '${move.id} was not set right: $detail' : '${move.id} was not moved: $detail'),
        ),
      );
      if (error.name == 'NotYetBuilt' || error.name == 'NotOffered') unavailableActions['SaveMoves'] = detail;
      // A refused correction reads the board again, since the warning it set right may have changed or gone.
      if (error.name == 'PartlySaved' || corrects) {
        try {
          final reply = await _read();
          if (_sameBoard(source, name)) _acceptNewest(reply.$1, reply.$2, reconcile: false);
        } catch (readError) {
          if (_sameBoard(source, name)) moveProblem = '$moveProblem\n${planningFailureWords(readError)}';
        }
      }
    } catch (error) {
      if (_sameBoard(source, name)) {
        refused = true;
        _keepRefusal(error, action: true);
        _addProblem(_refuseCard(key, planningShown('${move.id} was not moved: ${planningFailureWords(error)}')));
      }
    } finally {
      if (!handedOn) {
        if (_sameBoard(source, name) && shown != null && snapshot != null && _needsNoStory(snapshot!, key, shown)) {
          await _waitForNoStory(_generation, reconcile: !refused);
        }
        if (_sameBoard(source, name)) {
          _finishOperation(reconcile: !refused);
          _awaitUnanswered();
        }
      }
    }
    if (readAfter) {
      if (_sameBoard(source, name)) {
        await _readAfterSave(source, name, counted: true);
      } else {
        _readsAfterSave--;
      }
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

  /// The states of a phase, working first and otherwise in workflow order.
  List<PlanState> statesIn(PlanPhase phase) {
    final listed = states.where((state) => phase.states.contains(state.name)).toList();
    return [...listed.where((state) => state.kind == 'WORKING'), ...listed.where((state) => state.kind != 'WORKING')];
  }

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
      for (final move in _pending.entries) ...[move.key, move.value.to],
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

  /// What the stories outside the phases were sorted from, and the stories; sorted once per change, as [cards] are.
  (List<Object?>, List<PlanStory>)? _unplaced;

  /// Stories whose state is missing from the column configuration, kept visible with an explanation.
  List<PlanStory> get unplaced {
    final inputs = <Object?>[
      snapshot,
      preferences.search,
      preferences.priority,
      for (final move in _pending.entries) ...[move.key, move.value.to],
    ];
    final known = _unplaced;
    if (known != null && listEquals(known.$1, inputs)) return known.$2;
    final placed = {for (final phase in phases) ...phase.states};
    final sorted = List<PlanStory>.unmodifiable(
      _sort(stories.where((story) => state(displayedState(story)) == null || !placed.contains(displayedState(story)))),
    );
    _unplaced = (inputs, sorted);
    return sorted;
  }

  /// The states outside every phase that issues in no story stand in, under the board's search and priority filter.
  Set<String> get noStoryUnplaced {
    final placed = {for (final phase in phases) ...phase.states};
    final states = {
      for (final issue in noStory)
        if (!placed.contains(issue.state)) issue.state,
    };
    return {
      for (final state in states)
        if (noStoryIn(state).isNotEmpty) state,
    };
  }

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

  /// Toggles one phase.
  void foldPhase(String name) {
    _toggle(preferences.foldedPhases, name);
  }

  /// Toggles one state.
  void foldState(String name) {
    _toggle(preferences.foldedStates, name);
  }

  /// Toggles the card of issues in no story for one state.
  void expandNoStory(String state) {
    if (preferences.expandedNoStoryStates.contains(state)) preferences.allNoStoryStates.remove(state);
    _toggle(preferences.expandedNoStoryStates, state);
  }

  /// Reveals the remaining issues in no story for one state.
  void showAllNoStory(String state) {
    preferences.allNoStoryStates.add(state);
    _changed();
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
    _fetchWait?.cancel();
    _dropQueue();
    live = false;
    reopening = false;
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
  /// it makes can start work from its Needs you rows, on every board of the same project, through [wizards] when the
  /// window has them.
  ///
  /// With [seen], the rows the person marked seen leave [needsYou] and each board's cards, following its changes.
  /// With [taskChanges], each board it makes can stop its running work before any planning screen was shown.
  ///
  /// Each board it makes follows the limits of its source when that is a [PlanningLimited], 1.0 otherwise, so a
  /// source the window makes after its `Limits()` read reaches every board made from it.
  PlanningBoards(
    this.client, {
    PlanningStartTask? startTask,
    PlanningAgents? agents,
    PlanningWizards? wizards,
    this.seen,
    this.taskChanges,
  }) {
    if (startTask != null && agents != null) {
      startHooks = PlanningStartHooks(
        startTask: startTask,
        agents: agents,
        machines: machinesOf,
        unavailableMachines: unavailableMachinesOf,
        wizards: wizards,
      );
    }
    client.addListener(_sync);
    seen?.addListener(_changed);
    _sync();
  }

  /// The providers of planning and what the window tells them.
  final PlanningClient client;

  /// What the person marked seen in Needs you, kept by the window; null when the window keeps no marks.
  final PlanningSeen? seen;

  /// What a start from a Needs you row asks of the window, handed to each board this makes; null without it.
  PlanningStartHooks? startHooks;

  /// The window's task changes, which stop a story's work, handed to each board this makes; null without them.
  final PlanningTaskChanges? taskChanges;

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
        limits: switch (entry.value) {
          PlanningLimited(:final limits) => limits,
          _ => PlanningLimits.ones,
        },
      );
      board.startHooks = startHooks;
      if (taskChanges case final changes?) board.taskChanges = changes;
      board.rowSeen = (row) => _seenRow(board, row);
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
    _count();
    notifyListeners();
  }

  /// The stories waiting for the person's review over every project shown, those standing in the state their
  /// workflow's last work ends in, each from the board its project is shown from; null while any project's figures
  /// are not known or its board is not live, so a partial or old sum is never shown as the whole.
  ValueListenable<int?> get review => _review;

  /// The issues left to build over every project shown, counted as [review] is.
  ValueListenable<int?> get open => _open;

  final ValueNotifier<int?> _review = ValueNotifier(null);
  final ValueNotifier<int?> _open = ValueNotifier(null);

  void _count() {
    var review = 0;
    var left = 0;
    var known = projects.isNotEmpty;
    for (final project in projects) {
      // The board the project is shown from, as the working count takes it, so a stale machine's never wins.
      final board = _provider(project);
      // A board that stopped answering has figures that no longer say what is open; a save keeps the figures known
      // before it until the planner's read after it replaces them.
      final figures = board != null && (board.live || board.reopening) ? board.snapshot?.open : null;
      if (figures == null) {
        known = false;
        break;
      }
      final waiting = planningStoryReviewState(board!.snapshot);
      review += waiting == null ? 0 : board.stories.where((story) => story.state == waiting).length;
      left += figures.left;
    }
    _review.value = known ? review : null;
    _open.value = known ? left : null;
  }

  /// Boards holding unsaved work, including providers no longer selected.
  Iterable<PlanningBoard> get pendingBoards =>
      _boards.values.where((board) => board.pending.isNotEmpty || board.saving);

  /// Whether any board holds an unsaved move or a save still waiting for its answer, so a leave is asked first.
  bool get hasPendingMoves => pendingBoards.isNotEmpty;

  /// The provider names known now, for saying which did not supply a project.
  List<String> get providerNames => [for (final provider in client.providers) provider.name];

  /// Ended story runs and stopped readings that need a person, from every source including stale ones, without
  /// the rows the window marks seen in [seen].
  List<PlanningNeedsYou> get needsYou => [
    for (final board in sources)
      for (final story in board.stories)
        for (final row in storyAttention(board, story))
          if (!_seenRow(board, row))
            PlanningNeedsYou(
              row.issue == null
                  ? 'story ended ${machineOf(board)}/${board.project}/${story.id}'
                  : 'reading ${machineOf(board)}/${board.project}/${row.issue!.id}',
              machineOf(board),
              row,
            ),
  ];

  /// Whether the window marked [row] of [board] seen.
  bool _seenRow(PlanningBoard board, PlanningAttention row) {
    final machine = machineOf(board);
    if (row.endOn(machine) case final end?) return seen?.endSeen(end) ?? false;
    if (row.putAwayOn(machine) case final kept?) return seen?.putAwaySeen(kept) ?? false;
    return false;
  }

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
    seen?.removeListener(_changed);
    for (final board in _boards.values) {
      board.removeListener(_changed);
      board.dispose();
    }
    _boards.clear();
    _review.dispose();
    _open.dispose();
    super.dispose();
  }
}

/// Preserves a daemon refusal in its own detail words, on one line and at most 4096 characters long.
String planningFailureWords(Object error) {
  final safe = planningShown(_failureWords(error), multiline: false);
  return safe.length > 4096 ? safe.substring(0, 4096) : safe;
}

/// The issues a partly written move moved, `id=state` comma-joined, as `id to state`; an id
/// without a state is shown alone.
String _planningMovedIssues(String issues) => issues
    .split(',')
    .map((pair) => pair.trim())
    .where((pair) => pair.isNotEmpty)
    .map((pair) {
      final at = pair.indexOf('=');
      return at < 0 ? pair : '${pair.substring(0, at)} to ${pair.substring(at + 1)}';
    })
    .join(', ');

String _failureWords(Object error) {
  if (error is PlanningRefusal) {
    if (error.name == 'NotYetBuilt') return 'The planner does not offer ${error.parameters['method']} yet.';
    // The labels' refusals name what they need in the screen's words, whatever words the planner sent.
    if (error.name == 'LabelNotAllowed') {
      final label = error.parameters['label'];
      return 'The planner sets only the topic labels ${planningTopicLabels.join(', ')}'
          '${label is String && label.isNotEmpty ? '; $label is none of them' : ''}.';
    }
    if (error.name == 'LabelsNotKept') {
      final repository = error.parameters['repository'];
      final item = error.parameters['item'];
      return 'The tracker did not keep the labels of ${item is String && item.isNotEmpty ? item : 'the item'}'
          '${repository is String && repository.isNotEmpty ? ' in $repository' : ''}: the account may not set labels '
          'there.';
    }
    // The planning header names the directory and the planner's words; the board says only what it means for it.
    if (error.name == 'ItemsDirectoryRefused') return 'The board is not read while its planning directory is refused.';
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
    if (error.name == 'EnteredOnlyByWork') {
      return '${error.parameters['to']} is entered by hand only by an issue no run of this planner works.';
    }
    if (error.name == 'ItemChanged') {
      final changed = planningChangedItems(error);
      return 'Changed meanwhile: ${changed.isEmpty ? 'an item this needs' : changed.join(', ')}. '
          'Look at the board again and choose again.';
    }
    if (error.name == 'BasisIncomplete') {
      final missing = error.parameters['missing'];
      final ids = missing is List ? missing.whereType<String>().toList() : const <String>[];
      return 'The board shown does not hold ${ids.isEmpty ? 'an item this needs' : ids.join(', ')}. '
          'Look at the board again and choose again.';
    }
    // A held save's refusals name what the person can do now, whatever words the planner sent.
    if (error.name == 'NoSuchSave') return 'The save is no longer held. Look at the board again.';
    if (error.name == 'SaveNotHeld') return 'The save is not held: it runs, or was sent again.';
    if (error.name == 'SaveOfRun') return 'The save is a halted run\'s: its run\'s Stop or Start decides it.';
    if (error.name == 'SaveFromElsewhere') {
      final repository = error.parameters['repository'];
      final branch = error.parameters['branch'];
      return 'The save was kept from '
          '${repository is String && repository.isNotEmpty ? repository : 'another repository'}'
          '${branch is String && branch.isNotEmpty ? ' on $branch' : ''}, so it is never written here.';
    }
    if (error.name == 'PartlySaved') {
      final pushed = error.parameters['pushed'];
      final failed = error.parameters['failed'];
      final issues = error.parameters['issues'];
      return '${error.description.trim()}${pushed is String ? ' Pushed: $pushed.' : ''}'
          '${failed is String ? ' Not pushed: $failed.' : ''}'
          '${issues is String && issues.trim().isNotEmpty ? ' Moved: ${_planningMovedIssues(issues)}.' : ''}';
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

  String revision;

  bool answered = false;
}

/// A move waiting in a board's queue: its card's key, the move, what its card shows, and the board it was made on.
typedef _QueuedMove = ({String key, PlanningMove move, PendingPlanningMove shown, PlanningSource source, String name});

/// The ids an `ItemChanged` refusal names, each once in its order, with ` (gone)` after one the planner no longer
/// holds; empty for any other error.
List<String> planningChangedItems(Object error) {
  if (error is! PlanningRefusal || error.name != 'ItemChanged') return const [];
  final gone = <String>{
    if (error.parameters['items'] case final List<dynamic> items)
      for (final item in items.whereType<Map<String, dynamic>>())
        if (item['gone'] == true && item['id'] is String) item['id'] as String,
  };
  final changed = error.parameters['changed'];
  return [
    if (changed is List)
      for (final id in changed.whereType<String>().toSet()) gone.contains(id) ? '$id (gone)' : id,
  ];
}
