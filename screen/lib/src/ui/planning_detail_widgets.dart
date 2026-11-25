import '../client.dart' show planningTopicLabelOf, planningTopicLabels;
import '../models.dart';
import '../story_work_order.dart' show PlanningRunPlace;

import 'package:flutter/material.dart';

import '../planning_board.dart';
import 'actions.dart';
import 'style.dart';

/// An absent value is not an empty successful report.
String planningValue(String value) => value.isEmpty ? 'Not reported' : planningShown(value);

/// The common section surface used by planning details; [heading] stands in the title's row after it.
Widget planningSection(BuildContext context, String title, List<Widget> children, {List<Widget> heading = const []}) =>
    Card(
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (heading.isEmpty)
              Text(title, style: Theme.of(context).textTheme.titleMedium)
            else
              Row(
                spacing: Space.small,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleMedium),
                  ...heading,
                ],
              ),
            const SizedBox(height: Space.small),
            ...children,
          ],
        ),
      ),
    );

/// Offers only a web address actually supplied by the machine.
class PlanningWebLink extends StatefulWidget {
  /// Takes the machine's link unchanged.
  const PlanningWebLink({required this.link, required this.label, required this.openLink, super.key});

  /// The reported link.
  final String link;

  /// Open story or Open issue.
  final String label;

  /// Opens the address in the person's browser.
  final Future<void> Function(Uri) openLink;

  @override
  State<PlanningWebLink> createState() => _PlanningWebLinkState();
}

class _PlanningWebLinkState extends State<PlanningWebLink> {
  String? _problem;

  @override
  void didUpdateWidget(PlanningWebLink oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.link != widget.link) _problem = null;
  }

  @override
  Widget build(BuildContext context) {
    final address = Uri.tryParse(widget.link);
    if (address == null || !address.isScheme('https') || address.host.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OutlinedButton.icon(
          onPressed: planningWebAction(
            widget.label,
            widget.link,
            widget.openLink,
            onError: (error) {
              if (mounted) setState(() => _problem = planningFailureWords(error));
            },
          ).run,
          icon: const Icon(Icons.open_in_new),
          label: Text(widget.label),
        ),
        if (_problem case final problem?) Text(problem, style: TextStyle(color: Theme.of(context).colorScheme.error)),
      ],
    );
  }
}

/// A task named by this story, with the machine it runs on as reported and when it ended; one at work opens in the
/// window, with a visible explanation when the window cannot open it, and any other is named alone.
class PlanningTaskLink extends StatelessWidget {
  /// Takes availability from the task's own machine.
  const PlanningTaskLink({
    required this.name,
    required this.onOpen,
    required this.unavailable,
    this.machine,
    this.endedAt,
    this.opens = true,
    this.run,
    super.key,
  });

  /// The task as the planner served it, whose duty role, run role, place and held items are shown under its name.
  final PlanWorkTask? run;

  /// The task's full name.
  final String name;

  /// Opens the task only when it is known on this machine.
  final VoidCallback onOpen;

  /// Why the machine or task is unavailable, if so.
  final String? unavailable;

  /// The machine the task runs or ran on, as the planner reports it, if reported.
  final String? machine;

  /// When the task ended, as the planner reports it, if it ended.
  final String? endedAt;

  /// Whether the window is asked to open the task, which only a task at work can be.
  final bool opens;

  @override
  Widget build(BuildContext context) {
    final where = [
      if (machine case final machine? when machine.trim().isNotEmpty) 'on ${planningShown(machine, multiline: false)}',
      if (endedAt case final at?) 'ended ${planningLocalTime(at)}',
    ].join(' · ');
    final facts = run == null ? null : Text(planningRunFacts(run!), key: Key('planning-task-run $name'));
    if (!opens) {
      final line = Text(
        [planningShown(name, multiline: false), if (where.isNotEmpty) where].join(' · '),
        key: Key('planning-task $name'),
      );
      return facts == null ? line : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [line, facts]);
    }
    final open = Wrap(
      spacing: Space.small,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Tooltip(
          message: planningShown(unavailable ?? 'Open $name', multiline: false),
          child: TextButton(
            key: Key('planning-task $name'),
            onPressed: unavailable == null ? onOpen : null,
            child: Text(planningShown(name, multiline: false)),
          ),
        ),
        if (where.isNotEmpty) Text(where),
        if (unavailable case final reason?) Text(planningShown(reason, multiline: false)),
      ],
    );
    return facts == null ? open : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [open, facts]);
  }
}

