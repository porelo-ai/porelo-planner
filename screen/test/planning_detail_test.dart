import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

Future<void> _flush() => Future<void>.delayed(Duration.zero);

class _StrictPlanningBoard extends PlanningBoard {
  _StrictPlanningBoard(super.backend, super.project);

  @override
  void removeListener(void Function() listener) {
    if (disposed) throw StateError('Listener removed from a disposed board');
    super.removeListener(listener);
  }
}

void main() {
  // Harm: completing a request after leaving the screen notifies a disposed model or continues reading.
  test('late replies after disposal cannot publish or start the dependent read', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final pending = Completer<PlanStoryDetail>();
    backend.readStory = (_, _) => pending.future;
    final detail = PlanningDetail(board, 'ST291', null);
    final notifications = <String>[];
    detail.addListener(() => notifications.add('changed'));
    detail.dispose();
    pending.complete(
      PlanStoryDetail.from({
        'story': {'id': 'ST291', 'title': 'Late'},
      }),
    );
    await _flush();
    expect(notifications, isEmpty);
    expect(detail.story, isNull);
    expect(detail.issues, isNull);
    board.dispose();
    expect(backend.watchers, 0);
    await backend.end();
  });

  // Harm: a slow old snapshot overwrites the newer story after the watch changed.
  test('a newer board replacement invalidates an older detail reply', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final pending = <Completer<PlanStoryDetail>>[];
    backend.readStory = (_, _) {
      final reply = Completer<PlanStoryDetail>();
      pending.add(reply);
      return reply.future;
    };
    final detail = PlanningDetail(board, 'ST291', null);
    backend.push('demo', mockPlanningBoard());
    await _flush();
    expect(pending, hasLength(2));
    pending.last.complete(
      PlanStoryDetail.from({
        'story': {'id': 'ST291', 'title': 'New'},
      }),
    );
    await _flush();
    expect(detail.story!.story!.title, 'New');
    pending.first.complete(
      PlanStoryDetail.from({
        'story': {'id': 'ST291', 'title': 'Old'},
      }),
    );
    await _flush();
    expect(detail.story!.story!.title, 'New');
    expect(detail.problem, isNull);
    expect(detail.issues!.reported, isTrue);
    detail.dispose();
    final afterDispose = <String>[];
    backend.readStory = (_, _) async {
      afterDispose.add('read');
      return PlanStoryDetail.from({});
    };
    backend.push('demo', mockPlanningBoard());
    await _flush();
    expect(afterDispose, isEmpty);
    board.dispose();
    await backend.end();
  });

  // Harm: a reply for another identifier is rendered under the requested breadcrumb.
  test('a mismatched story is refused without publishing it', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    backend.readStory = (_, _) async => PlanStoryDetail.from({
      'story': {'id': 'other', 'title': 'Wrong'},
    });
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    expect(detail.story, isNull);
    expect(detail.problem, contains('did not return the requested story'));
    expect(detail.loading, isFalse);
    detail.dispose();
    board.dispose();
    await backend.end();
  });
  // Harm: removing a provider leaves detail disposal calling into its released board.
  test('detail disposal does not remove a listener from a disposed board', () async {
    final backend = MockPlanningSource();
    final board = _StrictPlanningBoard(backend, 'demo');
    await _flush();
    final pending = Completer<PlanStoryDetail>();
    backend.readStory = (_, _) => pending.future;
    final detail = PlanningDetail(board, 'ST291', null);
    final changed = <String>[];
    detail.addListener(() => changed.add('changed'));
    board.dispose();
    expect(detail.dispose, returnsNormally);
    pending.complete(
      PlanStoryDetail.from({
        'story': {'id': 'ST291'},
      }),
    );
    await _flush();
    expect(changed, isEmpty);
    expect(backend.watchers, 0);
    await backend.end();
  });

  // Harm: an in-flight issue-list read publishes a new story beside an older list.
  test('a reload publishes its parts together and names a failed issue list', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    final oldTitle = detail.story!.story!.title;
    final oldIssues = detail.issues;
    final pending = Completer<PlanIssueList>();
    backend.readIssues = (_, _) => pending.future;
    final replacement = mockPlanningBoard();
    mockPlanningStory(replacement, 'ST291')!['title'] = 'Changed after the watch';
    backend.push('demo', replacement);
    await _flush();
    expect(detail.loading, isTrue);
    expect(detail.story!.story!.title, oldTitle);
    expect(detail.issues, same(oldIssues));
    pending.completeError(mockRefusal('WorkNotAllowed', {'detail': 'List locked'}));
    await _flush();
    expect(detail.loading, isFalse);
    expect(detail.story!.story!.title, 'Changed after the watch');
    expect(detail.issues, same(oldIssues));
    expect(detail.issuesProblem, 'Issue list: List locked');
    detail.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a failed parent hides a successfully read issue or invents an owner.
  test('issue content survives a parent failure and an omitted owner is not inferred', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    backend.partFailures['Story'] = mockRefusal('WorkNotAllowed', {'detail': 'Parent locked'});
    final detail = PlanningDetail(board, 'ST291', 'B541');
    await _flush();
    expect(detail.issue!.issue!.id, 'B541');
    expect(detail.issue!.issue!.link, 'https://example.org/issues/B541');
    expect(detail.story, isNull);
    expect(detail.storyProblem, 'Story: Parent locked');
    mockPlanningStory(backend.boards['demo']!, 'ST291')!['issues'][1]['story'] = null;
    await detail.reload();
    expect(detail.issue!.issue!.id, 'B541');
    expect(detail.storyProblem, 'Story: Parent locked');
    expect(detail.story, isNull);
    detail.dispose();
    board.dispose();
    await backend.end();
  });
  // Harm: a failed issue refresh erases the last issue and its owning story.
  test('a failed issue refresh retains the last published issue group', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', 'B541');
    await _flush();
    final priorIssue = detail.issue, priorStory = detail.story;
    expect(priorIssue, isNotNull);
    expect(priorStory, isNotNull);
    backend.partFailures['Issue'] = mockRefusal('WorkNotAllowed', {'detail': 'Issue locked'});
    await detail.reload();
    expect(detail.issue, same(priorIssue));
    // The story read succeeded, so it replaces its own part; only the failed issue keeps its last answer.
    expect(detail.story!.story!.id, priorStory!.story!.id);
    expect(detail.storyProblem, isNull);
    expect(detail.issueProblem, 'Issue: Issue locked');
    detail.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a story's page stages a move for an issue of another story, which it does not show.
  test('a story page refuses a move for an issue it does not list', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    detail.queueMove('B553', 'Review');
    expect(detail.pendingMoves, isEmpty, reason: 'B553 belongs to ST292, not to the page shown');
    expect(board.pending, isEmpty, reason: 'nothing is staged on the board either');
    expect(detail.moveProblem, contains('B553'), reason: 'the person reads which move was not taken');
    detail.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a draft edits sibling states or appears saved before the daemon acknowledgement.
  test('one issue stays pending until its confirmed save is reread', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    detail.queueMove('B541', 'Review');
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B541').state, 'Implementing');
    expect(detail.pendingMoves.keys, ['B541']);
    final held = Completer<void>();
    backend.moveHeld = held;
    final save = detail.saveMoves();
    expect(detail.saving, isTrue);
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B541').state, 'Implementing');
    held.complete();
    await save;
    expect(detail.pendingMoves, isEmpty);
    expect(backend.moveCalls, hasLength(1));
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B541').state, 'Review');
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B540').state, 'Implementing');
    detail.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: stale planning is overwritten or pending work is silently discarded.
  test('stale issue moves reread and keep only still-applicable drafts without retrying writes', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    detail.queueMove('B540', 'Review');
    detail.queueMove('B541', 'Review');
    final raw = backend.boards['demo']!;
    raw['revision'] = 'elsewhere';
    final story = mockPlanningStory(raw, 'ST291')!;
    (story['issues'] as List).cast<Map<String, dynamic>>().singleWhere((issue) => issue['id'] == 'B540')['state'] =
        'Review';
    await detail.saveMoves();
    expect(detail.pendingMoves.keys, ['B541']);
    expect(backend.moveCalls, hasLength(1));
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B541').state, 'Implementing');
    expect(detail.moveNotice, contains('save again'));
    await detail.saveMoves();
    expect(backend.moveCalls, hasLength(2));
    expect(detail.pendingMoves, isEmpty);
    expect(detail.issues!.issues.singleWhere((issue) => issue.id == 'B541').state, 'Review');
    detail.dispose();
    board.dispose();
    await backend.end();
  });

  // Harm: a save finishing after navigation notifies a disposed detail model.
  test('a held save completes without notifying after disposal', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    await _flush();
    final detail = PlanningDetail(board, 'ST291', null);
    await _flush();
    detail.queueMove('B541', 'Review');
    final held = Completer<void>();
    backend.moveHeld = held;
    final save = detail.saveMoves();
    final changes = <String>[];
    detail.addListener(() => changes.add('changed'));
    detail.dispose();
    held.complete();
    await save;
    expect(changes, isEmpty);
    board.dispose();
    await backend.end();
  });
}
