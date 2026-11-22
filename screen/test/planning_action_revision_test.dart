import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

Future<(FakePlanningSource, PlanningBoard)> _open() async {
  final source = FakePlanningSource(smallBoard());
  final board = PlanningBoard(source, 'demo');
  addTearDown(() async {
    board.dispose();
    await source.close();
  });
  await Future<void>.delayed(Duration.zero);
  expect(board.live, isTrue);
  return (source, board);
}

void main() {
  // Harm: the person's priority change is lost when an unrelated save moves the board.
  test('a stale priority retries once against the new revision', () async {
    final (source, board) = await _open();
    source.board = smallBoard(revision: 'r2');

    expect(await board.setPriority('S1', 'Low'), isTrue);

    expect(source.priorities, [('demo', 'S1', 'Low', 'r1'), ('demo', 'S1', 'Low', 'r2')]);
    await Future<void>.delayed(Duration.zero);
    expect(board.stories.first.priority, 'Low');
    expect(board.actionProblem, isNull);
  });

  // Harm: retrying overwrites another person's priority without a fresh choice.
  test('a changed priority requires another choice and names the story', () async {
    final (source, board) = await _open();
    final next = smallBoard(revision: 'r2');
    (next['stories'] as List).first['priority'] = 'Highest';
    source.board = next;

    expect(await board.setPriority('S1', 'Low'), isFalse);

    expect(source.priorities, [('demo', 'S1', 'Low', 'r1')]);
    expect(board.actionProblem, allOf(contains('S1'), contains('Highest')));
    expect(board.pendingPriorities, isEmpty);
  });

  // Harm: a board arrival hides a refused action before the person can see it.
  test('an action refusal survives a board arrival until the next action', () async {
    final (source, board) = await _open();
    source.priorityFailure = const PlanningRefusal('MoveNotAllowed');
    expect(await board.setPriority('S1', 'Low'), isFalse);
    final refusal = board.actionProblem;
    expect(refusal, isNotNull);

    source.replace(smallBoard(revision: 'r2'));
    await Future<void>.delayed(Duration.zero);

    expect(board.snapshot!.revision, 'r2');
    expect(board.actionProblem, refusal);
    expect(board.refusal?.name, 'MoveNotAllowed');
    source.priorityFailure = null;
    await board.move('S1', 'Review');
    expect(board.actionProblem, isNull);
    expect(board.refusal, isNull);
  });

  // Harm: a saved notice for an earlier move claims that a refused priority was saved.
  test('starting a priority clears the earlier move notice', () async {
    final (source, board) = await _open();
    await board.move('S1', 'Review');
    expect(board.moveNotice, isNotNull);
    source.priorityFailure = const PlanningRefusal('MoveNotAllowed');

    expect(await board.setPriority('S1', 'Low'), isFalse);

    expect(board.moveNotice, isNull);
    expect(board.actionProblem, isNotNull);
  });

  // Harm: the person's worker change is lost when an unrelated save moves the board.
  test('a stale worker retries once against the new revision', () async {
    final (source, board) = await _open();
    final issue = board.stories.first.issues.single;
    source.board = smallBoard(revision: 'r2');

    expect(await board.setWorker(issue, IssueWorker.manual), isNull);

    expect(source.workers, [('demo', 'I1', IssueWorker.manual, 'r1'), ('demo', 'I1', IssueWorker.manual, 'r2')]);
    await Future<void>.delayed(Duration.zero);
    expect(board.stories.first.issues.single.worker, IssueWorker.manual);
    expect(board.actionProblem, isNull);
  });

  // Harm: repeated stale saves keep writing after the person has lost control of the action.
  test('a second stale priority refusal stops after two calls', () async {
    final (source, board) = await _open();
    source.board = smallBoard(revision: 'r2');
    source.priorityFailure = const PlanningRefusal('StaleRevision');

    expect(await board.setPriority('S1', 'Low'), isFalse);

    expect(source.priorities, [('demo', 'S1', 'Low', 'r1'), ('demo', 'S1', 'Low', 'r2')]);
    expect(board.actionProblem, isNotNull);
    expect(board.pendingPriorities, isEmpty);
  });

  // Harm: retrying a worker change ignores another person's choice of who works the issue.
  test('a changed worker requires another choice and names the issue', () async {
    final (source, board) = await _open();
    final issue = board.stories.first.issues.single;
    final next = smallBoard(revision: 'r2');
    ((next['stories'] as List).first['issues'] as List).first['worker'] = 'MANUAL';
    source.board = next;

    expect(await board.setWorker(issue, IssueWorker.manual), isNotNull);

    expect(source.workers, [('demo', 'I1', IssueWorker.manual, 'r1')]);
    expect(board.actionProblem, allOf(contains(issue.id), contains(IssueWorker.manual.wire)));
  });

  // Harm: the person's first priority on a story with none is lost when an unrelated save moves the board.
  test('a stale priority on a story without one retries once', () async {
    final first = smallBoard();
    (first['stories'] as List).first.remove('priority');
    final source = FakePlanningSource(first);
    final board = PlanningBoard(source, 'demo');
    addTearDown(() async {
      board.dispose();
      await source.close();
    });
    await Future<void>.delayed(Duration.zero);
    final next = smallBoard(revision: 'r2');
    (next['stories'] as List).first.remove('priority');
    source.board = next;

    expect(await board.setPriority('S1', 'Low'), isTrue);

    expect(source.priorities, [('demo', 'S1', 'Low', 'r1'), ('demo', 'S1', 'Low', 'r2')]);
  });

  // Harm: a click refused before it reaches the planner hides a refusal the person has not read yet.
  test('a click refused while busy keeps the earlier refusal', () async {
    final (source, board) = await _open();
    source.priorityFailure = const PlanningRefusal('MoveNotAllowed');
    expect(await board.setPriority('S1', 'Low'), isFalse);
    expect(board.refusal?.name, 'MoveNotAllowed');
    board.actionBusy = true;

    expect(await board.setPriority('S1', 'Medium'), isFalse);

    expect(board.refusal?.name, 'MoveNotAllowed', reason: 'a refused click is no action of its own');
    expect(source.priorities, hasLength(1));
  });

  // Harm: a board read that failed during the retry is kept as the action's refusal and outlives the mended read.
  test('a failed read during the retry leaves the stale refusal as the action answer', () async {
    final (source, board) = await _open();
    source.board = smallBoard(revision: 'r2');
    source.storiesFailure = const PlanningRefusal('NoForgeAccess', {'address': 'https://github.com'});

    expect(await board.setPriority('S1', 'Low'), isFalse);

    expect(board.refusal?.name, 'StaleRevision');
    expect(source.priorities, hasLength(1), reason: 'nothing sent on a board not read');
  });
}
