/// The planner's one rule for an open story's state, mirrored for the stand-in: read from the workflow's order and
/// its measured states alone, as the planner's core reads it.
///
/// F is the first measured open state, where building starts; B the open state just before it; R, the review
/// state, the first measured open state from F on that is directly followed by an open state that is not measured;
/// W every measured open state from F on but R and the last open state, before or after R alike; R or later every
/// open state from R on that is not in W, and the closed states. Issues in the rejecting state, or in a state the
/// workflow lacks, are left out. With every issue left in R or later, the story waits in R; otherwise, with an issue
/// left in W, it stands in the furthest W state among them; otherwise each issue in R or later counts as B, and the
/// story stands in the furthest state so counted.
class PlanningStoryRule {
  PlanningStoryRule._(this._states, this._closed, this._backlog, this._review, this._built);

  /// The state that leaves an issue unplanned, as the stand-in's workflow names it.
  static const rejecting = 'Rejected';

  /// The kind of the warning naming a story's issues that stand before its state.
  static const behindKind = 'IssueBehindStory';

  final List<String> _states;
  final Set<String> _closed;
  final int _backlog;
  final int _review;
  final Set<int> _built;

  /// The rule for [states], each a served state with its name, kind and measured flag, or null when F, B or R
  /// cannot be found in them.
  static PlanningStoryRule? of(List<Map<String, dynamic>> states) {
    final names = [for (final state in states) state['name'] as String];
    final closed = {
      for (final state in states)
        if (state['kind'] == 'CLOSED') state['name'] as String,
    };
    bool measuredOpen(int at) => !closed.contains(names[at]) && states[at]['measured'] == true;
    var first = -1;
    for (var at = 0; at < names.length && first < 0; at++) {
      if (measuredOpen(at)) first = at;
    }
    if (first <= 0 || closed.contains(names[first - 1])) return null;
    var review = -1;
    var lastOpen = -1;
    for (var at = first; at < names.length; at++) {
      if (!closed.contains(names[at])) lastOpen = at;
      if (review < 0 &&
          measuredOpen(at) &&
          at + 1 < names.length &&
          !closed.contains(names[at + 1]) &&
          states[at + 1]['measured'] != true) {
        review = at;
      }
    }
    if (review < 0) return null;
    final built = {
      for (var at = first; at < names.length; at++)
        if (measuredOpen(at) && at != review && at != lastOpen) at,
    };
    return PlanningStoryRule._(names, closed, first - 1, review, built);
  }

  /// R, the state a story waits in for the person's review.
  String get review => _states[_review];

  /// The state [issueStates] give an open story, or null when no issue is left.
  String? state(List<String> issueStates) {
    final left = [
      for (final state in issueStates)
        if (_place(state) >= 0) _place(state),
    ];
    if (left.isEmpty) return null;
    if (left.every(_reviewOrLater)) return _states[_review];
    final building = left.where(_building).toList();
    if (building.isNotEmpty) return _states[_furthestBuilt(building)];
    return _states[left.map((at) => _reviewOrLater(at) ? _backlog : at).reduce((a, b) => a > b ? a : b)];
  }

  /// The ids of [ids] whose state in [issueStates] stands before [storyState]; one in R or later stands behind none.
  List<String> behind(String storyState, List<String> ids, List<String> issueStates) {
    final story = _states.indexOf(storyState);
    final named = <String>[];
    for (var i = 0; i < ids.length; i++) {
      final at = _place(issueStates[i]);
      if (at >= 0 && !_reviewOrLater(at) && at < story && !named.contains(ids[i])) named.add(ids[i]);
    }
    return named;
  }

  /// The served warning naming [ids] behind a story in [state], worded as the planner words it.
  static Map<String, dynamic> warning(List<String> ids, String state) {
    final names = ids.length < 2 ? ids.join() : '${ids.sublist(0, ids.length - 1).join(', ')} and ${ids.last}';
    return {
      'kind': behindKind,
      'text':
          '${ids.length == 1 ? 'Its issue' : 'Its issues'} $names ${ids.length == 1 ? 'stands' : 'stand'} '
          'before its state $state.',
      'holds': false,
    };
  }

  /// The W state a story being built stands in: the furthest among its issues in W.
  static int _furthestBuilt(List<int> building) => building.reduce((a, b) => a > b ? a : b);

  int _place(String state) => state == rejecting ? -1 : _states.indexOf(state);

  bool _reviewOrLater(int at) => _closed.contains(_states[at]) || (at >= _review && !_built.contains(at));

  bool _building(int at) => _built.contains(at);
}
