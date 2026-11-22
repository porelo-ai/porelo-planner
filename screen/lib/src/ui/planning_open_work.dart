import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../hooks.dart';
import '../models.dart';
import '../open_work_filter.dart';
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

  /// The filter and sort, kept in the project's view choices across leaving Open work.
  PlanningOpenWorkFilter get _choices => board.preferences.openWork;

  late final _search = TextEditingController(text: _choices.search);

  @override
  void didUpdateWidget(PlanningOpenWorkView old) {
    super.didUpdateWidget(old);
    if (!identical(old.board, board)) _search.text = _choices.search;
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  void _searchFor(String text) => setState(() => _choices.search = text);

  void _closeSearch() => setState(() {
    _choices.closeSearch();
    _search.clear();
  });

  void _clearSearch() => setState(() {
    _choices.search = '';
    _search.clear();
  });

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

  /// Whether a batch of descriptions is being read; until all of it is in, the column shows none.
  bool _reading = false;

  /// The most descriptions read at once, so a long list does not flood the planner.
  static const _atOnce = 8;

  /// How long one description read may take before it counts as refused.
  static const _readTime = Duration(seconds: 30);

  /// Reads the descriptions not yet read of the listed stories: the first alone, so a planner that refuses is asked
  /// once, then the rest at most [_atOnce] at a time. The column shows them once all are in, never a part of them.
  Future<void> _readHolds(List<String> stories) async {
    final batch = [
      for (final id in stories)
        if (!_asked.contains(id)) id,
    ];
    if (batch.isEmpty || !mounted || board.disposed) return;
    setState(() => _reading = true);
    final read = <String, String>{};
    final refused = <String, String>{};
    Future<void> readOne(String id) async {
      _asked.add(id);
      try {
        // A read that never answers would hold the column at Reading for good, so it counts as refused.
        final reply = await board.backend.getStory(board.project, id).timeout(_readTime);
        final story = reply.story;
        if (story == null || story.id != id) throw const FormatException('The planner did not return the story.');
        read[id] = planningFirstParagraph(story.description, story.descriptionFormat);
      } catch (error) {
        refused[id] = planningFailureWords(error);
        _failedAt = board.snapshot?.revision;
      }
    }

    var next = 1;
    Future<void> readOn() async {
      while (refused.isEmpty && next < batch.length && mounted && !board.disposed) {
        await readOne(batch[next++]);
      }
    }

    try {
      await readOne(batch.first);
      await Future.wait([for (var reader = 0; reader < _atOnce; reader++) readOn()]);
    } finally {
      // A planner that refuses one read is asked no more until the board changes; the stories not reached are asked
      // for then.
      _asked.removeAll(batch.skip(next));
      if (mounted) {
        setState(() {
          _reading = false;
          _holds.addAll(read);
          for (final id in read.keys) {
            _unread.remove(id);
          }
          _unread.addAll(refused);
        });
      }
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
      final shown = list == null ? null : _choices.shown(list.rows);
      if (shown != null) _askHolds(shown);
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
            if (list == null || shown == null)
              const Text('Reading the planning…')
            else ...[
              Row(
                spacing: Space.small,
                children: [
                  Expanded(
                    child: Text(
                      [
                        '${list.rows.length} ${list.rows.length == 1 ? 'story' : 'stories'} listed',
                        if (_choices.filtering) '${shown.length} ${shown.length == 1 ? 'matches' : 'match'} the filter',
                        if (list.finished > 0)
                          '${list.finished} with nothing left to build ${list.finished == 1 ? 'is' : 'are'} not listed',
                        if (!known) 'what is left is not known now, so every story is listed',
                      ].join(' · '),
                      key: const Key('planning-open-work-summary'),
                    ),
                  ),
                  ..._controls(theme),
                ],
              ),
              const SizedBox(height: Space.small),
              _header(theme),
              const Divider(height: 1),
              if (shown.isEmpty && list.rows.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: Space.normal),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Nothing matches.', style: theme.textTheme.bodySmall),
                      TextButton(
                        key: const Key('planning-open-work-search-clear'),
                        onPressed: _closeSearch,
                        child: const Text('Clear the filter'),
                      ),
                    ],
                  ),
                ),
              Expanded(
                child: ListView.separated(
                  key: const Key('planning-open-work-list'),
                  itemCount: shown.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, index) => _row(context, shown[index]),
                ),
              ),
            ],
          ],
        ),
      );
    },
  );

  /// The filter and the sort, as the window's own lists hold them in their head: a magnifier that opens the filter
  /// field, and a menu that names the order and its direction.
  List<Widget> _controls(ThemeData theme) {
    final small = theme.textTheme.bodySmall;
    return [
      if (_choices.searchOpen)
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: PlanningStyle.filter),
          child: CallbackShortcuts(
            bindings: {const SingleActivator(LogicalKeyboardKey.escape): _clearSearch},
            child: TextField(
              key: const Key('planning-open-work-search'),
              controller: _search,
              autofocus: true,
              onChanged: _searchFor,
              decoration: InputDecoration(
                isDense: true,
                hintText: 'Filter open work',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  key: const Key('planning-open-work-search-close'),
                  tooltip: 'Close the filter',
                  icon: const Icon(Icons.close),
                  onPressed: _closeSearch,
                ),
              ),
            ),
          ),
        )
      else
        IconButton(
          key: const Key('planning-open-work-search-open'),
          tooltip: 'Filter open work',
          icon: const Icon(Icons.search),
          onPressed: () => setState(() => _choices.searchOpen = true),
        ),
      PopupMenuButton<PlanningOpenWorkSort>(
        key: const Key('planning-open-work-sort'),
        tooltip: 'Sort open work',
        onSelected: (sort) => setState(() => _choices.choose(sort)),
        itemBuilder: (_) => [
          for (final sort in PlanningOpenWorkSort.values)
            PopupMenuItem(value: sort, key: Key('planning-open-work-sort-to ${sort.name}'), child: Text(sort.label)),
        ],
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(_choices.ascending ? Icons.arrow_upward : Icons.arrow_downward, size: PlanningStyle.rowIcon),
            Text(_choices.sort.label, key: const Key('planning-open-work-sort-label'), style: small),
          ],
        ),
      ),
    ];
  }

  Widget _header(ThemeData theme) {
    final style = theme.textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w600);
    Widget cell(String words, double? width, {TextAlign align = TextAlign.start}) => width == null
        ? Expanded(
            child: Text(words, style: style, textAlign: align),
          )
        : SizedBox(
            width: width,
            child: Text(words, style: style, textAlign: align),
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
          // Over its values, which stand to the right.
          cell('Left', _Width.left, align: TextAlign.end),
          cell(_reading ? 'What it holds · Reading…' : 'What it holds', null),
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
    // While a batch is read, the texts already read stay and the rows being read show nothing until all are in.
    final hint = holds ?? (_reading ? '' : unread ?? '');
    final holdsText = Text(
      holds ?? (_reading || unread == null ? '' : 'Not read'),
      key: Key('planning-open-work-holds ${story.id}'),
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
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
            SizedBox(
              width: _Width.left,
              child: Text(left, key: Key('planning-open-work-left ${story.id}'), textAlign: TextAlign.end),
            ),
            Expanded(
              child: hint.isEmpty ? holdsText : Tooltip(message: hint, child: holdsText),
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
