import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'actions.dart';
import 'planning_menu.dart';
import 'style.dart';

/// What the board offers beside a warning's mark to set the warning right: the correction a warning names, as a menu
/// of its choices, and for a text without sections the link to where the item is kept, since the planner never
/// rewrites a person's text. Null when none of [warnings] offers anything the window knows.
Widget? planningCorrectionOffer({
  required PlanningBoard board,
  required String id,
  required bool story,
  required String state,
  required List<PlanWarning> warnings,
  required String link,
  required Future<void> Function(Uri) openLink,
}) {
  if (id.isEmpty || warnings.isEmpty) return null;
  final menu = switch (PlanningBoard.offeredCorrection(warnings, story: story)) {
    (final warning, final correction) => _CorrectionMenu(
      board: board,
      id: id,
      story: story,
      state: state,
      warning: warning,
      correction: correction,
    ),
    null => null,
  };
  final repair = _repair(board, id, warnings, link, openLink);
  if (menu == null || repair == null) return menu ?? repair;
  return Row(mainAxisSize: MainAxisSize.min, children: [menu, repair]);
}

/// The link to where the item is kept, for a text without sections; null for none.
Widget? _repair(
  PlanningBoard board,
  String id,
  List<PlanWarning> warnings,
  String link,
  Future<void> Function(Uri) openLink,
) {
  if (!warnings.any((warning) => warning.kind == PlanWarning.bodyNotSectioned)) return null;
  final open = planningWebAction(
    'Repair its text where it is kept',
    link,
    openLink,
    onError: board.reportActionProblem,
  );
  if (open.unavailable != null) return null;
  return IconButton(
    key: Key('planning-repair $id'),
    tooltip: 'Repair its text where it is kept: the planner reads only a text in its sections',
    iconSize: PlanningStyle.rowIcon,
    visualDensity: VisualDensity.compact,
    onPressed: open.run,
    icon: const Icon(Icons.open_in_new),
  );
}

class _CorrectionMenu extends StatelessWidget {
  const _CorrectionMenu({
    required this.board,
    required this.id,
    required this.story,
    required this.state,
    required this.warning,
    required this.correction,
  });

  final PlanningBoard board;
  final String id;
  final bool story;
  final String state;
  final PlanWarning warning;
  final PlanCorrection correction;

  @override
  Widget build(BuildContext context) {
    final unavailable = board.correctionUnavailable;
    final shown = planningShown(id, multiline: false);
    return PlanningMenu(
      entries: (_) => _entries(),
      builder: (_, toggle) => IconButton(
        key: Key('planning-correct $id'),
        tooltip: unavailable == null ? 'Set $shown right' : 'Set $shown right: $unavailable',
        iconSize: PlanningStyle.rowIcon,
        icon: const Icon(Icons.build_outlined),
        onPressed: unavailable == null ? toggle : null,
      ),
    );
  }

  /// The choices, against the board as it stands when the menu opens; a move's in a group for each kind of state.
  List<Widget> _entries() {
    final revision = board.snapshot?.revision;
    final generation = board.generation;
    Widget entry(String choice, String words) => MenuItemButton(
      key: Key('planning-correct-to $id $choice'),
      onPressed: () => _choose(choice, revision, generation),
      child: Text(planningShown(words, multiline: false)),
    );
    if (correction.kind != PlanCorrection.move) {
      return [for (final choice in correction.choices) entry(choice, 'Priority $choice')];
    }
    final groups = <String, List<Widget>>{for (final label in _kinds.values) label: [], _otherKind: []};
    // Taken from the workflow, so a state it lacks is not offered: the move would refuse it.
    for (final target in board.states.where((target) => correction.choices.contains(target.name))) {
      groups[_kinds[target.kind] ?? _otherKind]!.add(entry(target.name, 'Move to ${target.name}'));
    }
    return [
      for (final MapEntry(key: label, value: entries) in groups.entries)
        if (entries.isNotEmpty) SubmenuButton(menuChildren: entries, child: Text(label)),
    ];
  }

  void _choose(String choice, String? revision, int generation) {
    if (board.disposed || board.generation != generation) return;
    unawaited(
      story
          ? board.correctStory(id, warning.kind, choice, revision: revision)
          : board.correctIssue(id, warning.kind, choice, shown: state, revision: revision),
    );
  }

  /// The group of a state of each kind the workflow names, in this order.
  static const _kinds = {'WAITING': 'Waiting', 'WORKING': 'Working', 'CLOSED': 'Closed'};

  /// The group of a state of a kind the window does not know, which the move still takes.
  static const _otherKind = 'Other';
}

/// What the last whole board found wrong in the project's repositories themselves, in the planning header, each with
/// the correction its store offers; nothing when there is none. One warning is shown whole; several take one line until
/// the person opens them, so they never push the board out of sight.
class PlanningRepositoryWarnings extends StatefulWidget {
  /// Shows [board]'s repository warnings.
  const PlanningRepositoryWarnings({required this.board, super.key});

  /// The board whose repositories are warned about.
  final PlanningBoard board;

  @override
  State<PlanningRepositoryWarnings> createState() => _PlanningRepositoryWarningsState();
}

class _PlanningRepositoryWarningsState extends State<PlanningRepositoryWarnings> {
  bool _open = false;

  PlanningBoard get board => widget.board;

  @override
  Widget build(BuildContext context) {
    final warnings = board.repositoryWarnings;
    if (warnings.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final list = _list(warnings, theme);
    if (warnings.length < 2) return list;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          key: const Key('planning-repository-warnings-fold'),
          onTap: () => setState(() => _open = !_open),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            spacing: Space.small,
            children: [
              Icon(Icons.warning_amber_outlined, size: PlanningStyle.rowIcon, color: theme.colorScheme.error),
              Flexible(child: Text('${warnings.length} warnings about the repositories — ${_open ? 'hide' : 'show'}')),
            ],
          ),
        ),
        if (_open)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: PlanningStyle.column),
            child: SingleChildScrollView(child: list),
          ),
      ],
    );
  }

  Widget _list(List<PlanRepositoryWarning> warnings, ThemeData theme) {
    return Column(
      key: const Key('planning-repository-warnings'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // The offer wraps under the words when the window is narrow, so no row overflows.
        for (final warning in warnings)
          Wrap(
            key: Key('planning-repository-warning ${warning.repository} ${warning.kind}'),
            spacing: Space.small,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                spacing: Space.small,
                children: [
                  Icon(Icons.warning_amber_outlined, size: PlanningStyle.rowIcon, color: theme.colorScheme.error),
                  Flexible(child: Text(planningShown('${warning.repository}: ${warning.words}.', multiline: false))),
                ],
              ),
              ..._offer(warning),
            ],
          ),
      ],
    );
  }

  /// The button that previews the warning's Labels correction and, while it cannot be taken, the reason in words;
  /// none when its store offers no correction the window knows.
  List<Widget> _offer(PlanRepositoryWarning warning) {
    if (warning.correction?.kind != PlanCorrection.labels) return const [];
    final unavailable = board.correctionUnavailable;
    final generation = board.generation;
    return [
      TextButton.icon(
        key: Key('planning-correct-repository ${warning.repository} ${warning.kind}'),
        onPressed: unavailable != null
            ? null
            : () {
                if (board.disposed || board.generation != generation) return;
                unawaited(board.correctRepository(warning.repository, warning.kind));
              },
        icon: const Icon(Icons.build_outlined),
        label: const Text('Set its labels right'),
      ),
      if (unavailable != null) Text(unavailable),
    ];
  }
}
