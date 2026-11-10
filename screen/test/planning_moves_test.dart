import 'dart:io';
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

Map<String, dynamic> _fixture() => {
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Done', 'kind': 'CLOSED'},
    {'name': 'Elsewhere', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Future', 'kind': 'UNKNOWN'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Done', 'Elsewhere', 'Building', 'Future'],
    },
  ],
  'stories': [
    {
      'id': 'StoryOne',
      'state': 'Ready',
      'issues': [
        {'id': 'IssueOne', 'state': 'Ready'},
        {'id': 'IssueTwo', 'state': 'Elsewhere'},
      ],
    },
    {'id': 'StoryTwo', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
  ],
  'revision': 'initial',
};

class _Backend extends Fake implements PlanningSource {
  Map<String, dynamic> data = _fixture();
  final changes = StreamController<PlanningSnapshot>.broadcast(sync: true);
  final calls = <Map<String, dynamic>>[];
  String? refusedStory;
  Object? failure;
  Object? readFailure;
  bool confirmed = true;
  Completer<void>? gate;
  Completer<PlanningSnapshot>? listing;
  Completer<void>? listingStarted;
  Map<String, dynamic> Function(Map<String, dynamic>)? reply;
  int workflowReads = 0, storyReads = 0, watches = 0;
  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    watches++;
    return changes.stream;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async {
    workflowReads++;
    return PlanningSnapshot.from(data);
  }

  @override
  Future<PlanningSnapshot> stories(String project) async {
    storyReads++;
    if (listing case final waiting?) {
      listingStarted?.complete();
      return waiting.future;
    }
    if (readFailure case final error?) throw error;
    return PlanningSnapshot.from(data);
  }

  @override
  Future<PlanningMoveResult> saveMoves(String project, List<PlanningMove> moves, String revision) async {
    final parameters = {'project': project, 'moves': moves.map((move) => move.toMap()).toList(), 'revision': revision};
    calls.add(parameters);
    if (gate case final waiting?) await waiting.future;
    if (failure case final error?) throw error;
    if (moves.any((move) => move is PlanningStoryMove && move.story == refusedStory)) {
      throw mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {
        'story': refusedStory,
        'detail': 'Work is still running',
      });
    }
    if (!confirmed) return PlanningMoveResult.from({'revision': data['revision'], 'moved': <String>[]});
    data = applyPlanningMove(data, parameters);
    final answer = {'revision': data['revision'], 'moved': data['moved']};
    return PlanningMoveResult.from(reply?.call(answer) ?? answer);
  }
}

