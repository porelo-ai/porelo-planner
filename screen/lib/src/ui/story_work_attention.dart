import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../story_work.dart';
import 'actions.dart';
import 'style.dart';

/// Story outcomes and reading stops retain the daemon's words until the board clears them.
class StoryWorkAttentionView extends StatefulWidget {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkAttentionView({
    required this.row,
    required this.resolved,
    required this.machine,
    this.openStory = true,
    super.key,
  });

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
            if (row.reason.isNotEmpty && row.reason != 'NONE') Text(planningShown(row.reason)),
            if (row.issue case final issue?) ...[
              Text('${issue.id} · ${issue.repository} · reading ${issue.rounds ?? '?'} of ${issue.roundsMost ?? '?'}'),
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
          final comment = await showDialog<String>(context: context, builder: (_) => const _ReworkDialog());
          if (!mounted || comment == null || !identical(_reading, reading) || !reading.current) return;
          await reading.resolve(choice, comment: comment);
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

class _ReworkDialog extends StatefulWidget {
  const _ReworkDialog();
  @override
  State<_ReworkDialog> createState() => _ReworkDialogState();
}

class _ReworkDialogState extends State<_ReworkDialog> {
  String _comment = '';
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Rework'),
    content: TextField(
      key: const Key('reading-rework-comment'),
      autofocus: true,
      maxLines: null,
      decoration: const InputDecoration(labelText: 'What needs rework'),
      onChanged: (value) => setState(() => _comment = value),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
      FilledButton(
        key: const Key('reading-rework-send'),
        onPressed: _comment.trim().isEmpty ? null : () => Navigator.pop(context, _comment),
        child: const Text('Rework'),
      ),
    ],
  );
}
