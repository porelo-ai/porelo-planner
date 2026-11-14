import 'dart:async';

import '../models.dart';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../planning_detail.dart';
import 'planning_detail_widgets.dart';
import 'planning_text.dart';
import 'style.dart';

/// The issue as one page: a header of who and where, its text on the left, a panel of what it links to on the right.
class PlanningIssueView extends StatelessWidget {
  /// Keeps navigation on the issue's original provider.
  const PlanningIssueView({
    required this.detail,
    required this.onStory,
    required this.onIssue,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    this.onIssueOf,
    super.key,
  });

  /// The retained reads.
  final PlanningDetail detail;

  /// Returns to the owning story.
  final ValueChanged<String> onStory;

  /// Opens a sub-issue on this issue's original provider and project.
  final ValueChanged<String> onIssue;

  /// Opens another story's issue, such as one this issue waits on; without it, such an issue is named, not linked.
  final void Function(String story, String issue)? onIssueOf;

  /// Opens a reported task on this machine.
  final ValueChanged<String> onTask;

  /// Why a task cannot be opened on its own provider.
  final String? Function(String) taskUnavailable;

  /// Opens a web address in the person's browser.
  final Future<void> Function(Uri) openLink;

  /// The width from which the details panel stands beside the text rather than below it.
  static const double twoColumns = 840;

  /// The details panel's width beside the text.
  static const double panel = 340;

