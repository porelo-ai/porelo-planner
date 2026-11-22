import 'dart:async';

import 'package:flutter/material.dart';

import '../hooks.dart';
import '../models.dart';
import '../planning_board.dart';
import '../story_work_order.dart';
import '../client.dart';
import 'open_work_figures.dart';
import 'open_work_rows.dart';
import 'style.dart';

/// A project's stories by what is left to build of them, shown where the board stands when the place's view is
/// [PlanningView.openWork]; it takes what the board takes.
class PlanningOpenWorkView extends StatefulWidget {
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
  State<PlanningOpenWorkView> createState() => _PlanningOpenWorkViewState();
}

/// The widths of the columns that do not share the rest.
abstract final class _Width {
  static const double story = 300;
  static const double priority = 88;
  static const double open = 180;
  static const double now = 168;
  static const double left = 104;
}

class _PlanningOpenWorkViewState extends State<PlanningOpenWorkView> {
  PlanningBoard get board => widget.board;

  /// Each story's first paragraph, read once from its own page, since the board's list carries no description.
  final Map<String, String> _holds = {};

  /// The rows whose description is read, the first of the list; the rest show none, so a long list asks no more.
  static const _mostRead = 40;

  /// Why a story's description could not be read, by story.
  final Map<String, String> _unread = {};

  /// The stories whose description was asked for.
  final Set<String> _asked = {};

  /// The revision the failed reads were made at; a later one asks them again.
  String? _failedAt;

  bool _reading = false;

  /// Reads the descriptions not yet read of the listed stories, one at a time, so a long list does not flood the
  /// planner.
  Future<void> _readHolds(List<String> stories) async {
    _reading = true;
    try {
      for (final id in stories) {
        if (!mounted || board.disposed || _asked.contains(id)) continue;
        _asked.add(id);
        String? holds;
        String? problem;
        try {
          final reply = await board.backend.getStory(board.project, id);
          final story = reply.story;
          if (story == null || story.id != id) throw const FormatException('The planner did not return the story.');
          holds = planningFirstParagraph(story.description, story.descriptionFormat);
        } catch (error) {
          problem = planningFailureWords(error);
          _failedAt = board.snapshot?.revision;
        }
        if (!mounted) return;
        setState(() {
          if (holds != null) {
            _holds[id] = holds;
            _unread.remove(id);
          } else {
            _unread[id] = problem!;
          }
        });
        // A planner that refuses one read is asked no more until the board changes.
        if (problem != null) return;
      }
    } finally {
      _reading = false;
      // Stories listed while these were read are asked for on this build.
      if (mounted) setState(() {});
    }
  }

