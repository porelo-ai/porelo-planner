import 'dart:async';

import 'package:flutter/material.dart';

import '../hooks.dart';
import '../models.dart';
import '../planning_board.dart';
import 'style.dart';

/// One planning action: what it is called, what it does, and why it cannot run now.
///
/// The window lists them in its own command menu and palette; the screen runs them from its own buttons.
class PlanningAction {
  /// Takes everything a menu, a palette and a button need.
  const PlanningAction({
    required this.id,
    required this.label,
    required this.run,
    this.unavailable,
    this.danger = false,
    this.wayOn,
  });

  /// Stable identifier.
  final String id;

  /// What it is called, in the words a person would search for.
  final String label;

  /// What it does.
  final VoidCallback run;

  /// Why it cannot be run now, or null when it can; an unavailable action is still listed.
  final String? unavailable;

  /// Whether it takes something away.
  final bool danger;

  /// Where the person goes to settle what holds it, offered only while it cannot run; never runs the action.
  final PlanningWayOn? wayOn;

  /// Whether it can be run now.
  bool get available => unavailable == null;
}

/// A step that leads to what holds an unavailable action, such as the run that needs attention.
class PlanningWayOn {
  /// Takes what it is called and what it does.
  const PlanningWayOn({required this.label, required this.run});

  /// What it is called.
  final String label;

  /// What it does.
  final VoidCallback run;
}

/// Opens a planning destination on the provider that supplied it.
PlanningAction openPlanningAction(
  PlanningBoards boards,
  PlanningHost host,
  PlanningBoard board,
  String story, {
  String? issue,
}) => PlanningAction(
  id: 'planning.open/${boards.sourceOf(board)}/${board.project}/$story${issue == null ? '' : '/$issue'}',
  label: issue == null ? 'Open story $story' : 'Open issue $issue',
  unavailable: board.disposed ? 'The planning source is no longer watched.' : null,
  run: () => host.go(PlanningPlace(project: board.project, source: boards.sourceOf(board), story: story, issue: issue)),
);

/// A staged issue change always belongs to the board's reviewed batch.
PlanningAction movePlanningIssueAction(PlanningBoard board, PlanIssue issue, String to) => PlanningAction(
  id: 'planning.moveIssue/${board.project}/${issue.id}/$to',
  label: '${issue.id} → $to',
  unavailable: board.disposed || !board.live
      ? 'The planning board is not live.'
      : board.saving
      ? 'Planning is saving.'
      : null,
  run: () => board.moveIssue(issue.id, to, reported: issue),
);

/// The same save action is used by the board, detail screens and finder.
PlanningAction savePlanningAction(PlanningBoard board) => PlanningAction(
  id: 'planning.save/${board.project}',
  label: 'Save planning changes',
  unavailable:
      board.unavailableActions['SaveMoves'] ??
      (board.saving
          ? 'Planning is saving.'
          : !board.live
          ? 'The planning board is not live.'
          : board.pending.isEmpty
          ? 'No planning moves are pending.'
          : null),
  run: () => unawaited(board.save()),
);

/// Undo never calls the provider.
PlanningAction undoPlanningAction(PlanningBoard board) => PlanningAction(
  id: 'planning.undo/${board.project}',
  label: 'Undo planning changes',
  unavailable: board.saving
      ? 'Planning is saving.'
      : board.pending.isEmpty
      ? 'No planning moves are pending.'
      : null,
  run: board.undoAll,
);

/// Every story, issue and move, so each is reachable without a pointer gesture.
List<PlanningAction> planningActions(PlanningBoards boards, PlanningHost host) => [
  for (final board in boards.sources) ...[
    savePlanningAction(board),
    undoPlanningAction(board),
    for (final story in board.stories) ...[
      openPlanningAction(boards, host, board, story.id),
      planningWebAction(
        'Open story ${story.id} in browser',
        story.link,
        host.openLink,
        onError: board.reportActionProblem,
      ),
      for (final issue in story.issues) ...[
        openPlanningAction(boards, host, board, story.id, issue: issue.id),
        planningWebAction(
          'Open issue ${issue.id} in browser',
          issue.link,
          host.openLink,
          onError: board.reportActionProblem,
        ),
        if (board.pendingIssues.containsKey(issue.id)) movePlanningIssueAction(board, issue, issue.state),
        for (final state in board.states.where((state) => {'WAITING', 'CLOSED'}.contains(state.kind)))
          movePlanningIssueAction(board, issue, state.name),
      ],
    ],
  ],
];

/// A reported HTTPS address is the sole web destination offered for an item.
PlanningAction planningWebAction(
  String label,
  String link,
  Future<void> Function(Uri) openLink, {
  void Function(Object)? onError,
}) {
  final address = Uri.tryParse(link);
  return PlanningAction(
    id: 'planning.web/$link',
    label: label,
    unavailable: address == null || address.scheme != 'https' || address.host.isEmpty
        ? 'No HTTPS link was reported.'
        : null,
    run: () async {
      try {
        if (address != null) await openLink(address);
      } catch (error) {
        onError?.call(error);
      }
    },
  );
}

/// The actions one story offers, as one menu; what cannot run stays in it, grayed, with its reason.
class PlanningActionMenu extends StatelessWidget {
  /// Takes what to offer and what to say it is for.
  const PlanningActionMenu({required this.actions, required this.tooltip, this.dense = false, this.current, super.key});

  /// What this story can do, runnable or not.
  final List<PlanningAction> actions;

  /// The actions as they stand now, read when the menu opens and again when an entry is chosen.
  final List<PlanningAction> Function()? current;

  static const _wayOn = ' way-on';

  /// Whether the button takes only its icon's room, as in a folded column.
  final bool dense;

  /// What the button says it is for.
  final String tooltip;

  @override
  Widget build(BuildContext context) {
    if (actions.isEmpty) return const SizedBox.shrink();
    return PopupMenuButton<String>(
      tooltip: tooltip,
      icon: const Icon(Icons.more_vert, size: PlanningStyle.rowIcon),
      padding: dense ? EdgeInsets.zero : const EdgeInsets.all(8),
      style: dense
          ? IconButton.styleFrom(
              minimumSize: const Size.square(PlanningStyle.denseButton),
              fixedSize: const Size.square(PlanningStyle.denseButton),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            )
          : null,
      onSelected: (id) {
        for (final action in current?.call() ?? actions) {
          if (action.id == id && action.available) action.run();
          if ('${action.id}$_wayOn' == id && !action.available) action.wayOn?.run();
        }
      },
      itemBuilder: (context) => <PopupMenuEntry<String>>[
        for (final action in current?.call() ?? actions) ...[
          PopupMenuItem<String>(
            key: ValueKey<String>(action.id),
            value: action.id,
            enabled: action.available,
            child: ListTile(
              dense: true,
              enabled: action.available,
              contentPadding: EdgeInsets.zero,
              title: Text(
                action.label,
                style: action.danger && action.available ? TextStyle(color: Theme.of(context).colorScheme.error) : null,
              ),
              subtitle: action.available ? null : Text('Unavailable: ${action.unavailable}'),
            ),
          ),
          if (action.wayOn case final wayOn? when !action.available)
            PopupMenuItem<String>(
              key: ValueKey<String>('${action.id}$_wayOn'),
              value: '${action.id}$_wayOn',
              child: ListTile(
                dense: true,
                contentPadding: const EdgeInsets.only(left: Space.normal),
                leading: const Icon(Icons.arrow_forward, size: PlanningStyle.rowIcon),
                title: Text(wayOn.label),
              ),
            ),
        ],
      ],
    );
  }
}
