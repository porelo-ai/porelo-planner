import 'dart:async';

import 'package:flutter/material.dart';

import '../client.dart';
import '../hooks.dart';
import '../models.dart';
import '../run_attention.dart';
import '../story_work.dart';
import 'actions.dart';
import 'story_work_dialog.dart';
import 'style.dart';

/// Story outcomes and reading stops retain the daemon's words until the board clears them.
///
/// A row of a task's end opens that task's result in the window; a row of a stopped reading opens the Reworking
/// dialog. Where the task cannot be told or opened, the row says why in words. A row whose next step was not
/// available or whose save was refused starts again or stops; one stopped or failed starts again or moves on; one
/// blocked moves on; one whose findings could not be read goes on to the reading's done state. A reader that failed or
/// was blocked stops its reading, which reads again or goes to Review. Where one of these cannot be offered, the row
/// says why.
class StoryWorkAttentionView extends StatefulWidget {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkAttentionView({
    required this.row,
    required this.resolved,
    required this.machine,
    this.openStory = true,
    this.taskChanges,
    this.onPutAway,
    this.onStory,
    super.key,
  });

  /// Opens the row's story, where the person sees the move the row made.
  final ValueChanged<PlanningAttention>? onStory;

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

/// The lines of a task's words a row shows; the story's dialog shows them all.
const _endedLines = 6;

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
            Text(planningShown('${row.story.id} — ${row.story.title}', multiline: false)),
            if (row.ended case final ended?) ...[
              Text(
                ended.reason.isEmpty ? 'The task gave no words on why it ended' : _endedReason(ended),
                key: Key('story-work-ended ${row.story.id}'),
                maxLines: _endedLines,
                overflow: TextOverflow.ellipsis,
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
                        title: Text(planningShown('${row.story.id} — ${row.story.title}', multiline: false)),
                        content: SingleChildScrollView(
                          child: Text(
                            planningShown(
                              '${row.story.state}\n${row.story.description}\n'
                              '${ended.reason.isEmpty ? 'The task gave no words on why it ended' : _endedReason(ended)}',
                            ),
                          ),
                        ),
                        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
                      ),
                    ),
                  ),
                ),
            ],
            if (planReasonNeedsAttention(row.reason)) Text(planningShown(row.reason)),
            if (row.issue case final issue? when issue.readingStopped != true && !widget.resolved) ...[
              _taskEnd(issue),
              _answers(context, issue),
            ],
            // A reader that failed or was blocked stopped its reading; the row still says why and opens the task.
            if (row.issue case final issue?
                when issue.readingStopped == true && !widget.resolved && _readerEnds.contains(row.reason))
              _taskEnd(issue),
            if (row.issue case final issue?) ...[
              Text(
                planningShown(
                  '${issue.id} · ${issue.repository} · reading ${issue.rounds ?? '?'} of ${issue.roundsMost ?? '?'}',
                  multiline: false,
                ),
              ),
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
                    for (final choice in [
                      'REVIEW_ANYWAY',
                      'ONE_MORE_ROUND',
                      // A reading whose findings could not be read leaves a rework nothing to work from.
                      if (!_unreadable) 'REWORK',
                    ])
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

  /// What answers the row's reason: start again, stop, move on or go to the reading's done state, each with why
  /// it cannot be done where it cannot.
  Widget _answers(BuildContext context, PlanIssue issue) {
    final row = widget.row;
    final reason = row.reason;
    final actions = <(String, PlanningAction)>[
      if (PlanningPassOver.reasons.contains(reason)) ('story-work-start ${issue.id}', _start(context, issue)),
      if (_movesOn.contains(reason)) ('story-work-move-on ${issue.id}', _onward(issue, 'Move on')),
      if (reason == 'INVALID_FINDINGS') ('story-work-review ${issue.id}', _onward(issue, 'Go to')),
      if (reason != 'STOPPED' && planReasonNeedsAttention(reason))
        ('story-work-stop ${issue.id}', _stop(context, issue)),
    ];
    if (actions.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: Space.small,
          runSpacing: Space.small,
          children: [
            for (final (key, action) in actions)
              Tooltip(
                message: action.unavailable ?? action.label,
                child: OutlinedButton(
                  key: Key(key),
                  onPressed: action.available ? action.run : null,
                  child: Text(action.label),
                ),
              ),
          ],
        ),
        for (final (key, action) in actions)
          if (action.unavailable case final why?) Text('${action.label}: $why', key: Key('$key why')),
        if (_movesOn.contains(reason))
          if (_issueUnpushed(issue) case final left? when left > 0)
            Text('Moving on leaves the task\'s $left commit${left == 1 ? '' : 's'} unpushed.'),
      ],
    );
  }

  /// The row's own Start, passing over its reason as the row shows it.
  PlanningAction _start(BuildContext context, PlanIssue issue) {
    final row = widget.row;
    final board = row.board;
    final hooks = board.startHooks;
    final run = board.workOf(row.story)?.run;
    final story = board.stories.where((each) => each.id == row.story.id).firstOrNull;
    final target = board.state(issue.state)?.kind == 'WORKING'
        ? board.state(issue.state)
        : board.states.where((state) => state.kind == 'WORKING').firstOrNull;
    final passOver = run == null
        ? null
        : PlanningPassOver(issue: issue.id, reason: row.reason, revision: row.revision, run: run);
    final left = _unpushedLeft();
    return PlanningAction(
      id: 'story-work.start/${issue.id}',
      label: 'Start again…',
      unavailable: board.isDisposed
          ? 'this source is no longer watched'
          : hooks == null
          ? 'the window has not handed this board the hooks that start work'
          : story == null
          ? 'the story is no longer reported'
          : passOver == null
          ? 'the planner did not report the run'
          : target == null
          ? 'no working state was reported'
          : left == null
          ? 'the planner did not say whether an ended task left commits unpushed'
          : left > 0
          ? 'an ended task left $left unpushed commit${left == 1 ? '' : 's'}, which only it can push'
          : board.startUnavailable(story, passOver: passOver),
      run: () async {
        final work = StoryWork(
          board,
          story!,
          target!.name,
          startTask: hooks!.startTask,
          agentChoices: hooks.agents,
          machines: hooks.machines(board),
          unavailableMachines: hooks.unavailableMachines(board),
          onStarted: (widget.taskChanges ?? board.taskChanges)?.started,
          issues: [issue.parent ?? issue.id],
          passOver: passOver,
        );
        try {
          await showDialog<bool>(context: context, barrierDismissible: false, builder: (_) => StoryWorkDialog(work));
        } finally {
          work.dispose();
        }
      },
    );
  }

  /// The commits the last ended task of each raised issue of the run left unpushed, whatever the reason now says,
  /// since a stop renames a failure; null where one of those tasks did not say.
  int? _unpushedLeft() {
    final row = widget.row;
    final work = row.board.workOf(row.story);
    if (work == null) return 0;
    var left = 0;
    for (final item in work.issues) {
      if (!planReasonNeedsAttention(item.reason) && item.needsYou != true) continue;
      final ended = _lastEnded(work, item.issue);
      if (ended == null) continue;
      final count = ended.unpushed;
      if (count == null) return null;
      left += count;
    }
    return left;
  }

  /// The commits the last ended task of the row's issue left unpushed, or null where it did not say.
  int? _issueUnpushed(PlanIssue issue) {
    final work = widget.row.board.workOf(widget.row.story);
    return work == null ? null : _lastEnded(work, issue.id)?.unpushed;
  }

  /// The end of the last ended task of [issue] in [work], or null where none ended.
  static WorkEnded? _lastEnded(PlanWork work, String issue) =>
      work.tasks.reversed.where((task) => task.issues.contains(issue) && task.ended != null).firstOrNull?.ended;

  /// Stops the row's run, once the person confirms it.
  PlanningAction _stop(BuildContext context, PlanIssue issue) {
    final row = widget.row;
    final board = row.board;
    return PlanningAction(
      id: 'story-work.stop/${issue.id}',
      label: 'Stop work',
      unavailable: board.isDisposed ? 'this source is no longer watched' : board.stopUnavailable(row.story),
      run: () async {
        final revision = board.snapshot?.revision;
        final run = board.workOf(row.story)?.run;
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(planningShown('Stop work on ${row.story.id}?', multiline: false)),
            content: const Text('The story stays in its working state with nobody at work until it is moved.'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
              FilledButton(
                key: const Key('story-work-stop-confirm'),
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Stop work'),
              ),
            ],
          ),
        );
        if (!mounted || confirmed != true || revision == null) return;
        await board.stopWork(
          row.story.id,
          run: run,
          revision: revision,
          source: row.source,
          sourceProject: row.project,
        );
      },
    );
  }

  /// Stages the issue's move to the done state of the rule it stands under and opens its story, where the person
  /// saves it.
  PlanningAction _onward(PlanIssue issue, String verb) {
    final row = widget.row;
    final board = row.board;
    final done = (board.snapshot?.work ?? const <PlanWorkRule>[])
        .where((rule) => rule.state == issue.state)
        .firstOrNull
        ?.done;
    final move = done == null || done.isEmpty ? null : movePlanningIssueAction(board, issue, done);
    return PlanningAction(
      id: 'story-work.onward/${issue.id}',
      label: done == null || done.isEmpty ? verb : planningShown('$verb $done', multiline: false),
      unavailable: move == null
          ? planningShown('no work rule names where ${issue.state} leads', multiline: false)
          : move.unavailable,
      run: () {
        move!.run();
        widget.onStory?.call(row);
      },
    );
  }

  Widget _openStoryButton(BuildContext context, PlanningAction command) => Tooltip(
    message: command.unavailable ?? command.label,
    child: TextButton(onPressed: command.available ? command.run : null, child: const Text('Open story')),
  );

  Widget _decision(BuildContext context, ReadingDecision reading, String choice) {
    final command = PlanningAction(
      id: 'reading.$choice',
      label: _label(choice, unreadable: _unreadable),
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

  /// Whether the stopped reading's findings could not be read: its reason says so, or, once a failed choice renamed
  /// it, the run keeps no findings for it, as only such a stop does.
  bool get _unreadable {
    final row = widget.row;
    return switch (row.reason) {
      'INVALID_FINDINGS' || 'FAILED' || 'BLOCKED' => true,
      'NEXT_STEP_UNAVAILABLE' =>
        row.board
                .workOf(row.story)
                ?.issues
                .where((item) => item.issue == row.issue?.id)
                .firstOrNull
                ?.findings
                .isEmpty ==
            true,
      _ => false,
    };
  }

  String _label(String choice, {required bool unreadable}) => switch (choice) {
    'REVIEW_ANYWAY' => 'Review anyway',
    'ONE_MORE_ROUND' when unreadable => 'Read again',
    'ONE_MORE_ROUND' => 'One more round',
    _ => 'Rework…',
  };
}

/// The reasons whose row moves its issue on by the person's own move.
const _movesOn = {'STOPPED', 'FAILED', 'BLOCKED'};

/// The ends of a reader that stop its reading as one whose findings could not be read.
const _readerEnds = {'FAILED', 'BLOCKED'};

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
              if (rule.models.isEmpty)
                const Text(
                  'The Reworking rule names no agent; name one under `models` in the project\'s work rules.',
                  key: Key('reading-rework-no-agent'),
                )
              else
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
