import '../hooks.dart';
import '../models.dart';
import 'open_work_figures.dart';

/// The state group a filter keeps, or null for a filter that is not one state group.
PlanningStateKind? _group(PlanningFilter filter) => switch (filter) {
  PlanningFilter.implementing => PlanningStateKind.implementing,
  PlanningFilter.analysis => PlanningStateKind.analysis,
  PlanningFilter.backlog => PlanningStateKind.backlog,
  PlanningFilter.estimated => PlanningStateKind.estimated,
  PlanningFilter.idea => PlanningStateKind.idea,
  PlanningFilter.openWork || PlanningFilter.blocked || PlanningFilter.review => null,
};

/// The board's filters over one workflow: which it can use, which states each keeps, and which stories match.
class PlanningBoardFilter {
  /// Reads the workflow's [states] with [review] the states that wait for the person's review.
  PlanningBoardFilter(List<PlanState> states, this.review)
    : _kinds = {for (final state in states) state.name: planningStateKind(state, review)},
      _closed = {
        for (final state in states)
          if (state.kind == 'CLOSED') state.name,
      };

  /// The states that wait for the person's review.
  final Set<String> review;

  final Map<String, PlanningStateKind> _kinds;
  final Set<String> _closed;

  /// The workflow's states [filter] keeps, in workflow order; for Open work those left to build, for Blocked none,
  /// since a blocked issue is told by its blockers and not by its state.
  List<String> states(PlanningFilter filter) {
    final group = _group(filter);
    return [
      for (final MapEntry(key: name, value: kind) in _kinds.entries)
        if (switch (filter) {
          PlanningFilter.openWork => !_closed.contains(name) && !review.contains(name),
          PlanningFilter.review => review.contains(name),
          PlanningFilter.blocked => false,
          _ => kind == group,
        })
          name,
    ];
  }

  /// Whether the board can use [filter]: a state group needs a state of the workflow in it.
  bool usable(PlanningFilter filter) => _group(filter) == null || states(filter).isNotEmpty;

  /// The filters the board can use, in the order the filter row shows them.
  List<PlanningFilter> get filters => [
    for (final filter in PlanningFilter.values)
      if (usable(filter)) filter,
  ];

  /// Whether [story] has an issue [filter] keeps, from the planner's figures; null when its figures are unknown.
  bool? matches(PlanStory story, PlanningFilter filter) {
    final open = story.open;
    if (open == null) return null;
    return switch (filter) {
      PlanningFilter.openWork => open.left > 0,
      PlanningFilter.blocked => open.blocked > 0,
      PlanningFilter.review => open.review > 0,
      _ => states(filter).any((state) => (open.states[state] ?? 0) > 0),
    };
  }

  /// Whether [story] stays on a board filtered by [filter]: a story whose figures are unknown stays under Open work
  /// alone, since it may still hold work and is never hidden from it.
  bool keeps(PlanStory story, PlanningFilter? filter) {
    if (filter == null) return true;
    return matches(story, filter) ?? filter == PlanningFilter.openWork;
  }

  /// How many of [stories] each usable filter keeps by their figures; partial when some stories' figures are
  /// unknown, so the count is the least.
  Map<PlanningFilter, PlanningFilterCount> counts(Iterable<PlanStory> stories) {
    final listed = stories.toList();
    final unknown = listed.where((story) => story.open == null).length;
    return {
      for (final filter in filters)
        filter: PlanningFilterCount(listed.where((story) => matches(story, filter) == true).length, unknown),
    };
  }
}

/// How many stories a filter keeps by their figures, and how many it could not tell.
class PlanningFilterCount {
  /// Takes the stories counted and those whose figures are unknown.
  const PlanningFilterCount(this.stories, this.unknown);

  /// The stories whose figures say they have an issue of the filter.
  final int stories;

  /// The stories whose figures are unknown, in no count.
  final int unknown;

  /// Whether [stories] is only the least, since some figures are unknown.
  bool get partial => unknown > 0;
}
