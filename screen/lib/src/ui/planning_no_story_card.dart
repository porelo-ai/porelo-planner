import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'planning_detail_widgets.dart';
import 'planning_issue_row.dart';
import 'style.dart';

/// One state's issues in no story, folded independently of its stories.
class PlanningNoStoryCard extends StatelessWidget {
  /// Uses the same issue rows and size badges as the board's stories.
  const PlanningNoStoryCard({
    required this.board,
    required this.state,
    required this.onIssue,
    required this.sizeBadge,
    this.phaseFolded = false,
    super.key,
  });

  /// The board supplying the issues and saving their changes.
  final PlanningBoard board;

  /// The workflow state whose issues are shown.
  final String state;

  /// Opens an issue's own page on this board's provider.
  final ValueChanged<String> onIssue;

  /// Whether the phase shows compact tiles instead of expandable cards.
  final bool phaseFolded;

  /// Draws the board's reported size badge.
  final Widget? Function(PlanIssue) sizeBadge;

  @override
  Widget build(BuildContext context) {
    final issues = board.noStoryIn(state);
    final open = board.preferences.expandedNoStoryStates.contains(state) || board.preferences.search.trim().isNotEmpty;
    final all = board.preferences.allNoStoryStates.contains(state);
    if (phaseFolded) {
      return Card(
        key: Key('planning-no-story $state'),
        margin: const EdgeInsets.only(bottom: Space.tight),
        shape: _DashedBorder(
          side: BorderSide(color: Theme.of(context).colorScheme.outline),
          borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
        ),
        child: Padding(
          padding: const EdgeInsets.all(Space.tight),
          child: Row(
            children: [
              const Expanded(child: Text('No story', maxLines: 1, overflow: TextOverflow.ellipsis)),
              Text('${issues.length}'),
            ],
          ),
        ),
      );
    }
    return Card(
      key: Key('planning-no-story $state'),
      margin: const EdgeInsets.only(bottom: Space.small),
      shape: _DashedBorder(
        side: BorderSide(color: Theme.of(context).colorScheme.outline),
        borderRadius: BorderRadius.circular(PlanningStyle.radiusSmall),
      ),
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextButton(
              key: Key('planning-no-story-expand $state'),
              onPressed: () => board.expandNoStory(state),
              child: Row(
                children: [
                  Icon(open ? Icons.expand_more : Icons.chevron_right, size: PlanningStyle.mark),
                  const Expanded(child: Text('No story')),
                  ?planningRuleMark([
                    for (final issue in issues) ...board.rulesOf(issue.id),
                  ], key: Key('planning-no-story-rule $state')),
                  Text('${issues.length} ${issues.length == 1 ? 'issue' : 'issues'}'),
                ],
              ),
            ),
            if (open) ...[
              for (final issue in all ? issues : issues.take(20))
                Padding(
                  padding: const EdgeInsets.only(top: Space.small),
                  child: PlanningIssueRow(
                    issue: issue,
                    onOpen: () => onIssue(issue.id),
                    sizeBadge: sizeBadge(issue),
                    menu: _menu(context, issue),
                  ),
                ),
              if (!all && issues.length > 20)
                TextButton(
                  key: Key('planning-no-story-all $state'),
                  onPressed: () => board.showAllNoStory(state),
                  child: Text('Show all ${issues.length}'),
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _menu(BuildContext context, PlanIssue issue) => PopupMenuButton<(String, String, int)>(
    key: Key('planning-no-story-menu ${issue.id}'),
    tooltip: 'Change ${planningShown(issue.id, multiline: false)}',
    enabled: board.live && !board.holdsMoves && !board.actionBusy && !board.waitsOnItsSave('issue ${issue.id}'),
    icon: const Icon(Icons.more_vert, size: PlanningStyle.rowIcon),
    itemBuilder: (_) => [
      PopupMenuItem(
        value: ('hang', board.snapshot!.revision, board.generation),
        child: const Text('Hang into a story…'),
      ),
      PopupMenuItem(value: ('move', board.snapshot!.revision, board.generation), child: const Text('Move to…')),
    ],
    onSelected: (choice) => unawaited(_choose(context, issue, choice.$1, choice.$2, choice.$3)),
  );

  Future<void> _choose(BuildContext context, PlanIssue issue, String action, String revision, int generation) async {
    if (board.disposed || board.generation != generation) return;
    if (action == 'hang') {
      final story = await showDialog<String>(
        context: context,
        builder: (_) => _StorySearch(board: board),
      );
      if (story == null || board.disposed || board.generation != generation) return;
      if (!board.noStory.any((each) => each.id == issue.id)) {
        board.refuseMove(issue.id, 'it is no longer in no story.');
        return;
      }
      await board.hangIssue(issue.id, story, revision: board.snapshot?.revision);
    } else {
      final to = await showDialog<String>(
        context: context,
        builder: (context) => SimpleDialog(
          title: const Text('Move to…'),
          children: [
            for (final target in board.states)
              if (target.name != issue.state && board.issueMayEnter(issue, target))
                SimpleDialogOption(
                  key: Key('planning-move-to ${issue.id} ${target.name}'),
                  onPressed: () => Navigator.pop(context, target.name),
                  child: Text(planningShown(target.name, multiline: false)),
                ),
          ],
        ),
      );
      if (to == null || board.disposed || board.generation != generation) return;
      await board.moveIssue(issue.id, to, shown: issue.state, revision: revision);
    }
  }
}

class _StorySearch extends StatefulWidget {
  const _StorySearch({required this.board});
  final PlanningBoard board;

  @override
  State<_StorySearch> createState() => _StorySearchState();
}

class _StorySearchState extends State<_StorySearch> {
  String _search = '';

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Hang into a story…'),
    content: SizedBox(
      width: 420,
      height: 400,
      child: Column(
        children: [
          TextField(
            key: const Key('planning-hang-search'),
            decoration: const InputDecoration(labelText: 'Search stories by reference or title'),
            onChanged: (value) => setState(() => _search = value.trim().toLowerCase()),
          ),
          Expanded(
            child: AnimatedBuilder(
              animation: widget.board,
              builder: (context, _) {
                final stories = widget.board.stories
                    .where(
                      (story) =>
                          story.id.isNotEmpty &&
                          story.issuesReported &&
                          (story.id.toLowerCase().contains(_search) || story.title.toLowerCase().contains(_search)),
                    )
                    .toList();
                return ListView.builder(
                  itemCount: stories.length,
                  itemBuilder: (context, index) {
                    final story = stories[index];
                    return ListTile(
                      key: Key('planning-hang-story ${story.id}'),
                      title: Text(planningShown(story.id, multiline: false)),
                      subtitle: Text(planningShown(story.title, multiline: false)),
                      onTap: () => Navigator.pop(context, story.id),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    ),
    actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel'))],
  );
}

class _DashedBorder extends RoundedRectangleBorder {
  const _DashedBorder({required super.side, super.borderRadius});

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {
    final path = getOuterPath(rect.deflate(side.width / 2), textDirection: textDirection);
    final paint = Paint()
      ..color = side.color
      ..style = PaintingStyle.stroke
      ..strokeWidth = side.width;
    for (final metric in path.computeMetrics()) {
      for (var at = 0.0; at < metric.length; at += 8) {
        canvas.drawPath(metric.extractPath(at, math.min(at + 4, metric.length)), paint);
      }
    }
  }
}
