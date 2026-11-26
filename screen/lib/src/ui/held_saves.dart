import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models.dart';
import '../planning_board.dart';
import 'planning_detail_widgets.dart' show planningLocalTime;
import 'style.dart';

/// The most tries the planner sends for one line: a line tried more often names no exact count.
const _mostTries = 1000;

/// The saves the planner keeps for the person on one board, as the "Held saves" section of Needs you: per line where
/// it comes from, why it is held and its tries, opened in place for the details, with Resend where the planner resends
/// it and Drop. It shows nothing while the board holds none.
class PlanningHeldSavesView extends StatelessWidget {
  /// Shows the held saves of [board], held by the planner on [machine] when one is named.
  const PlanningHeldSavesView({required this.board, this.machine, super.key});

  /// The board whose held saves are shown and resent or dropped.
  final PlanningBoard board;

  /// The machine whose planner holds the saves, or null when the window shows only its own.
  final String? machine;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: board,
    builder: (context, _) {
      final saves = board.heldSaves;
      if (saves.isEmpty) return const SizedBox.shrink();
      return Card(
        key: Key('held-saves ${board.project}'),
        child: Padding(
          padding: const EdgeInsets.all(Space.normal),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Two machines may hold the same project; Resend and Drop act on this machine's planner only.
              Text(planningHeldSavesTitle(board.project, machine)),
              PlanningHeldSaveLines(saves: saves, project: board.project, machine: machine, actions: _actions),
              if (board.actionProblem case final problem?)
                Text(problem, key: Key('held-saves-problem ${board.project}')),
            ],
          ),
        ),
      );
    },
  );

  Widget? _actions(PlanHeldSave save) {
    // A line whose folded saves are not known is neither resent nor dropped: either could leave saves held unseen.
    if (save.savesUnreadable) return null;
    final unavailable = board.heldSaveUnavailable(save.id);
    final folded = save.saves.length > 1;
    return Wrap(
      spacing: Space.small,
      children: [
        // The planner judges whether a save can be sent again; a run's halted save or one from elsewhere cannot. A
        // folded line is sent once, its newest save, and the planner drops the others once it is written.
        if (save.resendable)
          TextButton(
            key: Key('held-save-resend ${save.id}'),
            onPressed: unavailable == null ? () => unawaited(board.resendSave(save.id)) : null,
            child: Text(folded ? 'Resend once' : 'Resend'),
          ),
        TextButton(
          key: Key('held-save-drop ${save.id}'),
          onPressed: unavailable == null ? () => unawaited(board.dropSave(save.id)) : null,
          child: Text(folded ? 'Drop all' : 'Drop'),
        ),
      ],
    );
  }
}

/// A project's held saves as the window keeps them while no board of it is live: the same lines, details and Copy as
/// [PlanningHeldSavesView], from [saves] alone, with no Resend or Drop, since no planner is reached to act on them.
/// It shows nothing while [saves] is empty.
class PlanningKeptHeldSavesView extends StatelessWidget {
  /// Shows [saves] of [project], held by the planner on [machine] when one is named.
  const PlanningKeptHeldSavesView({required this.saves, required this.project, this.machine, super.key});

  /// The held saves the window kept.
  final List<PlanHeldSave> saves;

  /// The project whose saves they are.
  final String project;

  /// The machine whose planner holds them, or null when the window shows only its own.
  final String? machine;

  @override
  Widget build(BuildContext context) {
    if (saves.isEmpty) return const SizedBox.shrink();
    return Card(
      key: Key('held-saves-kept $project'),
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(planningHeldSavesTitle(project, machine)),
            PlanningHeldSaveLines(saves: saves, project: project, machine: machine),
          ],
        ),
      ),
    );
  }
}

/// The title of a project's held saves, naming the machine whose planner holds them when one is named.
String planningHeldSavesTitle(String project, String? machine) =>
    'Held saves · ${planningShown(project, multiline: false)}'
    '${machine == null ? '' : ' on ${planningShown(machine, multiline: false)}'}';

/// Held saves, one line each, every line opened in place for its details and closed again. A line stays open while a
/// newer save folds into it, since it is known by the oldest save it folds. [actions] gives a line's Resend and Drop,
/// shown on the line whether it is open or not; without it the lines are only read.
class PlanningHeldSaveLines extends StatefulWidget {
  /// Shows [saves] of [project], held by the planner on [machine] when one is named.
  const PlanningHeldSaveLines({required this.saves, required this.project, this.machine, this.actions, super.key});

  /// The lines to show.
  final List<PlanHeldSave> saves;

  /// The project whose saves they are, named in the details copied.
  final String project;

  /// The machine whose planner holds them, named in the details copied; null when the window shows only its own.
  final String? machine;

  /// A line's Resend and Drop, or null for a line that offers none; null for lines that are only read.
  final Widget? Function(PlanHeldSave save)? actions;

  @override
  State<PlanningHeldSaveLines> createState() => _PlanningHeldSaveLinesState();
}

