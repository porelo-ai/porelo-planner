import 'dart:async';

import '../models.dart';

import 'package:flutter/material.dart';

import '../planning_detail.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The issue's facts, sub-issues, acceptance criteria, last reading and sub-agents.
class PlanningIssueView extends StatelessWidget {
  /// Keeps navigation on the issue's original provider.
  const PlanningIssueView({
    required this.detail,
    required this.onStory,
    required this.onIssue,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    super.key,
  });

  /// The retained reads.
  final PlanningDetail detail;

  /// Returns to the owning story.
  final ValueChanged<String> onStory;

  /// Opens a sub-issue on this issue's original provider and project.
  final ValueChanged<String> onIssue;

  /// Opens a reported task on this machine.
  final ValueChanged<String> onTask;

  /// Why a task cannot be opened on its own provider.
  final String? Function(String) taskUnavailable;

  /// Opens a web address in the person's browser.
  final Future<void> Function(Uri) openLink;

  @override
  Widget build(BuildContext context) {
    final reply = detail.issue;
    final issue = reply?.issue;
    if (reply == null || issue == null) {
      return detail.loading || detail.issueProblem != null
          ? const SizedBox.shrink()
          : const Text('Issue not reported.');
    }
    final work = detail.story?.story?.work;
    // A closed issue whose file is gone is answered from its index row alone.
    final settled = reply.textReported && reply.text.isEmpty && detail.board.state(issue.state)?.kind == 'CLOSED';
    final owner =
        detail.board.stories.where((story) => story.issues.any((item) => item.id == issue.id)).firstOrNull?.id ?? '';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          spacing: Space.small,
          children: [
            Text(planningShown(issue.id, multiline: false)),
            Chip(label: Text(planningValue(issue.state))),
            Text(planningValue(issue.repository)),
            Text('Worker: ${planningWorker(issue.worker)}'),
            if (issue.parent == null && issue.worker != null) _workerSwitch(issue),
          ],
        ),
        if (detail.workerProblem case final problem?)
          Text(
            problem,
            key: const Key('planning-worker-problem'),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        Text(planningValue(issue.title), style: Theme.of(context).textTheme.headlineSmall),
        PlanningWebLink(link: issue.link, label: 'Open issue', openLink: openLink),
        if (reply.revision.isNotEmpty) Text('Revision: ${reply.revision}'),
        planningSection(context, 'Size', [Text(planningValue(issue.size))]),
        planningSection(context, 'Blocked by', [
          Text(
            !issue.blockedByReported
                ? 'Not reported'
                : issue.blockedBy.isEmpty
                ? 'None'
                : issue.blockedBy.join(', '),
          ),
        ]),
        planningSection(context, 'Story', [
          if (owner.isEmpty)
            const Text('Not reported')
          else
            TextButton(key: const Key('planning-owner-story'), onPressed: () => onStory(owner), child: Text(owner)),
        ]),
        planningSection(context, 'Sub-issues', [
          if (!reply.subIssuesReported)
            const Text('Sub-issues not reported completely')
          else if (reply.subIssues.isEmpty)
            const Text('No sub-issues.'),
          for (final id in reply.subIssues)
            TextButton(
              key: Key('planning-sub-issue $id'),
              onPressed: () => onIssue(id),
              child: Text(planningShown(id, multiline: false)),
            ),
        ]),
        planningSection(context, 'Who builds it', [
          Text('Repository: ${planningValue(issue.repository)}'),
          if (owner.isEmpty)
            const Text('Owning story not reported')
          else if (work == null && detail.storyProblem == null)
            const Text('Work not reported')
          else if (work != null) ...[
            Text(switch (work.atWork) {
              true => 'At work on this story',
              false => 'Nobody at work on this story',
              null => 'Work status not reported',
            }),
            for (final task in work.tasks.where(
              (task) => task.issues.contains(issue.id) || task.repository == issue.repository,
            ))
              PlanningTaskLink(
                name: task.task,
                onOpen: () => onTask(task.task),
                unavailable: taskUnavailable(task.task),
              ),
          ],
        ]),
        planningSection(context, 'What must be true', [
          if (settled)
            const Text('Settled; its text was removed when it was finished.')
          else if (!reply.criteriaReported)
            Text(
              reply.criteriaSupplied
                  ? 'Acceptance criteria not reported completely'
                  : 'Acceptance criteria not reported',
            )
          else if (reply.whatMustBeTrue.isEmpty)
            const Text('No acceptance criteria.'),
          for (final text in reply.whatMustBeTrue) SelectableText(planningShown(text)),
        ]),
        planningSection(context, 'Last reader findings', [
          if (!reply.findingsReported)
            Text(reply.findingsSupplied ? 'Findings not reported completely' : 'Findings not reported'),
          if (reply.findingsReported && reply.findings.isEmpty) const Text('No findings in the last reading.'),
          for (final finding in reply.orderedFindings)
            Container(
              padding: const EdgeInsets.all(Space.small),
              child: Column(
                key: finding.number == null ? null : Key('planning-finding ${finding.number}'),
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: Space.small,
                    children: [
                      if (finding.severity == 'BLOCKING')
                        Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
                      Text(
                        '${finding.number ?? '?'} · ${planningValue(finding.severity)}',
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                    ],
                  ),
                  SelectableText(planningValue(finding.text)),
                  if (finding.file != null || finding.line != null)
                    SelectableText(
                      [
                        if (finding.file != null) planningShown(finding.file!, multiline: false),
                        if (finding.line != null) 'line ${finding.line}',
                      ].join(' · '),
                    ),
                ],
              ),
            ),
        ]),
        planningSection(context, 'Sub-agents', [
          if (!reply.subAgentsReported)
            Text(reply.subAgentsSupplied ? 'Sub-agents not reported completely' : 'Sub-agents not reported')
          else if (reply.subAgents.isEmpty)
            const Text('No sub-agents.'),
          for (final agent in reply.subAgents) ...[
            Text('${planningValue(agent.name)} · ${planningValue(agent.role)}'),
            Text('Agent: ${planningValue(agent.agent)} · Model: ${planningValue(agent.model)}'),
            SelectableText(planningValue(agent.brief)),
          ],
        ]),
      ],
    );
  }

  Widget _workerSwitch(PlanIssue issue) {
    final unavailable = detail.workerSaving ? 'waiting for the planner' : detail.board.workerUnavailable(issue);
    final next = issue.byHand ? IssueWorker.agent : IssueWorker.manual;
    return Tooltip(
      message: unavailable ?? 'Let ${next == IssueWorker.manual ? 'a person' : 'an agent'} work ${issue.id}',
      child: TextButton(
        key: const Key('planning-worker-switch'),
        onPressed: unavailable == null ? () => unawaited(detail.setWorker(next)) : null,
        child: Text('Switch to ${planningWorker(next)}'),
      ),
    );
  }
}