void main() {
  late _Backend backend;
  late PlanningBoard board;
  var disposed = false;
  setUp(() async {
    disposed = false;
    backend = _Backend();
    board = PlanningBoard(backend, 'demo');
    backend.changes.add(PlanningSnapshot.from(backend.data));
    await Future<void>.delayed(Duration.zero);
    backend.workflowReads = 0;
  });
  tearDown(() async {
    if (!disposed) board.dispose();
    await backend.changes.close();
  });

  // Harm: a refused batch appears moved or saved while its unaccepted drafts are lost.
  test('every refused save keeps the batch and sets the move problem', () async {
    for (final name in ['WorkNotAllowed', 'Busy', 'NotOffered']) {
      board.move('StoryOne', 'Done');
      board.move('StoryTwo', 'Done');
      backend.failure = PlanningRefusal(name, {'story': 'StoryOne'}, 'Refused.');
      await board.save();
      expect(board.pending, hasLength(2), reason: name);
      expect(board.moveProblem, contains('Refused.'), reason: name);
      expect(board.cards('Done'), isEmpty, reason: name);
      expect(board.unavailableActions.keys, name == 'NotOffered' ? ['SaveMoves'] : isEmpty);
      board.undoAll();
      board.unavailableActions.clear();
    }
  });

  // Harm: a partly pushed save hides the store's actual states or loses which repositories failed.
  test('a partly saved batch reloads the board and retains its typed refusal', () async {
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    backend.data = applyPlanningMove(backend.data, {
      'revision': 'initial',
      'moves': [
        {'story': 'StoryTwo', 'to': 'Done'},
      ],
    });
    backend.failure = const PlanningRefusal('PartlySaved', {'pushed': 'repo', 'failed': 'other'}, 'Partly saved.');
    await board.save();
    expect(backend.calls, hasLength(1));
    expect(backend.storyReads, 1);
    expect(board.stories.last.state, 'Done');
    expect(board.pending.keys, ['StoryOne', 'StoryTwo']);
    expect(board.moveProblem, contains('Partly saved.'));
    expect(board.moveRefusal?.parameters, {'pushed': 'repo', 'failed': 'other'});
  });

  // Harm: refusal text can conceal or reorder the destination and reason shown to the person.
  test('every refusal word shown strips controls and bidirectional formatting', () {
    for (final error in [
      const PlanningRefusal('Busy', {'detail': 'Wait\u202e\u0000\n\t\u0085here'}),
      const PlanningRefusal('Busy', {}, 'Wait\u202e\u0000\n\t\u0085here'),
      const PlanningRefusal('EnteredOnlyByWork', {'to': 'Wait\u202e\u0000\n\t\u0085here'}),
      const PlanningRefusal('NotYetBuilt', {'method': 'Wait\u202e\u0000\n\t\u0085here'}),
      const PlanningRefusal('Busy\u202e', {
        'tasks': ['Wait\u202e\u0000\n\t\u0085here'],
      }),
    ]) {
      expect(planningFailureWords(error), isNot(matches(RegExp(r'[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]'))));
    }
  });

  // Harm: staging or undo writes unreviewed changes or loses unrelated pending moves.
  test('staging and individual or complete undo never write', () {
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    expect(board.pending.keys, ['StoryOne', 'StoryTwo']);
    expect(board.stories.map((story) => story.state), ['Ready', 'Ready']);
    expect(board.cards('Done').map((story) => story.id), ['StoryOne', 'StoryTwo']);
    board.undo('StoryOne');
    expect(board.pending.keys, ['StoryTwo']);
    expect(board.cards('Ready').single.id, 'StoryOne');
    board.undoAll();
    expect(board.pending, isEmpty);
    expect(board.cards('Ready').map((story) => story.id), ['StoryOne', 'StoryTwo']);
    expect(backend.calls, isEmpty);
  });

  // Harm: several pending moves commit partly, or issue states stay stale until a watch arrives.
  test('one atomic save applies confirmed issues and rereads computed story states', () async {
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    await board.save();
    expect(backend.calls, [
      {
        'project': 'demo',
        'moves': [
          {'story': 'StoryOne', 'to': 'Done'},
          {'story': 'StoryTwo', 'to': 'Done'},
        ],
        'revision': 'initial',
      },
    ]);
    expect(board.pending, isEmpty);
    expect(board.cards('Done').map((story) => story.id), ['StoryTwo']);
    expect(board.stories.first.state, 'Elsewhere');
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    expect(board.snapshot!.revision, 'initial:moved');
    expect(backend.workflowReads, 0);
    expect(backend.storyReads, 1);
  });

  // Harm: issue drafts are lost on detail navigation or saved separately from story moves.
  test('story and explicit issue moves share one pending batch and one atomic save', () async {
    board.moveIssue('IssueTwo', 'Ready');
    board.move('StoryOne', 'Done');
    expect(board.pending, hasLength(2));
    expect(board.pendingIssues.keys, ['IssueTwo']);
    expect(backend.calls, isEmpty);
    await board.save();
    expect(backend.calls, [
      {
        'project': 'demo',
        'moves': [
          {'story': 'StoryOne', 'to': 'Done'},
          {'issue': 'IssueTwo', 'to': 'Ready'},
        ],
        'revision': 'initial',
      },
    ]);
    expect(board.pending, isEmpty);
    expect(board.stories.first.state, 'Ready');
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Ready']);
    expect(backend.workflowReads, 0);
    expect(backend.storyReads, 1);
  });

  // Harm: refusal of an explicit issue publishes part of a story-and-issue batch.
  test('a refused explicit issue leaves the entire mixed batch pending and the store unchanged', () async {
    board.move('StoryOne', 'Done');
    board.moveIssue('IssueTwo', 'Ready');
    backend.failure = mockRefusal('StateNotAllowed', {'item': 'IssueTwo', 'reason': 'Policy refuses'});
    await board.save();
    expect(backend.calls, hasLength(1));
    expect(board.pending, hasLength(2));
    expect(board.pending['StoryOne']!.problem, 'Policy refuses');
    expect(board.pendingIssues['IssueTwo']!.problem, 'Policy refuses');
    expect(board.stories.first.state, 'Ready');
    expect(board.stories.first.issues.map((issue) => issue.state), ['Ready', 'Elsewhere']);
    expect(backend.data['revision'], 'initial');
    board.undoAll();
    expect(board.pending, isEmpty);
    expect(board.pendingIssues, isEmpty);
    expect(backend.calls, hasLength(1));
  });

  // Harm: an unreported embedded issue list silently discards a reviewed standalone issue draft.
  test('issue drafts survive unreported board lists and accept confirmed standalone rows', () {
    board.moveIssue('IssueTwo', 'Done');
    backend.changes.add(
      PlanningSnapshot.from({
        ...backend.data,
        'stories': [
          {'id': 'StoryOne', 'state': 'Ready'},
          {'id': 'StoryTwo', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
        ],
        'revision': 'partial-issues',
      }),
    );
    expect(board.pendingIssues['IssueTwo']!.to, 'Done');
    board.moveIssue('Standalone', 'Done', reported: PlanIssue.from({'id': 'Standalone', 'state': 'Ready'}));
    expect(board.pendingIssues.keys, ['IssueTwo', 'Standalone']);
    expect(backend.calls, isEmpty);
    board.undoAll();
    expect(board.pending, isEmpty);
  });

  // Harm: a refused second story leaves an earlier story committed by an atomic save.
  test('the stand-in publishes none of a refused story batch', () {
    final source = _fixture();
    expect(
      () => applyPlanningMove(source, {
        'revision': 'initial',
        'moves': [
          {'story': 'StoryOne', 'to': 'Done'},
          {'story': 'StoryTwo', 'to': 'Building'},
        ],
      }),
      throwsA(isA<PlanningRefusal>().having((error) => error.parameters['item'], 'refused story', 'StoryTwo')),
    );
    expect((source['stories'] as List<dynamic>).map((story) => story['state']), ['Ready', 'Ready']);
    expect((source['stories'] as List<dynamic>).first['issues'][0]['state'], 'Ready');
    expect(source['revision'], 'initial');
  });

  // Harm: a refused issue batch leaves earlier issue changes published.
  test('issue batches remain atomic and reject stale or working destinations', () {
    final source = _fixture();
    expect(
      () => applyPlanningMove(source, {
        'revision': 'initial',
        'moves': [
          {'issue': 'IssueOne', 'to': 'Done'},
          {'issue': 'missing', 'to': 'Done'},
        ],
      }),
      throwsA(isA<PlanningRefusal>()),
    );
    expect((source['stories'] as List<dynamic>).first['issues'][0]['state'], 'Ready');
    for (final parameters in [
      {
        'revision': 'initial',
        'moves': [
          {'issue': 'IssueOne', 'to': 'Building'},
        ],
      },
      {
        'revision': 'old',
        'moves': [
          {'issue': 'IssueOne', 'to': 'Done'},
        ],
      },
    ]) {
      expect(() => applyPlanningMove(source, parameters), throwsA(isA<PlanningRefusal>()));
    }
    final result = applyPlanningMove(source, {
      'revision': 'initial',
      'moves': [
        {'issue': 'IssueOne', 'to': 'Done'},
      ],
    });
    final story = (result['stories'] as List<dynamic>).first;
    // The story's state is computed: its earliest open issue state in workflow order.
    expect(story['state'], 'Elsewhere');
    expect(story['issues'][0]['state'], 'Done');
    expect(story['issues'][1]['state'], 'Elsewhere');
    expect(result['revision'], allOf(isA<String>(), isNotEmpty, isNot(source['revision'])));
  });

  // Harm: a stale save overwrites others or loses the remaining operator intent.
  test('stale saves reread automatically and keep Save usable with reconciled intent', () async {
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    (backend.data['stories'] as List<dynamic>).first['state'] = 'Done';
    for (final issue
        in ((backend.data['stories'] as List<dynamic>).first['issues'] as List).cast<Map<String, dynamic>>()) {
      issue['state'] = 'Done';
    }
    (backend.data['stories'] as List<dynamic>)[1]['state'] = 'Elsewhere';
    backend.data['revision'] = 'changed';
    await board.save();
    expect(backend.calls.single['revision'], 'initial');
    expect(backend.workflowReads, 1);
    expect(backend.storyReads, 1);
    expect(board.pending.keys, ['StoryTwo']);
    expect(board.pending['StoryTwo']!.from, 'Elsewhere');
    expect(board.pending['StoryTwo']!.problem, isNull);
    expect(board.displayedState(board.stories.last), 'Done');
    expect(board.moveProblem, contains('StoryOne was moved to Done elsewhere'));
    await board.save();
    expect(backend.calls.last['revision'], 'changed');
    expect(board.pending, isEmpty);
    expect(board.stories.map((story) => story.state), ['Done', 'Done']);
  });

  // Harm: a newer watch answer leaves stale source states and revisions on pending moves.
  test('watch answers reconcile sources and use the displayed board revision', () async {
    board.move('StoryOne', 'Done');
    (backend.data['stories'] as List<dynamic>).first['state'] = 'Elsewhere';
    backend.data['revision'] = 'changed';
    backend.changes.add(PlanningSnapshot.from(backend.data));
    expect(board.pending['StoryOne']!.from, 'Elsewhere');
    board.move('StoryOne', 'Ready');
    expect(board.pending['StoryOne']!.from, 'Elsewhere');
    expect(board.pending['StoryOne']!.to, 'Ready');
    await board.save();
    expect(backend.calls.single['revision'], 'changed');
    expect(board.stories.first.state, 'Ready');
  });

  // Harm: a refused batch loses its explanation or erases a destination that still needs saving.
  test('a refused batch keeps its explanation and undo allows the remaining atomic save', () async {
    backend.refusedStory = 'StoryOne';
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    await board.save();
    expect(board.pending.keys, ['StoryOne', 'StoryTwo']);
    expect(board.pending['StoryOne']!.problem, 'Work is still running');
    expect(board.pending['StoryTwo']!.problem, 'Work is still running');
    expect(board.moveProblem, 'Work is still running');
    expect(board.cards('Ready').map((story) => story.id), ['StoryOne', 'StoryTwo']);
    board.move('StoryTwo', 'Elsewhere');
    expect(board.pending['StoryOne']!.problem, 'Work is still running');
    await board.reload();
    expect(board.pending['StoryOne']!.problem, 'Work is still running');
    expect(board.cards('Ready').single.id, 'StoryOne');
    board.undo('StoryOne');
    await board.save();
    expect(board.pending, isEmpty);
    expect(board.stories.map((story) => story.state), ['Ready', 'Elsewhere']);
  });

  // Harm: correcting one refused destination leaves Save blocked or clears another refusal.
  test('a new drag clears exactly its own refusal', () async {
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {'detail': 'Work is still running'});
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    await board.save();
    expect(board.pending.values.map((move) => move.problem), ['Work is still running', 'Work is still running']);
    board.move('StoryOne', 'Elsewhere');
    expect(board.pending['StoryOne']!.problem, isNull);
    expect(board.pending['StoryTwo']!.problem, 'Work is still running');
    board.undo('StoryTwo');
    backend.failure = null;
    await board.save();
    expect(board.stories.first.state, 'Elsewhere');
    expect(board.pending, isEmpty);
  });

  // Harm: unrecognized or unnamed daemon refusals leave rejected cards marked as moved.
  test('all named and unnamed refusal kinds return the affected cards to their reported columns', () async {
    for (final name in ['WorkNotAllowed', 'NoSuchStory', 'AnotherRefusal']) {
      for (final named in [true, false]) {
        board.move('StoryOne', 'Done');
        board.move('StoryTwo', 'Done');
        backend.failure = mockRefusal('org.fuin.porelo.Tasks1.$name', {
          'detail': 'The daemon refused this save',
          if (named) 'story': 'StoryOne',
        });
        await board.save();
        expect(board.pending['StoryOne']!.problem, 'The daemon refused this save');
        expect(board.pending['StoryTwo']!.problem, 'The daemon refused this save');
        expect(board.cards('Ready').map((story) => story.id), ['StoryOne', 'StoryTwo']);
        expect(board.moveProblem, 'The daemon refused this save');
        board.undoAll();
      }
    }
  });

  // Harm: a retried batch loses its current refusal or retains an obsolete one while the save waits.
  test('retry clears old problems before waiting and retains a new refusal for the whole batch', () async {
    board.move('StoryOne', 'Done');
    board.move('StoryTwo', 'Done');
    backend.refusedStory = 'StoryOne';
    await board.save();
    backend.refusedStory = 'StoryTwo';
    backend.gate = Completer<void>();
    final saved = board.save();
    expect(board.pending.values.map((move) => move.problem), [null, null]);
    backend.gate!.complete();
    await saved;
    expect(board.pending['StoryOne']!.problem, 'Work is still running');
    expect(board.pending['StoryTwo']!.problem, 'Work is still running');
  });

  // Harm: a read in flight answers a board older than the one streamed meanwhile and holds the board on it for good,
  // since the stream tells every revision in order and never an older one again.
  for (final call in ['reload', 'stale save', 'read after a save']) {
    test('a board streamed during a $call wins over the read that was in flight', () async {
      backend.listing = Completer<PlanningSnapshot>();
      backend.listingStarted = Completer<void>();
      if (call != 'reload') board.move('StoryOne', 'Done');
      if (call == 'stale save') {
        backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
      }
      final operation = call == 'reload' ? board.reload() : board.save();
      await backend.listingStarted!.future;
      final streamed = _fixture()..['revision'] = 'streamed';
      (streamed['stories'] as List<dynamic>).first['state'] = 'Elsewhere';
      backend.changes.add(PlanningSnapshot.from(streamed));
      backend.listing!.complete(PlanningSnapshot.from(_fixture()..['revision'] = 'read'));
      await operation;
      expect(board.snapshot!.revision, 'streamed');
      expect(board.snapshot!.stories.first.state, 'Elsewhere');
    });
  }

  // Harm: a board streamed before the save reached the planner shows the saved moves undone, with nothing left to save.
  for (final reread in ['answers', 'fails']) {
    test('a board streamed while a save waits loses to the saved board when the read after it $reread', () async {
      board.move('StoryTwo', 'Done');
      backend.gate = Completer<void>();
      final operation = board.save();
      backend.changes.add(PlanningSnapshot.from(_fixture()..['revision'] = 'before the save'));
      if (reread == 'fails') {
        backend.readFailure = const PlanningRefusal('PlannerUnreachable', {}, 'the planner cannot be reached');
      }
      backend.gate!.complete();
      await operation;
      expect(board.snapshot!.revision, isNot('before the save'));
      expect(
        board.snapshot!.stories.firstWhere((story) => story.id == 'StoryTwo').state,
        'Done',
        reason: 'the saved move',
      );
    });
  }

  // Harm: after a stale save the board shows one streamed before the save over the newer one read for it.
  test('a board streamed while a stale save waits loses to the read after the refusal', () async {
    board.move('StoryOne', 'Done');
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
    backend.gate = Completer<void>();
    final operation = board.save();
    backend.changes.add(PlanningSnapshot.from(_fixture()..['revision'] = 'before the save'));
    backend.gate!.complete();
    await operation;
    expect(board.snapshot!.revision, 'initial');
  });

  // Harm: a board streamed earlier but slower to read its workflow replaces a newer one, which the stream never tells
  // again.
  test('a board streamed during a reload is not replaced by an earlier one still reading its workflow', () async {
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final operation = board.reload();
    await backend.listingStarted!.future;
    (backend.data['states'] as List<dynamic>).add({'name': 'New', 'kind': 'WAITING'});
    final earlier = _fixture()
      ..['revision'] = 'earlier'
      ..['states'] = <Object>[]
      ..['phases'] = <Object>[];
    (earlier['stories'] as List<dynamic>).first['state'] = 'New';
    backend.changes.add(PlanningSnapshot.from(earlier));
    backend.changes.add(PlanningSnapshot.from(_fixture()..['revision'] = 'later'));
    await Future<void>.delayed(Duration.zero);
    backend.listing!.complete(PlanningSnapshot.from(_fixture()..['revision'] = 'read'));
    await operation;
    expect(board.snapshot!.revision, 'later');
  });

  // Harm: a pending move is dropped as made elsewhere by a read's board that a newer streamed board replaces.
  test('pending moves are reconciled only against the board that stays after a reload', () async {
    board.move('StoryOne', 'Done');
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final operation = board.reload();
    await backend.listingStarted!.future;
    final streamed = _fixture()..['revision'] = 'streamed';
    (streamed['stories'] as List<dynamic>).first['state'] = 'Elsewhere';
    backend.changes.add(PlanningSnapshot.from(streamed));
    final read = _fixture()..['revision'] = 'read';
    (read['stories'] as List<dynamic>).first['state'] = 'Done';
    backend.listing!.complete(PlanningSnapshot.from(read));
    await operation;
    expect(board.pending.keys, ['StoryOne'], reason: 'the move still waits to be saved');
    expect(board.moveProblem, isNull);
  });

  // Harm: after a stale save the board tells the person to save again although the moves were made elsewhere.
  test('a stale save tells what the board that stays shows', () async {
    board.move('StoryOne', 'Done');
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final operation = board.save();
    await backend.listingStarted!.future;
    final streamed = _fixture()..['revision'] = 'streamed';
    (streamed['stories'] as List<dynamic>).first['state'] = 'Done';
    backend.changes.add(PlanningSnapshot.from(streamed));
    backend.listing!.complete(PlanningSnapshot.from(_fixture()..['revision'] = 'read'));
    await operation;
    expect(board.pending, isEmpty);
    expect(board.moveNotice, 'The pending moves were made elsewhere.');
  });

  // Harm: missing watch fields erase the complete board returned by a successful save.
  test('incomplete and unversioned buffered watches cannot replace a saved reply', () async {
    for (final broken in [
      <String, dynamic>{'revision': 'broken'},
      {..._fixture(), 'revision': ''},
    ]) {
      backend.data = _fixture();
      backend.changes.add(PlanningSnapshot.from(backend.data));
      backend.gate = Completer<void>();
      board.move('StoryOne', 'Done');
      final saved = board.save();
      backend.changes.add(PlanningSnapshot.from(broken));
      backend.gate!.complete();
      await saved;
      expect(board.snapshot!.complete, isTrue);
      expect(board.snapshot!.revision, 'initial:moved');
      expect(board.stories.first.issues.first.state, 'Done');
      expect(board.stories.first.state, 'Elsewhere');
    }
  });

  // Harm: discarding every deferred watch loses a real later board after an RPC refusal.
  test('a complete deferred watch newer than the held board is applied', () async {
    backend.gate = Completer<void>();
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.NoSuchStory', {
      'story': 'StoryOne',
      'detail': 'Story unavailable',
    });
    board.move('StoryOne', 'Done');
    final saved = board.save();
    final changed = _fixture()..['revision'] = 'changed';
    (changed['stories'] as List<dynamic>).first['state'] = 'Elsewhere';
    backend.changes.add(PlanningSnapshot.from(changed));
    backend.gate!.complete();
    await saved;
    expect(board.snapshot!.revision, 'changed');
    expect(board.stories.first.state, 'Elsewhere');
    expect(board.pending['StoryOne']!.from, 'Elsewhere');
    expect(board.pending['StoryOne']!.problem, 'Story unavailable');
  });

  // Harm: dropping all pending moves leaves a stale error on a board with nothing to save.
  test('undoing the last move or all moves clears the board error', () async {
    for (final all in [false, true]) {
      board.move('StoryOne', 'Done');
      backend.failure = const SocketException('Connection lost');
      await board.save();
      expect(board.moveProblem, contains('Connection lost'));
      if (all) {
        board.undoAll();
      } else {
        board.undo('StoryOne');
      }
      expect(board.pending, isEmpty);
      expect(board.moveProblem, isNull);
    }
  });

  // Harm: malformed save answers erase intent or replace a complete board with missing data.
  test('save retains the board and pending intent on incomplete or unversioned replies', () async {
    for (final broken in <Map<String, dynamic> Function(Map<String, dynamic>)>[
      (reply) => {...reply}..remove('moved'),
      (reply) => {...reply, 'revision': ''},
    ]) {
      backend.data = _fixture();
      backend.changes.add(PlanningSnapshot.from(backend.data));
      backend.reply = broken;
      board.move('StoryOne', 'Done');
      await board.save();
      expect(board.snapshot!.complete, isTrue);
      expect(board.snapshot!.revision, 'initial');
      expect(board.stories.first.state, 'Ready');
      expect(board.pending.keys, ['StoryOne']);
      expect(board.pending['StoryOne']!.problem, isNotNull);
      expect(board.moveProblem, isNotNull);
      board.undoAll();
    }
  });

  // Harm: a negative confirmation is accepted as a saved board and loses pending work.
  test('a negative move confirmation retains intent and its original reported state', () async {
    backend.confirmed = false;
    board.move('StoryOne', 'Done');
    await board.save();
    expect(board.pending.keys, ['StoryOne']);
    expect(board.stories.first.state, 'Ready');
    expect(board.pending['StoryOne']!.problem, isNotNull);
    expect(board.cards('Ready').map((story) => story.id), contains('StoryOne'));
  });

  // Harm: reconnects during writes lose the only request to restart the watch.
  test('a reconnect during save restarts its watch after the save finishes', () async {
    backend.gate = Completer<void>();
    board.move('StoryOne', 'Done');
    final saved = board.save();
    await board.changeProject(backend, 'demo');
    expect(backend.watches, 1);
    backend.gate!.complete();
    await saved;
    await Future<void>.delayed(Duration.zero);
    expect(backend.watches, 2);
    backend.changes.add(PlanningSnapshot.from(backend.data));
    expect(board.live, isTrue);
    expect(board.stories.first.issues.first.state, 'Done');
    expect(board.stories.first.state, 'Elsewhere');
  });

  // Harm: repeated presses duplicate writes and edits during a write lose pending intent.
  test('an in-flight save rejects overlapping saves and edits', () async {
    backend.gate = Completer<void>();
    board.move('StoryOne', 'Done');
    final saved = board.save();
    await board.save();
    board.move('StoryTwo', 'Done');
    board.undoAll();
    expect(backend.calls, hasLength(1));
    expect(board.pending.keys, ['StoryOne']);
    backend.gate!.complete();
    await saved;
    expect(board.pending, isEmpty);
  });

  // Harm: completing a write after a closed window notifies a disposed model.
  test('late save replies do not notify after disposal', () async {
    backend.gate = Completer<void>();
    board.move('StoryOne', 'Done');
    var notifications = 0;
    board.addListener(() {
      notifications++;
    });
    final saved = board.save();
    final before = notifications;
    board.dispose();
    disposed = true;
    backend.gate!.complete();
    await saved;
    expect(notifications, before);
    expect(backend.changes.hasListener, isFalse);
  });

  // Harm: an unfamiliar state kind authorizes an unsupported move.
  test('unknown state kinds and working states never become pending', () async {
    board.move('StoryOne', 'Building');
    expect(board.pending, isEmpty);
    expect(board.moveProblem, isNull);
    board.move('StoryOne', 'Future');
    expect(board.pending, isEmpty);
    await board.save();
    expect(backend.calls, isEmpty);
    expect(board.stories.first.state, 'Ready');
  });

  // Harm: a deleted or unplaced story silently loses its pending intent or reconciliation notice.
  test('reconciliation names removed stories and retains notices across other drags', () async {
    board.move('StoryOne', 'Done');
    (backend.data['stories'] as List<dynamic>).removeAt(0);
    await board.reload();
    expect(board.pending, isEmpty);
    expect(board.moveProblem, contains('StoryOne is no longer reported'));
    (backend.data['stories'] as List<dynamic>).first['state'] = '';
    backend.changes.add(PlanningSnapshot.from(backend.data));
    // An unfamiliar state rereads the workflow before the watch answer is accepted.
    await Future<void>.delayed(Duration.zero);
    board.move('StoryTwo', 'Done');
    expect(board.pending['StoryTwo']!.from, '');
    expect(board.moveProblem, contains('StoryOne is no longer reported'));
  });

  // Harm: the mock shows a story where the daemon never puts it, so a saved move lands elsewhere than expected.
  test('every story of the mock board stands in the earliest open state of its issues', () {
    final board = mockPlanningBoard();
    final states = (board['states'] as List<dynamic>).cast<Map<String, dynamic>>();
    final order = [for (final state in states) state['name'] as String];
    final closed = {
      for (final state in states)
        if (state['kind'] == 'CLOSED') state['name'] as String,
    };
    final checked = <String>[];
    final breaking = <String>[];
    for (final story in (board['stories'] as List<dynamic>).cast<Map<String, dynamic>>()) {
      final issues = [
        for (final issue in (story['issues'] as List<dynamic>? ?? []).cast<Map<String, dynamic>>())
          issue['state'] as String,
      ];
      if (issues.isEmpty) continue;
      expect(issues.every(order.contains), isTrue, reason: '${story['id']} has an issue outside the workflow');
      final open = issues.where((state) => !closed.contains(state)).toList();
      final earliest = (open.isEmpty ? issues : open).reduce((a, b) => order.indexOf(a) <= order.indexOf(b) ? a : b);
      checked.add(story['id'] as String);
      if (story['state'] != earliest) breaking.add('${story['id']} in ${story['state']}, rule says $earliest');
    }
    expect(checked, [
      'ST72', 'ST14', 'ST15', 'ST291', 'ST292', 'ST23', 'ST27', //
      'ST12', 'ST5', 'ST193', 'ST1', 'ST2', 'ST3', 'ST10', 'ST243',
    ]);
    expect(breaking, isEmpty);
  });

  // Harm: the stand-in moves issues the daemon leaves, so a scenario saves a board the daemon never produces.
  test('the stand-in moves a story by its least advanced issues, whatever state the story reports', () {
    final source = _fixture();
    final story = (source['stories'] as List<dynamic>).first as Map<String, dynamic>;
    story['state'] = 'Elsewhere';
    final result = applyPlanningMove(source, {
      'revision': 'initial',
      'moves': [
        {'story': 'StoryOne', 'to': 'Done'},
      ],
    });
    final moved = (result['stories'] as List<dynamic>).first as Map<String, dynamic>;
    expect(result['moved'], ['IssueOne']);
    expect((moved['issues'] as List<dynamic>).map((issue) => issue['state']), ['Done', 'Elsewhere']);
  });

  // Harm: the board calls a story move the machine made unconfirmed, keeping a saved move pending.
  test('a story move expects its least advanced issues to move, not those in its shown state', () async {
    board.move('StoryOne', 'Done');
    backend.readFailure = mockRefusal('org.fuin.porelo.Tasks1.PlanningAdapterRefused', {
      'adapter': 'board',
      'detail': 'The store is not reachable',
    });
    await board.save();
    // The reread failed, so the card keeps the machine's last answer while its issues stand in Done and Elsewhere.
    expect(board.stories.first.state, 'Ready');
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    backend.readFailure = null;
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    board.move('StoryOne', 'Done');
    final saving = board.save();
    await backend.listingStarted!.future;
    expect(backend.calls.last['moves'], [
      {'story': 'StoryOne', 'to': 'Done'},
    ]);
    // The machine moved IssueTwo, the least advanced; the board must take that as its confirmation.
    expect(backend.data['moved'], ['IssueTwo']);
    expect(board.pending['StoryOne']?.problem, isNull);
    expect(board.pending, isEmpty);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Done']);
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
  });

  // Harm: a story shown closed while one of its issues is still open hides open work after a save.
  test('a saved story with issues keeps its reported state until the machine says its new one', () async {
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    board.move('StoryOne', 'Done');
    final saving = board.save();
    await backend.listingStarted!.future;
    expect(board.pending, isEmpty);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    expect(board.stories.first.state, 'Ready');
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
    expect(board.stories.first.state, 'Elsewhere');
  });

  // Harm: a reread failing after a made save goes unsaid, or turns off saving for good.
  test('a failed reread after a confirmed save says so and leaves Save offered', () async {
    board.move('StoryTwo', 'Done');
    backend.readFailure = mockRefusal('org.fuin.porelo.Tasks1.NotYetBuilt', {'method': 'ListStories'});
    await board.save();
    expect(board.pending, isEmpty);
    expect(board.moveProblem, isNotNull);
    expect(board.unavailableActions.keys, isNot(contains('SaveMoves')));
  });

  // Harm: a story move the machine did not confirm is dropped as saved.
  test('an empty answer confirms no move of a story whose issues are unreported or outside the workflow', () async {
    final unreported = _fixture();
    ((unreported['stories'] as List<dynamic>).first as Map<String, dynamic>).remove('issues');
    final unplaced = _fixture();
    ((unplaced['stories'] as List<dynamic>).first as Map<String, dynamic>)['issues'] = [
      {'id': 'IssueOne', 'state': 'Gone'},
    ];
    for (final (index, data) in [unreported, unplaced].indexed) {
      board.undoAll();
      backend.data = {...data, 'revision': 'shown-$index'};
      backend.changes.add(PlanningSnapshot.from(backend.data));
      await Future<void>.delayed(Duration.zero);
      backend.confirmed = false;
      board.move('StoryOne', 'Done');
      await board.save();
      expect(board.pending['StoryOne']?.problem, 'The machine did not confirm this destination.', reason: '$index');
    }
  });

  // Harm: after a save that moved an issue alone and with its story, the board shows it where the machine did not.
  test('an issue move staged before its story move shows where the machine put it', () async {
    board.moveIssue('IssueOne', 'Elsewhere');
    board.move('StoryOne', 'Done');
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final saving = board.save();
    await backend.listingStarted!.future;
    final stored =
        ((backend.data['stories'] as List<dynamic>).first as Map<String, dynamic>)['issues'] as List<dynamic>;
    expect(stored.map((issue) => issue['state']), ['Elsewhere', 'Elsewhere']);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Elsewhere', 'Elsewhere']);
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
  });

  // Harm: the stand-in moves a story by issues an earlier item of the same batch changed, not by the shown board.
  test('the stand-in moves a story by its least advanced issues on the shown board, before explicit issues', () {
    final result = applyPlanningMove(_fixture(), {
      'revision': 'initial',
      'moves': [
        {'issue': 'IssueOne', 'to': 'Elsewhere'},
        {'story': 'StoryOne', 'to': 'Done'},
      ],
    });
    final story = (result['stories'] as List<dynamic>).first as Map<String, dynamic>;
    expect((story['issues'] as List<dynamic>).map((issue) => issue['state']), ['Elsewhere', 'Elsewhere']);
  });

  // Harm: the stand-in writes the state of a story that has issues, which the daemon refuses.
  test('the stand-in refuses a story move when an issue stands outside the workflow', () {
    final source = _fixture();
    ((source['stories'] as List<dynamic>).first as Map<String, dynamic>)['issues'] = [
      {'id': 'IssueOne', 'state': 'Gone'},
    ];
    expect(
      () => applyPlanningMove(source, {
        'revision': 'initial',
        'moves': [
          {'story': 'StoryOne', 'to': 'Done'},
        ],
      }),
      throwsA(isA<PlanningRefusal>().having((error) => error.name, 'refusal', 'MoveNotAllowed')),
    );
  });

  // Harm: a story whose issues are all closed moves the wrong closed issue, in the stand-in or on the board.
  test('a story with every issue closed moves those in the earliest closed state', () async {
    Map<String, dynamic> closed() => {
      'states': [
        {'name': 'Ready', 'kind': 'WAITING'},
        {'name': 'Finished', 'kind': 'CLOSED'},
        {'name': 'Rejected', 'kind': 'CLOSED'},
      ],
      'phases': [
        {
          'name': 'Work',
          'states': ['Ready', 'Finished', 'Rejected'],
        },
      ],
      'stories': [
        {
          'id': 'StoryOne',
          'state': 'Finished',
          'issues': [
            {'id': 'IssueOne', 'state': 'Rejected'},
            {'id': 'IssueTwo', 'state': 'Finished'},
          ],
        },
      ],
      'revision': 'closed',
    };
    final result = applyPlanningMove(closed(), {
      'revision': 'closed',
      'moves': [
        {'story': 'StoryOne', 'to': 'Ready'},
      ],
    });
    expect(result['moved'], ['IssueTwo']);
    backend.data = closed();
    backend.changes.add(PlanningSnapshot.from(backend.data));
    await Future<void>.delayed(Duration.zero);
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    board.move('StoryOne', 'Ready');
    final saving = board.save();
    await backend.listingStarted!.future;
    expect(board.pending, isEmpty);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Rejected', 'Ready']);
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
  });

  // Harm: a prepared order changes planning state or a reading decision publishes an incorrect story state.
  test('the stand-in leaves an order unstarted and computes a reading decision from issue states', () {
    final board = mockPlanningBoard();
    final published = <String, Map<String, dynamic>>{};
    final work = MockStoryWork((project) => board, (project, changed) => published[project] = changed);
    Map<String, dynamic> story(Map<String, dynamic> board, String id) =>
        (board['stories'] as List<dynamic>).cast<Map<String, dynamic>>().firstWhere((story) => story['id'] == id);
    final order = work('StartStoryWork', {
      'project': 'demo',
      'story': 'ST291',
      'to': 'Reading',
      'mode': 'UNATTENDED',
      'revision': board['revision'],
    });
    expect(order['tasks'], isNotEmpty);
    expect(published, isEmpty);
    expect(story(board, 'ST291')['state'], 'Backlog');
    final reading = story(board, 'ST1');
    reading['state'] = 'Reading';
    final stopped = (reading['issues'] as List<dynamic>).first as Map<String, dynamic>;
    stopped.addAll({'state': 'Reading', 'readingStopped': true, 'rounds': 2, 'roundsMost': 2});
    work('ResolveIssueReading', {
      'project': 'demo',
      'story': 'ST1',
      'issue': stopped['id'],
      'round': 2,
      'choice': 'REVIEW_ANYWAY',
      'comment': '',
      'revision': board['revision'],
    });
    expect(story(published['demo']!, 'ST1')['state'], 'Review');
  });
}
