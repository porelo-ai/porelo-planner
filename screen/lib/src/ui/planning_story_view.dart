import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../client.dart';

import '../issue_filter.dart';
import '../models.dart';
import '../planning_board.dart';
import '../planning_detail.dart';
import '../story_groups.dart';
import '../story_work.dart';
import 'actions.dart';
import 'open_work_figures.dart';
import 'planning_correction.dart';
import 'planning_detail_widgets.dart';
import 'planning_menu.dart';
import 'planning_text.dart';
import 'planning_move_warning.dart';
import 'story_work_attention.dart';
import 'style.dart';

/// The machine's standalone story and separately read issue list.
class PlanningStoryView extends StatefulWidget {
  /// Opens issues and tasks on this story's own provider.
  const PlanningStoryView({
    required this.detail,
    required this.onIssue,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    this.onStartIssue,
    super.key,
  });

  /// Opens the start of an issue's work toward a working state; without it no issue's work starts here.
  final void Function(String issue, String to)? onStartIssue;

  /// The retained reads.
  final PlanningDetail detail;

  /// Opens an issue row.
  final ValueChanged<String> onIssue;

  /// Opens a reported task.
  final ValueChanged<String> onTask;

  /// Why a task cannot be opened on its own provider.
  final String? Function(String) taskUnavailable;

  /// Opens a web address in the person's browser.
  final Future<void> Function(Uri) openLink;

  @override
  State<PlanningStoryView> createState() => _PlanningStoryViewState();
}

class _PlanningStoryViewState extends State<PlanningStoryView> {
  PlanningDetail get detail => widget.detail;
  ValueChanged<String> get onIssue => widget.onIssue;
  ValueChanged<String> get onTask => widget.onTask;
  String? Function(String) get taskUnavailable => widget.taskUnavailable;
  Future<void> Function(Uri) get openLink => widget.openLink;
  void Function(String issue, String to)? get onStartIssue => widget.onStartIssue;

  PlanningPreferences get _preferences => detail.board.preferences;

  /// This story's search and sort, kept by the project's preferences across leaving the page.
  PlanningIssueFilter get _list => _preferences.issueList(detail.storyId);

  late final _search = TextEditingController(text: _list.search);

