import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_detail.dart';
import 'actions.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The machine's standalone story and separately read issue list.
class PlanningStoryView extends StatelessWidget {
  /// Opens issues and tasks on this story's own provider.
  const PlanningStoryView({
    required this.detail,
    required this.onIssue,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    super.key,
  });

  /// The retained reads.
  final PlanningDetail detail;

  /// Opens an issue row.
  final ValueChanged<String> onIssue;

  /// Opens a reported task.
  final ValueChanged<String> onTask;

  /// Why a task cannot be opened on its own provider.
  final String? Function(String) taskUnavailable;

  /// Opens a web address in the person's browser.
  final Future<void> Function(Uri) openLink;

  @override
  Widget build(BuildContext context) {
    final story = detail.story?.story;
    final issues = detail.issues;
    final completed = issues != null && issues.reported && issues.issues.every((issue) => issue.state.isNotEmpty)
        ? issues.issues.where((issue) => {'Review', 'Finished'}.contains(issue.state)).length
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (story == null && !detail.loading && detail.storyProblem == null) const Text('Story not reported.'),
        if (story != null) ...[
          Wrap(
            spacing: Space.small,
            children: [
              Text(story.id),
              Chip(label: Text(planningValue(story.priority ?? ''))),
              Chip(label: Text(planningValue(story.state))),
            ],
          ),
          Text(planningValue(story.title), style: Theme.of(context).textTheme.headlineSmall),
          PlanningWebLink(link: story.link, label: 'Open story', openLink: openLink),
          planningSection(context, 'Description', [SelectableText(planningValue(story.description))]),
        ],
        planningSection(context, 'Issues', [
          if (detail.moveUnavailable case final reason? when !detail.loading && !detail.saving) Text(reason),
          if (detail.moveProblem case final problem?) Text(problem, key: const Key('planning-move-error')),
          if (detail.moveNotice case final notice?) Text(notice, key: const Key('planning-move-notice')),
          if (detail.board.pending.isNotEmpty) ...[
            Text(
              '${detail.board.pending.length} planning move${detail.board.pending.length == 1 ? '' : 's'} not saved',
              key: const Key('planning-moves-pending'),
            ),
            for (final entry in detail.board.pending.entries)
              Text(
                '${entry.key} ${planningValue(entry.value.from)} → ${entry.value.to}',
                key: Key('planning-pending-move ${entry.key}'),
              ),
            Wrap(
              children: [
                FilledButton(
                  key: const Key('planning-save-moves'),
                  onPressed: detail.canSaveMoves ? savePlanningAction(detail.board).run : null,
                  child: Text(detail.saving ? 'Saving changes…' : 'Save changes'),
                ),
                TextButton(
                  key: const Key('planning-undo-moves'),
                  onPressed: detail.saving ? null : detail.undoMoves,
                  child: const Text('Undo changes'),
                ),
              ],
            ),
          ],

          if (issues == null && !detail.loading && detail.issuesProblem == null) const Text('Issues not reported'),
          if (issues != null) ...[
            if (!issues.reported)
              Text(issues.supplied ? 'Issues not reported completely' : 'Issues not reported')
            else if (issues.issues.isEmpty)
              const Text('No issues yet.')
            else if (completed != null)
              Text('$completed of ${issues.issues.length} in Review or Finished')
            else
              const Text('Issue progress not reported'),
            if (issues.issues.isNotEmpty)
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: DataTable(
                  dataRowMaxHeight: double.infinity,
                  columns: [
                    for (final label in ['Issue', 'Title', 'Repository', 'Size', 'State'])
                      DataColumn(label: Text(label)),
                  ],
                  rows: [
                    for (final issue in issues.issues)
                      DataRow(
                        cells: [
                          DataCell(
                            TextButton(
                              key: Key('planning-issue ${issue.id}'),
                              onPressed: issue.id.isEmpty ? null : () => onIssue(issue.id),
                              child: Text(planningValue(issue.id)),
                            ),
                          ),
                          DataCell(Text(planningValue(issue.title))),
                          DataCell(Text(planningValue(issue.repository))),
                          DataCell(Text(planningValue(issue.size))),
                          DataCell(_issueState(context, issue)),
                        ],
                      ),
                  ],
                ),
              ),
          ],
        ]),
        if (story != null)
          planningSection(context, 'Work', [
            if (story.work == null)
              const Text('Work not reported')
            else ...[
              Text(switch (story.work!.atWork) {
                true => 'At work',
                false => 'Nobody at work',
                null => 'Work status not reported',
              }),
              if (story.work!.coordinator case final coordinator? when coordinator.trim().isNotEmpty)
                Text('Coordinator: ${planningShown(coordinator, multiline: false)}'),
              Text('Agent: ${planningValue(story.work!.agent)} · Model: ${planningValue(story.work!.model)}'),
              Text('Mode: ${planningWorkMode(story.work!.mode)}'),
              if (story.work!.since.isNotEmpty) Text('Since ${story.work!.since}'),
              for (final task in story.work!.tasks)
                PlanningTaskLink(
                  name: task.task,
                  onOpen: () => onTask(task.task),
                  unavailable: taskUnavailable(task.task),
                ),
            ],
          ]),
      ],
    );
  }

  String? _moveUnavailable(PlanIssue issue) =>
      detail.moveUnavailable ??
      (issue.id.isEmpty
          ? 'Issue identifier not reported.'
          : issue.state.isEmpty
          ? 'Issue state not reported.'
          : detail.moveStates.isEmpty && !detail.pendingMoves.containsKey(issue.id)
          ? 'No waiting or closed destinations were reported.'
          : null);

  Widget _issueState(BuildContext context, PlanIssue issue) => Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      PopupMenuButton<String>(
        key: Key('planning-move ${issue.id}'),
        tooltip: _moveUnavailable(issue) ?? 'Change the state of ${issue.id}',
        enabled: _moveUnavailable(issue) == null,
        onSelected: (to) => movePlanningIssueAction(detail.board, issue, to).run(),
        itemBuilder: (_) => [
          if (detail.pendingMoves.containsKey(issue.id))
            PopupMenuItem(value: issue.state, child: const Text('Undo this move')),
          for (final state in detail.moveStates)
            PopupMenuItem(
              value: state.name,
              key: Key('planning-move-to ${issue.id} ${state.name}'),
              child: Text(state.name),
            ),
        ],
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(planningValue(issue.state)),
            const Icon(Icons.expand_more, size: PlanningStyle.rowIcon),
          ],
        ),
      ),
      if (_moveUnavailable(issue) case final reason?) Text(reason),
      if (detail.pendingMoves[issue.id] case final move?)
        Text('Pending: ${move.to}', key: Key('planning-move-pending ${issue.id}')),
      if (detail.moveProblems[issue.id] case final problem?)
        Text(
          problem,
          key: Key('planning-move-problem ${issue.id}'),
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
    ],
  );
}
