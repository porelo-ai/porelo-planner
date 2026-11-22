import 'models.dart';

/// What a group of a story's issues holds, in the order the page shows the groups.
enum StoryGroupKind {
  /// Issues in a working state that nothing open blocks.
  working,

  /// Issues left to build that an open issue on the board blocks.
  blocked,

  /// Issues in a waiting state that is no review state.
  waiting,

  /// Issues in a state that waits for the person's review.
  review,

  /// Issues in a state the workflow lacks, or of a kind the screen does not know.
  other,

  /// Issues in a closed state, folded until the person unfolds them.
  closed,
}

/// One issue of a story's page with what it waits on and who works it.
class StoryIssueRow {
  /// Takes the row's parts.
  const StoryIssueRow(this.issue, {this.blockers = const [], this.runAgent});

  /// The issue as the page lists it.
  final PlanIssue issue;

  /// The open issues on the board it waits on; empty unless it is blocked.
  final List<PlanIssue> blockers;

  /// The agent of the story's run task that works it now, or null when no task of a run works it.
  final String? runAgent;
}

/// One group of a story's issues.
class StoryIssueGroup {
  /// Takes the group's parts.
  const StoryIssueGroup(this.kind, this.label, this.rows);

  /// What the group holds.
  final StoryGroupKind kind;

  /// The group's name: its state's, or the kind's for blocked and other.
  final String label;

  /// Its issues, in the order the page was given them.
  final List<StoryIssueRow> rows;

  /// Names the group apart from the others on the page, so its fold survives a rebuild.
  String get key => '${kind.name} $label';

  /// Whether the group stands folded until the person unfolds it.
  bool get foldedByDefault => kind == StoryGroupKind.closed;
}

/// The state finished work waits in for the person's review, the one rule the board, the story page and the planner
/// count review by: of the states a work rule's work ends in that are neither a rule's own working state nor closed,
/// the one latest in the workflow, where the last work ends; null when there is none. An earlier work's end, such as
/// an analysis's or an estimate's, waits for the person's next step and is left to build, never review.
String? storyReviewState(List<PlanState> states, List<PlanWorkRule> work) {
  final closed = {
    for (final state in states)
      if (state.kind == 'CLOSED') state.name,
  };
  final working = {for (final rule in work) rule.state};
  final ends = {
    for (final rule in work)
      if (rule.done.isNotEmpty && !working.contains(rule.done) && !closed.contains(rule.done)) rule.done,
  };
  String? last;
  for (final state in states) {
    if (ends.contains(state.name)) last = state.name;
  }
  return last;
}

/// The states that wait for the person's review: the one [storyReviewState] names, or none.
Set<String> storyReviewStates(List<PlanState> states, List<PlanWorkRule> work) => {?storyReviewState(states, work)};