/// The most characters of a place from outside the screen shows.
const planningMostPlaceText = 120;

/// What a served place says, as plain text: a pull request's address is cut to a bounded length and never a link.
String planningPlaceText(PlanningRunPlace? place) {
  String cut(String text) {
    final shown = planningShown(text, multiline: false);
    final runes = shown.runes.toList();
    return runes.length <= planningMostPlaceText
        ? shown
        : '${String.fromCharCodes(runes.take(planningMostPlaceText))}…';
  }

  if (place == null) return "the task's own gate push";
  if (place.pullRequest case final address?) return 'pull request ${cut(address)}';
  if (place.ownPullRequest) return "the task's own pull request";
  final repository = place.gateRepository;
  return 'gate push of task ${cut(place.gatePush ?? '')}${repository == null ? '' : ' in ${cut(repository)}'}';
}

/// A run's duty role beside Porelo's run role, its place and its held items, as served.
String planningRunFacts(PlanWorkTask task) =>
    '${planningValue(task.role)} · run role ${task.runRole == 'REVIEWER' ? 'reviewer' : 'author'}'
    ' · at ${task.placeUnreadable ? 'a place the screen cannot read' : planningPlaceText(task.place)}'
    ' · held ${task.held ?? 'unknown'}';

/// A reported time in the person's own zone to the minute, with its date only when it is not today, or the reported
/// text when it is no time.
String planningLocalTime(String reported, {DateTime? now}) {
  final at = DateTime.tryParse(reported)?.toLocal();
  if (at == null) return planningShown(reported, multiline: false);
  final today = (now ?? DateTime.now()).toLocal();
  String two(int value) => value.toString().padLeft(2, '0');
  final time = '${two(at.hour)}:${two(at.minute)}';
  return at.year == today.year && at.month == today.month && at.day == today.day
      ? time
      : '${at.year}-${two(at.month)}-${two(at.day)} $time';
}

/// Human-readable names for known work modes; later daemon values remain as reported.
String planningWorkMode(String mode) => switch (mode) {
  'UNATTENDED' => 'unattended',
  'AGENT' => 'shell with you',
  _ => planningValue(mode),
};

/// Who works an issue, in a word.
String planningWorker(IssueWorker? worker) => switch (worker) {
  IssueWorker.agent => 'Agent',
  IssueWorker.manual => 'Manual',
  null => 'Not reported',
};

/// The mark of an item the store could not fully read, each warning's sentence a line of its tooltip; null when it
/// has none.
Widget? planningWarningMark(List<PlanWarning> warnings, {Key? key}) => warnings.isEmpty
    ? null
    : Tooltip(
        message: planningShown(
          warnings.map((warning) => warning.text.isEmpty ? warning.kind : warning.text).join('\n'),
        ),
        child: Icon(Icons.report_problem_outlined, key: key, size: PlanningStyle.rowIcon),
      );

/// The mark of an item breaking the stories' rules, its words in the tooltip; null when it breaks none.
Widget? planningRuleMark(List<String> words, {Key? key}) => words.isEmpty
    ? null
    : Tooltip(
        message: planningShown(words.join('\n')),
        child: Icon(Icons.rule, key: key, size: PlanningStyle.rowIcon),
      );

