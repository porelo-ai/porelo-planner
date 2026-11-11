import 'dart:async';

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';

import '../client.dart';
import '../hooks.dart';
import '../models.dart';
import '../planning_board.dart';
import '../planning_detail.dart';
import '../story_work.dart';
import '../story_work_order.dart';
import 'actions.dart';
import 'planning_issue_view.dart';
import 'planning_story_view.dart';
import 'story_work_dialog.dart';
import 'style.dart';

final _planningLeaveDialogs = Expando<bool>();

/// Asks before leaving unsaved work; a save in flight must finish first.
Future<bool> confirmPlanningLeave(BuildContext context, Iterable<PlanningBoard> boards, {bool quitting = false}) async {
  final watched = boards.toList();
  final navigator = Navigator.of(context);
  if (_planningLeaveDialogs[navigator] == true) return false;
  if (!watched.any((board) => board.pending.isNotEmpty || board.saving)) return true;
  _planningLeaveDialogs[navigator] = true;
  try {
    return await showDialog<bool>(
          context: context,
          builder: (context) => ListenableBuilder(
            listenable: Listenable.merge(watched),
            builder: (context, _) {
              final saving = watched.any((board) => board.saving);
              final pending = watched.any((board) => board.pending.isNotEmpty);
              return AlertDialog(
                title: Text(
                  saving
                      ? 'Planning is saving'
                      : pending
                      ? 'Unsaved planning moves'
                      : 'Planning saved',
                ),
                content: Text(
                  saving
                      ? 'Wait for the planner’s answer before leaving.'
                      : !pending
                      ? 'The planning changes are saved. You can continue.'
                      : quitting
                      ? 'Closing the window loses unsaved planning moves. Close anyway?'
                      : 'These planning moves have not been saved. Leave them pending and continue?',
                ),
                actions: [
                  TextButton(
                    key: const Key('planning-stay'),
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('Keep editing'),
                  ),
                  if (!saving)
                    TextButton(
                      key: const Key('planning-leave'),
                      onPressed: () => Navigator.pop(context, true),
                      child: Text(quitting ? 'Close anyway' : 'Leave with moves pending'),
                    ),
                ],
              );
            },
          ),
        ) ??
        false;
  } finally {
    _planningLeaveDialogs[navigator] = false;
  }
}

/// The planning board, its stories and issues, and their unsaved moves, supplied by the client's providers.
class PlanningScreen extends StatelessWidget {
  /// Takes the boards the window keeps and the hooks through which the window takes part.
  const PlanningScreen({
    required this.boards,
    required this.host,
    required this.startTask,
    required this.taskChanges,
    required this.agents,
    super.key,
  });

  /// The projects and their planning providers, kept by the window while the screen is not shown.
  final PlanningBoards boards;

  /// Where the screen stands, and what the window opens for it.
  final PlanningHost host;

  /// How a start of story work reaches the window.
  final PlanningStartTask startTask;

  /// What the screen shows of a run, and what the window does with it.
  final PlanningTaskChanges taskChanges;

