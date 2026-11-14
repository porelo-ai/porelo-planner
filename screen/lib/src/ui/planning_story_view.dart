import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../client.dart';

import '../issue_filter.dart';
import '../models.dart';
import '../planning_board.dart';
import '../planning_detail.dart';
import '../story_work.dart';
import 'planning_detail_widgets.dart';
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
    final story = detail.story?.story;
    final issues = detail.issues;
    final completed = issues != null && issues.reported && issues.issues.every((issue) => issue.state.isNotEmpty)
        ? issues.issues.where((issue) => {'Review', 'Finished'}.contains(issue.state)).length
        : null;
    final shown = issues == null
        ? const <PlanIssue>[]
        : _list.shown(issues.issues, [for (final state in detail.board.states) state.name]);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (story == null && !detail.loading && detail.storyProblem == null) const Text('Story not reported.'),
        if (story != null) ...[
          Wrap(
            spacing: Space.small,
            children: [
              Text(story.id),
              _priority(story.id, story.priority),
              Chip(label: Text(planningValue(story.state))),
              ?planningGivenMark(story, onFollow: (to) => unawaited(detail.board.followIssues(story.id, to))),
            ],
          ),
          Text(planningValue(story.title), style: Theme.of(context).textTheme.headlineSmall),
          PlanningWebLink(link: story.link, label: 'Open story', openLink: openLink),
          _description(context, story),
        ],
        planningSection(context, 'Issues', heading: _issuesHeading(context, issues), [
          if (detail.moveUnavailable case final reason? when !detail.loading && !detail.saving) Text(reason),
          if (detail.moveProblem case final problem?) Text(problem, key: const Key('planning-move-error')),
          if (detail.moveNotice case final notice?) Text(notice, key: const Key('planning-move-notice')),
          PlanningMoveWarningPanel(board: detail.board),

          if (issues == null && !detail.loading && detail.issuesProblem == null) const Text('Issues not reported'),
          if (issues != null) ...[
            if (!issues.reported)
              Text(issues.supplied ? 'Issues not reported completely' : 'Issues not reported')
            else if (issues.issues.isEmpty)
              Text('No issues yet.', style: Theme.of(context).textTheme.bodySmall)
            else if (completed != null)
              Text('$completed of ${issues.issues.length} in Review or Finished')
            else
              const Text('Issue progress not reported'),
            if (issues.issues.isNotEmpty && shown.isEmpty) ...[
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
            ] else if (issues.issues.isNotEmpty)
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: DataTable(
                  dataRowMaxHeight: double.infinity,
                  columns: [
                    for (final label in ['Issue', 'Title', 'Repository', 'Size', 'Worker', 'State'])
                      DataColumn(label: Text(label)),
                  ],
                  rows: [
                    for (final issue in shown)
                      DataRow(
                        cells: [
                          DataCell(
                            TextButton(
                              key: Key('planning-issue ${issue.id}'),
                              onPressed: issue.id.isEmpty ? null : () => onIssue(issue.id),
                              child: Text(planningValue(issue.id)),
                            ),
                          ),
                          DataCell(Text(planningValue(issue.title))),
                          DataCell(Text(planningValue(issue.repository))),
                          DataCell(Text(planningValue(issue.size))),
                          DataCell(Text(planningWorker(issue.worker))),
                          DataCell(_issueState(context, issue)),
                        ],
                      ),
                  ],
                ),
              ),
          ],
        ]),
        if (story != null)
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
                ),
            ],
          ]),
        ListenableBuilder(listenable: detail.board, builder: (context, _) => _attention(context)),
      ],
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
  List<Widget> _issuesHeading(BuildContext context, PlanIssueList? issues) {
    if (issues == null || issues.issues.isEmpty) return const [];
    final small = Theme.of(context).textTheme.bodySmall;
    return [
      Text('${issues.issues.length}', style: small),
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
      (issue.id.isEmpty
          ? 'Issue identifier not reported.'
          : issue.state.isEmpty
          ? 'Issue state not reported.'
          : detail.moveStatesOf(issue).isEmpty && _startStatesOf(issue).isEmpty
          ? 'No waiting or closed destinations were reported.'
          : null);

  /// The working states an agent's issue may be started toward; a person's issue is moved there by hand instead.
  List<PlanState> _startStatesOf(PlanIssue issue) => onStartIssue == null || issue.byHand
      ? const []
      : detail.board.states.where((state) => state.kind == 'WORKING' && state.name != issue.state).toList();

  Widget _issueState(BuildContext context, PlanIssue issue) => Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      PopupMenuButton<(String, String, bool, String)>(
        key: Key('planning-move ${issue.id}'),
        tooltip: _moveUnavailable(issue) ?? 'Change the state of ${issue.id}',
        enabled: _moveUnavailable(issue) == null,
        // Each item carries the state and the board's revision shown when the menu opened; a rebuild while it stands
        // open changes no item.
        onSelected: (choice) => choice.$3
            ? onStartIssue?.call(issue.id, choice.$2)
            : unawaited(detail.board.moveIssue(issue.id, choice.$2, shown: choice.$1, revision: choice.$4)),
        itemBuilder: (_) => [
          for (final state in detail.moveStatesOf(issue))
            PopupMenuItem(
              value: (issue.state, state.name, false, detail.board.snapshot?.revision ?? ''),
              key: Key('planning-move-to ${issue.id} ${state.name}'),
              child: Text(state.name),
            ),
          for (final state in _startStatesOf(issue))
            PopupMenuItem(
              value: (issue.state, state.name, true, detail.board.snapshot?.revision ?? ''),
              key: Key('planning-start-issue ${issue.id} ${state.name}'),
              child: Text('Start ${state.name}…'),
            ),
        ],
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(planningValue(issue.state)),
            const Icon(Icons.expand_more, size: PlanningStyle.rowIcon),
          ],
        ),
      ),
      if (_moveUnavailable(issue) case final reason?) Text(reason),
      if (detail.pendingMoves[issue.id] case final move?)
        Text('Saving… ${move.to}', key: Key('planning-move-pending ${issue.id}')),
    ],
  );
}
