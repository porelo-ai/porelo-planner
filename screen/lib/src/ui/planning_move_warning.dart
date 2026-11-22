import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The warnings of the move [PlanningBoard.question] holds, with Confirm and Cancel; nothing when no move is asked
/// about. Nothing is saved before Confirm, and Cancel sends nothing.
class PlanningMoveWarningPanel extends StatelessWidget {
  /// Shows [board]'s question, with Confirm and Cancel unless [buttons] is false and they stand apart.
  const PlanningMoveWarningPanel({required this.board, this.buttons = true, super.key});

  /// The board whose move is asked about.
  final PlanningBoard board;

  /// Whether Confirm and Cancel stand in the panel; the board keeps them apart, in view, below what scrolls.
  final bool buttons;

  @override
  Widget build(BuildContext context) {
    final asked = board.question;
    if (asked == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
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
            for (final warning in asked.warnings) ..._warning(warning, _closed(board)),
            if (asked.asksReason) ...[
              const SizedBox(height: Space.small),
              PlanningMoveReason(question: asked, key: ValueKey(asked)),
            ],
            if (buttons) ...[const SizedBox(height: Space.small), PlanningMoveButtons(board: board)],
          ],
        ),
      ),
    );
  }

  /// The board's closed states, into which a moved issue is settled and its file deleted.
  static Set<String> _closed(PlanningBoard board) => {
    for (final state in board.states)
      if (state.kind == 'CLOSED') state.name,
  };

  List<Widget> _warning(PlanningMoveWarning warning, Set<String> closed) => [
    Text(
      planningShown(_said(warning, closed), multiline: false),
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

  static String _said(PlanningMoveWarning warning, Set<String> closed) => switch (warning.kind) {
    PlanningMoveWarning.runningWork when warning.local =>
      '${warning.item} has a start this window handed on that the planner does not report yet; it is stopped first.',
    PlanningMoveWarning.runningWork =>
      '${warning.item} has agent tasks at work, stopped first: ${[...warning.tasks, ...warning.offers.map((offer) => 'offer $offer, not started')].join(', ')}.',
    PlanningMoveWarning.issuesAlong when closed.contains(warning.to) =>
      '${warning.item} carries these issues along; their files are deleted as each is closed:',
    PlanningMoveWarning.issuesAlong => '${warning.item} carries these issues along:',
    PlanningMoveWarning.skipsStates =>
      '${warning.item} skips ${warning.skipped.join(', ')} on its way from ${warning.from} to ${warning.to}.',
    PlanningMoveWarning.storyMismatch =>
      '${warning.item} will stand in ${warning.to} while these issues do not fit it:',
    _ => '${warning.item}: ${warning.kind}',
  };
}

/// The one-line field for the person's reason why the item is closed, which may stay empty; it counts characters as
/// the planner does, after trimming.
class PlanningMoveReason extends StatefulWidget {
  /// Edits [question]'s reason.
  const PlanningMoveReason({required this.question, super.key});

  /// The move asked about.
  final PlanningMoveQuestion question;

  @override
  State<PlanningMoveReason> createState() => _PlanningMoveReasonState();
}

class _PlanningMoveReasonState extends State<PlanningMoveReason> {
  late final TextEditingController _text = TextEditingController(text: widget.question.reason.value);

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<String>(
    valueListenable: widget.question.reason,
    builder: (context, reason, _) {
      final count = reason.trim().runes.length;
      return TextField(
        key: const Key('planning-move-reason'),
        controller: _text,
        maxLines: 1,
        decoration: InputDecoration(
          labelText: 'Why, in one sentence (optional): what was built, or why not',
          counterText: '$count/$planningMostReason',
          errorText: widget.question.reasonTooLong ? 'At most $planningMostReason characters.' : null,
        ),
        onChanged: (value) => widget.question.reason.value = value,
      );
    },
  );
}

/// Confirm and Cancel of the move [PlanningBoard.question] holds; nothing when no move is asked about.
class PlanningMoveButtons extends StatelessWidget {
  /// Answers [board]'s question.
  const PlanningMoveButtons({required this.board, super.key});

  /// The board whose move is asked about.
  final PlanningBoard board;

  @override
  Widget build(BuildContext context) {
    final asked = board.question;
    if (asked == null) return const SizedBox.shrink();
    final stops = asked.warnings.any((warning) => warning.kind == PlanningMoveWarning.runningWork);
    return Wrap(
      spacing: Space.small,
      children: [
        ValueListenableBuilder<String>(
          valueListenable: asked.reason,
          builder: (context, _, _) => FilledButton(
            key: const Key('planning-move-confirm'),
            onPressed: asked.reasonTooLong ? null : () => unawaited(board.confirmMove()),
            child: Text(stops ? 'Stop the tasks and move' : 'Move'),
          ),
        ),
        TextButton(key: const Key('planning-move-cancel'), onPressed: board.cancelMove, child: const Text('Cancel')),
      ],
    );
  }
}