  @override
  Widget build(BuildContext context) {
    final reply = detail.issue;
    final issue = reply?.issue;
    if (reply == null || issue == null) {
      return detail.loading || detail.issueProblem != null
          ? const SizedBox.shrink()
          : const Text('Issue not reported.');
    }
    // A closed issue whose file is gone is answered from its index row alone.
    final settled = reply.textReported && reply.text.isEmpty && detail.board.state(issue.state)?.kind == 'CLOSED';
    final text = _header(context, reply, issue, settled);
    final details = _details(context, reply, issue);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: Space.wide,
      children: [
        _top(context, reply, issue),
        LayoutBuilder(
          builder: (context, constraints) => constraints.maxWidth >= twoColumns
              ? Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: Space.wide,
                  children: [
                    Expanded(child: text),
                    SizedBox(width: panel, child: details),
                  ],
                )
              : Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: Space.wide, children: [text, details]),
        ),
      ],
    );
  }

  /// The id, repository, project and title; the state, size and worker as chips; the revision to copy.
  Widget _top(BuildContext context, PlanIssueDetail reply, PlanIssue issue) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final board = detail.board;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: Space.small,
      children: [
        Wrap(
          spacing: Space.small,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              planningShown(issue.id, multiline: false),
              style: theme.textTheme.labelLarge?.copyWith(fontFamily: 'monospace'),
            ),
            Text(planningValue(issue.repository), style: muted),
            Text('·', style: muted),
            Text('Project ${board.project}', style: muted),
            Text('·', style: muted),
            Text('From ${board.backend.label}', style: muted),
          ],
        ),
        Text(planningValue(issue.title), style: theme.textTheme.headlineSmall),
        Wrap(
          spacing: Space.small,
          runSpacing: Space.small,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Chip(key: const Key('planning-issue-state'), label: Text(planningValue(issue.state))),
            Chip(label: Text('Size ${planningValue(issue.size)}')),
            Chip(
              key: const Key('planning-issue-worker'),
              label: Wrap(
                spacing: Space.small,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text('Worker: ${planningWorker(issue.worker)}'),
                  if (issue.parent == null && issue.worker != null) _workerSwitch(issue),
                ],
              ),
            ),
            PlanningWebLink(link: issue.link, label: 'Open issue', openLink: openLink),
            if (planningShown(reply.revision, multiline: false).trim() case final revision when revision.isNotEmpty)
              _revision(context, revision),
          ],
        ),
        if (detail.workerProblem case final problem?)
          Text(
            problem,
            key: const Key('planning-worker-problem'),
            style: TextStyle(color: theme.colorScheme.error),
          ),
        const Divider(),
      ],
    );
  }

  /// The cleaned revision in seven characters, the whole of it in its tooltip and on the clipboard.
  Widget _revision(BuildContext context, String revision) {
    final shown = revision;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Revision ', style: Theme.of(context).textTheme.bodySmall),
        Tooltip(
          message: shown,
          child: Text(
            shown.length > planningRevisionShown ? shown.substring(0, planningRevisionShown) : shown,
            key: const Key('planning-revision'),
            style: const TextStyle(fontFamily: 'monospace'),
          ),
        ),
        IconButton(
          key: const Key('planning-revision-copy'),
          tooltip: 'Copy revision',
          iconSize: PlanningStyle.rowIcon,
          icon: const Icon(Icons.copy),
          // The whole revision, never the shortened one, which may name another commit; cleaned, since a pasted
          // line break or direction character would act where it is pasted.
          onPressed: () => unawaited(Clipboard.setData(ClipboardData(text: revision))),
        ),
      ],
    );
  }

  /// What must be true as flowing text and the issue's own text as its format, then its last reading and sub-agents.
  Widget _header(BuildContext context, PlanIssueDetail reply, PlanIssue issue, bool settled) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    spacing: Space.wide,
    children: [
      _part(context, 'What must be true', [
        if (settled)
          const Text('Settled; its text was removed when it was finished.')
        else if (!reply.criteriaReported)
          Text(
            reply.criteriaSupplied ? 'Acceptance criteria not reported completely' : 'Acceptance criteria not reported',
          )
        else if (reply.whatMustBeTrue.every((text) => text.trim().isEmpty))
          const Text('No acceptance criteria.')
        else
          for (final text in reply.whatMustBeTrue.where((text) => text.trim().isNotEmpty))
            PlanningText(text: text, format: reply.textFormat, openLink: openLink),
      ]),
      // A settled issue's file is gone and an unreported text is said nowhere, so neither leaves an empty box.
      if (!settled &&
          reply.textReported &&
          planningWithoutHeading(reply.text, issue.id, reply.textFormat).trim().isNotEmpty)
        _part(context, 'Description', [
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(Space.wide),
              child: PlanningText(
                key: const Key('planning-issue-text'),
                text: planningWithoutHeading(reply.text, issue.id, reply.textFormat),
                format: reply.textFormat,
                openLink: openLink,
              ),
            ),
          ),
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

  Widget _part(BuildContext context, String title, List<Widget> children) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: Space.small,
      children: [
        Text(
          title.toUpperCase(),
          style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, letterSpacing: 0.8),
        ),
        ...children,
      ],
    );
  }

  /// One panel of what the issue links to: its story, its waits, its sub-issues, its repository and its work.
  Widget _details(BuildContext context, PlanIssueDetail reply, PlanIssue issue) {
    final theme = Theme.of(context);
    final work = detail.story?.story?.work;
    final owner = _ownerOf(issue.id);
    Widget link(String id, VoidCallback? open, {Key? key}) => open == null
        ? Text(planningShown(id, multiline: false))
        : InkWell(
            key: key,
            onTap: open,
            child: Text(planningShown(id, multiline: false), style: TextStyle(color: theme.colorScheme.primary)),
          );
    // An issue opens under the story the board holds it in; without a way to name that story, only under this page's.
    VoidCallback? openIssue(String id) {
      final story = _ownerOf(id);
      if (story == null) return null;
      final opener = onIssueOf;
      if (opener != null) return () => opener(story, id);
      return story == detail.storyId ? () => onIssue(id) : null;
    }

    final rows = <(String, List<Widget>)>[
      (
        'Story',
        [
          if (owner == null)
            const Text('Not reported')
          else
            link(owner, () => onStory(owner), key: const Key('planning-owner-story')),
        ],
      ),
      (
        'Blocked by',
        [
          if (!issue.blockedByReported)
            const Text('Not reported')
          else if (issue.blockedBy.isEmpty)
            const Text('None')
          else
            for (final id in issue.blockedBy)
              Wrap(
                spacing: Space.small,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  link(id, openIssue(id), key: Key('planning-blocked-by $id')),
                  if (_issue(id) case final waited?)
                    Text(planningValue(waited.state), style: theme.textTheme.bodySmall),
                ],
              ),
        ],
      ),
      // An id has one dot at most, so a sub-issue has no sub-issues of its own; its parent stands in their place.
      if (issue.parent case final parent?)
        ('Parent', [link(parent, openIssue(parent), key: Key('planning-parent $parent'))])
      else
        (
          'Sub-issues',
          [
            if (!reply.subIssuesReported)
              const Text('Not reported completely')
            else if (reply.subIssues.isEmpty)
              const Text('None'),
            for (final id in reply.subIssues) link(id, () => onIssue(id), key: Key('planning-sub-issue $id')),
          ],
        ),
      ('Repository', [Text(planningValue(issue.repository))]),
      (
        'Work',
        [
          if (owner == null)
            const Text('Owning story not reported')
          else if (work == null && detail.storyProblem == null)
            const Text('Not reported yet')
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
        ],
      ),
    ];
    return Card(
      key: const Key('planning-issue-details'),
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: Space.wide, vertical: Space.small),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final (index, (label, values)) in rows.indexed) ...[
              if (index > 0) const Divider(height: 1),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: Space.normal),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 104,
                      child: Text(label, style: TextStyle(color: theme.colorScheme.onSurfaceVariant)),
                    ),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        spacing: Space.tight,
                        children: values,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// The issue as the board holds it under one of its stories, if any.
  PlanIssue? _issue(String id) =>
      detail.board.stories.expand((story) => story.issues).where((item) => item.id == id).firstOrNull;

  /// The story on the board that holds the issue, if any.
  String? _ownerOf(String id) => detail.board.stories
      .where((story) => story.id.isNotEmpty && story.issues.any((item) => item.id == id))
      .firstOrNull
      ?.id;

  Widget _workerSwitch(PlanIssue issue) {
    final unavailable = detail.workerSaving ? 'waiting for the planner' : detail.board.workerUnavailable(issue);
    final next = issue.byHand ? IssueWorker.agent : IssueWorker.manual;
    return Tooltip(
      message: planningShown(
        unavailable ?? 'Let ${next == IssueWorker.manual ? 'a person' : 'an agent'} work ${issue.id}',
        multiline: false,
      ),
      child: TextButton(
        key: const Key('planning-worker-switch'),
        onPressed: unavailable == null ? () => unawaited(detail.setWorker(next)) : null,
        child: Text('Switch to ${planningWorker(next)}'),
      ),
    );
  }
}

/// How many characters of a revision the issue page shows; the tooltip and the copy carry the whole.
const planningRevisionShown = 7;

/// A Markdown [text] without a first heading that opens with the issue's [id], since the page's header already shows
/// its id and title; any other text, heading or format stays whole.
String planningWithoutHeading(String text, String id, String format) {
  if (format != 'MARKDOWN' || id.isEmpty) return text;
  final match = RegExp(r'^\s*#[ \t]+([^\n]*)\n?').firstMatch(text);
  if (match == null || !RegExp('^${RegExp.escape(id)}(?![A-Za-z0-9._-])').hasMatch(match.group(1)!)) return text;
  return text.substring(match.end);
}
