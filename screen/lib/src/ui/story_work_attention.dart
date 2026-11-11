import 'dart:async';

import 'package:flutter/material.dart';

import '../client.dart';
import '../hooks.dart';
import '../models.dart';
import '../run_attention.dart';
import '../story_work.dart';
import 'actions.dart';
import 'style.dart';

/// Story outcomes and reading stops retain the daemon's words until the board clears them.
///
/// A row of a task's end opens that task's result in the window; a row of a stopped reading opens the Reworking
/// dialog. Where the task cannot be told or opened, the row says why in words.
class StoryWorkAttentionView extends StatefulWidget {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkAttentionView({
    required this.row,
    required this.resolved,
    required this.machine,
    this.openStory = true,
    this.taskChanges,
    this.onPutAway,
    super.key,
  });

  /// Where the window keeps an issue's row a person puts away; without it the row offers no Put away.
  final ValueChanged<PlanningPutAway>? onPutAway;

  /// How the window opens a task, given where the planning screen may never have been shown; the row's board's
  /// own hook otherwise.
  final PlanningTaskChanges? taskChanges;

  /// The source facts retained by this attention row.
  final PlanningAttention row;

  /// The machine identity used by the attention header and search.
  final String machine;

  /// Whether this row is retained only as a resolved outcome.
  final bool resolved;

  /// Whether an ended run offers to open its story, left out where the story is already open.
  final bool openStory;
  @override
  State<StoryWorkAttentionView> createState() => _StoryWorkAttentionViewState();
}

String _endedReason(WorkEnded ended) => planningShown(
  [ended.why, String.fromCharCodes(ended.answer.runes.take(65536))].where((text) => text.isNotEmpty).join('\n'),
);

class _StoryWorkAttentionViewState extends State<StoryWorkAttentionView> {
  ReadingDecision? _reading;
  @override
  void initState() {
    super.initState();
    _follow();
  }

  void _follow({PlanningIssueDetail? retained, bool read = false}) {
    if (widget.row.issue?.readingStopped == true && !widget.resolved) {
      final reading = _reading = ReadingDecision(widget.row);
      if (retained != null && reading.detail == null) reading.detail = retained;
      reading.addListener(_changed);
      // Another view may hold this reading's findings or its refusal; only the person's own request reads again.
      final unread = reading.detail?.revision != reading.revision && reading.problem == null;
      if ((read || unread) && !reading.busy && !reading.submitted) unawaited(reading.load());
    }
  }

