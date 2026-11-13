import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The warnings of the move [PlanningBoard.question] holds, with Confirm and Cancel; nothing when no move is asked
/// about. Nothing is saved before Confirm, and Cancel sends nothing.
class PlanningMoveWarningPanel extends StatelessWidget {
  /// Shows [board]'s question.
  const PlanningMoveWarningPanel({required this.board, super.key});

  /// The board whose move is asked about.
  final PlanningBoard board;

  @override
  Widget build(BuildContext context) {
    final asked = board.question;
    if (asked == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final stops = asked.warnings.any((warning) => warning.kind == PlanningMoveWarning.runningWork);
    return Card(
      key: const Key('planning-move-warning'),
      color: theme.colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              planningShown('Move ${asked.move.id} to ${asked.move.to}?', multiline: false),
              style: theme.textTheme.titleMedium,
            ),
            if (asked.again)
              const Text(
                'Planning changed since you confirmed this move; check it again.',
                key: Key('planning-move-warning-again'),
              ),
            for (final warning in asked.warnings) ..._warning(warning),
            const SizedBox(height: Space.small),
            Wrap(
              spacing: Space.small,
              children: [
                FilledButton(
                  key: const Key('planning-move-confirm'),
                  onPressed: () => unawaited(board.confirmMove()),
                  child: Text(stops ? 'Stop the tasks and move' : 'Move'),
                ),
                TextButton(
                  key: const Key('planning-move-cancel'),
                  onPressed: board.cancelMove,
                  child: const Text('Cancel'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _warning(PlanningMoveWarning warning) => [
    Text(
      planningShown(_said(warning), multiline: false),
      key: Key('planning-move-warning-item ${warning.kind} ${warning.item}'),
    ),
    for (final issue in warning.issues)
      Padding(
        padding: const EdgeInsets.only(left: Space.normal),
        child: Text(
          planningShown(
            '${issue.id} (${planningValue(issue.repository)}): ${planningValue(issue.from)} → ${planningValue(issue.to)}',
            multiline: false,
          ),
          key: Key('planning-move-warning-issue ${warning.kind} ${issue.id}'),
        ),
      ),
  ];

  static String _said(PlanningMoveWarning warning) => switch (warning.kind) {
    PlanningMoveWarning.runningWork when warning.local =>
      '${warning.item} has a start this window handed on that the planner does not report yet; it is stopped first.',
    PlanningMoveWarning.runningWork =>
      '${warning.item} has agent tasks at work, stopped first: ${[...warning.tasks, ...warning.offers.map((offer) => 'offer $offer, not started')].join(', ')}.',
    PlanningMoveWarning.issuesAlong => '${warning.item} carries these issues along:',
    PlanningMoveWarning.skipsStates =>
      '${warning.item} skips ${warning.skipped.join(', ')} on its way from ${warning.from} to ${warning.to}.',
    PlanningMoveWarning.storyMismatch =>
      '${warning.item} will stand in ${warning.to} while these issues do not fit it:',
    _ => '${warning.item}: ${warning.kind}',
  };
}
