import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models.dart';
import '../planning_board.dart';
import 'planning_detail_widgets.dart' show planningLocalTime;
import 'style.dart';

/// The most tries the planner sends for one line: a line tried more often names no exact count.
const _mostTries = 1000;

/// The width below which the section's Resend all and Drop all go under its title.
const _narrow = 600.0;

/// What the section says of itself, under its title.
const planningHeldSavesExplanation =
    'Saves made on the board that GitHub did not accept. Nothing is lost: each waits here until you resend or drop it.';

/// The saves the planner keeps for the person on one board, as the "Held saves" section of Needs you: its title and
/// what it is, Resend all and Drop all at its top right, and one card per held save, titled by its change, with its
/// issue as a link, its Held, reason and tries, Details in place, Resend where the planner resends it, and Drop. It
/// shows nothing while the board holds none.
class PlanningHeldSavesView extends StatelessWidget {
  /// Shows the held saves of [board], held by the planner on [machine] when one is named.
  const PlanningHeldSavesView({required this.board, this.machine, this.openLink, super.key});

  /// The board whose held saves are shown and resent or dropped.
  final PlanningBoard board;

  /// The machine whose planner holds the saves, or null when the window shows only its own.
  final String? machine;

  /// Opens an issue's page; null names the issue without a link.
  final Future<void> Function(Uri)? openLink;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: board,
    builder: (context, _) {
      final saves = board.heldSaves;
      if (saves.isEmpty) return const SizedBox.shrink();
      final project = board.project;
      final resendable = [
        for (final save in saves)
          if (save.resendable && !save.savesUnreadable) save.id,
      ];
      final droppable = [
        for (final save in saves)
          if (!save.savesUnreadable) save,
      ];
      final idle = board.live && !board.actionBusy && !board.saving;
      return Card(
        key: Key('held-saves $project'),
        child: Padding(
          padding: const EdgeInsets.all(Space.normal),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Two machines may hold the same project; Resend and Drop act on this machine's planner only.
              _HeldSavesHead(
                project: project,
                machine: machine,
                actions: [
                  if (resendable.isNotEmpty)
                    OutlinedButton(
                      key: Key('held-saves-resend-all $project'),
                      onPressed: idle ? () => unawaited(_resendAll(resendable)) : null,
                      child: Text('Resend all (${resendable.length})'),
                    ),
                  if (droppable.isNotEmpty)
                    TextButton(
                      key: Key('held-saves-drop-all $project'),
                      onPressed: idle ? () => unawaited(_dropAll(context, droppable)) : null,
                      child: const Text('Drop all'),
                    ),
                ],
              ),
              PlanningHeldSaveLines(
                saves: saves,
                project: project,
                machine: machine,
                actions: _actions,
                linkOf: _linkOf,
                openLink: openLink,
              ),
              if (board.actionProblem case final problem?)
                Padding(
                  padding: const EdgeInsets.only(top: Space.small),
                  child: Text(problem, key: Key('held-saves-problem $project')),
                ),
            ],
          ),
        ),
      );
    },
  );

  /// The page of the story or issue [target] names, as the board holds it; null when it holds none.
  Uri? _linkOf(String target) {
    for (final story in board.stories) {
      if (story.id == target) return Uri.tryParse(story.link);
      for (final issue in story.issues) {
        if (issue.id == target) return Uri.tryParse(issue.link);
      }
    }
    return null;
  }

  /// Resends [saves] one after the other, stopping at the first the planner does not write: its words stand in the
  /// board's problem, and the saves after it stay held as they are.
  Future<void> _resendAll(List<String> saves) async {
    for (final save in saves) {
      if (!await board.resendSave(save)) return;
    }
  }

  /// Drops [saves] once the person agrees, one after the other, stopping at the first the planner does not drop.
  Future<void> _dropAll(BuildContext context, List<PlanHeldSave> lines) async {
    final project = board.project;
    // A folded line drops every save it folds, so the person is told the saves, not the lines.
    final count = lines.fold(0, (sum, line) => sum + line.saves.length);
    final agreed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(count == 1 ? 'Drop the held save?' : 'Drop all $count held saves?'),
        content: const Text('Nothing of them is written to GitHub; the board then shows GitHub\'s values again.'),
        actions: [
          TextButton(
            key: Key('held-saves-drop-all-cancel $project'),
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: Key('held-saves-drop-all-confirm $project'),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(count == 1 ? 'Drop' : 'Drop all $count'),
          ),
        ],
      ),
    );
    if (agreed != true) return;
    for (final line in lines) {
      if (!await board.dropSave(line.id)) return;
    }
  }

  Widget? _actions(PlanHeldSave save) {
    // A line whose folded saves are not known is neither resent nor dropped: either could leave saves held unseen.
    if (save.savesUnreadable) return null;
    final unavailable = board.heldSaveUnavailable(save.id);
    return Wrap(
      spacing: Space.small,
      children: [
        // The planner judges whether a save can be sent again; a run's halted save or one from elsewhere cannot. A
        // folded line is sent once, its newest save, and the planner drops the others once it is written.
        if (save.resendable)
          TextButton(
            key: Key('held-save-resend ${save.id}'),
            onPressed: unavailable == null ? () => unawaited(board.resendSave(save.id)) : null,
            child: const Text('Resend'),
          ),
        TextButton(
          key: Key('held-save-drop ${save.id}'),
          onPressed: unavailable == null ? () => unawaited(board.dropSave(save.id)) : null,
          child: const Text('Drop'),
        ),
      ],
    );
  }
}

