import '../models.dart';

import 'package:flutter/material.dart';

import '../planning_board.dart';
import 'actions.dart';
import 'style.dart';

/// An absent value is not an empty successful report.
String planningValue(String value) => value.isEmpty ? 'Not reported' : planningShown(value);

/// The common section surface used by planning details.
Widget planningSection(BuildContext context, String title, List<Widget> children) => Card(
  child: Padding(
    padding: const EdgeInsets.all(Space.normal),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
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

/// A task named by this story, with a visible explanation when the window cannot open it.
class PlanningTaskLink extends StatelessWidget {
  /// Takes availability from the task's own machine.
  const PlanningTaskLink({required this.name, required this.onOpen, required this.unavailable, super.key});

  /// The task's full name.
  final String name;

  /// Opens the task only when it is known on this machine.
  final VoidCallback onOpen;

  /// Why the machine or task is unavailable, if so.
  final String? unavailable;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: Space.small,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      Tooltip(
        message: unavailable ?? 'Open $name',
        child: TextButton(
          key: Key('planning-task $name'),
          onPressed: unavailable == null ? onOpen : null,
          child: Text(name),
        ),
      ),
      if (unavailable case final reason?) Text(reason),
    ],
  );
}

/// Human-readable names for known work modes; later daemon values remain as reported.
String planningWorkMode(String mode) => switch (mode) {
  'UNATTENDED' => 'unattended',
  'AGENT' => 'shell with you',
  _ => planningValue(mode),
};
