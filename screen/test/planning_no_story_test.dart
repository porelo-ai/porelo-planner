import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

Map<String, dynamic> _looseIssue() => {
  'id': 'B900',
  'repository': 'porelo',
  'title': 'Planning work in no story',
  'state': 'Backlog',
  'size': 'S',
  'worker': 'AGENT',
  'blockedBy': <String>[],
};

class _Source extends MockPlanningSource {
  final reads = <Completer<PlanIssueList>>[];
  final lists = <(String, String, List<String>, String)>[];
  Object? editFailure;
  bool previewChangesOnce = false;
  bool takeOutOnPreviewChange = false;
  bool outsideTimesOut = false;
  int previews = 0;
  int boardReads = 0;
  Object? boardReadFailure;
  bool automatic = false;

  @override
  Future<PlanningSnapshot> stories(String project) {
    boardReads++;
    if (boardReadFailure case final error?) return Future.error(error);
    return super.stories(project);
  }

  @override
  Future<PlanningMovePreview> previewMoves(String project, List<PlanningMove> moves, String revision) {
    previews++;
    if (outsideTimesOut && moves.any((move) => move.id == 'B902')) {
      return Future.value(
        PlanningMovePreview.from({
          'revision': revision,
          'digest': FakePlanningSource.digestAt(revision),
          'issues': <Object>[],
          'stories': <Object>[],
          'warnings': <Object>[],
        }),
      );
    }
    return super.previewMoves(project, moves, revision);
  }

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    String revision, {
    required String digest,
    bool confirm = false,
  }) async {
    if (previewChangesOnce) {
      previewChangesOnce = false;
      final current = board(project);
      final stories = (current['stories'] as List).cast<Map<String, dynamic>>();
      final removed = (stories.first['issues'] as List).cast<Map<String, dynamic>>().first;
      boards[project] = {
        ...current,
        'revision': 'changed-preview',
        if (takeOutOnPreviewChange) ...{
          'stories': [
            {...stories.first, 'issues': <Object>[]},
            stories.last,
          ],
          'issues': [...(current['issues'] as List), removed],
        },
      };
      throw const PlanningRefusal('PreviewChanged');
    }
    if (outsideTimesOut && moves.any((move) => move.id == 'B902')) {
      throw const PlanningRefusal('TimedOut');
    }
    return super.saveMoves(project, moves, revision, digest: digest, confirm: confirm);
  }

  @override
  Future<PlanIssueList> listIssues(String project) {
    final reply = Completer<PlanIssueList>();
    reads.add(reply);
    if (automatic) {
      reply.complete(
        PlanIssueList.from({
          'revision': board(project)['revision'],
          'issues': mockPlanningNoStoryIssues(board(project)),
        }),
      );
    }
    return reply.future;
  }

  @override
  Future<String> setStoryIssues(String project, String story, List<String> issues, String revision) async {
    lists.add((project, story, List.of(issues), revision));
    if (editFailure case final error?) throw error;
    return super.setStoryIssues(project, story, issues, revision);
  }
}

Future<void> _turn() => Future<void>.delayed(Duration.zero);

Future<(_Source, PlanningBoard)> _opened({bool automatic = false}) async {
  final source = _Source();
  source.boards['demo'] = {
    ...smallBoard(),
    'issues': [_looseIssue()],
  };
  final board = PlanningBoard(source, 'demo');
  addTearDown(() async {
    board.dispose();
    await source.end();
  });
  await _turn();
  source.reads.single.complete(
    PlanIssueList.from({
      'revision': 'r1',
      'issues': [_looseIssue()],
    }),
  );
  await _turn();
  source.automatic = automatic;
  return (source, board);
}