/// A project's held saves as the window keeps them while no board of it is live: the same section, cards, details and
/// Copy as [PlanningHeldSavesView], from [saves] alone, with no Resend or Drop, since no planner is reached to act on
/// them. It shows nothing while [saves] is empty.
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
            _HeldSavesHead(project: project, machine: machine, actions: const []),
            PlanningHeldSaveLines(saves: saves, project: project, machine: machine),
          ],
        ),
      ),
    );
  }
}

/// The section's head: its title and what it is, with [actions] at its top right, or under it when narrow.
class _HeldSavesHead extends StatelessWidget {
  const _HeldSavesHead({required this.project, required this.machine, required this.actions});

  final String project;
  final String? machine;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final words = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(planningHeldSavesTitle(project, machine), style: theme.textTheme.titleMedium),
        const SizedBox(height: Space.tight),
        Text(
          planningHeldSavesExplanation,
          key: Key('held-saves-explanation $project'),
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      ],
    );
    if (actions.isEmpty) return words;
    final buttons = Wrap(spacing: Space.small, runSpacing: Space.tight, children: actions);
    return LayoutBuilder(
      builder: (context, constraints) => constraints.maxWidth < _narrow
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                words,
                const SizedBox(height: Space.small),
                buttons,
              ],
            )
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: words),
                const SizedBox(width: Space.normal),
                buttons,
              ],
            ),
    );
  }
}

/// The title of a project's held saves, naming the machine whose planner holds them when one is named.
String planningHeldSavesTitle(String project, String? machine) =>
    'Held saves · ${planningShown(project, multiline: false)}'
    '${machine == null ? '' : ' on ${planningShown(machine, multiline: false)}'}';

/// Held saves, one card each, every card opened in place for its details and closed again. A card stays open while a
/// newer save folds into it, since it is known by the oldest save it folds. [actions] gives a card's Resend and Drop,
/// shown on the card whether it is open or not; without it the cards are only read.
class PlanningHeldSaveLines extends StatefulWidget {
  /// Shows [saves] of [project], held by the planner on [machine] when one is named.
  const PlanningHeldSaveLines({
    required this.saves,
    required this.project,
    this.machine,
    this.actions,
    this.linkOf,
    this.openLink,
    super.key,
  });

  /// The lines to show.
  final List<PlanHeldSave> saves;

  /// The project whose saves they are, named in the details copied.
  final String project;

  /// The machine whose planner holds them, named in the details copied; null when the window shows only its own.
  final String? machine;