  @override
  void didUpdateWidget(PlanningStoryView old) {
    super.didUpdateWidget(old);
    // Another story, board or project's preferences hold another search.
    if (!identical(old.detail, detail)) _search.text = _list.search;
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  void _searchFor(String text) => setState(() => _list.search = text);

  void _closeSearch() => setState(() {
    _list.closeSearch();
    _search.clear();
  });

  void _clearSearch() => setState(() {
    _list.search = '';
    _search.clear();
  });

  @override
  Widget build(BuildContext context) {
    final boardStory = detail.boardStory;
    final story = detail.story?.story ?? boardStory;
    final issues = detail.issues?.issues ?? boardStory?.issues;
    final reported = detail.issues?.reported ?? boardStory?.issuesReported ?? false;
    final supplied = detail.issues?.supplied ?? boardStory?.issuesReported ?? false;
    final shown = issues == null
        ? const <PlanIssue>[]
        : _list.shown(issues, [for (final state in detail.board.states) state.name]);
    final board = detail.board;
    final groups = groupStoryIssues(
      issues: shown,
      // Every issue of the page too, so a blocker a search hides or the board's list lacks still blocks.
      board: [...?issues, ...board.stories.expand((story) => story.issues), ...board.noStory],
      states: board.states,
      work: board.snapshot?.work ?? const [],
      storyWork: story?.work,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (story == null && !detail.loading && detail.storyProblem == null) const Text('Story not reported.'),
        if (story != null) ...[
          Wrap(
            spacing: Space.small,
            children: [
              Text(planningShown(story.id)),
              _priority(story.id, story.priority),
              Chip(label: Text(planningValue(story.state))),
              ?planningWarningMark(story.warnings, key: Key('planning-warned ${story.id}')),
              ?planningCorrectionOffer(
                board: detail.board,
                id: story.id,
                story: true,
                state: story.state,
                warnings: story.warnings,
                link: story.link,
                openLink: openLink,
              ),
              ?planningLabels(story.id, story.labels),
              PlanningLabelsEdit(
                item: story.id,
                labels: story.labels,
                unavailable: detail.labelsSaving
                    ? 'waiting for the planner'
                    : detail.board.labelsUnavailable(story.id, story: true),
                onSave: (labels) => unawaited(detail.setLabels(story.id, labels, story: true)),
              ),
            ],
          ),
          if (detail.labelsProblem case final problem?)
            Text(
              problem,
              key: const Key('planning-labels-problem'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          Text(planningValue(story.title), style: Theme.of(context).textTheme.headlineSmall),
          PlanningWebLink(link: story.link, label: 'Open story', openLink: openLink),
          _head(context, story),
          if (detail.story != null) _description(context, story),
        ],
        planningSection(context, 'Issues', heading: _issuesHeading(context, issues), [
          if (detail.moveUnavailable case final reason? when !detail.loading && !detail.saving) Text(reason),
          if (detail.moveProblem case final problem?) Text(problem, key: const Key('planning-move-error')),
          if (detail.moveNotice case final notice?) Text(notice, key: const Key('planning-move-notice')),
          PlanningMoveWarningPanel(board: detail.board),

          if (issues == null && !detail.loading && detail.issuesProblem == null) const Text('Issues not reported'),
          if (issues != null) ...[
            if (!reported)
              Text(supplied ? 'Issues not reported completely' : 'Issues not reported')
            else if (issues.isEmpty)
              Text('No issues yet.', style: Theme.of(context).textTheme.bodySmall),
            if (issues.isNotEmpty && shown.isEmpty) ...[
              Text(
                'Nothing matches.',
                key: const Key('planning-issues-none-match'),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  key: const Key('planning-issue-search-clear'),
                  onPressed: _clearSearch,
                  child: const Text('Clear the filter'),
                ),
              ),
            ] else if (issues.isNotEmpty)
              for (final group in groups) ..._group(context, group),
          ],
        ]),
        if (story != null && detail.story != null)
          planningSection(context, 'Work', [
            if (story.work == null)
              const Text('Work not reported')
            else ...[
              Text(switch (story.work!.atWork) {
                true => 'At work',
                false => 'Nobody at work',
                null => 'Work status not reported',
              }),
              if (story.work!.coordinator case final coordinator? when coordinator.trim().isNotEmpty)
                Text('Coordinator: ${planningShown(coordinator, multiline: false)}'),
              Text('Agent: ${planningValue(story.work!.agent)} · Model: ${planningValue(story.work!.model)}'),
              Text('Mode: ${planningWorkMode(story.work!.mode)}'),
              if (story.work!.since.isNotEmpty) Text('Since ${story.work!.since}'),
              for (final task in story.work!.tasks)
                PlanningTaskLink(
                  name: task.task,
                  onOpen: () => onTask(task.task),
                  unavailable: taskUnavailable(task.task),
                  run: task,
                ),
            ],
          ]),
        ListenableBuilder(listenable: detail.board, builder: (context, _) => _attention(context)),
      ],
    );
  }

  /// The groups whose fold the person turned from its default on this page.
  final Set<String> _turned = {};

  /// What is still open of the story, from the planner's count alone, read unknown where the board's figures are:
  /// a count known before a save stays until the planner's read after it replaces it.
  Widget _head(BuildContext context, PlanStory story) {
    final board = detail.board;
    // A read under way still shows the previous count beside groups of a newer board.
    final known = planningFiguresKnown(board) && !detail.loading;
    final figures = StoryHeadFigures(known ? story.open : null, board.states, board.snapshot?.work ?? const []);
    final theme = Theme.of(context);
    final minutes = switch ((figures.left, figures.minutes)) {
      (null, _) => storyFigureUnknown,
      (0, _) => 'nothing left',
      (_, null) => storyFigureUnknown,
      (_, final minutes?) => [
        '≈ ${storyMinutesText(minutes)}',
        if (figures.unestimated case final count? when count > 0) '+ $count without minutes',
      ].join(' '),
    };
    Widget figure(String label, String value, String key) => Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: theme.textTheme.labelSmall),
        Text(value, key: Key(key), style: theme.textTheme.titleMedium),
      ],
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: Space.small),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: Space.small,
        children: [
          Wrap(
            spacing: Space.wide,
            runSpacing: Space.small,
            children: [
              figure('Still to build', switch ((figures.left, figures.total)) {
                (final left?, final total?) when left <= total => '$left of $total',
                // An issue in a state the workflow lacks is left but in no state of the total.
                (final left?, _) => '$left',
                _ => storyFigureUnknown,
              }, 'planning-story-left'),
              figure('Left by velocity', minutes, 'planning-story-minutes'),
              figure('Open by size', switch (figures.sizes) {
                null => storyFigureUnknown,
                [] => 'none',
                final sizes => sizes.join(' · '),
              }, 'planning-story-sizes'),
            ],
          ),
          if (figures.bar case final bar? when bar.isNotEmpty)
            Tooltip(
              message: [for (final MapEntry(:key, :value) in bar.entries) '$value ${_kindName(key)}'].join(', '),
              child: ClipRRect(
                key: const Key('planning-story-bar'),
                borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
                child: SizedBox(
                  height: Space.small,
                  child: Row(
                    children: [
                      for (final MapEntry(:key, :value) in bar.entries)
                        Expanded(
                          flex: value,
                          child: ColoredBox(color: _kindColor(key, theme.colorScheme)),
                        ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  static String _kindName(StoryGroupKind kind) => switch (kind) {
    StoryGroupKind.working => 'in work',
    StoryGroupKind.blocked => 'blocked',
    StoryGroupKind.waiting => 'waiting',
    StoryGroupKind.review => 'in review',
    StoryGroupKind.other => 'outside the workflow',
    StoryGroupKind.closed => 'closed',
  };

  static Color _kindColor(StoryGroupKind kind, ColorScheme scheme) => switch (kind) {
    StoryGroupKind.working => scheme.tertiary,
    StoryGroupKind.blocked => scheme.error,
    StoryGroupKind.waiting => scheme.outline,
    StoryGroupKind.review => scheme.primary,
    StoryGroupKind.other => scheme.secondary,
    StoryGroupKind.closed => scheme.outlineVariant,
  };

  /// One group's head, which folds and unfolds it, and its issues while it is unfolded.
  List<Widget> _group(BuildContext context, StoryIssueGroup group) {
    final theme = Theme.of(context);
    final folded = group.foldedByDefault != _turned.contains(group.key);
    final note = switch (group.kind) {
      StoryGroupKind.review => 'built and pushed, your decision',
      StoryGroupKind.other => 'in a state the workflow lacks',
      _ => '',
    };
    return [
      InkWell(
        key: Key('planning-issue-group ${group.key}'),
        onTap: () => setState(() {
          if (!_turned.remove(group.key)) _turned.add(group.key);
        }),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: Space.small),
          child: Row(
            spacing: Space.small,
            children: [
              Container(width: Space.tight, height: Space.wide, color: _kindColor(group.kind, theme.colorScheme)),
              Text(planningShown(group.label, multiline: false), style: theme.textTheme.titleSmall),
              Text('${group.rows.length}', style: theme.textTheme.bodySmall),
              if (note.isNotEmpty) Flexible(child: Text(note, style: theme.textTheme.bodySmall)),
              const Spacer(),
              Icon(folded ? Icons.expand_more : Icons.expand_less, size: PlanningStyle.rowIcon),
            ],
          ),
        ),
      ),
      if (!folded)
        // Every group's table takes the same widths from the same room, so no column moves between groups.
        LayoutBuilder(
          builder: (context, constraints) {
            final widths = _columnWidths(constraints.maxWidth);
            return SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                dataRowMaxHeight: double.infinity,
                horizontalMargin: _tableMargin,
                columnSpacing: _columnSpacing,
                columns: [
                  for (final (index, label) in _columns.indexed) DataColumn(label: _cell(widths[index], Text(label))),
                ],
                rows: [for (final row in group.rows) _row(context, group, row, widths)],
              ),
            );
          },
        ),
    ];
  }

  static const _columns = ['Issue', 'Title', 'Repository', 'Size', 'Left', 'Who', 'Waits on / next'];

  /// The fixed widths of the columns; the title's, null here, takes what room is left.
  static const _fixedWidths = <double?>[180, null, 160, 48, 72, 112, 176];

  static const double _titleWidth = 240;
  static const double _tableMargin = Space.normal;
  static const double _columnSpacing = Space.wide;

  /// Each column's width in [room]: the fixed ones as they are, the title at least its least width.
  static List<double> _columnWidths(double room) {
    final fixed = _fixedWidths.whereType<double>().fold<double>(0, (sum, width) => sum + width);
    final spacing = 2 * _tableMargin + (_columns.length - 1) * _columnSpacing;
    final title = room.isFinite ? (room - fixed - spacing).clamp(_titleWidth, double.infinity) : _titleWidth;
    return [for (final width in _fixedWidths) width ?? title];
  }

  /// A cell's content at its column's width, so a long text wraps inside it instead of widening the column.
  static Widget _cell(double width, Widget child) => SizedBox(
    width: width,
    child: Align(alignment: AlignmentDirectional.centerStart, child: child),
  );

  DataRow _row(BuildContext context, StoryIssueGroup group, StoryIssueRow row, List<double> widths) {
    final issue = row.issue;
    final error = Theme.of(context).colorScheme.error;
    // A wait the board also holds as a blocker is shown once, as the blocker.
    bool onBoard(PlanWait wait) => row.blockers.any(
      (blocker) => blocker.id == wait.reference || (blocker.link.isNotEmpty && blocker.link == wait.link),
    );
    final waits = [
      for (final blocker in row.blockers)
        _waitMark(issue.id, blocker.id, blocker.id, planningValue(blocker.state), blocker.link, error),
      if (issue.waitsOnReported)
        for (final (index, wait) in issue.waitsHolding.indexed)
          if (!onBoard(wait))
            _waitMark(
              issue.id,
              wait.reference ?? 'unreadable $index',
              wait.spoken,
              wait.stateSpoken,
              wait.link ?? '',
              error,
            ),
    ];
    final small = Theme.of(context).textTheme.bodySmall;
    final cells = <Widget>[
      Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          TextButton(
            key: Key('planning-issue ${issue.id}'),
            // An id that names another issue too opens no page, since no read by that id finds one.
            onPressed: issue.id.isEmpty || PlanWarning.namesTwice(issue.warnings) ? null : () => onIssue(issue.id),
            child: Text(planningValue(issue.id)),
          ),
          ?planningWarningMark(issue.warnings, key: Key('planning-warned-issue ${issue.id}')),
          ?planningCorrectionOffer(
            board: detail.board,
            id: issue.id,
            story: false,
            state: issue.state,
            warnings: issue.warnings,
            link: issue.link,
            openLink: openLink,
          ),
          ?planningLabels(issue.id, issue.labels),
        ],
      ),
      Text(planningValue(issue.title)),
      Text(planningValue(issue.repository)),
      Text(planningValue(issue.size)),
      Text(issue.minutesLeft == null ? '—' : storyMinutesText(issue.minutesLeft!)),
      Text(row.runAgent == null ? planningWorker(issue.worker) : planningShown(row.runAgent!, multiline: false)),
      Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!issue.blockedByReported)
            Text('Blockers not reported', key: Key('planning-blockers-unreported ${issue.id}'), style: small),
          if (!issue.waitsOnReported)
            Text('Waits not reported', key: Key('planning-waits-unreported ${issue.id}'), style: small),
          if (waits.isNotEmpty) Wrap(children: waits),
          if (group.kind == StoryGroupKind.review) const Text('Your review'),
          _issueState(context, issue),
        ],
      ),
    ];
    return DataRow(cells: [for (final (index, cell) in cells.indexed) DataCell(_cell(widths[index], cell))]);
  }

  /// A wait of [issue] on [waited], as an icon whose tooltip and semantics name [waited] and its [state]; a click
  /// opens [link] in the browser, an HTTPS link only. Without one it opens nothing but stays focusable, so its words
  /// are reached by keyboard too. [key] names it apart from the row's other waits.
  Widget _waitMark(String issue, String key, String waited, String state, String link, Color color) {
    final open = planningWebAction(
      'Open $waited in browser',
      link,
      openLink,
      onError: detail.board.reportActionProblem,
    );
    final words = 'Waits on ${planningShown(waited, multiline: false)} (${planningShown(state, multiline: false)})';
    return Semantics(
      label: open.available ? '$words, opens in the browser' : words,
      child: IconButton(
        key: Key('planning-wait $issue $key'),
        tooltip: words,
        onPressed: open.available ? open.run : () {},
        icon: const Icon(Icons.hourglass_top, size: PlanningStyle.rowIcon),
        color: color,
        padding: EdgeInsets.zero,
        style: IconButton.styleFrom(
          minimumSize: const Size.square(PlanningStyle.denseButton),
          fixedSize: const Size.square(PlanningStyle.denseButton),
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          enabledMouseCursor: open.available ? SystemMouseCursors.click : SystemMouseCursors.basic,
        ),
      ),
    );
  }

  /// The description, folded to its heading when the person folded it.
  Widget _description(BuildContext context, PlanStory story) {
    final folded = _preferences.foldedDescriptions.contains(story.id);
    return planningSection(
      context,
      'Description',
      heading: [
        IconButton(
          key: const Key('planning-description-fold'),
          tooltip: folded ? 'Unfold the description' : 'Fold the description',
          icon: Icon(folded ? Icons.expand_more : Icons.expand_less),
          onPressed: () => setState(() {
            if (!_preferences.foldedDescriptions.remove(story.id)) _preferences.foldedDescriptions.add(story.id);
          }),
        ),
      ],
      [if (!folded) PlanningText(text: story.description, format: story.descriptionFormat, openLink: openLink)],
    );
  }

  /// The issue list's head after its title: the count, the search and the sort.
  List<Widget> _issuesHeading(BuildContext context, List<PlanIssue>? issues) {
    if (issues == null || issues.isEmpty) return const [];
    final small = Theme.of(context).textTheme.bodySmall;
    return [
      Text('${issues.length}', style: small),
      if (_list.searchOpen)
        // The larger share of the room left: beside a Spacer or the sort's label it got less than its icons need.
        Expanded(
          flex: 3,
          child: Align(
            alignment: AlignmentDirectional.centerEnd,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: PlanningStyle.filter),
              child: CallbackShortcuts(
                bindings: {const SingleActivator(LogicalKeyboardKey.escape): _clearSearch},
                child: TextField(
                  key: const Key('planning-issue-search'),
                  controller: _search,
                  autofocus: true,
                  onChanged: _searchFor,
                  decoration: InputDecoration(
                    isDense: true,
                    hintText: 'Filter Issues',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: IconButton(
                      key: const Key('planning-issue-search-close'),
                      tooltip: 'Close the filter',
                      icon: const Icon(Icons.close),
                      onPressed: _closeSearch,
                    ),
                  ),
                ),
              ),
            ),
          ),
        )
      else ...[
        const Spacer(),
        IconButton(
          key: const Key('planning-issue-search-open'),
          tooltip: 'Filter issues',
          icon: const Icon(Icons.search),
          onPressed: () => setState(() => _list.searchOpen = true),
        ),
      ],
      Flexible(
        child: PopupMenuButton<PlanningIssueSort>(
          key: const Key('planning-issue-sort'),
          tooltip: 'Sort issues',
          onSelected: (sort) => setState(() => _list.choose(sort)),
          itemBuilder: (_) => [
            for (final sort in PlanningIssueSort.values)
              PopupMenuItem(value: sort, key: Key('planning-issue-sort-to ${sort.name}'), child: Text(sort.label)),
          ],
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(_list.ascending ? Icons.arrow_upward : Icons.arrow_downward, size: PlanningStyle.rowIcon),
              Flexible(
                child: Text(_list.sort.label, style: small, overflow: TextOverflow.ellipsis),
              ),
            ],
          ),
        ),
      ),
    ];
  }

  /// The run's attention for this story, from its own board, each row read only once it is opened.
  Widget _attention(BuildContext context) {
    final board = detail.board;
    final story = board.stories.where((story) => story.id == detail.storyId).firstOrNull;
    final rows = story == null ? const <PlanningAttention>[] : PlanningBoards.storyAttention(board, story);
    if (rows.isEmpty) return const SizedBox.shrink();
    final causes = {for (final issue in board.runAttention(story!)?.issues ?? const []) issue.issue: issue.causes};
    return planningSection(context, 'Needs attention', [
      for (final row in rows)
        ExpansionTile(
          key: row.issue == null ? const Key('planning-story-ended') : Key('planning-story-attention ${row.issue!.id}'),
          title: Text(
            row.issue == null
                ? 'The run ended'
                : '${row.issue!.id}: ${(causes[row.issue!.id] ?? const ['it needs attention']).join(', ')}',
          ),
          children: [StoryWorkAttentionView(row: row, resolved: false, machine: board.backend.label, openStory: false)],
        ),
    ]);
  }

  /// The story's priority as a choice of the five, the reported one marked, none when it reported none.
  Widget _priority(String id, String? priority) {
    final unavailable = detail.board.priorityUnavailable(id);
    final pending = detail.board.pendingPriorities[id];
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PopupMenuButton<String>(
          key: Key('planning-priority $id'),
          tooltip: unavailable ?? 'Change the priority of $id',
          enabled: unavailable == null,
          initialValue: planningPriorities.contains(priority) ? priority : null,
          onSelected: (chosen) {
            if (chosen != priority) unawaited(detail.board.setPriority(id, chosen));
          },
          itemBuilder: (_) => [
            for (final choice in planningPriorities)
              PopupMenuItem(value: choice, key: Key('planning-priority-to $id $choice'), child: Text(choice)),
          ],
          child: Chip(
            label: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(planningValue(priority ?? '')),
                const Icon(Icons.expand_more, size: PlanningStyle.rowIcon),
              ],
            ),
          ),
        ),
        if (pending != null) Text('Pending: $pending', key: Key('planning-priority-pending $id')),
        if (unavailable != null && pending == null && detail.board.live) Text(unavailable),
      ],
    );
  }

  String? _moveUnavailable(PlanIssue issue) =>
      detail.moveUnavailable ??
      (detail.board.waitsOnItsSave('issue ${issue.id}')
          ? 'Saving…'
          : issue.id.isEmpty
          ? 'Issue identifier not reported.'
          : issue.state.isEmpty
          ? 'Issue state not reported.'
          : detail.moveStatesOf(issue).isEmpty && _startStatesOf(issue).isEmpty
          ? 'No waiting or closed destinations were reported.'
          : null);

  /// The working states an agent's issue may be started toward; a person's issue is moved there by hand instead.
  /// An issue the planner holds starts toward none.
  List<PlanState> _startStatesOf(PlanIssue issue) =>
      onStartIssue == null || issue.byHand || PlanWarning.holding(issue.warnings)
      ? const []
      : detail.board.states.where((state) => state.kind == 'WORKING' && state.name != issue.state).toList();

  Widget _issueState(BuildContext context, PlanIssue issue) => Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      PlanningMenu(
        entries: (_) => _issueStateEntries(issue),
        builder: (_, toggle) => Tooltip(
          message: _moveUnavailable(issue) ?? 'Change the state of ${issue.id}',
          child: InkWell(
            key: Key('planning-move ${issue.id}'),
            onTap: _moveUnavailable(issue) == null ? toggle : null,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                // A long state name wraps inside its column's width.
                Flexible(child: Text(planningValue(issue.state))),
                const Icon(Icons.expand_more, size: PlanningStyle.rowIcon),
              ],
            ),
          ),
        ),
      ),
      if (_moveUnavailable(issue) case final reason?) Text(reason),
      if (detail.pendingMoves[issue.id] case final move?)
        Text(switch (detail.board.movingWords('issue ${issue.id}')) {
          'Saving…' || null => 'Saving… ${move.to}',
          final words => '$words ${move.to}',
        }, key: Key('planning-move-pending ${issue.id}')),
      if (detail.board.refusalOf('issue ${issue.id}') case final words?)
        Text(words, key: Key('planning-move-refused ${issue.id}')),
    ],
  );

  /// The moves and starts [issue] is offered, in a group each, or one group's alone without it. Each entry carries the
  /// state and the board's revision shown when the menu opened; a rebuild while it stands open changes no entry.
  List<Widget> _issueStateEntries(PlanIssue issue) {
    final shown = issue.state;
    final revision = detail.board.snapshot?.revision ?? '';
    final moves = <Widget>[
      for (final state in detail.moveStatesOf(issue))
        MenuItemButton(
          key: Key('planning-move-to ${issue.id} ${state.name}'),
          onPressed: () => unawaited(detail.board.moveIssue(issue.id, state.name, shown: shown, revision: revision)),
          child: Text(state.name),
        ),
    ];
    final starts = <Widget>[
      for (final state in _startStatesOf(issue))
        MenuItemButton(
          key: Key('planning-start-issue ${issue.id} ${state.name}'),
          onPressed: () => onStartIssue?.call(issue.id, state.name),
          child: Text('Start ${state.name}…'),
        ),
    ];
    if (moves.isEmpty || starts.isEmpty) return [...moves, ...starts];
    return [
      SubmenuButton(menuChildren: moves, child: const Text('Move to')),
      SubmenuButton(menuChildren: starts, child: const Text('Start')),
    ];
  }
}