/// Groups [issues] by state: working, blocked, each waiting state, each review state, other, and each closed state,
/// in workflow order and without empty groups.
///
/// An issue left to build is blocked while an issue it waits on stands in [board] and is not closed; [board] holds
/// every issue the board knows, other stories' and those in no story too, and a blocker it lacks blocks nothing.
List<StoryIssueGroup> groupStoryIssues({
  required List<PlanIssue> issues,
  required List<PlanIssue> board,
  required List<PlanState> states,
  required List<PlanWorkRule> work,
  PlanWork? storyWork,
}) {
  final kinds = {for (final state in states) state.name: state.kind};
  final review = storyReviewStates(states, work);
  final known = {for (final issue in issues) issue.id: issue, for (final issue in board) issue.id: issue};
  // A state the workflow lacks is no closed state, so its issue still blocks, as the planner counts it.
  bool open(PlanIssue issue) => kinds[issue.state] != 'CLOSED';
  final blockedRows = <StoryIssueRow>[];
  final otherRows = <StoryIssueRow>[];
  final byState = <String, List<StoryIssueRow>>{};
  for (final issue in issues) {
    final agent = storyWork?.tasks
        .where((task) => task.ended == null && task.agent.isNotEmpty && task.issues.contains(issue.id))
        .firstOrNull
        ?.agent;
    final building = open(issue) && !review.contains(issue.state);
    final blockers = [
      if (building && issue.blockedByReported)
        for (final id in issue.blockedBy)
          // As the planner counts it: an issue that names itself and is open blocks itself.
          if (known[id] case final blocker? when open(blocker)) blocker,
    ];
    final row = StoryIssueRow(issue, blockers: blockers, runAgent: agent);
    if (blockers.isNotEmpty) {
      blockedRows.add(row);
    } else if (kinds.containsKey(issue.state) &&
        (review.contains(issue.state) || {'WORKING', 'WAITING', 'CLOSED'}.contains(kinds[issue.state]))) {
      byState.putIfAbsent(issue.state, () => []).add(row);
    } else {
      otherRows.add(row);
    }
  }
  StoryGroupKind kindOf(PlanState state) => review.contains(state.name)
      ? StoryGroupKind.review
      : switch (state.kind) {
          'WORKING' => StoryGroupKind.working,
          'WAITING' => StoryGroupKind.waiting,
          _ => StoryGroupKind.closed,
        };
  final groups = [
    for (final state in states)
      if (byState[state.name] case final rows?) StoryIssueGroup(kindOf(state), state.name, rows),
    if (blockedRows.isNotEmpty) StoryIssueGroup(StoryGroupKind.blocked, 'Blocked', blockedRows),
    if (otherRows.isNotEmpty) StoryIssueGroup(StoryGroupKind.other, 'Other', otherRows),
  ];
  // The states keep their workflow order within each kind.
  return [for (final kind in StoryGroupKind.values) ...groups.where((group) => group.kind == kind)];
}

/// The figures of a story's head, all from the planner's count, each null when it is not known.
class StoryHeadFigures {
  /// Reads [open], which is null when the planner did not count the story or a move made its count stale.
  StoryHeadFigures(PlanOpenWork? open, List<PlanState> states, List<PlanWorkRule> work)
    : left = open?.left,
      total = open?.states.values.fold<int>(open.blocked, (sum, count) => sum + count),
      minutes = open?.minutes,
      unestimated = open?.unestimated,
      sizes = open == null
          ? null
          : [
              for (final entry in open.sizes.entries) '${entry.value} ${entry.key}',
              if (open.unsized > 0) '${open.unsized} unsized',
            ],
      bar = open == null ? null : _bar(open, states, work);

  /// The issues left to build, the blocked ones included.
  final int? left;

  /// The issues the planner counted in a workflow state or as blocked; one in a state the workflow lacks is not in it.
  final int? total;

  /// The minutes left over the issues that have any; null also when none has.
  final double? minutes;

  /// The issues left to build without minutes.
  final int? unestimated;

  /// Each open size with its count, smallest first, the unsized last; empty when nothing is left.
  final List<String>? sizes;

  /// The story's issues by kind of group, in group order, only kinds that occur.
  final Map<StoryGroupKind, int>? bar;

  static Map<StoryGroupKind, int> _bar(PlanOpenWork open, List<PlanState> states, List<PlanWorkRule> work) {
    final review = storyReviewStates(states, work);
    final kinds = {for (final state in states) state.name: state.kind};
    final counts = <StoryGroupKind, int>{};
    void add(StoryGroupKind kind, int count) {
      if (count > 0) counts[kind] = (counts[kind] ?? 0) + count;
    }

    add(StoryGroupKind.blocked, open.blocked);
    for (final MapEntry(key: name, value: count) in open.states.entries) {
      add(
        review.contains(name)
            ? StoryGroupKind.review
            : switch (kinds[name]) {
                'WORKING' => StoryGroupKind.working,
                'WAITING' => StoryGroupKind.waiting,
                'CLOSED' => StoryGroupKind.closed,
                _ => StoryGroupKind.other,
              },
        count,
      );
    }
    return {for (final kind in StoryGroupKind.values) kind: ?counts[kind]};
  }
}

/// What a figure reads while it is not known, never a 0.
const storyFigureUnknown = 'unknown';

/// Minutes as hours and minutes, rounded to the minute.
String storyMinutesText(double minutes) {
  final whole = minutes.round();
  if (whole < 60) return '$whole min';
  return '${whole ~/ 60} h ${(whole % 60).toString().padLeft(2, '0')}';
}