class _PlanningHeldSaveLinesState extends State<PlanningHeldSaveLines> {
  /// The open lines, by the oldest save each folds.
  final _open = <String>{};

  /// What a line is known by: the oldest save it folds, which a newer save folding in leaves as it is.
  static String _line(PlanHeldSave save) => save.saves.isEmpty ? save.id : save.saves.first;

  @override
  void didUpdateWidget(PlanningHeldSaveLines old) {
    super.didUpdateWidget(old);
    // A line no longer shown is forgotten, so one resent or dropped and held again later opens closed.
    _open.retainAll(widget.saves.map(_line));
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      for (final save in widget.saves)
        PlanningHeldSaveLine(
          save: save,
          project: widget.project,
          machine: widget.machine,
          open: _open.contains(_line(save)),
          onToggle: () => setState(() {
            final line = _line(save);
            if (!_open.remove(line)) _open.add(line);
          }),
          actions: widget.actions?.call(save),
          readOnly: widget.actions == null,
        ),
    ],
  );
}

/// One held save's line: where it comes from, why it is held and how often it was tried, with its [actions] when it
/// has any; opened, what it would change from the value last read, the forge's answer word for word, its request id
/// with Copy, every try and every command, and Copy all details.
class PlanningHeldSaveLine extends StatelessWidget {
  /// Shows [save] of [project], open or closed.
  const PlanningHeldSaveLine({
    required this.save,
    required this.project,
    required this.open,
    required this.onToggle,
    this.machine,
    this.actions,
    this.readOnly = false,
    super.key,
  });

  /// The line.
  final PlanHeldSave save;

  /// The project whose save it is.
  final String project;

  /// The machine whose planner holds it, or null.
  final String? machine;

  /// Whether its details show.
  final bool open;

  /// Opens or closes it.
  final VoidCallback onToggle;

  /// Its Resend and Drop, or null when it offers none.
  final Widget? actions;

  /// Whether it is only read, so that no Resend or Drop is ever offered for it.
  final bool readOnly;

  @override
  Widget build(BuildContext context) => Padding(
    key: Key('held-save ${save.id}'),
    padding: const EdgeInsets.only(top: Space.small),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(planningHeldSaveSource(save)),
        if (save.reason.isNotEmpty) Text(planningShown(save.reason, multiline: false)),
        Text(planningHeldSaveTries(save), key: Key('held-save-tries ${save.id}')),
        if (save.savesUnreadable)
          Text(
            readOnly
                ? 'The planner\'s list of the saves in this line cannot be read.'
                : 'The planner\'s list of the saves in this line cannot be read; Resend and Drop are not offered.',
            key: Key('held-save-unreadable ${save.id}'),
          ),
        Wrap(
          spacing: Space.small,
          children: [
            Semantics(
              label: 'Details of ${_targets()}',
              expanded: open,
              child: TextButton(
                key: Key('held-save-open ${save.id}'),
                onPressed: onToggle,
                child: Text(open ? 'Hide details' : 'Details'),
              ),
            ),
            ?actions,
          ],
        ),
        if (open) _details(),
      ],
    ),
  );

  /// The items the line would change, for the Details control's label.
  String _targets() {
    final targets = {for (final command in save.commands) command.target};
    return targets.isEmpty ? 'a held save' : planningShown(targets.join(', '), multiline: false);
  }

  Widget _details() => Padding(
    key: Key('held-save-details ${save.id}'),
    padding: const EdgeInsets.only(left: Space.normal),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (at, change) in save.changes.indexed)
          Text(planningHeldChange(change), key: Key('held-save-change ${save.id} $at')),
        if (save.answer case final answer?) ...[
          SelectableText(planningHeldAnswer(answer), key: Key('held-save-answer ${save.id}')),
          if (answer.requestId case final id?)
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: Space.small,
              children: [
                SelectableText('Request id ${_oneLine(id)}', key: Key('held-save-request ${save.id}')),
                TextButton(
                  key: Key('held-save-copy-request ${save.id}'),
                  // What is copied is what is shown, never a control or direction character the screen leaves out.
                  onPressed: () => unawaited(Clipboard.setData(ClipboardData(text: _oneLine(id)))),
                  child: const Text('Copy'),
                ),
              ],
            ),
        ],
        for (final (at, tried) in save.tries.indexed)
          SelectableText(planningHeldTry(tried), key: Key('held-save-try ${save.id} $at')),
        for (final command in save.commands)
          Text(planningHeldCommand(command), key: Key('held-save-command ${command.id}')),
        TextButton(
          key: Key('held-save-copy ${save.id}'),
          onPressed: () => unawaited(
            Clipboard.setData(
              ClipboardData(
                text: planningHeldSaveDetails(save, project: project, machine: machine),
              ),
            ),
          ),
          child: const Text('Copy all details'),
        ),
      ],
    ),
  );
}