void main() {
  // Harm: an issue the planner now shows warned, its parent cut, is dropped from the no-story area, so it is on no card.
  // A guard of behavior the screen already had: the no-story area showed such an issue before it was warned.
  test('an issue in no story that carries a warning is shown there', () async {
    final source = _Source();
    final warned = {
      ..._looseIssue(),
      'id': 'B901',
      'parent': null,
      'warnings': [
        {
          'kind': 'ParentInOtherRepository',
          'text': 'Its parent is in another repository, so it is shown without its parent.',
        },
      ],
    };
    source.boards['demo'] = {
      ...smallBoard(),
      'issues': [_looseIssue(), warned],
    };
    final board = PlanningBoard(source, 'demo');
    addTearDown(() async {
      board.dispose();
      await source.end();
    });
    await _turn();
    source.reads.single.complete(
      PlanIssueList.from({
        'revision': 'r1',
        'issues': [_looseIssue(), warned],
      }),
    );
    await _turn();
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900', 'B901']);
  });

  // Harm: an issue in no story in a state outside the workflow cannot be moved into it, or its unanswered move is
  // released before the list in no story shows where it stands.
  test('an issue in no story outside the workflow is moved from its own state and waits for the list', () async {
    final (source, board) = await _opened();
    final data = source.board('demo');
    source.push('demo', {...data, 'revision': 'outside'});
    await _turn();
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'outside',
        'issues': [
          _looseIssue(),
          {..._looseIssue(), 'id': 'B902', 'state': 'Accepted'},
        ],
      }),
    );
    await _turn();
    source.outsideTimesOut = true;
    await board.moveIssue('B902', 'Backlog');
    expect(board.pendingIssues['B902']!.from, 'Accepted', reason: 'moved from the state it stands in');
    expect(board.pendingIssues['B902']!.inNoStory, isTrue);
    source.push('demo', {...data, 'revision': 'returned'});
    await _turn();
    expect(board.pendingIssues, contains('B902'), reason: 'its place is known only from the list');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'returned',
        'issues': [
          _looseIssue(),
          {..._looseIssue(), 'id': 'B902'},
        ],
      }),
    );
    await _turn();
    expect(board.pendingIssues, isEmpty);
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900', 'B902']);
  });

  // Harm: preview recovery drops a move when the issue left its story while the save was refused.
  test('preview recovery waits for a story issue that is now in no story', () async {
    final (source, board) = await _opened();
    source.previewChangesOnce = true;
    source.takeOutOnPreviewChange = true;
    final moving = board.moveIssue('I1', 'Finished');
    await _turn();
    expect(source.previews, 1);
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'changed-preview',
        'issues': [
          _looseIssue(),
          {..._looseIssue(), 'id': 'I1'},
        ],
      }),
    );
    await moving;
    expect(source.previews, 2);
    expect(board.question?.again, isTrue);
    source.automatic = true;
    await board.confirmMove();
    expect(board.noStoryIn('Finished').map((issue) => issue.id), ['I1']);
    expect(board.moveProblem, isNull);
  });

  // Harm: persistently mismatched replies cause an endless loop of board reads.
  test('a mismatch after the catch-up board read reports a problem without reading the board again', () async {
    final (source, board) = await _opened();
    source.push('demo', {...source.board('demo'), 'revision': 'r2'});
    await _turn();
    for (var i = 0; i < 6; i++) {
      source.reads.last.complete(
        PlanIssueList.from({
          'revision': 'ahead',
          'issues': [_looseIssue()],
        }),
      );
      await _turn();
    }
    expect(source.boardReads, 1);
    expect(board.noStory, isEmpty);
    expect(board.noStoryProblem, isNotNull);
  });

  // Harm: a failed read leaves an old standalone list actionable beside its error.
  test('a failed standalone read clears the earlier list even at the same revision', () async {
    final (source, board) = await _opened();
    source.push('demo', source.board('demo'));
    await _turn();
    source.reads.last.completeError(const PlanningRefusal('Unreachable', {}, 'read failed'));
    await _turn();
    expect(board.noStory, isEmpty);
    expect(board.noStoryProblem, contains('read failed'));
  });

  // Harm: mismatched reads leave issues invisible forever while the board remains behind the store.
  test('repeated revision mismatches reread the board once and show the matching remaining issue', () async {
    final (source, board) = await _opened();
    source.push('demo', {...source.board('demo'), 'revision': 'r2'});
    await _turn();
    source.boards['demo'] = {...source.board('demo'), 'revision': 'r3'};
    for (var i = 0; i < 3; i++) {
      source.reads.last.complete(
        PlanIssueList.from({
          'revision': 'r3',
          'issues': [_looseIssue()],
        }),
      );
      await _turn();
    }
    expect(source.boardReads, 1, reason: 'the board is read once to catch up');
    expect(board.snapshot!.revision, 'r3');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'r3',
        'issues': [
          {..._looseIssue(), 'id': 'B901'},
        ],
      }),
    );
    await _turn();
    expect(board.noStory.map((issue) => issue.id), ['B901']);
    expect(board.noStoryProblem, isNull);
  });

  // Harm: a saved assignment is reported as unsaved and the person may repeat it.
  test('a failed board read after a saved hang reports that the save succeeded', () async {
    final (source, board) = await _opened(automatic: true);
    source.boardReadFailure = const PlanningRefusal('Unreachable', {}, 'read failed');
    await board.hangIssue('B900', 'S1');
    expect(board.moveProblem, contains('Saved, but planning could not be read again:'));
    expect(board.moveProblem, contains('read failed'));
    expect(board.moveRefusal, isNull, reason: 'the write was accepted');
    expect(source.lists, hasLength(1));
  });

  for (final name in ['StaleRevision', 'PartlySaved']) {
    // Harm: a refused assignment leaves the board showing planning that the store no longer holds.
    test('a $name hang refusal rereads the board and its standalone list', () async {
      final (source, board) = await _opened(automatic: true);
      source.boards['demo'] = {...source.board('demo'), 'revision': 'new-store', 'issues': <Object>[]};
      source.editFailure = PlanningRefusal(name, {}, 'save refused');
      await board.hangIssue('B900', 'S1');
      expect(source.boardReads, 1);
      expect(board.snapshot!.revision, 'new-store');
      expect(board.noStory, isEmpty);
      expect(board.moveRefusal?.name, name);
      expect(board.moveProblem, contains('save refused'));
    });
  }

  // Harm: a hang changes a story while that story's unanswered move may still land.
  test('a story with an unanswered move cannot take another issue', () async {
    final (source, board) = await _opened(automatic: true);
    source.moveFailure = const PlanningRefusal('TimedOut');
    await board.move('S1', 'Review');
    expect(board.waitsOnItsSave('S1'), isTrue);
    await board.hangIssue('B900', 'S1');
    expect(source.lists, isEmpty);
    expect(board.moveProblem, contains('may still land'));
  });

  // Harm: a parent's worker changes while a standalone sub-issue still has an unanswered move.
  test('a moving standalone sub-issue holds its parent worker change', () async {
    final (source, board) = await _opened();
    final child = {..._looseIssue(), 'id': 'B901', 'parent': 'B900'};
    source.push('demo', {
      ...source.board('demo'),
      'revision': 'family',
      'issues': [_looseIssue(), child],
    });
    await _turn();
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'family',
        'issues': [_looseIssue(), child],
      }),
    );
    await _turn();
    source.moveFailure = const PlanningRefusal('TimedOut');
    await board.moveIssue('B901', 'Finished');
    await board.setWorker(board.noStory.first, IssueWorker.manual);
    expect(source.workerCalls, isEmpty);
    expect(board.actionProblem, contains('sub-issue has a move'));
  });

  // Harm: a page's issue is treated as deleted while its standalone list is still being read.
  test('an issue page can move its issue while the matching standalone list is unread', () async {
    final (source, board) = await _opened();
    final shown = board.noStory.single;
    source.push('demo', {...source.board('demo'), 'revision': 'r2'});
    await _turn();
    source.moveFailure = const PlanningRefusal('TimedOut');
    final moving = board.moveIssue('B900', 'Finished', reported: shown);
    await _turn();
    expect(source.moveCalls, hasLength(1));
    expect(board.pendingIssues, contains('B900'));
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'r2',
        'issues': [_looseIssue()],
      }),
    );
    await moving;
    expect(board.pendingIssues, contains('B900'));
  });

  // Harm: removing a story membership releases an unanswered issue move before its destination is known.
  test('an unanswered story issue waits for the standalone list after it is taken out', () async {
    final (source, board) = await _opened();
    source.moveFailure = const PlanningRefusal('TimedOut');
    await board.moveIssue('I1', 'Finished');
    final data = source.board('demo');
    final stories = (data['stories'] as List).cast<Map<String, dynamic>>();
    source.push('demo', {
      ...data,
      'revision': 'taken-out',
      'stories': [
        {...stories.first, 'issues': <Object>[]},
        stories.last,
      ],
    });
    await _turn();
    expect(board.pendingIssues, contains('I1'));
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'taken-out',
        'issues': [
          {..._looseIssue(), 'id': 'I1'},
        ],
      }),
    );
    await _turn();
    expect(board.pendingIssues, contains('I1'), reason: 'the unanswered move has not landed');
  });

  // Harm: one project's expanded cards expose another project's rows or retain an unbounded list after folding.
  test('folding clears show all and changing projects clears standalone card choices', () async {
    final (source, board) = await _opened(automatic: true);
    board.expandNoStory('Backlog');
    board.showAllNoStory('Backlog');
    board.expandNoStory('Backlog');
    expect(board.preferences.allNoStoryStates, isEmpty);
    board.expandNoStory('Backlog');
    board.showAllNoStory('Backlog');
    source.boards['other'] = {...source.board('demo')};
    await board.changeProject(source, 'other');
    await _turn();
    expect(board.preferences.expandedNoStoryStates, isEmpty);
    expect(board.preferences.allNoStoryStates, isEmpty);
  });

  // Harm: an issue remains in no story after a save has hung it into a story.
  test('a save reads issues in no story again and an older reply is read again before it is shown', () async {
    final (source, board) = await _opened();
    expect(board.noStory.map((issue) => issue.id), ['B900']);

    var finished = false;
    final hanging = board.hangIssue('B900', 'S1').then((_) => finished = true);
    await _turn();
    expect(finished, isFalse, reason: 'the matching list must finish before the hang returns');
    expect(source.reads.length, greaterThan(1), reason: 'the changed board needs its own list');
    expect(board.noStory, isEmpty, reason: 'the earlier list cannot stand over the saved board');
    final older = source.reads.last;
    final before = source.reads.length;
    older.complete(
      PlanIssueList.from({
        'revision': 'r1',
        'issues': [_looseIssue()],
      }),
    );
    await _turn();
    expect(board.noStory, isEmpty, reason: 'an older reply must never put the issue back');
    expect(source.reads.length, before + 1, reason: 'the mismatched revision is read again');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': board.snapshot!.revision,
        'issues': [
          {..._looseIssue(), 'id': 'B901'},
        ],
      }),
    );
    await hanging;
    expect(board.noStory.map((issue) => issue.id), ['B901']);
    expect(board.noStoryProblem, isNull);
  });

  // Harm: a delayed list read from an earlier board puts a hung issue back in no story.
  test('a delayed read cannot replace the list of a newer streamed board', () async {
    final (source, board) = await _opened();
    final data = source.board('demo');
    source.push('demo', {...data, 'revision': 'r2'});
    await _turn();
    final delayed = source.reads.last;
    source.push('demo', {...data, 'revision': 'r3', 'issues': <Object>[]});
    await _turn();
    source.reads.last.complete(PlanIssueList.from({'revision': 'r3', 'issues': <Object>[]}));
    await _turn();
    delayed.complete(
      PlanIssueList.from({
        'revision': 'r2',
        'issues': [_looseIssue()],
      }),
    );
    await _turn();
    expect(board.snapshot!.revision, 'r3');
    expect(board.noStory, isEmpty);
    expect(board.noStoryProblem, isNull);
  });

  // Harm: an issue in no story cannot move through the same checked save as any other issue.
  test('an issue in no story moves through the existing issue move call', () async {
    final (source, board) = await _opened();
    var finished = false;
    final moving = board.moveIssue('B900', 'Finished').then((_) => finished = true);
    await _turn();
    expect(finished, isFalse, reason: 'the move waits for its matching issue list');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': board.snapshot!.revision,
        'issues': [
          {..._looseIssue(), 'state': 'Finished'},
        ],
      }),
    );
    await moving;
    expect(board.noStoryIn('Finished').map((issue) => issue.id), ['B900']);
    expect(source.moveCalls.single['moves'], [
      {'issue': 'B900', 'to': 'Finished'},
    ]);
    expect(source.board('demo')['issues'], contains(containsPair('state', 'Finished')));
  });

  // Harm: an unanswered move is released before it lands, letting the same issue be moved twice.
  test('an unanswered standalone move waits for the matching list before it is reconciled', () async {
    final (source, board) = await _opened();
    source.moveFailure = const PlanningRefusal('TimedOut');
    await board.moveIssue('B900', 'Finished');
    expect(board.pendingIssues['B900']!.unanswered, isTrue);
    final data = source.board('demo');
    source.push('demo', {...data, 'revision': 'r2'});
    await _turn();
    expect(board.pendingIssues, contains('B900'), reason: 'the matching issue list has not arrived');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'r2',
        'issues': [_looseIssue()],
      }),
    );
    await _turn();
    expect(board.pendingIssues, contains('B900'), reason: 'it is still in its source state');
    source.push('demo', {...data, 'revision': 'r3'});
    await _turn();
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'r3',
        'issues': [
          {..._looseIssue(), 'state': 'Finished'},
        ],
      }),
    );
    await _turn();
    expect(board.pendingIssues, isEmpty, reason: 'the matching list now proves the destination');
    expect(board.moveNotice, 'Issue changes saved.');
  });

  // Harm: a changed preview leaves a movable standalone issue stranded as if it had vanished.
  test('a changed preview rereads the standalone list before previewing the move again', () async {
    final (source, board) = await _opened();
    source.previewChangesOnce = true;
    final moving = board.moveIssue('B900', 'Finished');
    await _turn();
    expect(source.previews, 1, reason: 'recovery must first read the matching issue list');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'changed-preview',
        'issues': [_looseIssue()],
      }),
    );
    await moving;
    expect(source.previews, 2);
    expect(board.question?.again, isTrue, reason: 'the changed preview needs a fresh confirmation');
    final confirming = board.confirmMove();
    await _turn();
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': board.snapshot!.revision,
        'issues': [
          {..._looseIssue(), 'state': 'Finished'},
        ],
      }),
    );
    await confirming;
    expect(board.noStoryIn('Finished').map((issue) => issue.id), ['B900']);
    expect(source.moveCalls.single['moves'], [
      {'issue': 'B900', 'to': 'Finished'},
    ]);
    expect(board.moveProblem, isNull);
  });

  // Harm: hanging an issue drops the other issues from the story's whole list.
  test('hanging an issue appends it to the whole story list at the board revision', () async {
    final (source, board) = await _opened(automatic: true);
    await board.hangIssue('B900', 'S1');
    final call = source.lists.single;
    expect(call.$1, 'demo');
    expect(call.$2, 'S1');
    expect(call.$3, ['I1', 'B900']);
    expect(call.$4, 'r1');
    expect(board.stories.firstWhere((story) => story.id == 'S1').issues.map((issue) => issue.id), ['I1', 'B900']);
  });

  // Harm: a finished hang shows a board whose issues in no story are not yet read, so the hung issue's neighbors
  // vanish and a following action judges an empty list.
  test('a hang finishes only once the issues in no story of the saved board are read', () async {
    final (source, board) = await _opened();
    source.boards['demo'] = {
      ...source.board('demo'),
      'issues': [
        _looseIssue(),
        {..._looseIssue(), 'id': 'B901'},
      ],
    };
    var finished = false;
    final hanging = board.hangIssue('B900', 'S1').then((_) => finished = true);
    await _turn();
    await _turn();
    expect(finished, isFalse, reason: 'the list of the saved board is still being read');
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': board.snapshot!.revision,
        'issues': mockPlanningNoStoryIssues(source.board('demo')),
      }),
    );
    await hanging;
    expect(board.noStory.map((issue) => issue.id), ['B901']);
    expect(board.stories.firstWhere((story) => story.id == 'S1').issues.map((issue) => issue.id), ['I1', 'B900']);
  });

  // Harm: a refused edit hides an issue or drops the story's other issues.
  test('a refused hang shows the save refusal and leaves both lists unchanged', () async {
    final (source, board) = await _opened();
    const refusal = PlanningRefusal('EditNotAllowed', {}, 'the story is incomplete');
    source.editFailure = refusal;
    await board.hangIssue('B900', 'S1');
    expect(board.moveProblem, contains('the story is incomplete'));
    expect(board.moveRefusal, same(refusal));
    expect(board.noStory.map((issue) => issue.id), ['B900']);
    expect(board.stories.firstWhere((story) => story.id == 'S1').issues.map((issue) => issue.id), ['I1']);
  });

  // Harm: the board search and priority filter hide the wrong issues in no story.
  test('issues in no story follow the board search and the treatment of missing priorities', () async {
    final (_, board) = await _opened();
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900']);
    expect(board.noStoryIn('Review'), isEmpty);
    board.search('planning work');
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900']);
    board.search('B900');
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900']);
    board.search('unrelated');
    expect(board.noStoryIn('Backlog'), isEmpty);
    board.search('');
    board.filter(PlanningPriority.high);
    expect(board.noStoryIn('Backlog'), isEmpty, reason: 'issues without a story have no priority');
    board.filter(PlanningPriority.all);
    expect(board.noStoryIn('Backlog').map((issue) => issue.id), ['B900']);
  });

  // Harm: writing a partial story list silently removes issues that were not reported.
  test('an incomplete story cannot take an issue through a whole list save', () async {
    final (source, board) = await _opened();
    final data = source.board('demo');
    ((data['stories'] as List).first as Map<String, dynamic>).remove('issues');
    source.push('demo', {...data, 'revision': 'r2'});
    await _turn();
    source.reads.last.complete(
      PlanIssueList.from({
        'revision': 'r2',
        'issues': [_looseIssue()],
      }),
    );
    await _turn();
    await board.hangIssue('B900', 'S1');
    expect(source.lists, isEmpty, reason: 'unreported issues cannot be preserved by this edit');
    expect(board.moveProblem, isNotNull);
    expect(board.noStory.map((issue) => issue.id), ['B900']);
  });
}
