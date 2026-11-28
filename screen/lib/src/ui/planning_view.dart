import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../client.dart';
import '../forge_access_offer.dart';
import '../hooks.dart';
import '../items_directory.dart';
import '../models.dart';
import '../planning_board.dart';
import '../planning_detail.dart';
import '../planning_limits.dart';
import '../story_work.dart';
import '../story_work_order.dart';
import 'actions.dart';
import 'board_filter.dart';
import 'new_issue_dialog.dart';
import 'open_work_figures.dart';
import 'planning_correction.dart';
import 'planning_detail_widgets.dart';
import 'planning_move_warning.dart';
import 'planning_issue_view.dart';
import 'planning_issue_row.dart';
import 'planning_no_story_card.dart';
import 'planning_open_work.dart';
import 'planning_story_view.dart';
import 'story_work_wizard.dart';
import 'style.dart';

final _planningLeaveDialogs = Expando<bool>();

/// Asks before leaving while a move's save is still out; it must be answered or seen on the board first.
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
                      ? 'A move is still being saved'
                      : 'Planning saved',
                ),
                content: Text(
                  saving
                      ? 'Wait for the planner’s answer before leaving.'
                      : !pending
                      ? 'The planning changes are saved. You can continue.'
                      : quitting
                      ? 'The planner has not shown the move on the board yet. Close anyway?'
                      : 'The planner has not shown the move on the board yet. Leave anyway?',
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
                      child: Text(quitting ? 'Close anyway' : 'Leave anyway'),
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
    this.wizards,
    this.pageButton,
    this.pageNote,
    this.limits,
    this.chooseDirectory,
    super.key,
  });

  /// The window's folder dialog, through which a local project's planning directory is chosen; without one the
  /// screen takes a typed path.
  final PlanningChooseDirectory? chooseDirectory;

  /// The factors the screen's own limits follow, the shown board's when null.
  final PlanningLimits? limits;

  /// The window's own words for the page, such as a first visit's explanation, shown under the board's head row and
  /// under a story's or an issue's breadcrumb; none when null.
  final Widget? pageNote;

  /// The window's own button for the page, such as its help, shown beside the screen's title: after the board's head
  /// and at the end of a story's or an issue's breadcrumb; none when null.
  final Widget? pageButton;

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

  /// The window's wizard that shows Start work's steps, or null for the screen's own frame.
  final PlanningWizards? wizards;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: Listenable.merge([boards, host]),
    builder: (context, _) {
      for (final board in boards.sources) {
        board.startTask = startTask;
        board.taskChanges = taskChanges;
        // A window that handed the boards no start hooks still starts from a row once the screen was shown.
        board.startHooks ??= PlanningStartHooks(
          startTask: startTask,
          agents: agents,
          wizards: wizards,
          machines: boards.machinesOf,
          unavailableMachines: boards.unavailableMachinesOf,
        );
      }
      final place = host.place;
      final detailBoard = boards.at(place.source, place.project);
      final pinned = place.story != null;
      final board = pinned ? detailBoard : boards.selected;
      if (board == null) {
        return Column(
          children: [
            Row(
              children: [
                TextButton(
                  key: const Key('planning-crumb 0'),
                  onPressed: () => host.go(PlanningPlace(view: place.view, filter: place.filter)),
                  child: const Text('Board'),
                ),
                ?pageButton,
              ],
            ),
            ?pageNote,
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
      // Another project keeps the view and filter, as the window's history keeps them.
      void select(String project) {
        boards.select(project);
        final selected = boards.selected;
        host.go(
          PlanningPlace(
            project: project,
            source: selected == null ? null : boards.sourceOf(selected),
            view: host.place.view,
            filter: host.place.filter,
          ),
        );
      }

      return Stack(
        fit: StackFit.expand,
        children: [
          Offstage(
            offstage: place.story != null,
            child: switch (place.view) {
              PlanningView.board => _BoardView(
                key: ObjectKey(board),
                // Kept mounted under a detail page, the board leaves the window's widgets to that page, built once.
                pageButton: place.story == null ? pageButton : null,
                pageNote: place.story == null ? pageNote : null,
                board: board,
                host: host,
                filter: place.filter,
                projects: boards.projects,
                select: select,
                providerProblems: boards.problemsFor(board),
                onNeedsYou: host.openNeedsYou,
                onStarted: taskChanges.started,
                startTask: startTask,
                agents: agents,
                wizards: wizards,
                unavailableMachines: boards.unavailableMachinesOf(board),
                machines: boards.machinesOf(board),
                chooseDirectory: chooseDirectory,
                onStory: (story) => open(board, story),
                onIssue: (story, issue) => open(board, story, issue),
              ),
              PlanningView.openWork => PlanningOpenWorkView(
                key: ObjectKey(board),
                limits: limits ?? board.limits,
                pageButton: place.story == null ? pageButton : null,
                pageNote: place.story == null ? pageNote : null,
                board: board,
                host: host,
                projects: boards.projects,
                select: select,
                providerProblems: boards.problemsFor(board),
                onNeedsYou: host.openNeedsYou,
                onStarted: taskChanges.started,
                startTask: startTask,
                agents: agents,
                wizards: wizards,
                unavailableMachines: boards.unavailableMachinesOf(board),
                machines: boards.machinesOf(board),
                chooseDirectory: chooseDirectory,
                onStory: (story) => open(board, story),
                onIssue: (story, issue) => open(board, story, issue),
              ),
            },
          ),
          if (place.story != null && detailBoard != null)
            _PlanningDetailView(
              key: ValueKey((detailBoard, place.story, place.issue)),
              pageButton: pageButton,
              pageNote: pageNote,
              startTask: startTask,
              agents: agents,
              wizards: wizards,
              machines: boards.machinesOf(detailBoard),
              unavailableMachines: boards.unavailableMachinesOf(detailBoard),
              onStarted: taskChanges.started,
              board: detailBoard,
              story: place.story!,
              issue: place.issue,
              onBoard: () => host.go(
                PlanningPlace(
                  project: detailBoard.project,
                  source: boards.sourceOf(detailBoard),
                  view: place.view,
                  filter: place.filter,
                ),
              ),
              onParent: () => open(detailBoard, place.story!),
              onStory: (story) => open(detailBoard, story),
              onIssue: (issue) => open(detailBoard, place.story!, issue),
              onIssueOf: (story, issue) => open(detailBoard, story, issue),
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
    required this.pageButton,
    required this.pageNote,
    required this.board,
    required this.host,
    required this.filter,
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
    required this.chooseDirectory,
    super.key,
  });
  final PlanningChooseDirectory? chooseDirectory;
  final Widget? pageButton;
  final Widget? pageNote;
  final PlanningBoard board;
  final PlanningHost host;

  /// The filter the place asks for, or null for every story.
  final PlanningFilter? filter;
  final List<String> projects;
  final ValueChanged<String> select;
  final List<String> providerProblems;
  final VoidCallback onNeedsYou;

  final ValueChanged<String> onStory;
  final void Function(String, String) onIssue;
  final Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>) onStarted;
  final PlanningStartTask startTask;
  final PlanningAgents agents;
  final PlanningWizards? wizards;
  final Map<PlanningBoard, String> machines;
  final List<String> unavailableMachines;
  @override
  State<_BoardView> createState() => _BoardViewState();
}

class _BoardViewState extends State<_BoardView> {
  late final _search = TextEditingController(text: board.preferences.search);

  /// Whether the search field is open; it opens on its button, and stands open while a search is kept.
  late bool _searchOpen = board.preferences.search.isNotEmpty;
  Key _project = UniqueKey();
  bool _faultsOpen = false;
  bool _rulesOpen = false;
  PlanningBoard get board => widget.board;

  /// Each phase's own vertical scroll, by phase name, starting where the person left it.
  final Map<String, ScrollController> _phaseScrolls = {};

  /// Each column at the board's end for a state no phase shows, by the state's value; a map of its own, so no phase's
  /// name can share a column's scroll.
  final Map<String, ScrollController> _unplacedScrolls = {};
  late final _horizontal = ScrollController(
    initialScrollOffset: board.preferences.horizontalOffset,
    keepScrollOffset: false,
  )..addListener(_rememberScroll);
  final _top = ScrollController();
  final _panel = GlobalKey();
  Object? _asked;

  void _rememberScroll() {
    if (_horizontal.hasClients) board.preferences.horizontalOffset = _horizontal.offset;
  }

  /// The vertical scroll of the column [id], which remembers its offset under [remember]; a folded phase has a
  /// scroll of its own, so its short column never clamps the open one's.
  ScrollController _phaseScroll(String id, {required String remember, bool folded = false}) =>
      _phaseScrolls.putIfAbsent(folded ? '$id folded' : id, () {
        late final ScrollController controller;
        controller = _RememberedScroll(() => folded ? 0 : board.preferences.phaseOffsets[remember] ?? 0)
          ..addListener(() {
            if (!folded && controller.hasClients) board.preferences.phaseOffsets[remember] = controller.offset;
          });
        return controller;
      });

  /// The vertical scroll of the column for [state], a state no phase shows, which remembers its offset by the value.
  ScrollController _unplacedScroll(String state) => _unplacedScrolls.putIfAbsent(state, () {
    late final ScrollController controller;
    controller = _RememberedScroll(() => board.preferences.unplacedOffsets[state] ?? 0)
      ..addListener(() {
        if (controller.hasClients) board.preferences.unplacedOffsets[state] = controller.offset;
      });
    return controller;
  });

  /// The key name of the phase at [index]: its name, and for a later phase of a name already shown its index too, so
  /// no two columns share a key or a scroll.
  String _phaseId(int index) {
    final name = board.phases[index].name;
    final first = board.phases.indexWhere((phase) => phase.name == name);
    return first == index ? name : '$name #$index';
  }

  /// Searches, and once the frame stands brings the first card holding a found issue into view; only a change of the
  /// search scrolls, so scrolling by hand is not taken back on a redraw.
  void _searchFor(String query) {
    board.search(query);
    final found = board.foundIssues;
    if (found.isEmpty) {
      _shownFound = null;
      return;
    }
    final generation = ++_searches;
    // The first found card in a state that shows its cards; a folded state shows only its heading.
    for (final (at, phase) in board.phases.indexed) {
      final folded = board.preferences.foldedPhases.contains(phase.name);
      final shown = [
        for (final state in board.statesIn(phase))
          if (folded || !board.preferences.foldedStates.contains(state.name)) ..._shown(state.name),
      ];
      final index = shown.indexWhere((story) => story.issues.any((issue) => found.contains(issue.id)));
      if (index < 0) continue;
      _reveal(() => _phaseScrolls[folded ? '${_phaseId(at)} folded' : _phaseId(at)], shown, index, generation);
      return;
    }
    for (final column in _unplacedColumns()) {
      final index = column.stories.indexWhere((story) => story.issues.any((issue) => found.contains(issue.id)));
      if (index >= 0) {
        _reveal(() => _unplacedScrolls[column.state], column.stories, index, generation);
        return;
      }
    }
    _shownFound = null;
  }

  /// Each search's number, so a scroll toward an older search's card stops once a newer search began.
  int _searches = 0;

  /// Brings the card at [index] of the column's [stories] into view, the column's scroll found by [scroll], unless it
  /// is the card the search showed last.
  void _reveal(ScrollController? Function() scroll, List<PlanStory> stories, int index, int generation) {
    final key = Key(_cardKey(stories[index]));
    // The same first card again is left where the person scrolled it.
    if (key == _shownFound) return;
    final cards = [for (final story in stories) Key(_cardKey(story))];
    WidgetsBinding.instance.addPostFrameCallback((_) => _step(scroll, cards, index, generation, 0));
  }

  /// Scrolls the column toward the card at [index] of its [cards], frame by frame, until the card stands in view; a
  /// column builds only the cards near its view, and their heights differ, so one jump rarely lands.
  void _step(ScrollController? Function() scroll, List<Key> cards, int index, int generation, int tries) {
    if (!mounted || generation != _searches || tries > 60) return;
    final controller = scroll();
    if (controller == null || !controller.hasClients) return;
    final position = controller.position;
    final viewport = position.context.notificationContext?.findRenderObject();
    if (viewport is! RenderBox || !viewport.hasSize) return;
    final viewTop = viewport.localToGlobal(Offset.zero).dy;
    final built = _elementsKeyed(context, cards.toSet());
    // Each built card with where its top stands in the column's view.
    final shown = [
      for (final (at, key) in cards.indexed)
        if (built[key]?.findRenderObject() case final RenderBox box when box.hasSize && box.attached)
          (at, box.localToGlobal(Offset.zero).dy - viewTop, box.size.height),
    ];
    final double target;
    final hit = shown.where((card) => card.$1 == index).firstOrNull;
    if (hit != null) {
      // A card taller than the view stands in it once its top does.
      final fits = hit.$2 + hit.$3 <= position.viewportDimension || hit.$2 <= Space.normal;
      if (hit.$2 >= 0 && fits) {
        _shownFound = cards[index];
        return;
      }
      target = hit.$2;
    } else if (shown.isEmpty) {
      target = position.viewportDimension;
    } else {
      final mean = shown.fold<double>(0, (sum, card) => sum + card.$3) / shown.length;
      // From the nearest built card on the card's side, so the step always goes toward it.
      final (at, top, height) = index > shown.last.$1 ? shown.last : shown.first;
      target = index > at ? top + height + (index - at - 1) * mean : top - (at - index) * mean;
    }
    final next = (position.pixels + target - Space.normal).clamp(position.minScrollExtent, position.maxScrollExtent);
    if (next == position.pixels) return;
    controller.jumpTo(next);
    WidgetsBinding.instance.addPostFrameCallback((_) => _step(scroll, cards, index, generation, tries + 1));
    WidgetsBinding.instance.scheduleFrame();
  }

  /// The card the search last brought into view, so a further key finding it again does not scroll; set only once it
  /// stands in view, so a search that a newer one cut short is taken up again.
  Key? _shownFound;

  /// The elements under [root] whose widgets carry one of [keys].
  static Map<Key, BuildContext> _elementsKeyed(BuildContext root, Set<Key> keys) {
    final found = <Key, BuildContext>{};
    void visit(Element element) {
      final key = element.widget.key;
      if (key != null && keys.contains(key)) {
        found.putIfAbsent(key, () => element);
      } else {
        element.visitChildElements(visit);
      }
    }

    root.visitChildElements(visit);
    return found;
  }

  @override
  void dispose() {
    for (final controller in [..._phaseScrolls.values, ..._unplacedScrolls.values]) {
      controller.dispose();
    }
    _hovered.dispose();
    _horizontal.dispose();
    _top.dispose();
    _search.dispose();
    super.dispose();
  }

  /// The workflow's filters, read anew with each build of the board.
  PlanningBoardFilter _filters = PlanningBoardFilter(const [], const {});

  /// The filter the board can use of the one the place asks for, or null.
  PlanningFilter? _chosen;

  /// Whether the board's figures say what is open now.
  bool _known = false;

  /// Whether the planner counts open work at all: it sent figures, or a move made the figures it sent stale until the
  /// next read; a planner that counts none gets no filter row, summary or card figures.
  bool _offered = false;

  /// The filter applied to the cards: none while the figures are stale, so no story is kept or hidden by them.
  PlanningFilter? get _applied => _known ? _chosen : null;

  /// The filter last reported back as unusable, so it is reported once.
  PlanningFilter? _fellBack;

  /// Each state's cards under the applied filter, with the cards and filter they were taken from.
  final Map<String, (List<PlanStory>, PlanningFilter?, List<PlanStory>)> _filtered = {};

  /// The cards of the state [name] the applied filter keeps.
  List<PlanStory> _shown(String name) {
    final cards = board.cards(name);
    final applied = _applied;
    if (applied == null) return cards;
    final kept = _filtered[name];
    if (kept != null && identical(kept.$1, cards) && kept.$2 == applied) return kept.$3;
    final shown = [
      for (final story in cards)
        if (_filters.keeps(story, applied)) story,
    ];
    _filtered[name] = (cards, applied, shown);
    return shown;
  }

  /// The columns at the board's end, one for each state no phase shows that a story the applied filter keeps, or an
  /// issue in no story, stands in: by the state's value, and no state last; each with its stories.
  List<({String state, List<PlanStory> stories})> _unplacedColumns() {
    final stories = <String, List<PlanStory>>{};
    for (final story in board.unplaced) {
      if (_filters.keeps(story, _applied)) (stories[board.displayedState(story)] ??= []).add(story);
    }
    final states = {
      ...stories.keys,
      for (final state in board.noStoryUnplaced)
        if (_noStoryShown(state)) state,
    }.toList()..sort((a, b) => a.isEmpty != b.isEmpty ? (a.isEmpty ? 1 : -1) : a.compareTo(b));
    return [for (final state in states) (state: state, stories: stories[state] ?? const [])];
  }

  /// Whether the card of issues in no story stands in the state [name]: under a filter only in the states it keeps,
  /// since those issues have no story whose figures could match.
  bool _noStoryShown(String name) => _applied == null || _filters.states(_applied!).contains(name);

  /// Every story the board shows under its priority filter and search, before the open-work filter.
  List<PlanStory> _listed() {
    final placed = {for (final phase in board.phases) ...phase.states};
    final names = <String>{};
    return [
      for (final state in board.states)
        if (placed.contains(state.name) && names.add(state.name)) ...board.cards(state.name),
      ...board.unplaced,
    ];
  }

  /// Takes the place's filter for this board; one the workflow cannot use falls back to none, reported to the host.
  void _readFilter() {
    _filters = PlanningBoardFilter(
      board.states,
      planningReviewStates(board.snapshot),
      storyReview: planningStoryReviewState(board.snapshot),
    );
    final snapshot = board.snapshot;
    _offered =
        snapshot != null &&
        (snapshot.open != null ||
            snapshot.stories.any((story) => story.open != null) ||
            // Once the planner sent figures, the row stays, also where a board read later names none.
            _offered);
    _known = _offered && planningFiguresKnown(board) && snapshot?.open != null;
    final asked = widget.filter;
    if (asked == null || board.snapshot?.statesReported != true || _filters.usable(asked)) {
      _chosen = asked;
      _fellBack = null;
      return;
    }
    _chosen = null;
    if (_fellBack == asked) return;
    _fellBack = asked;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final host = widget.host;
      if (mounted && host.place.filter == asked) host.go(host.place.copyWith(filter: () => null));
    });
  }

  void _pick(PlanningFilter? filter) {
    final host = widget.host;
    if (host.place.filter != filter) host.go(host.place.copyWith(filter: () => filter));
  }

  /// The story filters, each with the stories it keeps; while the figures are stale no count is told.
  List<_FilterEntry> _entries() {
    final listed = _listed();
    final counts = _filters.counts(listed);
    String count(PlanningFilterCount? count) => !_known || count == null
        ? '?'
        : count.partial
        ? '${count.stories}+'
        : '${count.stories}';
    String hint(PlanningFilter filter, PlanningFilterCount? count) {
      final states = _filters.states(filter);
      final what = switch (filter) {
        PlanningFilter.openWork => 'issues left to build',
        PlanningFilter.blocked => 'issues an open issue blocks',
        PlanningFilter.review => '',
        _ => 'issues in ${states.map(_line).join(', ')}',
      };
      final stories = !_known || count == null
          ? 'not known now'
          : '${count.stories} ${count.stories == 1 ? 'story' : 'stories'}'
                '${count.partial ? ', and ${count.unknown} whose open work is not known' : ''}';
      if (filter == PlanningFilter.review) {
        return 'Stories in ${_line(_filters.storyReview ?? 'review')}, waiting for your review: $stories';
      }
      return 'Stories with $what: $stories';
    }

    PlanningStateKind? kind(PlanningFilter? filter) => switch (filter) {
      null || PlanningFilter.openWork => null,
      PlanningFilter.blocked => PlanningStateKind.blocked,
      PlanningFilter.review => PlanningStateKind.review,
      PlanningFilter.implementing => PlanningStateKind.implementing,
      PlanningFilter.analysis => PlanningStateKind.analysis,
      PlanningFilter.backlog => PlanningStateKind.backlog,
      PlanningFilter.estimated => PlanningStateKind.estimated,
      PlanningFilter.idea => PlanningStateKind.idea,
    };

    return [
      (filter: null, label: 'All', number: '${listed.length}', hint: 'Every story: ${listed.length}', kind: null),
      for (final filter in _filters.filters)
        (
          filter: filter,
          label: switch (filter) {
            PlanningFilter.openWork => 'Open work',
            PlanningFilter.review => 'Needs your review',
            PlanningFilter.blocked => 'Blocked',
            PlanningFilter.implementing => 'Implementing',
            PlanningFilter.analysis => 'In analysis',
            PlanningFilter.backlog => 'Backlog',
            PlanningFilter.estimated => 'Estimated',
            PlanningFilter.idea => 'Idea',
          },
          number: count(counts[filter]),
          hint: hint(filter, counts[filter]),
          kind: kind(filter),
        ),
    ];
  }

  /// The story filter as one dropdown button naming the picked filter and its count; its menu lists every filter with
  /// its icon, name and count, the picked one marked.
  Widget _storiesMenu(List<_FilterEntry> entries) {
    final picked = entries.firstWhere((entry) => entry.filter == _chosen, orElse: () => entries.first);
    Widget icon(_FilterEntry entry) => entry.kind == null
        ? Icon(entry.filter == null ? Icons.view_list_outlined : Icons.schedule, size: PlanningStyle.rowIcon)
        : PlanningStateIcon(entry.kind!, size: PlanningStyle.rowIcon);
    const figures = TextStyle(fontWeight: FontWeight.w600, fontFeatures: [FontFeature.tabularFigures()]);
    return MenuAnchor(
      menuChildren: [
        for (final entry in entries)
          Tooltip(
            message: entry.hint,
            child: MergeSemantics(
              key: Key('planning-filter ${entry.filter?.name ?? 'all'}'),
              child: Semantics(
                checked: entry.filter == picked.filter,
                inMutuallyExclusiveGroup: true,
                child: MenuItemButton(
                  leadingIcon: icon(entry),
                  trailingIcon: Text(
                    entry.number,
                    key: Key('planning-filter-count ${entry.filter?.name ?? 'all'}'),
                    style: figures,
                  ),
                  style: entry.filter == picked.filter
                      ? MenuItemButton.styleFrom(backgroundColor: Theme.of(context).colorScheme.secondaryContainer)
                      : null,
                  onPressed: () => _pick(entry.filter),
                  child: Text(entry.label),
                ),
              ),
            ),
          ),
      ],
      builder: (context, menu, _) => Semantics(
        label: 'Show stories with',
        child: OutlinedButton(
          key: const Key('planning-stories'),
          style: OutlinedButton.styleFrom(
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.symmetric(horizontal: Space.normal),
          ),
          onPressed: () => menu.isOpen ? menu.close() : menu.open(),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            spacing: Space.tight,
            children: [
              icon(picked),
              const Text('Stories:'),
              Flexible(
                child: Text(
                  picked.label,
                  key: const Key('planning-stories-picked'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              Text(picked.number, key: const Key('planning-stories-count'), style: figures),
              const Icon(Icons.arrow_drop_down, size: PlanningStyle.rowIcon),
            ],
          ),
        ),
      ),
    );
  }

  /// The narrow segment beside the dropdown: one icon-and-count button for each filter that asks for the person, a
  /// press filtering to it and a second press going back to All.
  Widget? _segment(List<_FilterEntry> entries) {
    final asking = [
      for (final filter in [PlanningFilter.review, PlanningFilter.blocked])
        ?entries.where((entry) => entry.filter == filter).firstOrNull,
    ];
    if (asking.isEmpty) return null;
    final colors = Theme.of(context).colorScheme;
    return Container(
      key: const Key('planning-segment'),
      decoration: BoxDecoration(
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(Space.wide),
      ),
      clipBehavior: Clip.antiAlias,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final entry in asking)
            Tooltip(
              message: '${entry.label}: ${entry.number} ${entry.number == '1' ? 'story' : 'stories'}',
              child: Semantics(
                key: Key('planning-segment ${entry.filter!.name}'),
                label: '${entry.label}, ${entry.number}',
                button: true,
                toggled: _chosen == entry.filter,
                excludeSemantics: true,
                child: InkWell(
                  onTap: () => _pick(_chosen == entry.filter ? null : entry.filter),
                  child: Ink(
                    color: _chosen == entry.filter ? colors.secondaryContainer : null,
                    padding: const EdgeInsets.symmetric(horizontal: Space.small, vertical: Space.tight + 2),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      spacing: Space.tight,
                      children: [
                        PlanningStateIcon(entry.kind!, size: PlanningStyle.rowIcon),
                        Text(
                          entry.number,
                          key: Key('planning-segment-count ${entry.filter!.name}'),
                          style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()]),
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
  }

  /// What the filters cannot tell now, each a line below the figures.
  List<Widget> _filterNotes() {
    final unknown = _listed().where((story) => story.open == null).length;
    return [
      if (_known && unknown > 0)
        Text(
          'The open work of $unknown ${unknown == 1 ? 'story is' : 'stories are'} not known.',
          key: const Key('planning-filter-partial'),
        ),
      if (!_known && widget.filter != null)
        const Text(
          'The open work is not known now, so every story is shown until the planner says it again.',
          key: Key('planning-filter-stale'),
        ),
    ];
  }

  /// The board's one toolbar row: the window's page button, or the board's own info where no window gives one, the
  /// state filters scrolling sideways, then
  /// the project and priority choices, search, reload and the board's menu. Below [PlanningStyle.toolbarRow] the
  /// buttons stay in the first row, the choices and an open search share a row of their own and the filters scroll in
  /// a third, so every control stays reachable down to phone width.
  Widget _toolbar(BuildContext context) => LayoutBuilder(
    builder: (context, room) {
      final narrow = room.maxWidth < PlanningStyle.toolbarRow;
      // One info icon: the window's page button explains the page, and the board's own words then stand on its first
      // figure; only a board shown without a page button, or without figures, carries them on its own.
      final page = widget.pageButton;
      final lead = <Widget>[
        if (page == null)
          _boardInfo(
            const Padding(
              padding: EdgeInsets.all(Space.small),
              child: Icon(Icons.info_outline, size: PlanningStyle.rowIcon + 2),
            ),
          )
        else if (_offered)
          page
        else
          _boardInfo(page),
      ];
      final entries = _offered ? _entries() : null;
      final segment = entries == null ? null : _segment(entries);
      final chips = entries == null
          ? null
          : Row(
              key: const Key('planning-filters'),
              mainAxisSize: MainAxisSize.min,
              spacing: Space.small,
              children: [
                Flexible(child: _storiesMenu(entries)),
                ?segment,
              ],
            );
      final (choices, buttons) = _controls(narrow: narrow);
      if (!narrow) {
        return Row(
          key: const Key('planning-toolbar'),
          spacing: Space.small,
          children: [
            ...lead,
            Expanded(child: chips ?? const SizedBox.shrink()),
            ...choices,
            ...buttons,
          ],
        );
      }
      return Column(
        key: const Key('planning-toolbar'),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: Space.small,
        children: [
          Row(spacing: Space.tight, children: [...lead, const Spacer(), ...buttons]),
          if (choices.isNotEmpty) Row(spacing: Space.small, children: choices),
          ?chips,
        ],
      );
    },
  );

  /// The board's description and where it is read from, told on [child]; one tap shows it on a touch screen.
  Widget _boardInfo(Widget child) => Tooltip(
    key: const Key('planning-board-info'),
    message:
        'The stories of project ${board.project} by state, the most important first.\n'
        'From ${board.backend.label}',
    triggerMode: TooltipTriggerMode.tap,
    child: child,
  );

  /// The toolbar's choices (project, priority and the open search) and its buttons (search, reload and the board's
  /// menu); a choice takes a fixed width, or at phone width an equal share of its own row.
  (List<Widget>, List<Widget>) _controls({required bool narrow}) {
    Widget sized(Widget child, {Key? key, bool whole = false}) => narrow
        ? Expanded(key: key, child: child)
        : whole
        // As wide as its widest label, so the picked one reads in full.
        ? IntrinsicWidth(key: key, child: child)
        : SizedBox(key: key, width: PlanningStyle.filter, child: child);
    const dense = InputDecoration(
      isDense: true,
      contentPadding: EdgeInsets.symmetric(horizontal: Space.normal, vertical: Space.small),
      border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(Space.wide))),
    );
    final choices = <Widget>[
      if (widget.projects.length > 1)
        sized(
          key: const Key('planning-project'),
          DropdownButtonFormField<String>(
            key: _project,
            initialValue: board.project,
            decoration: dense.copyWith(labelText: 'Project'),
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
      sized(
        whole: true,
        Semantics(
          label: 'Priority',
          child: DropdownButtonFormField<PlanningPriority>(
            key: const Key('planning-priority'),
            initialValue: board.preferences.priority,
            decoration: dense,
            isExpanded: narrow,
            selectedItemBuilder: (context) => [
              for (final priority in PlanningPriority.values)
                Text('Priority: ${priority.label}', maxLines: 1, overflow: TextOverflow.ellipsis),
            ],
            items: [
              for (final priority in PlanningPriority.values)
                DropdownMenuItem(value: priority, child: Text(priority.label)),
            ],
            onChanged: (priority) {
              if (priority != null) board.filter(priority);
            },
          ),
        ),
      ),
      if (_searchOpen)
        sized(
          CallbackShortcuts(
            bindings: {const SingleActivator(LogicalKeyboardKey.escape): _clearSearch},
            child: TextField(
              key: const Key('planning-search'),
              controller: _search,
              autofocus: true,
              onChanged: _searchFor,
              decoration: dense.copyWith(
                labelText: 'Search',
                floatingLabelBehavior: FloatingLabelBehavior.never,
                hintText: 'Story ID or a word',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  key: const Key('planning-search-close'),
                  tooltip: 'Close the search',
                  icon: const Icon(Icons.close),
                  onPressed: _closeSearch,
                ),
              ),
            ),
          ),
        ),
    ];
    final buttons = <Widget>[
      if (!_searchOpen)
        IconButton(
          key: const Key('planning-search-open'),
          tooltip: 'Search stories',
          icon: const Icon(Icons.search),
          onPressed: () => setState(() => _searchOpen = true),
        ),
      if (board.snapshot != null || board.problem != null)
        IconButton(
          key: const Key('planning-reload'),
          tooltip: 'Reload planning',
          onPressed: board.reloadHeld ? null : () => unawaited(board.reloadPlanning()),
          icon: const Icon(Icons.refresh),
        ),
      PlanningActionMenu(
        key: const Key('planning-board-menu'),
        tooltip: 'Planning of ${board.project}',
        actions: [
          PlanningAction(
            id: 'planning-discuss-idea',
            label: 'Discuss an idea…',
            unavailable:
                board.unavailableActions['DiscussIdea'] ?? (board.discussing ? 'waiting for the planner' : null),
            run: () => unawaited(_discuss()),
          ),
          PlanningAction(
            id: 'planning-new-issue',
            label: 'New issue…',
            unavailable: board.newIssueUnavailable,
            run: () => unawaited(_newIssue()),
          ),
        ],
      ),
    ];
    return (choices, buttons);
  }

  /// Ends the search: the field closes and the board shows every story again.
  void _closeSearch() {
    _search.clear();
    _searchFor('');
    setState(() => _searchOpen = false);
  }

  /// Clears the search and keeps its field open, as Escape does in Open work's filter.
  void _clearSearch() {
    _search.clear();
    _searchFor('');
    setState(() {});
  }

  /// The board's open work as the planner counted it, over every story and issue, in one plain line with the notes'
  /// marker and a Reload's read at its end; unknown while stale.
  Widget _summary(BuildContext context, ThemeData theme) {
    final shown = [
      for (final story in _listed())
        if (_filters.keeps(story, _applied)) story,
    ].length;
    final open = _known ? board.snapshot?.open : null;
    String figure(int? count) => count == null ? '?' : '$count';
    final time = open != null && open.left == 0
        ? 'nothing left'
        : open == null || open.minutes == null
        ? 'time left unknown'
        : open.unestimated > 0
        ? 'at least ${planningMinutes(open.minutes)} left by velocity, '
              '${open.unestimated} ${open.unestimated == 1 ? 'issue' : 'issues'} without an estimate'
        : 'about ${planningMinutes(open.minutes)} left by velocity';
    Widget part(String key, String number, String words) => Text.rich(
      key: Key('planning-summary $key'),
      TextSpan(
        children: [
          TextSpan(
            text: number,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          TextSpan(text: ' $words'),
        ],
      ),
    );
    return Wrap(
      key: const Key('planning-summary'),
      spacing: Space.wide,
      runSpacing: Space.tight,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (widget.pageButton == null)
          part('stories', '$shown', shown == 1 ? 'story shown' : 'stories shown')
        else
          _boardInfo(part('stories', '$shown', shown == 1 ? 'story shown' : 'stories shown')),
        part('left', figure(open?.left), 'issues still to build'),
        Text(time, key: const Key('planning-summary time')),
        if (board.rules.isNotEmpty) _notesMarker(theme),
        _reloadProgress(),
      ],
    );
  }

  /// A Reload's read of the whole board at the end of the figures' line, one line high so the line keeps its height,
  /// and only once it takes longer than a moment; a look for changes on a live board is words alone, no bar, so it
  /// never reads as a load.
  Widget _reloadProgress() => board.lookingLive && !board.fetching
      // As wide as its words, up to the whole line: the figures' line wraps before it cuts them.
      ? Text(board.loading!.words, key: const Key('planning-look'), maxLines: 1, overflow: TextOverflow.ellipsis)
      : _SlowProgress(
          active: board.fetching || board.loading != null,
          child: SizedBox(
            width: PlanningStyle.filter,
            child: Row(
              spacing: Space.small,
              children: [
                SizedBox(
                  width: PlanningStyle.progress,
                  child: LinearProgressIndicator(key: const Key('planning-load-bar'), value: board.loading?.done),
                ),
                Expanded(
                  child: Text(
                    board.loading?.words ?? 'Loading the planning…',
                    key: const Key('planning-waiting'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
        );

  /// A card's open work from the story's own figures: its state bar, a badge per open state and what is left.
  Widget _openWork(BuildContext context, PlanStory story) {
    final open = _known ? story.open : null;
    final theme = Theme.of(context);
    if (open == null) {
      return Text(
        'Open work not known now',
        key: Key('planning-card-open ${story.id}'),
        style: theme.textTheme.bodySmall,
      );
    }
    final colors = theme.colorScheme;
    final review = _filters.review;
    // Each kind with its issues and the states they stand in, in the design's order.
    final parts = <PlanningStateKind, (int, List<String>)>{};
    void add(PlanningStateKind kind, int issues, String state) {
      if (issues <= 0) return;
      final (count, states) = parts[kind] ?? (0, <String>[]);
      parts[kind] = (count + issues, [...states, state]);
    }

    for (final MapEntry(key: name, value: issues) in open.states.entries) {
      final state = board.state(name);
      add(state == null ? PlanningStateKind.other : planningStateKind(state, review), issues, name);
    }
    add(PlanningStateKind.blocked, open.blocked, 'Blocked');
    const order = [
      PlanningStateKind.implementing,
      PlanningStateKind.blocked,
      PlanningStateKind.review,
      PlanningStateKind.backlog,
      PlanningStateKind.estimated,
      PlanningStateKind.analysis,
      PlanningStateKind.idea,
      PlanningStateKind.other,
      PlanningStateKind.done,
      PlanningStateKind.rejected,
    ];
    final shown = [
      for (final kind in order)
        if (parts[kind] case final part?) (kind, part.$1, part.$2),
    ];
    String said((PlanningStateKind, int, List<String>) part) => '${part.$2} ${part.$3.map(_line).join(', ')}';
    final sizes = planningSizes(open);
    final rest = open.left == 0
        ? ''
        : open.minutes == null
        ? 'time unknown'
        : '≈ ${planningMinutes(open.minutes)}${open.unestimated > 0 ? '+' : ''}';
    return Column(
      key: Key('planning-card-open ${story.id}'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: Space.small,
      children: [
        if (shown.isNotEmpty)
          Semantics(
            label: shown.map(said).join(', '),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(Space.tight),
              child: SizedBox(
                height: Space.small,
                child: Row(
                  children: [
                    for (final part in shown)
                      Expanded(
                        flex: part.$2,
                        child: ColoredBox(color: planningStateColor(part.$1, colors)),
                      ),
                  ],
                ),
              ),
            ),
          ),
        Wrap(
          spacing: Space.tight,
          runSpacing: Space.tight,
          children: [
            for (final part in shown)
              if (part.$1 != PlanningStateKind.done && part.$1 != PlanningStateKind.rejected)
                Tooltip(
                  message: said(part),
                  child: Container(
                    key: Key('planning-card-badge ${story.id} ${part.$1.name}'),
                    padding: const EdgeInsets.symmetric(horizontal: Space.small - 2, vertical: 2),
                    decoration: BoxDecoration(
                      border: Border.all(color: planningStateColor(part.$1, colors).withValues(alpha: 0.4)),
                      borderRadius: BorderRadius.circular(Space.normal),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      spacing: Space.tight,
                      children: [
                        PlanningStateIcon(part.$1),
                        Text(
                          '${part.$2}',
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: planningStateColor(part.$1, colors),
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
          ],
        ),
        Row(
          children: [
            Expanded(
              child: Text(
                open.left == 0 ? 'Nothing left to build' : '${open.left} open${sizes.isEmpty ? '' : ' · $sizes'}',
                key: Key('planning-card-left ${story.id}'),
                style: theme.textTheme.bodySmall,
              ),
            ),
            Text(rest, key: Key('planning-card-time ${story.id}'), style: theme.textTheme.labelMedium),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, room) => _board(context, room.maxHeight));

  /// The most height above the board: the view's, less the board's least and the move's buttons.
  double _topRoom(double height) => math.max(
    0,
    height -
        2 * Space.wide -
        Space.normal -
        PlanningStyle.boardMinimum -
        (board.question == null && board.correctionQuestion == null ? 0 : PlanningStyle.moveButtons),
  );

  /// Brings a newly asked move's warnings into view once, since they stand below everything else that scrolls.
  void _showAsked() {
    final Object? asked = board.question ?? board.correctionQuestion;
    if (identical(asked, _asked)) return;
    _asked = asked;
    if (_asked == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final panel = _panel.currentContext;
      if (panel != null && panel.mounted) unawaited(Scrollable.ensureVisible(panel));
    });
  }

  Widget _board(BuildContext context, double height) => ListenableBuilder(
    listenable: board,
    builder: (context, _) {
      if (board.unsupported) {
        return Center(
          child: Text('${board.backend.label} does not offer planning.', key: const Key('planning-no-planning')),
        );
      }
      final theme = Theme.of(context);
      _showAsked();
      _readFilter();
      return Padding(
        key: const Key('planning-board'),
        padding: const EdgeInsets.all(Space.wide),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Everything above the board scrolls within what the board and the move's buttons leave, so neither
            // problem lines nor warnings push Confirm and Cancel out of the view.
            ConstrainedBox(
              constraints: BoxConstraints(minWidth: double.infinity, maxHeight: _topRoom(height)),
              child: Scrollbar(
                controller: _top,
                thumbVisibility: true,
                child: SingleChildScrollView(
                  key: const Key('planning-board-top'),
                  controller: _top,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ?widget.pageNote,
                      ItemsDirectoryHeader(board: board, choose: widget.chooseDirectory),
                      PlanningRepositoryWarnings(board: board),
                      _toolbar(context),
                      if (_added case final added?) Text(added, key: const Key('planning-new-issue-added')),
                      if (_offered) ...[_summary(context, theme), ..._filterNotes()],
                      if (!_offered && board.rules.isNotEmpty) _notesMarker(theme),
                      if (_rulesOpen && board.rules.isNotEmpty) _notes(theme),
                      for (final problem in widget.providerProblems) _failure(problem, theme),
                      if (board.catchingUp)
                        const Text('Catching up with the planner’s changes…', key: Key('planning-catching-up')),
                      if (!board.live && (board.snapshot != null || board.problem != null))
                        const Text(
                          'Planning is not live. Reload planning shows the current state.',
                          key: Key('planning-not-live'),
                        ),
                      if (board.actionProblem case final problem?)
                        _failure(problem, theme, key: const Key('planning-action-error')),
                      for (final entry in board.workProblems.entries)
                        _failure(_line('${entry.key}: ${entry.value}'), theme),
                      for (final story in board.workUnsupported)
                        Text(_line('$story: the planner does not offer story work status.')),
                      if (board.problem case final problem?)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: Space.small),
                          child: _problem(problem, theme),
                        ),
                      if (board.noStoryProblem case final problem?)
                        _failure('Issues in no story: $problem', theme, key: const Key('planning-no-story-error')),
                      if (board.readProblem case final problem?)
                        _failure(problem, theme, key: const Key('planning-read-error')),
                      if (board.snapshot != null && !board.snapshot!.complete)
                        const Text('The planner did not report the complete planning workflow and story list.'),
                      if (board.moveProblem case final problem?)
                        _failure(problem, theme, key: const Key('planning-move-error')),
                      if (board.moveNotice case final notice?) Text(notice, key: const Key('planning-move-notice')),
                      ForgeAccessOffer(board: board, host: widget.host, provider: widget.machines[board] ?? ''),
                      // Without the figures' line, a Reload's read stands on a line of its own.
                      if (!_offered && (board.snapshot != null || board.problem != null)) _reloadProgress(),
                      PlanningMoveWarningPanel(key: _panel, board: board, buttons: false),
                      if (board.snapshot == null && board.problem == null)
                        switch (board.loading) {
                          final load? => _loadProgress(load),
                          null => const Row(
                            spacing: Space.small,
                            children: [
                              // A still mark: an endless animation would keep a waiting page from ever settling.
                              Icon(Icons.hourglass_empty, key: Key('planning-loading'), size: PlanningStyle.mark),
                              Flexible(child: Text('Waiting for the planner’s answer.', key: Key('planning-waiting'))),
                            ],
                          ),
                        },
                    ],
                  ),
                ),
              ),
            ),
            PlanningMoveButtons(board: board),
            const SizedBox(height: Space.normal),
            Expanded(
              child: board.snapshot == null
                  ? const SizedBox.shrink()
                  // The sideways bar sits at the view's foot, not under the tallest column, so it can be seen.
                  : LayoutBuilder(
                      builder: (context, room) => Scrollbar(
                        key: const Key('planning-board-sideways'),
                        controller: _horizontal,
                        thumbVisibility: true,
                        notificationPredicate: (notification) =>
                            notification.depth == 0 && notification.metrics.axis == Axis.horizontal,
                        child: SingleChildScrollView(
                          key: const Key('planning-board-scroll'),
                          controller: _horizontal,
                          scrollDirection: Axis.horizontal,
                          // Each phase scrolls on its own and builds only the cards in its view.
                          child: SizedBox(
                            height: room.maxHeight,
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                for (final (at, phase) in board.phases.indexed)
                                  if (board.statesIn(phase).isNotEmpty) _phase(context, at),
                                for (final column in _unplacedColumns())
                                  _unplaced(context, column.state, column.stories),
                                if (board.unlisted.isNotEmpty) _unlisted(context),
                              ],
                            ),
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

  /// A failure above the board reads as one, in the theme's error colour behind an error icon.
  /// The planner's read of the board: a bar with the share done, moving without one until it knows how many entries
  /// there are, and one sentence how far it is.
  Widget _loadProgress(PlanningLoad? load, {bool line = false}) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    mainAxisSize: MainAxisSize.min,
    spacing: Space.small,
    children: [
      LinearProgressIndicator(key: const Key('planning-load-bar'), value: load?.done),
      Text(
        load?.words ?? 'Loading the planning…',
        key: const Key('planning-waiting'),
        maxLines: line ? 1 : null,
        overflow: line ? TextOverflow.ellipsis : null,
      ),
    ],
  );

  Widget _failure(String words, ThemeData theme, {Key? key}) {
    final colour = theme.colorScheme.error;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.error_outline, size: PlanningStyle.mark, color: colour),
        const SizedBox(width: Space.small),
        Flexible(
          child: Text(
            words,
            key: key,
            style: TextStyle(color: colour),
          ),
        ),
      ],
    );
  }

  /// Several faults of the stories and issues take one line until the person opens them, so they never push the board
  /// out of sight; any other problem is shown whole.
  Widget _problem(String problem, ThemeData theme) {
    final faults = board.snapshot?.faults ?? const <String>[];
    final style = TextStyle(color: theme.colorScheme.error);
    final full = _failure(problem, theme, key: const Key('planning-error'));
    if (faults.length < 2 || problem != planningShown(faults.join('\n'))) return full;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          key: const Key('planning-faults'),
          onTap: () => setState(() => _faultsOpen = !_faultsOpen),
          child: Text(
            '${faults.length} problems in the stories and issues — ${_faultsOpen ? 'hide' : 'show'}',
            style: style,
          ),
        ),
        if (_faultsOpen)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: PlanningStyle.column),
            child: SingleChildScrollView(child: full),
          ),
      ],
    );
  }

  /// The breaches of the stories' rules above the board: one is said whole, several take one line until the person
  /// opens them. They are no failure, so they are not in the error colour.
  /// The board's planning notes, its breaches of the stories' rules, behind a small marker that counts them.
  Widget _notesMarker(ThemeData theme) {
    final count = board.rules.length;
    final colour = theme.colorScheme.tertiary;
    return Tooltip(
      message: _rulesOpen ? 'Hide the planning notes' : 'Show the planning notes',
      child: InkWell(
        key: const Key('planning-rules'),
        borderRadius: BorderRadius.circular(Space.normal),
        onTap: () => setState(() => _rulesOpen = !_rulesOpen),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: Space.small, vertical: 2),
          decoration: BoxDecoration(
            color: colour.withValues(alpha: 0.08),
            border: Border.all(color: colour.withValues(alpha: 0.5)),
            borderRadius: BorderRadius.circular(Space.normal),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            spacing: Space.tight,
            children: [
              Icon(Icons.rule, size: PlanningStyle.mark, color: colour),
              Text(
                '$count ${count == 1 ? 'note' : 'notes'}',
                style: theme.textTheme.labelMedium?.copyWith(color: colour),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The planning notes the marker opened, each in the planner's words.
  Widget _notes(ThemeData theme) {
    final words = [for (final breach in board.rules) planningShown(breach.words, multiline: false)];
    final colour = theme.colorScheme.tertiary;
    return Padding(
      padding: const EdgeInsets.only(top: Space.tight),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: PlanningStyle.column),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final (at, text) in words.indexed)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: Space.small,
                  children: [
                    Icon(Icons.rule, size: PlanningStyle.mark, color: colour),
                    Flexible(child: Text(text, key: Key('planning-rule-line $at'))),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// A phase fills the board's height and scrolls on its own; its states' cards are built only once in its view.
  Widget _phase(BuildContext context, int at) {
    final phase = board.phases[at];
    final id = _phaseId(at);
    final folded = board.preferences.foldedPhases.contains(phase.name);
    final states = board.statesIn(phase);
    final count = states.fold<int>(0, (count, state) => count + _shown(state.name).length);
    final controller = _phaseScroll(id, remember: phase.name, folded: folded);
    return Container(
      key: Key('planning-phase $id'),
      width: folded ? PlanningStyle.foldedColumn : PlanningStyle.column,
      margin: const EdgeInsets.only(right: Space.normal),
      padding: const EdgeInsets.all(Space.small),
      decoration: BoxDecoration(
        color: PlanningStyle.phase(Theme.of(context).colorScheme),
        borderRadius: BorderRadius.circular(PlanningStyle.radiusMedium),
      ),
      child: Scrollbar(
        controller: controller,
        child: CustomScrollView(
          key: Key('planning-phase-scroll $id'),
          controller: controller,
          slivers: [
            SliverToBoxAdapter(
              child: Row(
                children: [
                  Expanded(child: Text(_line(phase.name).toUpperCase(), style: Theme.of(context).textTheme.labelLarge)),
                  Text('$count'),
                  IconButton(
                    key: Key('planning-fold-phase $id'),
                    tooltip: '${folded ? 'Unfold' : 'Fold'} ${_line(phase.name)}',
                    onPressed: () => board.foldPhase(phase.name),
                    icon: Icon(folded ? Icons.chevron_right : Icons.chevron_left),
                  ),
                ],
              ),
            ),
            if (_applied != null && count == 0) const SliverToBoxAdapter(child: Text('No story matches the filter.')),
            for (final state in states) _state(context, state, phaseFolded: folded),
          ],
        ),
      ),
    );
  }

  /// The [stories] and the issues in no story in [state], which no phase shows, in a column of their own named after
  /// the value, or No state, that takes no drop.
  Widget _unplaced(BuildContext context, String state, List<PlanStory> stories) {
    final controller = _unplacedScroll(state);
    final noStory = _noStoryShown(state) && board.noStoryIn(state).isNotEmpty;
    return Container(
      key: Key(state.isEmpty ? 'planning-no-state' : 'planning-unknown-state $state'),
      width: PlanningStyle.column,
      margin: const EdgeInsets.only(right: Space.normal),
      padding: const EdgeInsets.all(Space.small),
      decoration: BoxDecoration(
        color: PlanningStyle.phase(Theme.of(context).colorScheme),
        borderRadius: BorderRadius.circular(PlanningStyle.radiusMedium),
      ),
      child: Scrollbar(
        controller: controller,
        child: CustomScrollView(
          controller: controller,
          slivers: [
            SliverToBoxAdapter(
              child: Tooltip(
                message: state.isEmpty
                    ? 'Stories whose state cannot be told'
                    : board.state(state) != null
                    ? 'Stories and issues in a state of the workflow that no phase shows'
                    : 'Stories and issues in a state the workflow does not have',
                child: Text(state.isEmpty ? 'No state' : _line(state), style: Theme.of(context).textTheme.labelLarge),
              ),
            ),
            SliverList.builder(
              itemCount: stories.length,
              itemBuilder: (context, index) => _card(context, stories[index]),
            ),
            if (noStory)
              SliverToBoxAdapter(
                child: PlanningNoStoryCard(
                  board: board,
                  state: state,
                  onIssue: (id) => widget.onIssue('', id),
                  sizeBadge: (issue) => _size(context, issue.size, key: 'planning-issue-size ${issue.id}'),
                  openLink: widget.host.openLink,
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// The open issues of the project's repositories that are no items of the project, each with its tracker link, its
  /// author and an Add; the title, the author's login and association are shown as plain text, and only an HTTPS link
  /// opens.
  Widget _unlisted(BuildContext context) {
    final issues = board.unlisted;
    return SizedBox(
      key: const Key('planning-unlisted'),
      width: PlanningStyle.column,
      child: CustomScrollView(
        slivers: [
          const SliverToBoxAdapter(child: Text('Issues not on the project')),
          SliverList.builder(
            itemCount: issues.length,
            itemBuilder: (context, index) {
              final issue = issues[index];
              final open = planningWebAction(
                'Open ${issue.story ? 'story' : 'issue'} ${issue.id} in browser',
                issue.link,
                widget.host.openLink,
                onError: board.reportActionProblem,
              );
              return Card(
                key: Key('planning-unlisted ${issue.id}'),
                child: Padding(
                  padding: const EdgeInsets.all(Space.small),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              '${_line(issue.id)}  ${_line(issue.title)}',
                              maxLines: 3,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          IconButton(
                            key: Key('planning-unlisted-open ${issue.id}'),
                            tooltip: open.available ? open.label : open.unavailable,
                            onPressed: open.available ? open.run : null,
                            icon: const Icon(Icons.open_in_new, size: PlanningStyle.rowIcon),
                          ),
                        ],
                      ),
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              [
                                issue.story ? 'Story' : 'Issue',
                                if (issue.author.isNotEmpty) 'by ${_line(issue.author)}',
                                if (issue.authorAssociation.isNotEmpty) _line(issue.authorAssociation),
                              ].join('  '),
                              key: Key('planning-unlisted-author ${issue.id}'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          TextButton(
                            key: Key('planning-unlisted-add ${issue.id}'),
                            onPressed: board.takesMoves && !board.holdsMoves && !board.actionBusy
                                ? () => unawaited(board.addUnlistedIssue(issue.id, revision: board.snapshot?.revision))
                                : null,
                            child: Text(board.listing == issue.id ? 'Adding…' : 'Add'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  /// The state a dragged card is held over, so its heading says where it would land.
  final _hovered = ValueNotifier<String?>(null);

  bool _accepts(PlanState state, _CardDrag drag) =>
      // A move made while another card's save is out is queued behind it.
      !board.waitsOnItsSave(drag.story) &&
      board.takesMoves &&
      board.stories.any(
        (story) =>
            story.id == drag.story && board.displayedState(story) != state.name && board.storyMayEnter(story, state),
      );

  void _dropped(PlanState state, _CardDrag drag) {
    _hovered.value = null;
    final story = board.stories.where((story) => story.id == drag.story).firstOrNull;
    if (story == null || board.displayedState(story) == state.name) return;
    if (state.kind == 'WORKING') {
      unawaited(_start(story, target: state));
    } else {
      unawaited(board.move(drag.story, state.name, revision: drag.revision));
    }
  }

  /// [child] as a part of [state] a card may be dropped on: its heading and each of its cards.
  Widget _dropOn(PlanState state, Widget child, {Key? key}) => DragTarget<_CardDrag>(
    key: key,
    onWillAcceptWithDetails: (details) {
      final accepted = _accepts(state, details.data);
      if (accepted) _hovered.value = state.name;
      return accepted;
    },
    onLeave: (_) {
      if (_hovered.value == state.name) _hovered.value = null;
    },
    onAcceptWithDetails: (details) => _dropped(state, details.data),
    builder: (context, candidates, rejected) => child,
  );

  /// A state as slivers: its heading, then its cards built as they come into view, on the state's own colour.
  Widget _state(BuildContext context, PlanState state, {required bool phaseFolded}) {
    final cards = _shown(state.name);
    // Only the column's chevron narrows its boxes to tiles; a box's own chevron folds it to its heading.
    final folded = !phaseFolded && board.preferences.foldedStates.contains(state.name);
    final raised = folded ? cards.where(_needsYou).length : 0;
    final theme = Theme.of(context);
    final heading = _dropOn(
      state,
      key: Key('planning-drop ${state.name}'),
      Container(
        padding: const EdgeInsets.all(Space.tight),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                if (state.kind == 'WORKING')
                  const Padding(
                    padding: EdgeInsets.only(right: Space.tight),
                    child: Icon(Icons.schedule, size: PlanningStyle.mark),
                  ),
                // The hint takes the name's place, so nothing under the pointer moves while a card is dragged.
                Expanded(
                  child: ValueListenableBuilder<String?>(
                    valueListenable: _hovered,
                    builder: (context, hovered, _) => Text(
                      hovered == state.name ? 'Drop here for ${_line(state.name)}' : _line(state.name),
                      style: theme.textTheme.labelMedium,
                      softWrap: false,
                      overflow: TextOverflow.fade,
                    ),
                  ),
                ),
                if (raised > 0)
                  Tooltip(
                    message: '$raised need you',
                    child: Row(
                      key: Key('planning-state-raised ${state.name}'),
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.warning_amber, size: PlanningStyle.mark),
                        Text('$raised'),
                        const SizedBox(width: Space.tight),
                      ],
                    ),
                  ),
                Text('${cards.length}'),
                if (!phaseFolded)
                  IconButton(
                    key: Key('planning-fold-state ${state.name}'),
                    tooltip: '${folded ? 'Unfold' : 'Fold'} ${_line(state.name)}',
                    onPressed: () => board.foldState(state.name),
                    icon: Icon(folded ? Icons.chevron_right : Icons.expand_more, size: PlanningStyle.rowIcon),
                  ),
              ],
            ),
            if (state.kind.isEmpty)
              const Text('State kind not reported')
            else if (!{'WAITING', 'WORKING', 'CLOSED'}.contains(state.kind))
              Text(_line(state.kind)),
          ],
        ),
      ),
    );
    // The state's key stands over its heading and its cards alike, so a card is found within its state.
    return KeyedSubtree(
      key: Key('planning-state ${state.name}'),
      child: SliverPadding(
        padding: const EdgeInsets.only(bottom: Space.small),
        sliver: DecoratedSliver(
          decoration: BoxDecoration(
            color: state.kind == 'WORKING' ? PlanningStyle.working(theme.colorScheme) : null,
            borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
          ),
          sliver: SliverMainAxisGroup(
            slivers: [
              SliverToBoxAdapter(child: heading),
              if (!folded)
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: Space.tight),
                  sliver: SliverList.builder(
                    itemCount: cards.length,
                    itemBuilder: (context, index) =>
                        _dropOn(state, phaseFolded ? _tile(context, cards[index]) : _card(context, cards[index])),
                  ),
                ),
              if (!folded && _noStoryShown(state.name) && board.noStoryIn(state.name).isNotEmpty)
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: Space.tight),
                    child: PlanningNoStoryCard(
                      phaseFolded: phaseFolded,
                      board: board,
                      state: state.name,
                      onIssue: (id) => widget.onIssue('', id),
                      sizeBadge: (issue) => _size(context, issue.size, key: 'planning-issue-size ${issue.id}'),
                      openLink: widget.host.openLink,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// An id as a folded column shows it: its number from `#` on, or the whole id where it has none.
  static String _folded(String id) {
    final at = id.lastIndexOf('#');
    return at > 0 && at < id.length - 1 ? id.substring(at) : id;
  }

  /// A story in a folded phase: its priority, id, size and marks on one line.

  Widget _tile(BuildContext context, PlanStory story) => Padding(
    padding: const EdgeInsets.only(bottom: Space.tight),
    child: _drag(
      story,
      InkWell(
        key: Key('planning-story ${story.id}'),
        onTap: _opens(story) ? () => widget.onStory(story.id) : null,
        child: Tooltip(
          message: _line('${story.id} — ${story.title} (${story.priority})'),
          child: Container(
            key: Key(_cardKey(story)),
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
                    // A folded column is too narrow for a repository's name; the tooltip names the whole id.
                    Expanded(child: Text(_line(_folded(story.id)), softWrap: false, overflow: TextOverflow.fade)),
                    ?_size(context, story.size, key: 'planning-size ${story.id}'),
                    if (_needsYou(story))
                      Icon(
                        Icons.warning_amber,
                        key: Key('planning-needs-you ${story.id}'),
                        size: PlanningStyle.mark,
                        semanticLabel: 'needs you',
                      ),
                    _menu(story, dense: true),
                  ],
                ),
                if (board.movingWords(story.id) case final words?) Text(words),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  // The data is taken when the drag starts, so the drop carries the board the person saw then.
  Widget _drag(PlanStory story, Widget child) => Draggable<_CardDrag>(
    data: (story: story.id, revision: board.snapshot?.revision ?? ''),
    // A card under the pointer may leave the view while it is dragged, and its target then says nothing.
    onDragEnd: (_) => _hovered.value = null,
    // A story the planner holds where it stands takes no drag; one held in a state the workflow lacks may leave it.
    maxSimultaneousDrags:
        board.waitsOnItsSave(story.id) || !board.takesMoves || board.heldInPlace(story.state, story.warnings) ? 0 : 1,
    feedback: Material(
      elevation: Space.tight,
      child: Padding(padding: const EdgeInsets.all(Space.small), child: Text(_line(story.id))),
    ),
    childWhenDragging: Opacity(opacity: PlanningStyle.stale, child: child),
    child: child,
  );

  /// A size badge as the contract gives sizes, `XS` to `XL`; anything else a server sends is shown as not understood,
  /// never as its own text, and no size shows no badge.
  Widget? _size(BuildContext context, String size, {required String key}) {
    if (size.isEmpty || size == '-') return null;
    final known = const {'XS', 'S', 'M', 'L', 'XL'}.contains(size);
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      label: known ? 'Size $size' : 'Size not understood',
      child: Container(
        key: Key(key),
        padding: const EdgeInsets.symmetric(horizontal: Space.tight),
        decoration: BoxDecoration(
          color: colors.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
        ),
        child: Text(
          known ? size : '?',
          style: Theme.of(context).textTheme.labelSmall?.copyWith(color: colors.onSurfaceVariant),
        ),
      ),
    );
  }

  Widget _priority(BuildContext context, String priority, {bool mark = false}) {
    final colors = PlanningStyle.priority(priority, Theme.of(context).colorScheme);
    final label = mark
        ? switch (priority) {
            'Highest' => '▲▲',
            'High' => '▲',
            'Medium' => '▬',
            'Low' => '▼',
            _ => priority.isEmpty ? '?' : _line(priority),
          }
        : priority.isEmpty
        ? 'Priority not reported'
        : _line(priority);
    return Semantics(
      label: priority.isEmpty ? 'Priority not reported' : _line(priority),
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
    // The tasks that build the story's work; one that reads it is told by its reading.
    final mainTasks = work?.tasks.where((task) => task.role.pushesWork && task.atWork == true).length ?? 0;
    if (work == null) return working ? 'Work not reported' : null;
    if (work.atWork == false && work.issues.any((issue) => issue.reason == 'STOPPED')) return 'stopped';
    if (work.atWork == false) return working ? 'nobody at work' : null;
    return [
      if (work.atWork == null) 'Work status not reported',
      if (work.coordinator?.isNotEmpty ?? false) _line(work.coordinator!),
      if (mainTasks > 0) '$mainTasks ${mainTasks == 1 ? 'task' : 'tasks'}',
      work.agent.isEmpty ? 'Agent not reported' : _line(work.agent),
      work.model.isEmpty ? 'Model not reported' : _line(work.model),
      if (work.mode.isNotEmpty)
        switch (work.mode) {
          'UNATTENDED' => 'unattended',
          'AGENT' => 'shell with you',
          _ => _line(work.mode),
        },
      if (work.since.isNotEmpty) 'since ${_line(planningLocalTime(work.since))}',
    ].join(' · ');
  }

  Widget _reading(PlanStory story) {
    final reading = story.issues.where((issue) => issue.state == 'Reading' || issue.state == 'Reworking');
    final stopped = !board.attentionSeen(story) && story.issues.any((issue) => issue.readingStopped == true);
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

  /// Whether the story waits on the person for any reason, a stopped reading included; what a fold leaves showing.
  bool _needsYou(PlanStory story) =>
      !board.attentionSeen(story) && (_raised(story) || story.issues.any((issue) => issue.readingStopped == true));

  /// Whether an issue of the story was raised outside a stopped reading, such as by a task that failed.
  bool _raised(PlanStory story) {
    if (board.attentionSeen(story)) return false;
    final stopped = {
      for (final issue in story.issues)
        if (issue.readingStopped == true) issue.id,
    };
    return story.issues.any((issue) => issue.needsYou == true && !stopped.contains(issue.id)) ||
        (board.workOf(story)?.issues ?? const <PlanWorkIssue>[]).any(
          (issue) =>
              (planReasonNeedsAttention(issue.reason) || issue.needsYou == true) &&
              issue.readingStopped != true &&
              !stopped.contains(issue.issue),
        );
  }

  /// A warning mark leading to Needs you, where the work stopped and needs the person.
  Widget _warning(PlanStory story) => TextButton.icon(
    key: Key('planning-warning ${story.id}'),
    onPressed: widget.onNeedsYou,
    icon: const Icon(Icons.warning_amber, size: PlanningStyle.mark),
    label: const Text('needs you'),
  );

  Future<void> _start(PlanStory story, {PlanState? target}) async {
    target ??=
        board.states.where((state) => state.kind == 'WORKING' && state.name != story.state).firstOrNull ??
        board.states.where((state) => state.kind == 'WORKING').firstOrNull;
    if (target == null) return;
    await _openStart(
      context,
      board,
      story,
      target,
      startTask: widget.startTask,
      agents: widget.agents,
      wizards: widget.wizards,
      machines: widget.machines,
      unavailableMachines: widget.unavailableMachines,
      onStarted: widget.onStarted,
    );
  }

  Future<void> _discuss() async {
    final idea = await showDialog<String>(context: context, builder: (_) => const _IdeaDialog());
    if (!mounted || idea == null) return;
    await board.discussIdea(idea, widget.startTask);
  }

  /// What the last new issue was given, shown until the next one.
  String? _added;

  Future<void> _newIssue() async {
    setState(() => _added = null);
    final id = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (_) => NewIssueDialog(board: board),
    );
    if (mounted && id != null) setState(() => _added = 'Added $id in state New.');
  }

  Future<void> _stop(PlanStory story) async {
    final source = board;
    final backend = source.backend;
    final project = source.project;
    final revision = source.snapshot?.revision;
    final run = source.workOf(story)?.run;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Stop work on ${_line(story.id)}?'),
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
    await source.stopWork(story.id, run: run, revision: revision, source: backend, sourceProject: project);
  }

  Widget _menu(PlanStory story, {bool dense = false}) => PlanningActionMenu(
    key: Key('planning-menu ${story.id}'),
    dense: dense,
    tooltip: 'Work on ${_line(story.id)}',
    actions: _actions(story),
    current: () => _actions(story),
  );

  List<PlanningAction> _actions(PlanStory story) => [
    PlanningAction(
      id: 'planning-start ${story.id}',
      label: 'Start work…',
      unavailable: _startUnavailable(story),
      run: () => unawaited(_start(story)),
      wayOn: board.startHold(story)?.attention == null
          ? null
          : PlanningWayOn(label: 'Open the run that needs attention', run: () => widget.onStory(story.id)),
    ),
    PlanningAction(
      id: 'planning-stop ${story.id}',
      label: 'Stop work',
      unavailable: _stopUnavailable(story),
      run: () => unawaited(_stop(story)),
    ),
    for (final priority in planningPriorities) setPriorityAction(board, story, priority),
  ];

  String? _startUnavailable(PlanStory story) =>
      board.startUnavailable(story) ??
      (!board.states.any((state) => state.kind == 'WORKING') ? 'no working state was reported' : null);

  String? _stopUnavailable(PlanStory story) => board.stopUnavailable(story);

  /// Whether [story]'s card leads to its page: not without an id, nor for an id that names another story too, whose
  /// page no read by that id finds.
  static bool _opens(PlanStory story) => story.id.isNotEmpty && !PlanWarning.namesTwice(story.warnings);

  /// Each story's place among the board's stories, by identity, for an id the board names more than once; made once
  /// per board read.
  (List<PlanStory>, Map<PlanStory, int>)? _copies;

  /// The key of [story]'s card: by its id, and for an id the board names more than once by its place among the
  /// board's stories too, so no two cards share a key and the search brings the copy it found into view.
  String _cardKey(PlanStory story) {
    final stories = board.stories;
    var copies = _copies;
    if (copies == null || !identical(copies.$1, stories)) {
      final counts = <String, int>{};
      for (final each in stories) {
        counts[each.id] = (counts[each.id] ?? 0) + 1;
      }
      final places = Map<PlanStory, int>.identity();
      for (final (index, each) in stories.indexed) {
        if (counts[each.id]! > 1) places[each] = index;
      }
      copies = _copies = (stories, places);
    }
    final place = copies.$2[story];
    return place == null ? 'planning-card ${story.id}' : 'planning-card ${story.id} $place';
  }

  /// Each card as last built, with what it was built from; a board of hundreds of cards is not built anew on
  /// every change, only the cards whose inputs changed.
  final Map<String, (List<Object?>, Widget)> _cards = {};

  Widget _card(BuildContext context, PlanStory story) {
    final inputs = <Object?>[
      board,
      story,
      Theme.of(context),
      board.preferences.expandedStories.contains(story.id),
      _foundIn(story).join(' '),
      _work(story),
      _needsYou(story),
      board.pending[story.id],
      board.movingWords(story.id),
      board.refusalOf(story.id),
      [for (final issue in story.issues) board.refusalOf('issue ${issue.id}')].join('\n'),
      board.holdsMoves,
      board.snapshot?.revision,
      board.pendingPriorities[story.id],
      board.priorityUnavailable(story.id),
      board.rulesOf(story.id).join('\n'),
      board.saving,
      board.live,
      board.takesMoves,
      _known,
      _offered,
      _startUnavailable(story),
      board.startHold(story)?.attention,
      _stopUnavailable(story),
    ];
    final built = _cards[story.id];
    if (built != null && listEquals(built.$1, inputs)) return built.$2;
    final card = _buildCard(context, story);
    _cards[story.id] = (inputs, card);
    return card;
  }

  /// The ids of [story]'s issues the current search finds.
  List<String> _foundIn(PlanStory story) {
    final found = board.foundIssues;
    return found.isEmpty
        ? const []
        : [
            for (final issue in story.issues)
              if (found.contains(issue.id)) issue.id,
          ];
  }

  Widget _buildCard(BuildContext context, PlanStory story) {
    final theme = Theme.of(context);
    final found = _foundIn(story);
    // A found issue opens its list while the search stands, without changing the person's own choice.
    final open = board.preferences.expandedStories.contains(story.id) || found.isNotEmpty;
    final work = _work(story);
    return _drag(
      story,
      Card(
        key: Key(_cardKey(story)),
        margin: const EdgeInsets.only(bottom: Space.small),
        child: InkWell(
          key: Key('planning-story ${story.id}'),
          onTap: _opens(story) ? () => widget.onStory(story.id) : null,
          child: Padding(
            padding: const EdgeInsets.all(Space.normal),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Wrap(
                        spacing: Space.small,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(_line(story.id), style: theme.textTheme.labelMedium),
                          _priority(context, story.priority ?? ''),
                          ?planningRuleMark(board.rulesOf(story.id), key: Key('planning-rule ${story.id}')),
                          ?planningWarningMark(story.warnings, key: Key('planning-warned ${story.id}')),
                          ?planningCorrectionOffer(
                            board: board,
                            id: story.id,
                            story: true,
                            state: story.state,
                            warnings: story.warnings,
                            link: story.link,
                            openLink: widget.host.openLink,
                          ),
                          ?_size(context, story.size, key: 'planning-size ${story.id}'),
                          ?planningLabels(story.id, story.labels),
                        ],
                      ),
                    ),
                    _menu(story),
                  ],
                ),
                const SizedBox(height: Space.small),
                Text(_line(story.title)),
                if (_offered) ...[const SizedBox(height: Space.small), _openWork(context, story)],
                if (board.movingWords(story.id) case final words?) Text(words),
                if (board.refusalOf(story.id) case final words?)
                  Text(
                    words,
                    key: Key('planning-card-refused ${story.id}'),
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                if (board.pendingPriorities[story.id] case final priority?)
                  Text('Priority $priority pending', key: Key('planning-priority-pending ${story.id}')),
                ...[
                  if (work != null && work.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: Space.small),
                      child: Text(work, style: theme.textTheme.bodySmall),
                    ),
                  if (story.issues.any(
                    (issue) => issue.state == 'Reading' || issue.state == 'Reworking' || issue.readingStopped == true,
                  ))
                    _reading(story),
                  if (_raised(story)) _warning(story),
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
                              story.workIssues.isEmpty
                                  ? 'no issues yet'
                                  : '${story.workIssues.length} ${story.workIssues.length == 1 ? 'issue' : 'issues'}',
                            ),
                          ),
                          if (story.workIssues.isNotEmpty && story.completedIssues != null) ...[
                            SizedBox(
                              width: PlanningStyle.progress,
                              child: LinearProgressIndicator(
                                value: story.completedIssues! / story.workIssues.length,
                                semanticsLabel: 'Issues in Review or Finished',
                              ),
                            ),
                            const SizedBox(width: Space.tight),
                            Text('${story.completedIssues}/${story.workIssues.length}'),
                          ],
                        ],
                      ),
                    ),
                  if (story.issuesReported && story.completedIssues == null) const Text('Issue progress not reported'),
                  if (open)
                    for (final (index, issue) in story.issues.indexed)
                      Padding(
                        key: Key(_issueKey(story, index)),
                        padding: const EdgeInsets.only(top: Space.small),
                        child: _markFound(
                          context,
                          found.contains(issue.id) ? issue.id : null,
                          PlanningIssueRow(
                            issue: issue,
                            refusal: board.refusalOf('issue ${issue.id}'),
                            onOpen: () => widget.onIssue(story.id, issue.id),
                            sizeBadge: _size(context, issue.size, key: 'planning-issue-size ${issue.id}'),
                            correction: planningCorrectionOffer(
                              board: board,
                              id: issue.id,
                              story: false,
                              state: issue.state,
                              warnings: issue.warnings,
                              link: issue.link,
                              openLink: widget.host.openLink,
                            ),
                          ),
                        ),
                      ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The key of [story]'s issue row at [index]: by its id, and for an id the list holds twice by its repository and
/// place too, so no two rows share a key.
String _issueKey(PlanStory story, int index) {
  final issue = story.issues[index];
  return story.issues.where((other) => other.id == issue.id).length > 1
      ? 'planning-issue ${story.id} ${issue.id} ${issue.repository} $index'
      : 'planning-issue ${story.id} ${issue.id}';
}

/// Marks the row of the issue [found] by the search with a highlighted background; any other row stays as it is.
Widget _markFound(BuildContext context, String? found, Widget row) => found == null
    ? row
    : DecoratedBox(
        key: Key('planning-issue-found $found'),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.secondaryContainer,
          borderRadius: BorderRadius.circular(Space.tight),
        ),
        child: row,
      );

/// Opens the start dialog for [story]'s work toward [target], or for [issues] of it alone; nothing is staged, so
/// closing the dialog before a task starts leaves everything where it stood.
Future<void> _openStart(
  BuildContext context,
  PlanningBoard board,
  PlanStory story,
  PlanState target, {
  required PlanningStartTask startTask,
  required PlanningAgents agents,
  required PlanningWizards? wizards,
  required Map<PlanningBoard, String> machines,
  required List<String> unavailableMachines,
  required Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)
  onStarted,
  List<String>? issues,
}) async {
  if (board.startUnavailable(story) != null) return;
  final work = StoryWork(
    board,
    story,
    target.name,
    startTask: startTask,
    agentChoices: agents,
    machines: machines,
    unavailableMachines: unavailableMachines,
    onStarted: onStarted,
    issues: issues,
  );
  try {
    await showStoryWork(context, work, wizards: wizards);
  } finally {
    work.dispose();
  }
}

class _PlanningDetailView extends StatefulWidget {
  const _PlanningDetailView({
    required this.pageButton,
    required this.pageNote,
    required this.startTask,
    required this.agents,
    required this.wizards,
    required this.machines,
    required this.unavailableMachines,
    required this.onStarted,
    required this.board,
    required this.story,
    required this.issue,
    required this.onBoard,
    required this.onParent,
    required this.onStory,
    required this.onIssue,
    required this.onIssueOf,
    required this.onTask,
    required this.taskUnavailable,
    required this.openLink,
    super.key,
  });
  final PlanningStartTask startTask;
  final PlanningAgents agents;
  final PlanningWizards? wizards;
  final Map<PlanningBoard, String> machines;
  final List<String> unavailableMachines;
  final Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>) onStarted;
  final PlanningBoard board;
  final Future<void> Function(Uri) openLink;
  final String story;
  final String? issue;
  final Widget? pageButton;
  final Widget? pageNote;
  final VoidCallback onBoard, onParent;
  final ValueChanged<String> onStory, onIssue, onTask;
  final void Function(String story, String issue) onIssueOf;
  final String? Function(String) taskUnavailable;

  @override
  State<_PlanningDetailView> createState() => _PlanningDetailViewState();
}

class _PlanningDetailViewState extends State<_PlanningDetailView> {
  late final detail = PlanningDetail(widget.board, widget.story, widget.issue);

  /// A move of one issue into a working state is its start: the dialog opens for that issue alone.
  void _startIssue(String issue, String to) {
    final story = widget.board.stories.where((story) => story.id == widget.story).firstOrNull;
    final target = widget.board.state(to);
    if (story == null || target == null) return;
    unawaited(
      _openStart(
        context,
        widget.board,
        story,
        target,
        startTask: widget.startTask,
        agents: widget.agents,
        wizards: widget.wizards,
        machines: widget.machines,
        unavailableMachines: widget.unavailableMachines,
        onStarted: widget.onStarted,
        issues: [issue],
      ),
    );
  }

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
              TextButton(key: const Key('planning-crumb 0'), onPressed: widget.onBoard, child: const Text('Board')),
              if (widget.story.isNotEmpty) ...[
                const Text('›'),
                TextButton(
                  key: const Key('planning-crumb 1'),
                  onPressed: widget.issue == null ? null : widget.onParent,
                  child: Text(planningShown(widget.story, multiline: false)),
                ),
              ],
              if (widget.issue != null) ...[
                const Text('›'),
                // A text button pads its label; the last crumb, plain text, takes the same room.
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: Space.small),
                  child: Text(planningShown(widget.issue!, multiline: false), key: const Key('planning-crumb 2')),
                ),
              ],
              ?widget.pageButton,
            ],
          ),
          ?widget.pageNote,
          // The issue page names the project in its own header.
          if (widget.issue == null) Text('Project ${widget.board.project} · From ${widget.board.backend.label}'),
          if (detail.loading) ...[
            Text(
              widget.issue == null ? 'Reading the latest details…' : 'Waiting for the planner’s answer.',
              key: const Key('planning-waiting'),
            ),
            if (widget.board.saving) const Text('A change from this window is still being saved.'),
          ],
          if (widget.board.problem case final problem?) Text(problem, key: const Key('planning-watch-error')),
          if (!widget.board.live) const Text('Planning is no longer live. The last answer may be out of date.'),
          if (widget.board.problem != null || !widget.board.live)
            TextButton.icon(
              key: const Key('planning-reload'),
              onPressed: widget.board.reloadHeld ? null : () => unawaited(widget.board.reloadPlanning()),
              icon: const Icon(Icons.refresh),
              label: const Text('Reload planning'),
            ),
          if (widget.board.moveProblem case final problem?)
            Text(
              problem,
              key: const Key('planning-reload-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
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
              onStartIssue: _startIssue,
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
              onIssueOf: widget.onIssueOf,
              onTask: widget.onTask,
              taskUnavailable: widget.taskUnavailable,
              openLink: widget.openLink,
            ),
        ],
      ),
    ),
  );
}

/// Asks for the first words of an idea, as many as the planner takes.
class _IdeaDialog extends StatefulWidget {
  const _IdeaDialog();

  @override
  State<_IdeaDialog> createState() => _IdeaDialogState();
}

class _IdeaDialogState extends State<_IdeaDialog> {
  static const _most = 4000;
  final _words = TextEditingController();

  // The planner counts characters, not what the field shows as one.
  bool get _sendable => _words.text.trim().isNotEmpty && _words.text.runes.length <= _most;

  @override
  void dispose() {
    _words.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Discuss an idea'),
    content: SizedBox(
      width: PlanningStyle.column * 2,
      child: TextField(
        key: const Key('planning-idea-words'),
        controller: _words,
        autofocus: true,
        minLines: 4,
        maxLines: 12,
        maxLength: _most,
        onChanged: (_) => setState(() {}),
        decoration: const InputDecoration(labelText: 'Your idea', hintText: 'What you have in mind, in your own words'),
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
      FilledButton(
        key: const Key('planning-idea-discuss'),
        onPressed: _sendable ? () => Navigator.pop(context, _words.text) : null,
        child: const Text('Discuss'),
      ),
    ],
  );
}

/// [text] on one line, as the screen shows what an agent or a repository wrote.
String _line(String text) => planningShown(text, multiline: false);

/// A card being dragged: its story and the board's revision when the drag began.
typedef _CardDrag = ({String story, String revision});

/// One story filter as the dropdown and the segment show it: null for All.
typedef _FilterEntry = ({PlanningFilter? filter, String label, String number, String hint, PlanningStateKind? kind});

/// A scroll that starts where its column was left, read when it attaches, so a column built again keeps its place.
class _RememberedScroll extends ScrollController {
  _RememberedScroll(this._offset) : super(keepScrollOffset: false);

  final double Function() _offset;

  @override
  double get initialScrollOffset => _offset();
}

/// Shows [child] only once [active] has lasted longer than a moment, so a quick read shows nothing.
class _SlowProgress extends StatefulWidget {
  const _SlowProgress({required this.active, required this.child});

  /// Whether the read runs.
  final bool active;

  /// What shows once it runs long.
  final Widget child;

  @override
  State<_SlowProgress> createState() => _SlowProgressState();
}

class _SlowProgressState extends State<_SlowProgress> {
  /// How long a read runs before its progress shows.
  static const _moment = Duration(seconds: 1);

  Timer? _timer;
  bool _slow = false;

  @override
  void initState() {
    super.initState();
    _follow();
  }

  @override
  void didUpdateWidget(_SlowProgress old) {
    super.didUpdateWidget(old);
    if (old.active != widget.active) _follow();
  }

  void _follow() {
    _timer?.cancel();
    _timer = null;
    if (!widget.active) {
      _slow = false;
      return;
    }
    _timer = Timer(_moment, () {
      if (mounted) setState(() => _slow = true);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => _slow && widget.active ? widget.child : const SizedBox.shrink();
}
