import 'dart:async';

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'planning_board.dart';

/// Reads one destination from its original board's machine and refreshes on board replacements.
class PlanningDetail extends ChangeNotifier {
  /// Begins the reads and observes only this board.
  PlanningDetail(this.board, this.storyId, this.issueId) {
    board.addListener(_boardChanged);
    _snapshot = board.snapshot;
    unawaited(reload());
  }

  /// The original provider and project.
  final PlanningBoard board;

  /// The story from which this destination was opened.
  final String storyId;

  /// The issue, or null for a story screen.
  final String? issueId;

  /// The standalone story from the last completed read group.
  PlanStoryDetail? story;

  /// The issue list from that same completed read group.
  PlanIssueList? issues;

  /// The standalone issue from that same completed read group.
  PlanIssueDetail? issue;

  /// The failure to read the story, with the daemon's words intact.
  String? storyProblem;

  /// The failure to read the issue list.
  String? issuesProblem;

  /// The failure to read the issue.
  String? issueProblem;

  /// All failed parts named explicitly.
  String? get problem {
    final errors = [storyProblem, issuesProblem, issueProblem].whereType<String>().toList();
    return errors.isEmpty ? null : errors.join('\n');
  }

  /// Whether a replacement read group is pending; the last completed group stays readable.
  bool loading = true;

  /// Issue drafts retained by the board across detail navigation.
  Map<String, PendingPlanningMove> get pendingMoves => board.pendingIssues;

  /// Daemon refusals attached to their exact issue.
  Map<String, String> get moveProblems => {for (final entry in pendingMoves.entries) entry.key: ?entry.value.problem};

  /// Save-level failure from the shared batch.
  String? get moveProblem => board.moveProblem;

  /// Confirmation or revision-change feedback.
  String? get moveNotice => board.moveNotice;

  /// Whether the board's shared save is pending.
  bool get saving => board.saving;

  /// States that a person may request, in the configured workflow order.
  List<PlanState> get moveStates =>
      board.states.where((state) => state.name.isNotEmpty && {'WAITING', 'CLOSED'}.contains(state.kind)).toList();

  /// Why a new issue move cannot be staged now.
  String? get moveUnavailable => saving
      ? 'Saving issue changes.'
      : loading
      ? 'Waiting for the latest issue details.'
      : !board.live
      ? 'A live planning answer is needed before changing issues.'
      : problem != null
      ? 'The latest planning details could not be read.'
      : issues?.reported != true
      ? 'The complete issue list must be read before changing issues.'
      : board.snapshot?.revision.isNotEmpty != true
      ? 'The planning revision was not reported.'
      : null;

  /// Whether the shared Save has reviewed work and a current connection.
  bool get canSaveMoves =>
      board.pending.isNotEmpty && moveUnavailable == null && !board.unavailableActions.containsKey('SaveMoves');

  /// Stages one issue in the board's pending batch.
  void queueMove(String id, String to) {
    if (_disposed || board.disposed || moveUnavailable != null) return;
    final issue = issues?.issues.where((issue) => issue.id == id).firstOrNull;
    if (issue == null || issue.state.isEmpty || to != issue.state && !moveStates.any((state) => state.name == to)) {
      return;
    }
    board.moveIssue(id, to, reported: issue);
  }

  /// Drops all drafts through the same Undo used on the board.
  void undoMoves() {
    if (_disposed || board.disposed) return;
    board.undoAll();
  }

  /// Saves stories and issues together through the board's only write path.
  Future<void> saveMoves() async {
    if (_disposed || board.disposed || !canSaveMoves) return;
    await board.save();
    if (_disposed || board.disposed) return;
    await reload();
  }

  PlanningSnapshot? _snapshot;
  int _generation = 0;
  bool _disposed = false;

  void _boardChanged() {
    if (_disposed) return;
    if (!identical(_snapshot, board.snapshot)) {
      _snapshot = board.snapshot;
      unawaited(reload());
    } else {
      notifyListeners();
    }
  }

  Future<void>? _reading;

  /// Waits for the newest read group, including a replacement started by a save's watch update.
  Future<void> reload() async {
    if (_disposed || board.disposed) return;
    var reading = _readParts();
    _reading = reading;
    while (true) {
      await reading;
      if (_disposed || board.disposed) return;
      final latest = _reading;
      if (latest == null || identical(reading, latest)) return;
      reading = latest;
    }
  }

  /// Collects every part before publishing one replacement; a failed reload keeps the last complete publication readable.
  Future<void> _readParts() async {
    if (_disposed || board.disposed) return;
    final generation = ++_generation;
    bool current() => !_disposed && !board.disposed && generation == _generation;
    loading = true;
    notifyListeners();
    PlanStoryDetail? nextStory;
    PlanIssueList? nextIssues;
    PlanIssueDetail? nextIssue;
    String? storyError, issuesError, issueError;

    Future<void> readStory(String id) async {
      try {
        if (id.isEmpty) throw const FormatException('The machine did not report a story identifier.');
        final reply = await board.backend.getStory(board.project, id);
        if (reply.story?.id != id) throw const FormatException('The machine did not return the requested story.');
        nextStory = reply;
      } catch (error) {
        storyError = 'Story: ${planningFailureWords(error)}';
      }
    }

    if (issueId case final id?) {
      try {
        if (id.isEmpty) throw const FormatException('The machine did not report an issue identifier.');
        final reply = await board.backend.getIssue(board.project, id);
        if (reply.issue?.id != id) throw const FormatException('The machine did not return the requested issue.');
        nextIssue = reply;
      } catch (error) {
        issueError = 'Issue: ${planningFailureWords(error)}';
      }
      if (!current()) return;
      final owner =
          board.stories.where((story) => story.issues.any((issue) => issue.id == id)).firstOrNull?.id ?? storyId;
      if (owner.isNotEmpty) await readStory(owner);
    } else {
      await readStory(storyId);
      if (!current()) return;
      try {
        nextIssues = await board.backend.listStoryIssues(board.project, storyId);
      } catch (error) {
        issuesError = 'Issue list: ${planningFailureWords(error)}';
      }
    }
    if (!current()) return;
    if (nextStory != null) story = nextStory;
    if (nextIssues != null) issues = nextIssues;
    if (nextIssue != null) issue = nextIssue;
    storyProblem = storyError;
    issuesProblem = issuesError;
    issueProblem = issueError;
    loading = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    if (!board.disposed) board.removeListener(_boardChanged);
    super.dispose();
  }
}