  @override
  void didUpdateWidget(StoryWorkAttentionView old) {
    super.didUpdateWidget(old);
    if (old.resolved != widget.resolved ||
        old.row.board != widget.row.board ||
        !identical(old.row.source, widget.row.source) ||
        old.row.project != widget.row.project ||
        old.row.issue?.readingStopped != widget.row.issue?.readingStopped ||
        old.row.issue?.id != widget.row.issue?.id ||
        old.row.issue?.rounds != widget.row.issue?.rounds) {
      _release();
      _follow();
    }
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  void _release() {
    _reading?.removeListener(_changed);
    _reading?.dispose();
    _reading = null;
  }

  @override
  void dispose() {
    _release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return _body(context);
  }

  Widget _body(BuildContext context) {
    final row = widget.row;
    final reading = _reading;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${widget.resolved ? 'Resolved' : 'Needs you'} · ${row.project} · ${planningShown(widget.machine, multiline: false)}',
            ),
            Text('${row.story.id} — ${row.story.title}'),
            if (row.ended case final ended?) ...[
              Text(
                ended.reason.isEmpty ? 'Reason not reported' : _endedReason(ended),
                key: Key('story-work-ended ${row.story.id}'),
              ),
              if (ended.at.isNotEmpty) Text(planningShown(ended.at, multiline: false)),
              if (widget.openStory)
                _openStoryButton(
                  context,
                  PlanningAction(
                    id: 'planning.open-story',
                    label: 'Open story',
                    unavailable: widget.resolved
                        ? 'this row is resolved'
                        : row.board.isDisposed
                        ? 'this source is no longer watched'
                        : null,
                    run: () => showDialog<void>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: Text('${row.story.id} — ${row.story.title}'),
                        content: SingleChildScrollView(
                          child: Text(
                            '${row.story.state}\n${row.story.description}\n${ended.reason.isEmpty ? 'Reason not reported' : _endedReason(ended)}',
                          ),
                        ),
                        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
                      ),
                    ),
                  ),
                ),
            ],
            if (planReasonNeedsAttention(row.reason)) Text(planningShown(row.reason)),
            if (row.issue case final issue? when issue.readingStopped != true && !widget.resolved) _taskEnd(issue),
            if (row.issue case final issue?) ...[
              Text('${issue.id} · ${issue.repository} · reading ${issue.rounds ?? '?'} of ${issue.roundsMost ?? '?'}'),
              if (widget.onPutAway case final putAway? when !widget.resolved)
                if (row.putAwayOn(widget.machine) case final kept?)
                  Tooltip(
                    message: 'Leave this row out of Needs you until its reason or state changes',
                    child: TextButton(
                      key: Key('story-work-put-away ${issue.id}'),
                      onPressed: () => putAway(kept),
                      child: const Text('Put away'),
                    ),
                  ),
              if (!widget.resolved && reading?.detail == null && reading?.busy == true)
                const Text('Reading the last findings…'),
              for (final finding in reading?.detail?.blockingFirst ?? <PlanFinding>[])
                Text('${planningShown(finding.severity, multiline: false)}: ${planningShown(finding.text)}'),
              if (reading?.problem case final problem?) Text(problem, key: Key('reading-error ${issue.id}')),
              if (!widget.resolved && reading != null) ...[
                if (reading.problem != null)
                  TextButton(
                    key: Key('reading-retry ${issue.id}'),
                    onPressed: reading.busy ? null : () => unawaited(reading.load()),
                    child: const Text('Retry findings'),
                  ),
                if (reading.unavailable case final reason? when reason != reading.problem)
                  Text(reason, key: Key('reading-unavailable ${issue.id}')),
                if (!reading.current && !row.board.isDisposed)
                  TextButton(
                    key: Key('reading-refresh ${issue.id}'),
                    onPressed: () {
                      if (!row.board.live) {
                        unawaited(reading.load());
                      } else {
                        final retained = reading.detail;
                        _release();
                        _follow(retained: retained, read: true);
                        setState(() {});
                      }
                    },
                    child: const Text('Read the current findings'),
                  ),
                Wrap(
                  spacing: Space.small,
                  runSpacing: Space.small,
                  children: [
                    for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK'])
                      _decision(context, reading, choice),
                  ],
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  /// The task end that raised the issue, opened in the window, or the reason in words where it cannot be.
  Widget _taskEnd(PlanIssue issue) {
    final row = widget.row;
    final reason = row.reason;
    final words = planReasonNeedsAttention(reason) ? planningReasonWords(reason) : 'it needs you';
    final task = planningTaskEndReasons.contains(reason)
        ? planningRaisingTask(row.board.workOf(row.story), issue.id, reason)
        : null;
    if (task == null) return Text(words, key: Key('story-work-reason ${issue.id}'));
    final changes = widget.taskChanges ?? row.board.taskChanges;
    final unavailable = row.board.isDisposed
        ? 'this source is no longer watched'
        : changes == null
        ? 'the window cannot open tasks here'
        : changes.unavailable(row.board, task.task);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Tooltip(
          message: unavailable ?? 'Open the task that raised this',
          child: TextButton(
            key: Key('story-work-open-task ${issue.id}'),
            onPressed: unavailable == null ? () => changes!.open(row.board, task.task) : null,
            child: const Text('Open task'),
          ),
        ),
        if (unavailable != null) Text('$words; $unavailable', key: Key('story-work-reason ${issue.id}')),
      ],
    );
  }

  Widget _openStoryButton(BuildContext context, PlanningAction command) => Tooltip(
    message: command.unavailable ?? command.label,
    child: TextButton(onPressed: command.available ? command.run : null, child: const Text('Open story')),
  );

  Widget _decision(BuildContext context, ReadingDecision reading, String choice) {
    final command = PlanningAction(
      id: 'reading.$choice',
      label: _label(choice),
      unavailable: reading.unavailable,
      run: () async {
        if (choice == 'REWORK') {
          final decided = await showDialog<_Decision>(
            context: context,
            builder: (_) => _ReworkingDialog(
              findings: reading.detail?.blockingFirst ?? const [],
              blocking: reading.blocking,
              rule: reading.reworking,
            ),
          );
          if (!mounted || decided == null || !identical(_reading, reading) || !reading.current) return;
          await reading.resolve(decided.choice, comment: decided.comment, rework: decided.rework);
        } else {
          await reading.resolve(choice);
        }
      },
    );
    return Tooltip(
      message: command.unavailable ?? command.label,
      child: OutlinedButton(
        key: Key('reading-resolve ${widget.row.issue!.id} $choice'),
        onPressed: command.available ? command.run : null,
        child: Text(command.label),
      ),
    );
  }

  String _label(String choice) => switch (choice) {
    'REVIEW_ANYWAY' => 'Review anyway',
    'ONE_MORE_ROUND' => 'One more round',
    _ => 'Rework…',
  };
}