/// An item's labels as small chips, in the planner's order, each once; null when it carries none.
Widget? planningLabels(String item, List<String> labels) => labels.isEmpty
    ? null
    : Wrap(
        key: Key('planning-labels $item'),
        spacing: Space.tight,
        runSpacing: Space.tight,
        children: [
          for (final label in labels.toSet())
            Chip(
              key: Key('planning-label $item $label'),
              label: Text(planningShown(label, multiline: false)),
              visualDensity: VisualDensity.compact,
              materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
        ],
      );

/// The button that chooses the topic labels an item carries, in a dialog showing what the save leaves on it; a label
/// of the person's own stays and is shown as kept.
class PlanningLabelsEdit extends StatelessWidget {
  /// Takes the item, its labels, why it cannot be changed now and what saves the choice.
  const PlanningLabelsEdit({
    required this.item,
    required this.labels,
    required this.unavailable,
    required this.onSave,
    super.key,
  });

  /// The item's id.
  final String item;

  /// The labels it carries as shown.
  final List<String> labels;

  /// Why its labels cannot be changed now, or null when they can.
  final String? unavailable;

  /// Saves the topic labels chosen, in the order of [planningTopicLabels].
  final ValueChanged<List<String>> onSave;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: Space.small,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      TextButton.icon(
        key: Key('planning-labels-edit $item'),
        onPressed: unavailable == null
            ? () async {
                final chosen = await showDialog<List<String>>(
                  context: context,
                  builder: (_) => _LabelsDialog(item: item, labels: labels),
                );
                if (chosen != null) onSave(chosen);
              }
            : null,
        icon: const Icon(Icons.label_outline, size: PlanningStyle.rowIcon),
        label: Tooltip(
          message: unavailable ?? 'Choose the topic labels of ${planningShown(item, multiline: false)}',
          child: const Text('Labels'),
        ),
      ),
      // A greyed button says why in text, never on hover alone.
      if (unavailable case final reason?) Text(planningShown(reason, multiline: false)),
    ],
  );
}

class _LabelsDialog extends StatefulWidget {
  const _LabelsDialog({required this.item, required this.labels});

  final String item;

  final List<String> labels;

  @override
  State<_LabelsDialog> createState() => _LabelsDialogState();
}

class _LabelsDialogState extends State<_LabelsDialog> {
  // A label carried in another case is the topic label, so it is chosen and taken off as one.
  late final Set<String> _before = {for (final label in widget.labels) ?planningTopicLabelOf(label)};

  late final Set<String> _chosen = {..._before};

  late final List<String> _kept = [
    for (final label in widget.labels.toSet())
      if (planningTopicLabelOf(label) == null) label,
  ];

  List<String> get _ordered => [
    for (final label in planningTopicLabels)
      if (_chosen.contains(label)) label,
  ];

  bool get _changed => _chosen.length != _before.length || !_chosen.containsAll(_before);

  @override
  Widget build(BuildContext context) {
    final after = [..._kept, ..._ordered];
    return AlertDialog(
      title: Text('Labels of ${planningShown(widget.item, multiline: false)}'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: PlanningStyle.dialog),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: Space.normal,
          children: [
            Wrap(
              spacing: Space.small,
              runSpacing: Space.small,
              children: [
                for (final label in planningTopicLabels)
                  FilterChip(
                    key: Key('planning-label-choice $label'),
                    label: Text(label),
                    selected: _chosen.contains(label),
                    onSelected: (selected) => setState(() => selected ? _chosen.add(label) : _chosen.remove(label)),
                  ),
              ],
            ),
            if (_kept.isNotEmpty)
              Text(
                planningShown('Kept as they are: ${_kept.join(', ')}', multiline: false),
                key: const Key('planning-labels-kept'),
              ),
            Text(
              planningShown('After saving: ${after.isEmpty ? 'no labels' : after.join(', ')}', multiline: false),
              key: const Key('planning-labels-preview'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key('planning-labels-cancel'),
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('planning-labels-save'),
          onPressed: _changed ? () => Navigator.of(context).pop(_ordered) : null,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