  /// The agents a start may choose.
  final PlanningAgents agents;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: Listenable.merge([boards, host]),
    builder: (context, _) {
      for (final board in boards.sources) {
        board.startTask = startTask;
        board.taskChanges = taskChanges;
      }
      final place = host.place;
      final detailBoard = boards.at(place.source, place.project);
      final pinned = place.story != null;
      final board = pinned ? detailBoard : boards.selected;
      if (board == null) {
        return Column(
          children: [
            TextButton(
              key: const Key('planning-crumb 0'),
              onPressed: () => host.go(const PlanningPlace()),
              child: const Text('Planning'),
            ),
            Text(
              pinned
                  ? 'The planner supplying this planning item is no longer available.'
                  : 'No project planning has been read from the planner.',
            ),
          ],
        );
      }
      void open(PlanningBoard owner, String story, [String? issue]) =>
          openPlanningAction(boards, host, owner, story, issue: issue).run();
      return Stack(
        fit: StackFit.expand,
        children: [
          Offstage(
            offstage: place.story != null,
            child: _BoardView(
              key: ObjectKey(board),
              board: board,
              projects: boards.projects,
              select: (project) {
                boards.select(project);
                final selected = boards.selected;
                host.go(PlanningPlace(project: project, source: selected == null ? null : boards.sourceOf(selected)));
              },
              providerProblems: boards.problemsFor(board),
              onNeedsYou: host.openNeedsYou,
              onStarted: taskChanges.started,
              startTask: startTask,
              agents: agents,
              unavailableMachines: [
                for (final machine in boards.providerNames)
                  if (!boards.sources.any(
                    (source) => source.project == board.project && boards.machineOf(source) == machine,
                  ))
                    machine,
              ],
              machines: {
                for (final source in boards.sources.where((source) => source.project == board.project))
                  source: boards.machineOf(source),
              },
              onStory: (story) => open(board, story),
              onIssue: (story, issue) => open(board, story, issue),
            ),
          ),
          if (place.story != null && detailBoard != null)
            _PlanningDetailView(
              key: ValueKey((detailBoard, place.story, place.issue)),
              board: detailBoard,
              story: place.story!,
              issue: place.issue,
              onBoard: () => host.go(PlanningPlace(project: detailBoard.project, source: boards.sourceOf(detailBoard))),
              onParent: () => open(detailBoard, place.story!),
              onStory: (story) => open(detailBoard, story),
              onIssue: (issue) => open(detailBoard, place.story!, issue),
              onTask: (task) => taskChanges.open(detailBoard, task),
              taskUnavailable: (task) => taskChanges.unavailable(detailBoard, task),
              openLink: host.openLink,
            ),
        ],
      );
    },
  );
}

class _BoardView extends StatefulWidget {
  const _BoardView({
    required this.board,
    required this.projects,
    required this.select,
    required this.providerProblems,
    required this.onNeedsYou,
    required this.onStory,
    required this.onIssue,
    required this.onStarted,
    required this.startTask,
    required this.agents,
    required this.machines,
    required this.unavailableMachines,
    super.key,
  });
  final PlanningBoard board;
  final List<String> projects;
  final ValueChanged<String> select;
  final List<String> providerProblems;
  final VoidCallback onNeedsYou;

  final ValueChanged<String> onStory;
  final void Function(String, String) onIssue;
  final Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>) onStarted;
  final PlanningStartTask startTask;
  final PlanningAgents agents;
  final Map<PlanningBoard, String> machines;
  final List<String> unavailableMachines;
  @override
  State<_BoardView> createState() => _BoardViewState();
}

class _BoardViewState extends State<_BoardView> {
  late final _search = TextEditingController(text: board.preferences.search);
  Key _project = UniqueKey();
  PlanningBoard get board => widget.board;
  late final _vertical = ScrollController(
    initialScrollOffset: board.preferences.verticalOffset,
    keepScrollOffset: false,
  )..addListener(_rememberScroll);
  late final _horizontal = ScrollController(
    initialScrollOffset: board.preferences.horizontalOffset,
    keepScrollOffset: false,
  )..addListener(_rememberScroll);

  void _rememberScroll() {
    if (_vertical.hasClients) board.preferences.verticalOffset = _vertical.offset;
    if (_horizontal.hasClients) board.preferences.horizontalOffset = _horizontal.offset;
  }

