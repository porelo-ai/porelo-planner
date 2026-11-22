import 'package:flutter/material.dart';

import '../hooks.dart';
import '../planning_board.dart';
import '../story_work_order.dart';
import '../client.dart';
import 'style.dart';

/// A project's stories by what is left to build of them, shown where the board stands when the place's view is
/// [PlanningView.openWork]; it takes what the board takes.
class PlanningOpenWorkView extends StatelessWidget {
  /// Takes the board, the window's hooks and what a story or an issue opens, as the board view does.
  const PlanningOpenWorkView({
    required this.board,
    required this.host,
    required this.projects,
    required this.select,
    required this.providerProblems,
    required this.onNeedsYou,
    required this.onStory,
    required this.onIssue,
    required this.onStarted,
    required this.startTask,
    required this.agents,
    required this.wizards,
    required this.machines,
    required this.unavailableMachines,
    this.pageButton,
    this.pageNote,
    super.key,
  });

  /// The window's own button for the page, or null.
  final Widget? pageButton;

  /// The window's own words for the page, or null.
  final Widget? pageNote;

  /// The project's board.
  final PlanningBoard board;

  /// Where the screen stands, and what the window opens for it.
  final PlanningHost host;

  /// The projects to choose from.
  final List<String> projects;

  /// Chooses another project.
  final ValueChanged<String> select;

  /// What the providers could not supply.
  final List<String> providerProblems;

  /// Opens Needs you.
  final VoidCallback onNeedsYou;

  /// Opens a story, keeping this view for the way back.
  final ValueChanged<String> onStory;

  /// Opens an issue of a story, keeping this view for the way back.
  final void Function(String story, String issue) onIssue;

  /// Tells the window of tasks a start began.
  final Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>) onStarted;

  /// How a start of story work reaches the window.
  final PlanningStartTask startTask;

  /// The agents a start may choose.
  final PlanningAgents agents;

  /// The window's wizard for Start work's steps, or null.
  final PlanningWizards? wizards;

  /// The machines a start may choose.
  final Map<PlanningBoard, String> machines;

  /// The machines that have not supplied the project.
  final List<String> unavailableMachines;

  @override
  Widget build(BuildContext context) => Padding(
    key: const Key('planning-open-work'),
    padding: const EdgeInsets.all(Space.wide),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          spacing: Space.small,
          children: [
            Text('Open work', style: Theme.of(context).textTheme.headlineSmall),
            ?pageButton,
          ],
        ),
        ?pageNote,
        Text('The stories of project ${board.project} by what is left to build.'),
      ],
    ),
  );
}