/// Where a held save comes from, in one sentence.
String planningHeldSaveSource(PlanHeldSave save) {
  if (save.earlier) {
    final where = save.repository.isEmpty ? 'another repository' : save.repository;
    return planningShown(
      'Kept from $where${save.branch.isEmpty ? '' : ' on ${save.branch}'}; it is never written here.',
      multiline: false,
    );
  }
  return save.run ? 'A save of a run.' : 'A save made on the board.';
}

/// How often a line was tried, and how many saves it folds; a line tried as often as the planner sends tries for names
/// no exact count, since older ones are left out.
String planningHeldSaveTries(PlanHeldSave save) {
  final count = save.tries.length;
  final tries = count >= _mostTries
      ? '$_mostTries or more tries'
      : count == 1
      ? '1 try'
      : '$count tries';
  return save.saves.length > 1 ? '${save.saves.length} saves of one change · $tries' : tries;
}

/// One command: its target, where it stands and why.
String planningHeldCommand(PlanHeldCommand command) {
  final state = switch (command.state) {
    'QUEUED' => 'waiting',
    'DONE' => 'saved',
    'REFUSED' => 'refused',
    'HELD' => 'held',
    _ => 'not known',
  };
  final reason = command.reason.isEmpty || command.reason == 'NONE'
      ? ''
      : ' · ${command.reason}${command.subject.isEmpty ? '' : ': ${command.subject}'}';
  return planningShown('${command.target} · $state$reason', multiline: false);
}

String _read(PlanHeldChange change) => switch (change.read) {
  PlanHeldValueRead.never => 'not read',
  PlanHeldValueRead.empty => 'empty',
  PlanHeldValueRead.value => change.now!,
};

/// One change: the field of its item, the value last read with when it was read, and the value it would write; a
/// value never read names no time.
String planningHeldChange(PlanHeldChange change, {DateTime? now}) {
  final at = change.nowAt == null
      ? ''
      : ' · last read on GitHub ${planningLocalTime(change.nowAt!.toIso8601String(), now: now)}';
  return planningShown(
    '${change.target} ${change.field}: ${_read(change)} → ${change.saved ?? 'removed'}$at',
    multiline: false,
  );
}

/// The forge's answer word for word, after its status.
String planningHeldAnswer(PlanHeldAnswer answer) =>
    '${answer.status == 0 ? 'No status' : 'HTTP ${answer.status}'}${answer.text.isEmpty ? '' : ' · ${_oneLine(answer.text)}'}';

/// [text] on one line: each line break or tab a space, so the forge's words stay apart, then without the control and
/// direction characters the screen never shows.
String _oneLine(String text) =>
    planningShown(text.replaceAll(RegExp(r'\r\n|[\r\n\t\u2028\u2029\x0b\x0c\x85]'), ' '), multiline: false);

/// One try: when it ended, how, why, and the forge's answer to it.
String planningHeldTry(PlanHeldTry tried, {DateTime? now}) {
  final at = tried.at == null ? 'at a time not known' : planningLocalTime(tried.at!.toIso8601String(), now: now);
  final result = switch (tried.result) {
    'REFUSED' => 'refused',
    'HELD' => 'held',
    _ => 'not known',
  };
  final answer = tried.answer == null ? '' : ' · ${planningHeldAnswer(tried.answer!)}';
  return planningShown('$at · $result · ${tried.reason}$answer', multiline: false);
}

/// The whole record of a held save as text, for the person to copy: its project and machine, where it comes from, why
/// it is held, the saves it folds, what it would change, the forge's answer with its request id, every try and every
/// command. Times are in UTC, so a copy reads alike wherever it is pasted.
String planningHeldSaveDetails(PlanHeldSave save, {required String project, String? machine}) {
  String utc(DateTime? at) => at == null ? 'time not known' : at.toUtc().toIso8601String();
  final answer = save.answer;
  final lines = [
    planningHeldSavesTitle(project, machine),
    planningHeldSaveSource(save),
    if (save.reason.isNotEmpty) 'Why: ${save.reason}',
    'Saves: ${save.savesUnreadable ? 'the list cannot be read' : save.saves.join(', ')}',
    for (final change in save.changes)
      'Change: ${change.target} ${change.field}: ${_read(change)} → ${change.saved ?? 'removed'}'
          '${change.nowAt == null ? '' : ' (last read on GitHub ${utc(change.nowAt)})'}',
    if (answer != null) 'Answer: ${planningHeldAnswer(answer)}',
    if (answer?.requestId case final id?) 'Request id: ${_oneLine(id)}',
    for (final tried in save.tries)
      'Try: ${utc(tried.at)} · ${tried.result} · ${tried.reason}'
          '${tried.answer == null ? '' : ' · ${planningHeldAnswer(tried.answer!)}'}',
    for (final command in save.commands) 'Command: ${planningHeldCommand(command)}',
  ];
  return lines.map((line) => planningShown(line, multiline: false)).join('\n');
}
