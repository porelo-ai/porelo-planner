import 'package:flutter/material.dart';

import '../models.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The board's issue row, shared by stories and the card of issues in no story.
class PlanningIssueRow extends StatelessWidget {
  /// Keeps issue navigation and actions with the board that supplied the row.
  const PlanningIssueRow({
    required this.issue,
    required this.onOpen,
    this.sizeBadge,
    this.menu,
    this.refusal,
    super.key,
  });

  /// The issue as reported at the board's revision.
  final PlanIssue issue;

  /// Opens the issue's own page.
  final VoidCallback onOpen;

  /// The board's size badge, absent when no size is reported.
  final Widget? sizeBadge;

  /// Actions available from this row.
  final Widget? menu;

  /// Why the issue's last move was refused, or null.
  final String? refusal;

  @override
  Widget build(BuildContext context) => Column(
    key: Key('planning-issue-row ${issue.id}'),
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Expanded(
            child: Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                key: Key('planning-issue ${issue.id}'),
                onPressed: issue.id.isEmpty ? null : onOpen,
                child: Text(planningShown('${issue.id} · ${issue.repository}', multiline: false)),
              ),
            ),
          ),
          ?menu,
        ],
      ),
      if (issue.title.isNotEmpty) Text(planningShown(issue.title, multiline: false)),
      Wrap(
        spacing: Space.small,
        children: [
          if (issue.state.isNotEmpty) Text(planningShown(issue.state, multiline: false)),
          ?sizeBadge,
          ?planningWarningMark(issue.warnings, key: Key('planning-warned-issue ${issue.id}')),
          if (issue.worker case final worker?) Text(planningWorker(worker)),
          if (issue.noRunHere) Text('worked outside this planner', key: Key('planning-no-run ${issue.id}')),
        ],
      ),
      if (!issue.blockedByReported)
        const Text('Blockers not reported')
      else if (issue.blockedBy.isNotEmpty)
        Text(planningShown('Blocked by ${issue.blockedBy.join(', ')}', multiline: false)),
      if (!issue.waitsOnReported)
        const Text('Waits not reported')
      else if (issue.waitsHolding.isNotEmpty)
        Text(
          planningShown('Waits on ${issue.waitsHolding.map((wait) => wait.spoken).join(', ')}', multiline: false),
          key: Key('planning-waits ${issue.id}'),
        ),
      if (refusal case final words?)
        Text(
          words,
          key: Key('planning-issue-refused ${issue.id}'),
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
    ],
  );
}
