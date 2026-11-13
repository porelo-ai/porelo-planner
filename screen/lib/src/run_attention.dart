import 'package:flutter/foundation.dart';

import 'models.dart';

/// A reported issue reason in words a person can act on; a reason without words shows its wire name.
String planningReasonWords(String reason) => switch (reason) {
  'BLOCKING_FINDINGS' => 'its reading found what blocks it',
  'ROUND_LIMIT' => 'its reading rounds ran out',
  'FAILED' => 'its work failed',
  'BLOCKED' => 'its work is blocked',
  'UNPUSHED' => 'its work was not pushed',
  'INVALID_FINDINGS' => 'its findings could not be read',
  'STOPPED' => 'its work was stopped',
  'SAVE_REFUSED' => 'saving its findings was refused',
  'NEXT_STEP_UNAVAILABLE' => 'its next step is not available',
  _ => reason,
};

/// The reasons of a stopped reading, which the Reworking dialog answers.
const planningStopReasons = {'BLOCKING_FINDINGS', 'ROUND_LIMIT'};

/// The reasons of a task's end whose result the window can open: the task failed, declared its work blocked, left
/// its work unpushed, or read without a findings block.
const planningTaskEndReasons = {'FAILED', 'BLOCKED', 'UNPUSHED', 'INVALID_FINDINGS'};

/// Whether [task]'s end is one that raises [reason], so a row may open that task's result.
bool planningEndRaised(String reason, PlanWorkTask task) {
  final ended = task.ended;
  if (ended == null) return false;
  return switch (reason) {
    'BLOCKED' => ended.outcome == 'BLOCKED',
    'FAILED' => ended.outcome != 'BLOCKED' && (ended.finished == false || ended.outcome == 'FAILED'),
    // A reader delivers only its answer, so what it left unpushed raises nothing.
    'UNPUSHED' => task.role != 'READER' && !ended.declaredStop && ended.finished == true && (ended.unpushed ?? 0) > 0,
    'INVALID_FINDINGS' => task.role == 'READER' && !ended.declaredStop && ended.finished == true,
    _ => false,
  };
}

/// The task whose end raised [reason] for [issue]: the latest ended task of the issue, when its end is one that
/// raises that reason; null where none can be told, such as an issue in a working state with no run.
PlanWorkTask? planningRaisingTask(PlanWork? work, String issue, String reason) {
  final latest = work?.tasks.reversed.where((task) => task.issues.contains(issue) && task.ended != null).firstOrNull;
  return latest != null && planningEndRaised(reason, latest) ? latest : null;
}

/// One issue of a story's run that needs a person, with what the machine reported for it.
@immutable
class PlanningIssueAttention {
  /// Takes the issue and what was reported for it.
  const PlanningIssueAttention(this.issue, {this.reason = '', this.readingStopped = false, this.needsYou = false});

  /// The issue's identifier.
  final String issue;

  /// The reported reason's wire name, empty or `NONE` when none was reported.
  final String reason;

  /// Whether its reading stopped and waits for a decision.
  final bool readingStopped;

  /// Whether it was reported as needing a person.
  final bool needsYou;

  /// Why it needs attention, in words, never empty.
  List<String> get causes {
    final words = [
      if (planReasonNeedsAttention(reason)) planningReasonWords(reason),
      if (readingStopped && !planningStopReasons.contains(reason)) 'its reading stopped',
      if (needsYou) 'it needs you',
    ];
    return words.isEmpty ? const ['it needs attention'] : words;
  }

  @override
  bool operator ==(Object other) =>
      other is PlanningIssueAttention &&
      other.issue == issue &&
      other.reason == reason &&
      other.readingStopped == readingStopped &&
      other.needsYou == needsYou;

  @override
  int get hashCode => Object.hash(issue, reason, readingStopped, needsYou);
}

/// What in a story's run needs a person before its work can start again.
@immutable
class PlanningRunAttention {
  /// Takes the run's end and the issues that need attention.
  const PlanningRunAttention({this.ended = false, this.issues = const []});

  /// Whether the run ended and its end is not settled.
  final bool ended;

  /// The issues that need attention, in the order reported.
  final List<PlanningIssueAttention> issues;

  /// What needs attention, in words.
  String get words {
    final named = [
      if (ended) 'the run ended',
      for (final issue in issues) '${issue.issue}: ${issue.causes.join(', ')}',
    ];
    return named.isEmpty ? 'the run needs attention' : 'the run needs attention - ${named.join('; ')}';
  }

  @override
  bool operator ==(Object other) =>
      other is PlanningRunAttention && other.ended == ended && listEquals(other.issues, issues);

  @override
  int get hashCode => Object.hash(ended, Object.hashAll(issues));
}

/// Why an action cannot run now, and whether a run's attention is what holds it.
@immutable
class PlanningStartHold {
  /// Takes the words and, when attention holds it, that attention.
  const PlanningStartHold(this.words, {this.attention});

  /// Why it cannot run, in words.
  final String words;

  /// The attention that holds it, or null for any other reason.
  final PlanningRunAttention? attention;
}