  /// A line's Resend and Drop, or null for a line that offers none; null for lines that are only read.
  final Widget? Function(PlanHeldSave save)? actions;

  /// The page of a story or issue by its id, or null where none is known.
  final Uri? Function(String target)? linkOf;

  /// Opens a page; null names each issue without a link.
  final Future<void> Function(Uri)? openLink;

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
    crossAxisAlignment: CrossAxisAlignment.stretch,
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
          link: _issueLink(save),
          openLink: widget.openLink,
        ),
    ],
  );

  Uri? _issueLink(PlanHeldSave save) {
    final target = planningHeldSaveIssue(save);
    if (target == null) return null;
    final link = widget.linkOf?.call(target);
    return link != null && (link.scheme == 'https' || link.scheme == 'http') ? link : null;
  }
}

/// One held save's card: its change as the title, its issue as a link, the chips Held, the short reason and the
/// tries, with Details and its [actions]; opened, four blocks: what it changes, why it is held with the forge's answer
/// word for word and its request id with Copy, its tries, and what the person can do, with Copy all details.
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
    this.link,
    this.openLink,
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

  /// The page of its issue, or null where none is known.
  final Uri? link;

  /// Opens [link]; null names the issue without a link.
  final Future<void> Function(Uri)? openLink;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final id = save.id;
    final issue = planningHeldSaveIssue(save);
    return Container(
      key: Key('held-save $id'),
      margin: const EdgeInsets.only(top: Space.normal),
      padding: const EdgeInsets.all(Space.normal),
      decoration: BoxDecoration(
        border: Border.all(color: theme.colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LayoutBuilder(
            builder: (context, constraints) {
              final words = Wrap(
                spacing: Space.small,
                runSpacing: Space.tight,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    planningHeldSaveTitle(save),
                    key: Key('held-save-title $id'),
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
                  ),
                  if (issue != null) _issue(theme, issue),
                  _chip(theme, 'held', 'Held', strong: true),
                  _chip(theme, 'reason', planningHeldSaveReasonShort(save)),
                  _chip(theme, 'tries', planningHeldSaveTries(save)),
                ],
              );
              final buttons = Wrap(
                spacing: Space.small,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Semantics(
                    label: 'Details of ${_targets()}',
                    expanded: open,
                    child: TextButton.icon(
                      key: Key('held-save-open $id'),
                      onPressed: onToggle,
                      icon: Icon(open ? Icons.expand_less : Icons.expand_more, size: 18),
                      label: Text(open ? 'Hide details' : 'Details'),
                    ),
                  ),
                  ?actions,
                ],
              );
              // On a phone the buttons go under the title and chips, so the title keeps the card's width.
              return constraints.maxWidth < _narrow
                  ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [words, buttons])
                  : Row(
                      children: [
                        Expanded(child: words),
                        const SizedBox(width: Space.small),
                        buttons,
                      ],
                    );
            },
          ),
          if (save.earlier || save.run)
            Text(
              planningHeldSaveSource(save),
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          if (save.savesUnreadable)
            Text(
              readOnly
                  ? 'The planner\'s list of the saves in this line cannot be read.'
                  : 'The planner\'s list of the saves in this line cannot be read; Resend and Drop are not offered.',
              key: Key('held-save-unreadable $id'),
            ),
          if (open) _details(theme),
        ],
      ),
    );
  }

  Widget _issue(ThemeData theme, String issue) {
    final shown = planningShown(issue, multiline: false);
    final page = link;
    final open = openLink;
    if (page == null || open == null) {
      return Text(shown, key: Key('held-save-issue ${save.id}'), style: theme.textTheme.bodyMedium);
    }
    void tap() => unawaited(open(page));
    return Semantics(
      link: true,
      label: 'Open $shown on GitHub',
      onTap: tap,
      excludeSemantics: true,
      child: InkWell(
        onTap: tap,
        child: Text(
          shown,
          key: Key('held-save-issue ${save.id}'),
          style: theme.textTheme.bodyMedium?.copyWith(
            color: theme.colorScheme.primary,
            decoration: TextDecoration.underline,
            decorationColor: theme.colorScheme.primary,
          ),
        ),
      ),
    );
  }

  Widget _chip(ThemeData theme, String kind, String label, {bool strong = false}) => Container(
    key: Key('held-save-chip-box ${save.id} $kind'),
    padding: const EdgeInsets.symmetric(horizontal: Space.small, vertical: 2),
    decoration: BoxDecoration(
      color: strong ? theme.colorScheme.errorContainer : theme.colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      label,
      key: Key('held-save-chip ${save.id} $kind'),
      style: theme.textTheme.labelSmall?.copyWith(
        color: strong ? theme.colorScheme.onErrorContainer : theme.colorScheme.onSurfaceVariant,
      ),
    ),
  );

  /// The items the line would change, for the Details control's label.
  String _targets() {
    final targets = {for (final command in save.commands) command.target};
    return targets.isEmpty ? 'a held save' : planningShown(targets.join(', '), multiline: false);
  }

  Widget _block(ThemeData theme, String kind, String heading, List<Widget> children) => Padding(
    padding: const EdgeInsets.only(top: Space.normal),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          heading,
          key: Key('held-save-block ${save.id} $kind'),
          style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: Space.tight),
        ...children,
      ],
    ),
  );

  Widget _details(ThemeData theme) {
    final id = save.id;
    final quiet = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final head = theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final several = {for (final change in save.changes) change.target}.length > 1;
    final answer = save.answer;
    final folded = save.saves.length;
    Widget cell(Widget child) => Padding(
      padding: const EdgeInsets.symmetric(vertical: Space.tight, horizontal: Space.tight),
      child: child,
    );
    return Column(
      key: Key('held-save-details $id'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _block(theme, 'change', 'What this save changes', [
          if (save.changes.isEmpty)
            Text('It changes no field the board names.', style: quiet)
          else
            Table(
              defaultColumnWidth: const IntrinsicColumnWidth(),
              defaultVerticalAlignment: TableCellVerticalAlignment.middle,
              children: [
                TableRow(
                  children: [
                    cell(Text('Field', style: head)),
                    cell(Text('On GitHub now', style: head)),
                    cell(Text('Your save', style: head)),
                  ],
                ),
                for (final (at, change) in save.changes.indexed)
                  TableRow(
                    children: [
                      cell(
                        Text(
                          planningShown(
                            several
                                ? '${change.target} ${planningHeldField(change.field)}'
                                : planningHeldField(change.field),
                            multiline: false,
                          ),
                          key: Key('held-save-field $id $at'),
                        ),
                      ),
                      cell(
                        Text(
                          planningShown(_read(change), multiline: false),
                          key: Key('held-save-before $id $at'),
                          style: change.read == PlanHeldValueRead.never
                              ? quiet?.copyWith(fontStyle: FontStyle.italic)
                              : TextStyle(
                                  decoration: TextDecoration.lineThrough,
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                        ),
                      ),
                      cell(
                        Text(
                          planningShown(change.saved ?? 'removed', multiline: false),
                          key: Key('held-save-after $id $at'),
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
              ],
            ),
          for (final (at, change) in save.changes.indexed)
            if (change.nowAt case final read?)
              Text(
                planningShown(
                  '${planningHeldField(change.field)} of ${change.target} last read on GitHub '
                  '${planningLocalTime(read.toIso8601String())}.',
                  multiline: false,
                ),
                key: Key('held-save-read $id $at'),
                style: quiet,
              ),
          if (save.changes.isNotEmpty)
            Text(planningHeldSaveWhere(save, project), key: Key('held-save-where $id'), style: quiet),
          if (save.commands.length > 1) ...[
            const SizedBox(height: Space.tight),
            Text('Parts of this save', style: head),
            for (final command in save.commands)
              Text(planningHeldCommand(command), key: Key('held-save-command ${command.id}'), style: quiet),
          ],
        ]),
        _block(theme, 'why', 'Why it is held', [
          Text(
            save.reason.isEmpty ? 'The planner names no reason.' : planningShown(save.reason, multiline: false),
            key: Key('held-save-why $id'),
          ),
          if (answer != null) ...[
            const SizedBox(height: Space.tight),
            Container(
              padding: const EdgeInsets.all(Space.small),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(4),
              ),
              child: SelectableText(
                planningHeldAnswer(answer),
                key: Key('held-save-answer $id'),
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ),
            if (answer.requestId case final request?)
              Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: Space.small,
                children: [
                  Text('GitHub request id', style: head),
                  SelectableText(
                    _oneLine(request),
                    key: Key('held-save-request $id'),
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  ),
                  TextButton(
                    key: Key('held-save-copy-request $id'),
                    // What is copied is what is shown, never a control or direction character the screen leaves out.
                    onPressed: () => unawaited(Clipboard.setData(ClipboardData(text: _oneLine(request)))),
                    child: const Text('Copy'),
                  ),
                ],
              ),
          ],
        ]),
        _block(theme, 'tries', 'Tries', [
          for (final (at, tried) in save.tries.indexed)
            SelectableText(planningHeldTryLine(save, at, tried), key: Key('held-save-try $id $at')),
          if (folded > 1)
            Padding(
              padding: const EdgeInsets.only(top: Space.tight),
              child: Text(planningHeldFoldNote(folded), key: Key('held-save-fold $id'), style: quiet),
            ),
        ]),
        _block(theme, 'do', 'What you can do', [
          Text(_help(), key: Key('held-save-help $id')),
          TextButton(
            key: Key('held-save-copy $id'),
            onPressed: () => unawaited(
              Clipboard.setData(
                ClipboardData(
                  text: planningHeldSaveDetails(save, project: project, machine: machine),
                ),
              ),
            ),
            child: const Text('Copy all details'),
          ),
        ]),
      ],
    );
  }

  String _help() {
    if (readOnly) {
      return 'The planner that holds this save is not reached now; Resend and Drop show here once it is.';
    }
    if (save.savesUnreadable) {
      return 'The planner\'s list of the saves in this line cannot be read, so it is neither resent nor dropped here.';
    }
    if (!save.resendable) {
      return 'The planner does not send this save again. Drop it once you have checked the issue on GitHub: the '
          'board then shows GitHub\'s value again.';
    }
    return 'Resend once GitHub answers again; the save is sent as it is shown above. Drop it if the change is no '
        'longer wanted: the board then shows GitHub\'s value again.';
  }
}

/// A field of an item as the person knows it: an issue's state is its Status on the board.
String planningHeldField(String field) {
  if (field == 'state') return 'Status';
  if (field.isEmpty) return 'Field';
  return field[0].toUpperCase() + field.substring(1);
}

/// What a held save changes, as its card's title: its first change, and how many more it makes; where it changes no
/// field the board names, where it comes from.
String planningHeldSaveTitle(PlanHeldSave save) {
  if (save.changes.isEmpty) return planningHeldSaveSource(save);
  final first = save.changes.first;
  final more = save.changes.length - 1;
  return planningShown(
    '${planningHeldField(first.field)} → ${first.saved ?? 'removed'}${more == 0 ? '' : ' and $more more'}',
    multiline: false,
  );
}

/// The story or issue a held save is about: its first change's, else its first command's; null where it names none.
String? planningHeldSaveIssue(PlanHeldSave save) {
  if (save.changes.isNotEmpty) return save.changes.first.target;
  if (save.commands.isNotEmpty) return save.commands.first.target;
  return null;
}

/// Where a held save applies, in one sentence per item it changes.
String planningHeldSaveWhere(PlanHeldSave save, String project) {
  final fields = <String, List<String>>{};
  for (final change in save.changes) {
    (fields[change.target] ??= []).add(change.field);
  }
  final shown = planningShown(project, multiline: false);
  return planningShown(
    [for (final MapEntry(key: target, value: names) in fields.entries) _where(target, names, shown)].join(' '),
    multiline: false,
  );
}

String _where(String target, List<String> names, String project) {
  final others = [
    for (final name in names)
      if (name != 'state') planningHeldField(name).toLowerCase(),
  ].join(', ');
  if (!names.contains('state')) return 'Sets the $others of $target in project $project.';
  return others.isEmpty
      ? 'Moves $target on the board of project $project.'
      : 'Moves $target on the board of project $project and sets its $others.';
}

/// Why a held save is held, in a few words for its chip: by the reason of its first command refused or held.
String planningHeldSaveReasonShort(PlanHeldSave save) {
  final command = save.commands.where((each) => each.state == 'REFUSED' || each.state == 'HELD').firstOrNull;
  return switch (command?.reason) {
    'Unreachable' => 'GitHub unreachable',
    'Changed' || 'StaleRevision' => 'Changed on GitHub',
    'CredentialRefused' => 'Login refused',
    'NoForgeAccess' => 'No access',
    'NotSavable' || 'NotOffered' => 'Not allowed',
    'PartlySaved' => 'Partly saved',
    'NoSuchStory' || 'NoSuchIssue' => 'Not found',
    'WorkflowMissing' => 'No workflow',
    'Unreadable' => 'Not readable',
    'ItemsDirectoryRefused' => 'Directory refused',
    'BasisIncomplete' => 'Board incomplete',
    'InStory' => 'In a story',
    'Busy' => 'Planner busy',
    'LabelsNotKept' => 'Labels not kept',
    _ => 'Not saved',
  };
}

/// One try, as the Tries block shows it: when it ended, how it was made and how it ended. The first saves of a line
/// are its saves, the first of them on the board or by a run; a try past them is a resend.
String planningHeldTryLine(PlanHeldSave save, int at, PlanHeldTry tried, {DateTime? now}) {
  final time = tried.at == null ? 'time not known' : planningLocalTime(tried.at!.toIso8601String(), now: now);
  final saves = save.saves.length;
  // Without the line's saves, or with its older tries cut, the place of a try says nothing of how it was made.
  final how = save.saves.isEmpty || save.tries.length >= _mostTries
      ? 'tried'
      : at == 0
      ? (save.earlier
            ? 'saved elsewhere'
            : save.run
            ? 'saved by a run'
            : 'saved on the board')
      : at < saves
      ? 'saved again'
      : 'resent';
  final result = switch (tried.result) {
    'REFUSED' => 'refused',
    'HELD' => 'held',
    _ => 'not known',
  };
  return planningShown('$time · $how · $result', multiline: false);
}

const _counted = ['', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];

/// What a line folding [saves] saves of one change means for Resend and Drop.
String planningHeldFoldNote(int saves) {
  final word = saves < _counted.length ? _counted[saves] : '$saves';
  final head = word[0].toUpperCase() + word.substring(1);
  return '$head saves of the same change are folded into this line. Resend sends it once; Drop drops all $word.';
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

/// How often a line was tried, and whether it folds several saves of one change; a line tried as often as the
/// planner sends tries for names no exact count, since older ones are left out.
String planningHeldSaveTries(PlanHeldSave save) {
  final count = save.tries.length;
  final tries = count >= _mostTries
      ? '$_mostTries or more tries'
      : count == 1
      ? '1 try'
      : '$count tries';
  return save.saves.length > 1 ? '$tries, same change' : tries;
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

/// The forge's answer word for word, after its status.
String planningHeldAnswer(PlanHeldAnswer answer) =>
    '${answer.status == 0 ? 'No status' : 'HTTP ${answer.status}'}${answer.text.isEmpty ? '' : ' · ${_oneLine(answer.text)}'}';

/// [text] on one line: each line break or tab a space, so the forge's words stay apart, then without the control and
/// direction characters the screen never shows.
String _oneLine(String text) =>
    planningShown(text.replaceAll(RegExp(r'\r\n|[\r\n\t\u2028\u2029\x0b\x0c\x85]'), ' '), multiline: false);

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
