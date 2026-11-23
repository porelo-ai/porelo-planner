import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'actions.dart';
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
    return PopupMenuButton<(String, String?, int)>(
      key: Key('planning-correct $id'),
      tooltip: unavailable == null ? 'Set $shown right' : 'Set $shown right: $unavailable',
      enabled: unavailable == null,
      iconSize: PlanningStyle.rowIcon,
      icon: const Icon(Icons.build_outlined),
      itemBuilder: (_) => [
        for (final choice in correction.choices)
          // A state the board's workflow lacks is refused by the move, so it is not offered.
          if (correction.kind != PlanCorrection.move || board.state(choice) != null)
            PopupMenuItem(
              key: Key('planning-correct-to $id $choice'),
              value: (choice, board.snapshot?.revision, board.generation),
              child: Text(
                planningShown(
                  correction.kind == PlanCorrection.move ? 'Move to $choice' : 'Priority $choice',
                  multiline: false,
                ),
              ),
            ),
      ],
      onSelected: (picked) {
        final (choice, revision, generation) = picked;
        if (board.disposed || board.generation != generation) return;
        unawaited(
          story
              ? board.correctStory(id, warning.kind, choice, revision: revision)
              : board.correctIssue(id, warning.kind, choice, shown: state, revision: revision),
        );
      },
    );
  }
}

/// What the last whole board found wrong in the project's repositories themselves, in the planning header, each with
/// the correction its store offers; nothing when there is none.
class PlanningRepositoryWarnings extends StatelessWidget {
  /// Shows [board]'s repository warnings.
  const PlanningRepositoryWarnings({required this.board, super.key});

  /// The board whose repositories are warned about.
  final PlanningBoard board;

  @override
  Widget build(BuildContext context) {
    final warnings = board.repositoryWarnings;
    if (warnings.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
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
