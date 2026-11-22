import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';

/// What a workflow state means to the person, as the screen draws it; a workflow names its states itself, so a state
/// the screen cannot place is [other] and drawn with a neutral mark.
enum PlanningStateKind {
  /// An idea, not yet accepted for work.
  idea,

  /// In analysis or estimation.
  analysis,

  /// Estimated, waiting to be planned.
  estimated,

  /// In the backlog, ready to be built.
  backlog,

  /// Being built, read or reworked.
  implementing,

  /// Left to build, but an open issue blocks it.
  blocked,

  /// Waiting for the person's review.
  review,

  /// Finished.
  done,

  /// Rejected.
  rejected,

  /// A state the screen cannot place.
  other,
}

/// The states the planner's names place, beside what the state kind and the work rules say.
const _named = {
  'Idea': PlanningStateKind.idea,
  'Accepted': PlanningStateKind.idea,
  'In analysis': PlanningStateKind.analysis,
  'Analyzed': PlanningStateKind.analysis,
  'Under estimation': PlanningStateKind.analysis,
  'Estimated': PlanningStateKind.estimated,
  'Backlog': PlanningStateKind.backlog,
  'Rejected': PlanningStateKind.rejected,
};

/// The states that wait for the person's review: the one [planningStoryReviewState] names, or none. An earlier work's
/// end, such as an analysis's, waits for the person's next step and shows by its own state, never as review.
Set<String> planningReviewStates(PlanningSnapshot? snapshot) {
  final review = planningStoryReviewState(snapshot);
  return review == null ? const {} : {review};
}

/// The state a story waits in for the person's review: of the states a rule's work ends in that are neither a rule's
/// own working state nor closed, the one latest in the workflow, where its last work ends; null when there is none.
/// Only a story as a whole is reviewed, so only a story in this state needs the person's review.
String? planningStoryReviewState(PlanningSnapshot? snapshot) {
  if (snapshot == null) return null;
  final closed = {
    for (final state in snapshot.states)
      if (state.kind == 'CLOSED') state.name,
  };
  final working = {for (final rule in snapshot.work) rule.state};
  final ends = {
    for (final rule in snapshot.work)
      if (rule.done.isNotEmpty && !working.contains(rule.done) && !closed.contains(rule.done)) rule.done,
  };
  String? last;
  for (final state in snapshot.states) {
    if (ends.contains(state.name)) last = state.name;
  }
  return last;
}

/// What [state] means, with [review] the states that wait for the person's review; review and a closed kind go
/// before a name, so a workflow that names its states differently is still placed where its kinds allow.
PlanningStateKind planningStateKind(PlanState state, Set<String> review) {
  if (review.contains(state.name)) return PlanningStateKind.review;
  final named = _named[state.name];
  if (state.kind == 'CLOSED') return named == PlanningStateKind.rejected ? named! : PlanningStateKind.done;
  // Being built is the planner's working kind alone: a state no work rule measures waits, whatever its name.
  if (state.kind == 'WORKING') return PlanningStateKind.implementing;
  if (named != null && named != PlanningStateKind.rejected) return named;
  return PlanningStateKind.other;
}

/// What the state is called in a legend or a hint.
String planningStateKindLabel(PlanningStateKind kind) => switch (kind) {
  PlanningStateKind.idea => 'Idea',
  PlanningStateKind.analysis => 'In analysis',
  PlanningStateKind.estimated => 'Estimated',
  PlanningStateKind.backlog => 'Backlog',
  PlanningStateKind.implementing => 'Implementing',
  PlanningStateKind.blocked => 'Blocked',
  PlanningStateKind.review => 'Review',
  PlanningStateKind.done => 'Finished',
  PlanningStateKind.rejected => 'Rejected',
  PlanningStateKind.other => 'Other state',
};

/// The color of a state's mark: the design's on a light theme, lightened to read on a dark one.
Color planningStateColor(PlanningStateKind kind, ColorScheme scheme) {
  final light = switch (kind) {
    PlanningStateKind.idea => const Color(0xFF8A8F98),
    PlanningStateKind.analysis => const Color(0xFF6C5BB5),
    PlanningStateKind.estimated => const Color(0xFF3F6AA8),
    PlanningStateKind.backlog => const Color(0xFF5F6B7A),
    PlanningStateKind.implementing => const Color(0xFFB85400),
    PlanningStateKind.blocked => const Color(0xFFB3261E),
    PlanningStateKind.review => const Color(0xFF0B6E99),
    PlanningStateKind.done => const Color(0xFF2E7D32),
    PlanningStateKind.rejected => const Color(0xFF8A8F98),
    PlanningStateKind.other => const Color(0xFF5F6B7A),
  };
  return scheme.brightness == Brightness.light ? light : Color.lerp(light, Colors.white, 0.4)!;
}

/// The icon of a state, with a neutral mark for a state the screen cannot place.
IconData planningStateIconData(PlanningStateKind kind) => switch (kind) {
  PlanningStateKind.idea => Icons.lightbulb_outline,
  PlanningStateKind.analysis => Icons.search,
  PlanningStateKind.estimated => Icons.straighten,
  PlanningStateKind.backlog => Icons.layers_outlined,
  PlanningStateKind.implementing => Icons.construction,
  PlanningStateKind.blocked => Icons.block,
  PlanningStateKind.review => Icons.visibility_outlined,
  PlanningStateKind.done => Icons.check_circle_outline,
  PlanningStateKind.rejected => Icons.cancel_outlined,
  PlanningStateKind.other => Icons.radio_button_unchecked,
};

/// A state's icon in its color, named for a screen reader.
class PlanningStateIcon extends StatelessWidget {
  /// Takes the state's meaning and the icon's size.
  const PlanningStateIcon(this.kind, {this.size = 16, super.key});

  /// What the state means.
  final PlanningStateKind kind;

  /// The icon's size.
  final double size;

  @override
  Widget build(BuildContext context) => Icon(
    planningStateIconData(kind),
    size: size,
    color: planningStateColor(kind, Theme.of(context).colorScheme),
    semanticLabel: planningStateKindLabel(kind),
  );
}

/// The sizes of what is left to build, in the order the planner gives them, such as "2 S · 1 M · 3 unsized";
/// empty when nothing is left.
String planningSizes(PlanOpenWork open) => [
  for (final entry in open.sizes.entries)
    if (entry.value > 0) '${entry.value} ${entry.key}',
  if (open.unsized > 0) '${open.unsized} unsized',
].join(' · ');

/// Minutes as hours and minutes, such as "3 h 05" or "40 min"; null reads as unknown, never as no time.
String planningMinutes(double? minutes) {
  if (minutes == null) return 'unknown';
  final whole = minutes.ceil();
  if (whole < 60) return '$whole min';
  return '${whole ~/ 60} h ${(whole % 60).toString().padLeft(2, '0')}';
}

/// Whether [board]'s open-work figures are shown: while its watch is live or a Reload opens it anew. A save keeps
/// the figures known before it until the planner's read after it replaces them, so a count once known never turns
/// unknown.
bool planningFiguresKnown(PlanningBoard board) => board.live || board.reopening;