/// What the Reworking dialog was closed with.
class _Decision {
  const _Decision(this.choice, {this.comment = '', this.rework});
  final String choice, comment;
  final PlanningRework? rework;
}

/// The Reworking dialog of a stopped reading: its findings, the Reworking rule's modes and agents with the first
/// preselected, the person's words, and beside Rework the other two choices of a stopped reading.
class _ReworkingDialog extends StatefulWidget {
  /// Takes the findings, whether one blocks, and the Reworking rule, null where the board reports none.
  const _ReworkingDialog({required this.findings, required this.blocking, required this.rule});

  /// The stopped reading's findings, blocking ones first.
  final List<PlanFinding> findings;

  /// Whether a finding blocks, so a rework needs no words.
  final bool blocking;

  /// The rule the rework runs under.
  final PlanWorkRule? rule;

  @override
  State<_ReworkingDialog> createState() => _ReworkingDialogState();
}

class _ReworkingDialogState extends State<_ReworkingDialog> {
  String _comment = '';
  String? _mode;
  String? _agent;

  @override
  void initState() {
    super.initState();
    _mode = widget.rule?.modes.firstOrNull;
    _agent = widget.rule?.models.keys.firstOrNull;
  }

  PlanningRework? get _rework {
    final model = widget.rule?.models[_agent];
    return _mode == null || _agent == null || model == null ? null : PlanningRework(_mode!, _agent!, model);
  }

  @override
  Widget build(BuildContext context) {
    final rule = widget.rule;
    final ready = (widget.blocking || _comment.trim().isNotEmpty) && (rule == null || _rework != null);
    return AlertDialog(
      title: const Text('Rework'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final finding in widget.findings)
              Text('${planningShown(finding.severity, multiline: false)}: ${planningShown(finding.text)}'),
            if (rule == null)
              const Text('The Reworking rule was not reported; the planner takes its first mode and model.')
            else ...[
              DropdownButton<String>(
                key: const Key('reading-rework-mode'),
                value: _mode,
                items: [
                  for (final mode in rule.modes)
                    DropdownMenuItem(value: mode, child: Text(planningShown(mode, multiline: false))),
                ],
                onChanged: (value) => setState(() => _mode = value),
              ),
              DropdownButton<String>(
                key: const Key('reading-rework-agent'),
                value: _agent,
                items: [
                  for (final entry in rule.models.entries)
                    DropdownMenuItem(
                      value: entry.key,
                      child: Text(planningShown('${entry.key} · ${entry.value}', multiline: false)),
                    ),
                ],
                onChanged: (value) => setState(() => _agent = value),
              ),
            ],
            TextField(
              key: const Key('reading-rework-comment'),
              autofocus: true,
              maxLines: null,
              decoration: InputDecoration(
                labelText: widget.blocking ? 'What else needs rework (optional)' : 'What needs rework',
              ),
              onChanged: (value) => setState(() => _comment = value),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        TextButton(
          key: const Key('reading-rework-review'),
          onPressed: () => Navigator.pop(context, const _Decision('REVIEW_ANYWAY')),
          child: const Text('Review anyway'),
        ),
        TextButton(
          key: const Key('reading-rework-more'),
          onPressed: () => Navigator.pop(context, const _Decision('ONE_MORE_ROUND')),
          child: const Text('One more reading'),
        ),
        FilledButton(
          key: const Key('reading-rework-send'),
          onPressed: ready
              ? () => Navigator.pop(context, _Decision('REWORK', comment: _comment, rework: _rework))
              : null,
          child: const Text('Rework'),
        ),
      ],
    );
  }
}