  @override
  void dispose() {
    _vertical.dispose();
    _horizontal.dispose();
    _search.dispose();
    super.dispose();
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
      return Padding(
        key: const Key('planning-board'),
        padding: const EdgeInsets.all(Space.wide),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Planning', style: theme.textTheme.headlineSmall),
            Text('The stories of project ${board.project} by state, the most important first.'),
            Text('From ${board.backend.label}', style: theme.textTheme.bodySmall),
            const SizedBox(height: Space.normal),
            Wrap(
              spacing: Space.normal,
              runSpacing: Space.small,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                if (widget.projects.length > 1)
                  SizedBox(
                    key: const Key('planning-project'),
                    width: PlanningStyle.filter,
                    child: DropdownButtonFormField<String>(
                      key: _project,
                      initialValue: board.project,
                      decoration: const InputDecoration(labelText: 'Project'),
                      isExpanded: true,
                      items: [for (final name in widget.projects) DropdownMenuItem(value: name, child: Text(name))],
                      onChanged: board.saving
                          ? null
                          : (name) async {
                              if (name != null &&
                                  name != board.project &&
                                  await confirmPlanningLeave(context, [board]) &&
                                  mounted) {
                                widget.select(name);
                              } else if (mounted) {
                                setState(() => _project = UniqueKey());
                              }
                            },
                    ),
                  ),
                SizedBox(
                  width: PlanningStyle.filter,
                  child: DropdownButtonFormField<PlanningPriority>(
                    key: const Key('planning-priority'),
                    initialValue: board.preferences.priority,
                    decoration: const InputDecoration(labelText: 'Priority'),
                    isExpanded: true,
                    items: [
                      for (final priority in PlanningPriority.values)
                        DropdownMenuItem(value: priority, child: Text(priority.label)),
                    ],
                    onChanged: (priority) {
                      if (priority != null) board.filter(priority);
                    },
                  ),
                ),
                SizedBox(
                  width: PlanningStyle.filter,
                  child: TextField(
                    key: const Key('planning-search'),
                    controller: _search,
                    onChanged: board.search,
                    decoration: const InputDecoration(
                      labelText: 'Search',
                      hintText: 'Story ID or a word',
                      prefixIcon: Icon(Icons.search),
                    ),
                  ),
                ),
                FilterChip(
                  key: const Key('planning-show-closed'),
                  label: Text('Show Finished and Rejected (${board.closedCount ?? '?'})'),
                  selected: board.preferences.showClosed,
                  onSelected: (_) => board.toggleClosed(),
                ),
              ],
            ),
            for (final problem in widget.providerProblems)
              Text(problem, style: TextStyle(color: theme.colorScheme.error)),
            if (board.catchingUp)
              const Text('Catching up with the planner’s changes…', key: Key('planning-catching-up')),
            if (!board.live && (board.snapshot != null || board.problem != null))
              const Text(
                'Planning is not live. Reload planning shows the current state.',
                key: Key('planning-not-live'),
              ),
            if (board.actionProblem case final problem?) Text(problem, key: const Key('planning-action-error')),
            for (final entry in board.workProblems.entries) Text('${entry.key}: ${entry.value}'),
            for (final story in board.workUnsupported) Text('$story: the planner does not offer story work status.'),
            if (board.problem case final problem?)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: Space.small),
                child: Text(
                  problem,
                  key: const Key('planning-error'),
                  style: TextStyle(color: theme.colorScheme.error),
                ),
              ),
            if (board.snapshot != null && !board.snapshot!.complete)
              const Text('The planner did not report the complete planning workflow and story list.'),
            if (board.problem != null)
              TextButton.icon(
                key: const Key('planning-retry'),
                onPressed: () => unawaited(board.retry()),
                icon: const Icon(Icons.refresh),
                label: const Text('Retry planning'),
              ),
            if (board.moveProblem case final problem?)
              Text(
                problem,
                key: const Key('planning-move-error'),
                style: TextStyle(color: theme.colorScheme.error),
              ),
            if (board.moveNotice case final notice?) Text(notice, key: const Key('planning-move-notice')),
            if (board.snapshot != null || board.problem != null)
              TextButton.icon(
                key: const Key('planning-reload'),
                onPressed: board.saving ? null : () => unawaited(board.reload()),
                icon: const Icon(Icons.refresh),
                label: const Text('Reload planning'),
              ),
            // Not flexible: a loose share of the height would stay empty below the board.
            if (board.pending.isNotEmpty) _pending(context),
            const SizedBox(height: Space.normal),
            if (board.snapshot == null && board.problem == null)
              const Text('Waiting for the planner’s answer.', key: Key('planning-waiting')),
            Expanded(
              child: board.snapshot == null
                  ? const SizedBox.shrink()
                  // The sideways bar sits at the view's foot, not under the tallest column, so it can be seen.
                  : Scrollbar(
                      key: const Key('planning-board-sideways'),
                      controller: _horizontal,
                      thumbVisibility: true,
                      notificationPredicate: (notification) => notification.depth == 1,
                      child: SingleChildScrollView(
                        key: const Key('planning-board-scroll'),
                        controller: _vertical,
                        child: SingleChildScrollView(
                          controller: _horizontal,
                          scrollDirection: Axis.horizontal,
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              for (final phase in board.phases)
                                if (board.statesIn(phase).isNotEmpty) _phase(context, phase),
                              if (board.unplaced.isNotEmpty)
                                SizedBox(
                                  width: PlanningStyle.column,
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      const Text('Stories outside the reported workflow'),
                                      for (final story in board.unplaced) ...[
                                        Text('State: ${story.state.isEmpty ? 'not reported' : story.state}'),
                                        _card(context, story),
                                      ],
                                    ],
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
            ),
          ],
        ),
      );
    },
  );

  Widget _phase(BuildContext context, PlanPhase phase) {
    final folded = board.preferences.foldedPhases.contains(phase.name);
    final states = board.statesIn(phase);
    final count = states.fold<int>(0, (count, state) => count + board.cards(state.name).length);
    return Container(
      key: Key('planning-phase ${phase.name}'),
      width: folded ? PlanningStyle.foldedColumn : PlanningStyle.column,
      margin: const EdgeInsets.only(right: Space.normal),
      padding: const EdgeInsets.all(Space.small),
      decoration: BoxDecoration(
        color: PlanningStyle.phase(Theme.of(context).colorScheme),
        borderRadius: BorderRadius.circular(PlanningStyle.radiusMedium),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(child: Text(phase.name.toUpperCase(), style: Theme.of(context).textTheme.labelLarge)),
              Text('$count'),
              IconButton(
                key: Key('planning-fold-phase ${phase.name}'),
                tooltip: '${folded ? 'Unfold' : 'Fold'} ${phase.name}',
                onPressed: () => board.foldPhase(phase.name),
                icon: Icon(folded ? Icons.chevron_right : Icons.chevron_left),
              ),
            ],
          ),
          for (final state in states) _state(context, state, phaseFolded: folded),
        ],
      ),
    );
  }

  Widget _state(BuildContext context, PlanState state, {required bool phaseFolded}) {
    final cards = board.cards(state.name);
    final folded = phaseFolded || board.preferences.foldedStates.contains(state.name);
    final theme = Theme.of(context);
    return DragTarget<String>(
      key: Key('planning-drop ${state.name}'),
      onWillAcceptWithDetails: (details) =>
          !board.saving &&
          {'WAITING', 'WORKING', 'CLOSED'}.contains(state.kind) &&
          board.live &&
          board.stories.any((story) => story.id == details.data && board.displayedState(story) != state.name),
      onAcceptWithDetails: (details) {
        final story = board.stories.where((story) => story.id == details.data).firstOrNull;
        if (story == null || board.displayedState(story) == state.name) return;
        if (state.kind == 'WORKING') {
          unawaited(_start(story, target: state));
        } else {
          board.move(details.data, state.name);
        }
      },
      builder: (context, candidates, rejected) => Container(
        key: Key('planning-state ${state.name}'),
        padding: const EdgeInsets.all(Space.tight),
        margin: const EdgeInsets.only(bottom: Space.small),
        decoration: BoxDecoration(
          color: state.kind == 'WORKING' ? PlanningStyle.working(theme.colorScheme) : null,
          borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (candidates.isNotEmpty) Text('Drop here for ${state.name}'),
            Row(
              children: [
                if (state.kind == 'WORKING')
                  const Padding(
                    padding: EdgeInsets.only(right: Space.tight),
                    child: Icon(Icons.schedule, size: PlanningStyle.mark),
                  ),
                Expanded(child: Text(state.name, style: theme.textTheme.labelMedium)),
                Text('${cards.length}'),
                if (!phaseFolded)
                  IconButton(
                    key: Key('planning-fold-state ${state.name}'),
                    tooltip: '${folded ? 'Unfold' : 'Fold'} ${state.name}',
                    onPressed: () => board.foldState(state.name),
                    icon: Icon(folded ? Icons.chevron_right : Icons.expand_more, size: PlanningStyle.rowIcon),
                  ),
              ],
            ),
            if (state.kind.isEmpty)
              const Text('State kind not reported')
            else if (!{'WAITING', 'WORKING', 'CLOSED'}.contains(state.kind))
              Text(state.kind),
            if (folded)
              Wrap(
                spacing: Space.tight,
                runSpacing: Space.tight,
                children: [
                  for (final story in cards)
                    _drag(
                      story,
                      InkWell(
                        key: Key('planning-story ${story.id}'),
                        onTap: story.id.isEmpty ? null : () => widget.onStory(story.id),
                        child: Tooltip(
                          message: '${story.id} — ${story.title} (${story.priority})',
                          child: Container(
                            key: Key('planning-card ${story.id}'),
                            padding: const EdgeInsets.all(Space.tight),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    // One width for every mark, so the numbers and menus stand in line.
                                    SizedBox(
                                      width: PlanningStyle.foldedMark,
                                      child: Align(
                                        alignment: Alignment.centerLeft,
                                        child: _priority(context, story.priority ?? '', mark: true),
                                      ),
                                    ),
                                    Expanded(child: Text(story.id, softWrap: false, overflow: TextOverflow.fade)),
                                    _menu(story, dense: true),
                                  ],
                                ),
                                if (board.pending[story.id] case final move? when move.problem == null)
                                  const Text('moved'),
                                if (board.pending[story.id]?.problem case final problem?) Text(problem),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              )
            else
              for (final story in cards) _card(context, story),
          ],
        ),
      ),
    );
  }

  Widget _pending(BuildContext context) => ConstrainedBox(
    constraints: const BoxConstraints(maxHeight: PlanningStyle.column),
    child: SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${board.pending.length} state change(s) not saved yet:'),
          if (savePlanningAction(board).unavailable case final reason?) Text(reason),
          for (final entry in board.pending.entries)
            Wrap(
              key: Key('planning-pending ${entry.key}'),
              spacing: Space.small,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text(
                  '${entry.key} ${entry.value.from.isEmpty ? 'not reported' : entry.value.from} → ${entry.value.to}',
                ),
                if (entry.value.problem != null) Text(entry.value.problem!),
                TextButton(
                  key: Key('planning-undo ${entry.key}'),
                  onPressed: board.saving ? null : () => board.undo(entry.key),
                  child: const Text('Undo'),
                ),
              ],
            ),
          Wrap(
            spacing: Space.small,
            children: [
              TextButton(
                key: const Key('planning-undo-all'),
                onPressed: board.saving ? null : board.undoAll,
                child: const Text('Undo all'),
              ),
              FilledButton(
                key: const Key('planning-save'),
                onPressed: savePlanningAction(board).available ? savePlanningAction(board).run : null,
                child: Text(board.saving ? 'Saving…' : 'Save'),
              ),
            ],
          ),
        ],
      ),
    ),
  );

  Widget _drag(PlanStory story, Widget child) => Draggable<String>(
    data: story.id,
    maxSimultaneousDrags: board.saving || !board.live ? 0 : 1,
    feedback: Material(
      elevation: Space.tight,
      child: Padding(padding: const EdgeInsets.all(Space.small), child: Text(story.id)),
    ),
    childWhenDragging: Opacity(opacity: PlanningStyle.stale, child: child),
    child: child,
  );

  Widget _priority(BuildContext context, String priority, {bool mark = false}) {
    final colors = PlanningStyle.priority(priority, Theme.of(context).colorScheme);
    final label = mark
        ? switch (priority) {
            'Highest' => '▲▲',
            'High' => '▲',
            'Medium' => '▬',
            'Low' => '▼',
            _ => priority.isEmpty ? '?' : priority,
          }
        : priority.isEmpty
        ? 'Priority not reported'
        : priority;
    return Semantics(
      label: priority.isEmpty ? 'Priority not reported' : priority,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: Space.tight),
        decoration: BoxDecoration(
          color: colors.background,
          borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
        ),
        child: Text(label, style: Theme.of(context).textTheme.labelSmall?.copyWith(color: colors.foreground)),
      ),
    );
  }

  String? _work(PlanStory story) {
    final work = board.workOf(story);
    final working = board.state(story.state)?.kind == 'WORKING';
    final mainTasks = work?.tasks.where((task) => task.role == 'MAIN').length ?? 0;
    if (work == null) return working ? 'Work not reported' : null;
    if (work.atWork == false) return working ? 'nobody at work' : null;
    return [
      if (work.atWork == null) 'Work status not reported',
      if (work.coordinator?.isNotEmpty ?? false) planningShown(work.coordinator!, multiline: false),
      if (mainTasks > 0) '$mainTasks ${mainTasks == 1 ? 'task' : 'tasks'}',
      work.agent.isEmpty ? 'Agent not reported' : work.agent,
      work.model.isEmpty ? 'Model not reported' : work.model,
      if (work.mode.isNotEmpty)
        switch (work.mode) {
          'UNATTENDED' => 'unattended',
          'AGENT' => 'shell with you',
          _ => work.mode,
        },
      if (work.since.isNotEmpty) 'since ${work.since}',
    ].join(' · ');
  }

  Widget _reading(PlanStory story) {
    final reading = story.issues.where((issue) => issue.state == 'Reading' || issue.state == 'Reworking');
    final stopped = story.issues.any((issue) => issue.readingStopped == true);
    final lines = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final issue in reading) Text('reading ${issue.rounds ?? '?'} of ${issue.roundsMost ?? '?'}'),
        if (stopped) const Text('stopped in Reading - needs you'),
      ],
    );
    final key = Key('planning-reading ${story.id}');
    return stopped
        ? TextButton(key: key, onPressed: widget.onNeedsYou, child: lines)
        : KeyedSubtree(key: key, child: lines);
  }

  Future<void> _start(PlanStory story, {PlanState? target}) async {
    target ??=
        board.states.where((state) => state.kind == 'WORKING' && state.name != story.state).firstOrNull ??
        board.states.where((state) => state.kind == 'WORKING').firstOrNull;
    if (target == null || board.startUnavailable(story) != null) return;
    final work = StoryWork(
      board,
      story,
      target.name,
      startTask: widget.startTask,
      agentChoices: widget.agents,
      machines: widget.machines,
      unavailableMachines: widget.unavailableMachines,
      onStarted: widget.onStarted,
    );
    try {
      await showDialog<bool>(context: context, barrierDismissible: false, builder: (_) => StoryWorkDialog(work));
    } finally {
      work.dispose();
    }
  }

  Future<void> _stop(PlanStory story) async {
    final source = board;
    final backend = source.backend;
    final project = source.project;
    final revision = source.snapshot?.revision;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Stop work on ${story.id}?'),
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
    await source.stopWork(story.id, revision: revision, source: backend, sourceProject: project);
  }

  Widget _menu(PlanStory story, {bool dense = false}) => PlanningActionMenu(
    key: Key('planning-menu ${story.id}'),
    dense: dense,
    tooltip: 'Work on ${story.id}',
    actions: [
      PlanningAction(
        id: 'planning-start ${story.id}',
        label: 'Start work…',
        unavailable: _startUnavailable(story),
        run: () => unawaited(_start(story)),
      ),
      PlanningAction(
        id: 'planning-stop ${story.id}',
        label: 'Stop work',
        unavailable: _stopUnavailable(story),
        run: () => unawaited(_stop(story)),
      ),
    ],
  );

  String? _startUnavailable(PlanStory story) =>
      board.startUnavailable(story) ??
      (!board.states.any((state) => state.kind == 'WORKING') ? 'no working state was reported' : null);

  String? _stopUnavailable(PlanStory story) =>
      board.unavailableActions['StopStoryWork'] ??
      (!board.live
          ? 'the planning board is not live'
          : board.actionBusy
          ? 'waiting for the planner'
          : board.workOf(story)?.atWork != true
          ? 'no running work was reported'
          : null);

  /// Each card as last built, with what it was built from; a board of hundreds of cards is not built anew on
  /// every change, only the cards whose inputs changed.
  final Map<String, (List<Object?>, Widget)> _cards = {};

  Widget _card(BuildContext context, PlanStory story) {
    final inputs = <Object?>[
      board,
      story,
      Theme.of(context),
      board.preferences.expandedStories.contains(story.id),
      _work(story),
      board.pending[story.id],
      board.pending[story.id]?.problem,
      board.saving,
      board.live,
      _startUnavailable(story),
      _stopUnavailable(story),
    ];
    final built = _cards[story.id];
    if (built != null && listEquals(built.$1, inputs)) return built.$2;
    final card = _buildCard(context, story);
    _cards[story.id] = (inputs, card);
    return card;
  }

  Widget _buildCard(BuildContext context, PlanStory story) {
    final theme = Theme.of(context);
    final open = board.preferences.expandedStories.contains(story.id);
    final work = _work(story);
    return _drag(
      story,
      Card(
        key: Key('planning-card ${story.id}'),
        margin: const EdgeInsets.only(bottom: Space.small),
        child: InkWell(
          key: Key('planning-story ${story.id}'),
          onTap: story.id.isEmpty ? null : () => widget.onStory(story.id),
          child: Padding(
            padding: const EdgeInsets.all(Space.normal),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: Space.small,
                  children: [
                    Text(story.id, style: theme.textTheme.labelMedium),
                    _priority(context, story.priority ?? ''),
                    _menu(story),
                  ],
                ),
                const SizedBox(height: Space.small),
                Text(story.title),
                if (board.pending[story.id] case final move? when move.problem == null) const Text('moved'),
                if (board.pending[story.id]?.problem case final problem?) Text(problem),
                if (work != null && work.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: Space.small),
                    child: Text(work, style: theme.textTheme.bodySmall),
                  ),
                if (story.issues.any(
                  (issue) => issue.state == 'Reading' || issue.state == 'Reworking' || issue.readingStopped == true,
                ))
                  _reading(story),
                if (!story.issuesReported)
                  const Text('Issues not reported')
                else
                  TextButton(
                    key: Key('planning-expand ${story.id}'),
                    onPressed: story.issues.isEmpty ? null : () => board.expand(story.id),
                    style: TextButton.styleFrom(padding: EdgeInsets.zero),
                    child: Row(
                      key: Key('planning-issues ${story.id}'),
                      children: [
                        Icon(open ? Icons.expand_more : Icons.chevron_right, size: PlanningStyle.mark),
                        Expanded(
                          child: Text(
                            story.issues.isEmpty
                                ? 'no issues yet'
                                : '${story.issues.length} ${story.issues.length == 1 ? 'issue' : 'issues'}',
                          ),
                        ),
                        if (story.issues.isNotEmpty && story.completedIssues != null) ...[
                          SizedBox(
                            width: PlanningStyle.progress,
                            child: LinearProgressIndicator(
                              value: story.completedIssues! / story.issues.length,
                              semanticsLabel: 'Issues in Review or Finished',
                            ),
                          ),
                          const SizedBox(width: Space.tight),
                          Text('${story.completedIssues}/${story.issues.length}'),
                        ],
                      ],
                    ),
                  ),
                if (story.issuesReported && story.completedIssues == null) const Text('Issue progress not reported'),
                if (open)
                  for (final issue in story.issues)
                    Padding(
                      key: Key('planning-issue ${story.id} ${issue.id}'),
                      padding: const EdgeInsets.only(top: Space.small),
                      child: Column(
                        key: Key('planning-issue-row ${issue.id}'),
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          TextButton(
                            key: Key('planning-issue ${issue.id}'),
                            onPressed: issue.id.isEmpty ? null : () => widget.onIssue(story.id, issue.id),
                            child: Text('${issue.id} · ${issue.repository}'),
                          ),
                          if (issue.title.isNotEmpty) Text(issue.title),
                          Wrap(
                            spacing: Space.small,
                            children: [
                              if (issue.state.isNotEmpty) Text(issue.state),
                              if (issue.size.isNotEmpty) Text(issue.size),
                            ],
                          ),
                          if (!issue.blockedByReported)
                            const Text('Blockers not reported')
                          else if (issue.blockedBy.isNotEmpty)
                            Text('Blocked by ${issue.blockedBy.join(', ')}'),
                        ],
                      ),
                    ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PlanningDetailView extends StatefulWidget {
  const _PlanningDetailView({
    required this.board,
    required this.story,
    required this.issue,
    required this.onBoard,
    required this.onParent,
    required this.onStory,
    required this.onIssue,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    super.key,
  });
  final PlanningBoard board;
  final Future<void> Function(Uri) openLink;
  final String story;
  final String? issue;
  final VoidCallback onBoard, onParent;
  final ValueChanged<String> onStory, onIssue, onTask;
  final String? Function(String) taskUnavailable;

  @override
  State<_PlanningDetailView> createState() => _PlanningDetailViewState();
}

class _PlanningDetailViewState extends State<_PlanningDetailView> {
  late final detail = PlanningDetail(widget.board, widget.story, widget.issue);

  @override
  void dispose() {
    detail.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: detail,
    builder: (context, _) => SingleChildScrollView(
      key: const Key('planning-detail'),
      padding: const EdgeInsets.all(Space.normal),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              TextButton(key: const Key('planning-crumb 0'), onPressed: widget.onBoard, child: const Text('Planning')),
              const Text('›'),
              TextButton(
                key: const Key('planning-crumb 1'),
                onPressed: widget.issue == null ? null : widget.onParent,
                child: Text(planningShown(widget.story, multiline: false)),
              ),
              if (widget.issue != null) ...[
                const Text('›'),
                Text(planningShown(widget.issue!, multiline: false), key: const Key('planning-crumb 2')),
              ],
            ],
          ),
          Text('Project ${widget.board.project} · From ${widget.board.backend.label}'),
          if (detail.loading) const Text('Waiting for the planner’s answer.', key: Key('planning-waiting')),
          if (widget.board.problem case final problem?) ...[
            Text(problem, key: const Key('planning-watch-error')),
            TextButton.icon(
              key: const Key('planning-retry'),
              onPressed: () => unawaited(widget.board.retry()),
              icon: const Icon(Icons.refresh),
              label: const Text('Retry planning'),
            ),
          ],
          if (!widget.board.live) const Text('Planning is no longer live. The last answer may be out of date.'),
          if (detail.problem case final problem?) ...[
            if (detail.story != null || detail.issues != null || detail.issue != null)
              const Text('Showing the last read details; the latest read failed.'),
            Text(
              problem,
              key: const Key('planning-detail-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            TextButton(
              onPressed: detail.saving ? null : () => unawaited(detail.reload()),
              child: const Text('Retry details'),
            ),
          ],
          if (widget.issue == null)
            PlanningStoryView(
              detail: detail,
              onIssue: widget.onIssue,
              onTask: widget.onTask,
              taskUnavailable: widget.taskUnavailable,
              openLink: widget.openLink,
            )
          else
            PlanningIssueView(
              detail: detail,
              onStory: widget.onStory,
              onIssue: widget.onIssue,
              onTask: widget.onTask,
              taskUnavailable: widget.taskUnavailable,
              openLink: widget.openLink,
            ),
        ],
      ),
    ),
  );
}