  /// Asks after this frame for the descriptions the rows lack, and again for those that failed once the board moved
  /// on.
  void _askHolds(List<PlanningOpenWorkRow> rows) {
    final revision = board.snapshot?.revision;
    if (_unread.isNotEmpty && revision != null && revision != _failedAt) {
      _asked.removeAll(_unread.keys);
    }
    if (_reading || _unread.isNotEmpty && revision == _failedAt) return;
    final missing = [
      for (final row in rows.take(_mostRead))
        if (!_asked.contains(row.story.id)) row.story.id,
    ];
    if (missing.isEmpty) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_reading) unawaited(_readHolds(missing));
    });
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: board,
    builder: (context, _) {
      if (board.unsupported) {
        return Center(
          child: Text('${board.backend.label} does not offer planning.', key: const Key('planning-no-planning')),
        );
      }
      final theme = Theme.of(context);
      final snapshot = board.snapshot;
      final known = planningFiguresKnown(board);
      final list = snapshot == null
          ? null
          : planningOpenWork(board.stories, board.states, planningReviewStates(snapshot), known: known);
      if (list != null) _askHolds(list.rows);
      return Padding(
        key: const Key('planning-open-work'),
        padding: const EdgeInsets.all(Space.wide),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              spacing: Space.small,
              children: [
                Text('Open work', style: theme.textTheme.headlineSmall),
                ?widget.pageButton,
              ],
            ),
            ?widget.pageNote,
            Text('The stories of project ${board.project} by what is left to build, the most first.'),
            Text('From ${board.backend.label}', style: theme.textTheme.bodySmall),
            for (final problem in widget.providerProblems) _failure(problem, theme),
            if (!board.live && (snapshot != null || board.problem != null))
              const Text(
                'Planning is not live. Reload planning shows the current state.',
                key: Key('planning-not-live'),
              ),
            if (board.problem case final problem?) _failure(problem, theme),
            const SizedBox(height: Space.normal),
            if (list == null)
              const Text('Reading the planning…')
            else ...[
              Text(
                [
                  '${list.rows.length} ${list.rows.length == 1 ? 'story' : 'stories'} listed',
                  if (list.finished > 0)
                    '${list.finished} with nothing left to build ${list.finished == 1 ? 'is' : 'are'} not listed',
                  if (!known) 'what is left is not known now, so every story is listed',
                ].join(' · '),
                key: const Key('planning-open-work-summary'),
              ),
              const SizedBox(height: Space.small),
              _header(theme),
              const Divider(height: 1),
              Expanded(
                child: ListView.separated(
                  key: const Key('planning-open-work-list'),
                  itemCount: list.rows.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, index) => _row(context, list.rows[index]),
                ),
              ),
            ],
          ],
        ),
      );
    },
  );

  Widget _header(ThemeData theme) {
    final style = theme.textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w600);
    Widget cell(String words, double? width) => width == null
        ? Expanded(child: Text(words, style: style))
        : SizedBox(
            width: width,
            child: Text(words, style: style),
          );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: Space.small, horizontal: Space.small),
      child: Row(
        spacing: Space.normal,
        children: [
          cell('Story', _Width.story),
          cell('Priority', _Width.priority),
          cell('Open issues', _Width.open),
          cell('Now', _Width.now),
          cell('What it holds', null),
          cell('Left', _Width.left),
        ],
      ),
    );
  }

  Widget _row(BuildContext context, PlanningOpenWorkRow row) {
    final theme = Theme.of(context);
    final story = row.story;
    final open = row.open;
    final sizes = open == null ? '' : planningSizes(open);
    final minutes = row.minutes;
    final left = open != null && open.left == 0
        ? 'nothing to build'
        : open == null || minutes == null
        ? planningMinutes(null)
        : '≈ ${planningMinutes(minutes)}${open.unestimated > 0 ? '+' : ''}';
    final holds = _holds[story.id];
    final unread = _unread[story.id];
    final hint = unread ?? holds ?? '';
    final holdsText = Text(
      holds ?? (unread != null ? 'Not read' : (_asked.contains(story.id) ? '…' : '')),
      key: Key('planning-open-work-holds ${story.id}'),
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      style: theme.textTheme.bodySmall,
    );
    return InkWell(
      key: Key('planning-open-work-row ${story.id}'),
      onTap: () => widget.onStory(story.id),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: Space.normal, horizontal: Space.small),
        child: Row(
          spacing: Space.normal,
          children: [
            SizedBox(
              width: _Width.story,
              child: Text.rich(
                TextSpan(
                  children: [
                    TextSpan(
                      text: planningShown(story.id, multiline: false),
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    TextSpan(text: '  ${planningShown(story.title, multiline: false)}'),
                  ],
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            SizedBox(width: _Width.priority, child: Text(planningShown(story.priority ?? '—', multiline: false))),
            SizedBox(
              width: _Width.open,
              child: Text(
                open == null ? 'unknown' : '${open.left}${sizes.isEmpty ? '' : ' · $sizes'}',
                key: Key('planning-open-work-open ${story.id}'),
              ),
            ),
            SizedBox(
              width: _Width.now,
              child: open == null
                  ? Text('unknown', key: Key('planning-open-work-now ${story.id}'))
                  : Wrap(
                      key: Key('planning-open-work-now ${story.id}'),
                      spacing: Space.small,
                      runSpacing: Space.tight,
                      children: [
                        for (final (kind, issues) in row.now)
                          Tooltip(
                            message: '$issues ${planningStateKindLabel(kind)}',
                            child: Row(
                              key: Key('planning-open-work-state ${story.id} ${kind.name}'),
                              mainAxisSize: MainAxisSize.min,
                              spacing: Space.tight,
                              children: [
                                PlanningStateIcon(kind),
                                Text(
                                  '$issues',
                                  style: theme.textTheme.labelSmall?.copyWith(
                                    color: planningStateColor(kind, theme.colorScheme),
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ],
                            ),
                          ),
                      ],
                    ),
            ),
            Expanded(
              child: hint.isEmpty ? holdsText : Tooltip(message: hint, child: holdsText),
            ),
            SizedBox(
              width: _Width.left,
              child: Text(left, key: Key('planning-open-work-left ${story.id}'), textAlign: TextAlign.end),
            ),
          ],
        ),
      ),
    );
  }

  Widget _failure(String words, ThemeData theme) {
    final colour = theme.colorScheme.error;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.error_outline, size: PlanningStyle.mark, color: colour),
        const SizedBox(width: Space.small),
        Flexible(
          child: Text(planningShown(words), style: TextStyle(color: colour)),
        ),
      ],
    );
  }
}
