import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';

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

  final previewed = <Map<String, dynamic>>[];
  final digests = <String>[];
  final confirms = <bool>[];
  List<Map<String, dynamic>> warnings = const [];
  Object? previewFailure;

  @override
  Future<PlanningMovePreview> previewMoves(String project, List<PlanningMove> moves, String revision) async {
    previewed.add({'moves': moves.map((move) => move.toMap()).toList(), 'revision': revision});
    if (previewFailure case final error?) throw error;
    if (revision != data['revision']) {
      throw mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'revision': data['revision']});
    }
    return PlanningMovePreview.from({
      'revision': revision,
      'digest': FakePlanningSource.digestAt(revision),
      'issues': <Object>[],
      'warnings': warnings,
    });
  }

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    String revision, {
    required String digest,
    bool confirm = false,
  }) async {
    final parameters = {'project': project, 'moves': moves.map((move) => move.toMap()).toList(), 'revision': revision};
    calls.add(parameters);
    digests.add(digest);
    confirms.add(confirm);
    if (gate case final waiting?) await waiting.future;
    if (failure case final error?) throw error;
    if (moves.any((move) => move is PlanningStoryMove && move.story == refusedStory)) {
      throw mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {
        'story': refusedStory,
        'detail': 'Work is still running',
      });
    }
    if (!confirmed) return PlanningMoveResult.from({'revision': data['revision'], 'moved': <String>[]});
    // This stand-in warns only of what a test sets, so it saves as confirmed: a story that would not fit its issues is
    // the stand-in's preview's to warn of, tested on its own.
    data = applyPlanningMove(data, {...parameters, 'confirm': true});
    final answer = {'revision': data['revision'], 'moved': data['moved']};
    return PlanningMoveResult.from(reply?.call(answer) ?? answer);
  }
}

/// A provider whose first save outlasts the screen's wait: the screen is told `TimedOut`, and the save lands later.
class _SlowSource extends FakePlanningSource {
  _SlowSource() : super(smallBoard());

  bool _timesOut = true;
  (String, List<PlanningMove>, String, String)? _late;

  /// Whether a landed save's board is kept from every watch, as one not streamed yet.
  bool holding = false;

  /// How often the store was written.
  int written = 0;

  @override
  Future<PlanningMoveResult> saveMoves(
    String project,
    List<PlanningMove> moves,
    String revision, {
    required String digest,
    bool confirm = false,
  }) async {
    if (_timesOut) {
      _timesOut = false;
      _late = (project, moves, revision, digest);
      throw const PlanningRefusal('TimedOut', {}, 'the planner did not answer within 30 s');
    }
    final saved = await super.saveMoves(project, moves, revision, digest: digest, confirm: confirm);
    written++;
    return saved;
  }

  /// Lands the save the screen stopped waiting for.
  Future<void> land() async {
    final (project, moves, revision, digest) = _late!;
    await saveMoves(project, moves, revision, digest: digest);
  }

  @override
  void replace(Map<String, dynamic> next) {
    if (holding) {
      board = next;
    } else {
      super.replace(next);
    }
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

  // Harm: told a slow save failed, the person makes a saved move again.
  test('a save answered too late shows as saving and then as saved once the board shows it', () async {
    final source = _SlowSource();
    final slow = PlanningBoard(source, 'demo');
    addTearDown(slow.dispose);
    await pumpEventQueue();

    await slow.move('S1', 'Review');

    expect(slow.moveProblem, isNull, reason: 'a save the screen stopped waiting for has not failed');
    expect(slow.moveNotice, isNotNull, reason: 'the person is told the save still runs');
    expect(slow.saving, isFalse, reason: 'the stream must be taken to see the save land');
    await source.land();
    await pumpEventQueue();
    expect(slow.pending, isEmpty, reason: 'the streamed board shows the move');
    expect(slow.moveProblem, isNull, reason: 'a landed save is no move made elsewhere');
    expect(slow.moveNotice, 'Planning changes saved.', reason: 'the board shows the save done');
  });

  // Harm: a save that never lands is told as still saving, and the person leaves a lost change behind.
  test('an unanswered move the board never shows turns into a problem after the deadline', () async {
    final source = _SlowSource();
    final slow = PlanningBoard(source, 'demo', unansweredWait: const Duration(milliseconds: 1));
    addTearDown(slow.dispose);
    await pumpEventQueue();

    await slow.move('S1', 'Review');
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(slow.pending, isEmpty, reason: 'the move is no longer awaited, and its card stands where it is reported');
    expect(slow.moveProblem, contains('S1'), reason: 'the person is told to move it again');
    expect(slow.moveNotice, isNull, reason: 'nothing says it is still saving');
    expect(slow.saving, isFalse);
  });

  // Harm: a move written twice, or a second move sent while the first one's save may still land.
  test('while a slow save is unanswered no other move is taken, and none is sent', () async {
    final source = _SlowSource();
    final slow = PlanningBoard(source, 'demo');
    addTearDown(slow.dispose);
    await pumpEventQueue();
    await slow.move('S1', 'Review');

    await slow.move('S2', 'Backlog');

    expect(source.previews, hasLength(1), reason: 'the second move is not even previewed');
    expect(slow.moveProblem, contains('Saving…'), reason: 'the person reads why the move was not taken');
    expect(slow.pending.keys, ['S1'], reason: 'the slow save still waits on the board');
    await source.land();
    await pumpEventQueue();
    expect(source.written, 1, reason: 'the store is written once');
    expect(slow.pending, isEmpty, reason: 'the board shows the move');
  });

  // Harm: a refused move shows as moved, or a feature the planner lacks stays offered.
  test('every refused save puts the card back where it is reported and says why', () async {
    for (final name in ['WorkNotAllowed', 'Busy', 'NotOffered']) {
      backend.failure = PlanningRefusal(name, {'story': 'StoryOne'}, 'Refused.');
      await board.move('StoryOne', 'Done');
      expect(board.pending, isEmpty, reason: name);
      expect(board.moveProblem, contains('Refused.'), reason: name);
      expect(board.cards('Done'), isEmpty, reason: name);
      expect(board.unavailableActions.keys, name == 'NotOffered' ? ['SaveMoves'] : isEmpty);
      board.unavailableActions.clear();
    }
  });

  // Harm: a partly pushed save hides the store's actual states or loses which repositories failed.
  test('a partly saved move reloads the board and retains its typed refusal', () async {
    backend.data = applyPlanningMove(backend.data, {
      'revision': 'initial',
      'moves': [
        {'story': 'StoryTwo', 'to': 'Done'},
      ],
    })..['revision'] = 'initial';
    backend.failure = const PlanningRefusal('PartlySaved', {'pushed': 'repo', 'failed': 'other'}, 'Partly saved.');
    await board.move('StoryTwo', 'Done');
    expect(backend.calls, hasLength(1));
    expect(backend.storyReads, 1);
    expect(board.stories.last.state, 'Done');
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

  // Harm: a move with nothing to warn of waits for a Save that no longer exists, or goes out unpreviewed.
  test('a move with no warning is previewed and saved at once with the digest of its preview', () async {
    await board.move('StoryTwo', 'Done');

    expect(backend.previewed, [
      {
        'moves': [
          {'story': 'StoryTwo', 'to': 'Done'},
        ],
        'revision': 'initial',
      },
    ]);
    expect(backend.calls, hasLength(1), reason: 'saved once, with no wait');
    expect(backend.digests, [FakePlanningSource.digestAt('initial')], reason: 'the save carries what was shown');
    expect(board.question, isNull);
    expect(board.stories.last.state, 'Done');
  });

  // Harm: a story's move carries an issue of another repository along unasked.
  test('a move with a warning sends no save until confirmed, and its cancel sends nothing', () async {
    backend.warnings = [
      {
        'kind': 'ISSUES_ALONG',
        'item': 'StoryOne',
        'from': 'Ready',
        'to': 'Done',
        'run': null,
        'tasks': <String>[],
        'offers': <String>[],
        'issues': [
          {'id': 'IssueOne', 'repository': 'other', 'from': 'Ready', 'to': 'Done'},
        ],
        'skipped': <String>[],
      },
    ];

    final moving = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    expect(board.question?.warnings.single.issues.single.repository, 'other', reason: 'the person sees it first');
    expect(board.cards('Done').map((story) => story.id), ['StoryOne'], reason: 'the card keeps its new place');
    expect(backend.calls, isEmpty, reason: 'nothing is saved before a confirmation');
    board.cancelMove();
    await moving;
    expect(backend.calls, isEmpty, reason: 'a cancel sends nothing');
    expect(board.pending, isEmpty);
    expect(board.cards('Ready').map((story) => story.id), contains('StoryOne'), reason: 'the card goes back');

    final again = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    await board.confirmMove();
    await again;
    expect(backend.calls, hasLength(1), reason: 'a confirmation saves once');
    expect(board.pending, isEmpty);
  });

  // Harm: a story set where its issues do not fit, confirmed without the planner knowing it was confirmed.
  test('a confirmed move whose story will not fit its issues is saved with confirm', () async {
    backend.warnings = [
      {'kind': 'STORY_MISMATCH', 'item': 'StoryTwo', 'from': 'Ready', 'to': 'Done', 'issues': <Object>[]},
    ];
    final moving = board.move('StoryTwo', 'Done');
    await pumpEventQueue();
    await board.confirmMove();
    await moving;
    expect(backend.confirms, [true]);
  });

  // Harm: the move's stop ends a task the person was never shown, or a task the save stopped is left running.
  test('a confirmed move over running work stops nothing itself and ends exactly the tasks its save names', () async {
    final ends = _Ends();
    board.taskChanges = ends;
    backend.warnings = [
      {
        'kind': 'RUNNING_WORK',
        'item': 'StoryTwo',
        'from': 'Ready',
        'to': 'Done',
        'run': 'run-1',
        'tasks': ['build'],
        'offers': <String>[],
        'issues': <Object>[],
        'skipped': <String>[],
      },
    ];
    backend.reply = (answer) => {
      ...answer,
      'stopped': [
        {
          'story': 'StoryTwo',
          'run': 'run-1',
          'tasks': [
            {'task': 'build', 'containerId': 'box'},
          ],
        },
      ],
    };
    final moving = board.move('StoryTwo', 'Done');
    await pumpEventQueue();
    expect(board.question, isNotNull, reason: 'the running work is asked about');
    await board.confirmMove();
    await moving;
    expect(backend.calls, hasLength(1), reason: 'the save alone went out; a stop of its own would have failed first');
    expect(backend.confirms, [true], reason: 'the save carries the confirmation that lets it stop the run');
    expect(ends.stopped, ['StoryTwo build@box'], reason: 'exactly the task the save stopped is ended');
    expect(board.moveProblem, isNull);
  });

  // Harm: a saved move's issue states stay stale until a watch arrives.
  test('a saved move applies the confirmed issues and rereads computed story states', () async {
    await board.move('StoryOne', 'Done');
    expect(backend.calls, [
      {
        'project': 'demo',
        'moves': [
          {'story': 'StoryOne', 'to': 'Done'},
        ],
        'revision': 'initial',
      },
    ]);
    expect(board.pending, isEmpty);
    expect(
      board.stories.first.state,
      'Done',
      reason: 'the planner writes the state of a story moved over its issues once confirmed',
    );
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    expect(board.snapshot!.revision, 'initial:moved');
    expect(backend.workflowReads, 0);
    expect(backend.storyReads, 1);
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

  // Harm: a move confirmed on one board saved on another the person never saw.
  test('a save refused as stale reads the board again and asks anew before saving', () async {
    backend.warnings = [
      {
        'kind': 'SKIPS_STATES',
        'item': 'StoryTwo',
        'from': 'Ready',
        'to': 'Elsewhere',
        'skipped': ['Done'],
      },
    ];
    final moving = board.move('StoryTwo', 'Elsewhere');
    await pumpEventQueue();
    backend.data = {...backend.data, 'revision': 'changed'};
    await board.confirmMove();
    await pumpEventQueue();

    expect(backend.calls.single['revision'], 'initial', reason: 'the confirmed save went against the board shown');
    expect(board.snapshot!.revision, 'changed', reason: 'the board is read again');
    expect(board.question?.again, isTrue, reason: 'the move is asked about anew');
    expect(backend.previewed.last['revision'], 'changed');
    await board.confirmMove();
    await moving;
    expect(backend.calls.last['revision'], 'changed');
    expect(board.stories.last.state, 'Elsewhere');
  });

  // Harm: a gesture begun on an older board previewed against the new one.
  test('a preview refused as stale moves nothing and reads the board again', () async {
    backend.data = {...backend.data, 'revision': 'changed'};
    await board.move('StoryTwo', 'Done');
    await pumpEventQueue();
    expect(backend.calls, isEmpty);
    expect(board.pending, isEmpty);
    expect(board.moveProblem, contains('Move it again'));
    expect(board.snapshot!.revision, 'changed');
  });

  // Harm: a planner without the preview answered as if the move were made.
  test('a preview the planner does not know moves nothing', () async {
    backend.previewFailure = const PlanningRefusal('Http404', {}, 'the planner answered HTTP 404');
    await board.move('StoryTwo', 'Done');
    expect(backend.calls, isEmpty);
    expect(board.pending, isEmpty);
    expect(board.moveProblem, contains('404'));
    expect(board.stories.last.state, 'Ready');
  });

  // Harm: unrecognized or unnamed daemon refusals leave rejected cards marked as moved.
  test('all named and unnamed refusal kinds return the card to its reported column', () async {
    for (final name in ['WorkNotAllowed', 'NoSuchStory', 'AnotherRefusal']) {
      for (final named in [true, false]) {
        backend.failure = mockRefusal('org.fuin.porelo.Tasks1.$name', {
          'detail': 'The daemon refused this save',
          if (named) 'story': 'StoryOne',
        });
        await board.move('StoryOne', 'Done');
        expect(board.pending, isEmpty);
        expect(board.cards('Ready').map((story) => story.id), ['StoryOne', 'StoryTwo']);
        expect(board.moveProblem, contains('The daemon refused this save'));
      }
    }
  });

  // Harm: a read in flight answers a board older than the one streamed meanwhile and holds the board on it for good,
  // since the stream tells every revision in order and never an older one again.
  for (final call in ['reload', 'stale save', 'read after a save']) {
    test('a board streamed during a $call wins over the read that was in flight', () async {
      backend.listing = Completer<PlanningSnapshot>();
      backend.listingStarted = Completer<void>();
      if (call == 'stale save') {
        backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
      }
      final operation = call == 'reload' ? board.reload() : board.move('StoryOne', 'Done');
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

  // Harm: a board streamed before the save reached the planner shows the saved move undone.
  for (final reread in ['answers', 'fails']) {
    test('a board streamed while a save waits loses to the saved board when the read after it $reread', () async {
      backend.gate = Completer<void>();
      final operation = board.move('StoryTwo', 'Done');
      await pumpEventQueue();
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
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
    backend.gate = Completer<void>();
    final operation = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    backend.changes.add(PlanningSnapshot.from(_fixture()..['revision'] = 'before the save'));
    backend.gate!.complete();
    await pumpEventQueue();
    expect(board.snapshot!.revision, 'initial');
    board.cancelMove();
    await operation;
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

  // Harm: after a stale save the move is asked about anew although the board now shows it made elsewhere.
  test('a stale save tells what the board that stays shows', () async {
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.StaleRevision', {'detail': 'Board changed'});
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final operation = board.move('StoryOne', 'Done');
    await backend.listingStarted!.future;
    final streamed = _fixture()..['revision'] = 'streamed';
    (streamed['stories'] as List<dynamic>).first['state'] = 'Done';
    backend.changes.add(PlanningSnapshot.from(streamed));
    backend.listing!.complete(PlanningSnapshot.from(_fixture()..['revision'] = 'read'));
    await operation;
    expect(board.pending, isEmpty);
    expect(board.question, isNull, reason: 'nothing is asked about a board that moved on');
    expect(board.moveProblem, contains('now stands in Done'));
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
      final saved = board.move('StoryOne', 'Done');
      await pumpEventQueue();
      backend.changes.add(PlanningSnapshot.from(broken));
      backend.gate!.complete();
      await saved;
      expect(board.snapshot!.complete, isTrue);
      expect(board.snapshot!.revision, 'initial:moved');
      expect(board.stories.first.issues.first.state, 'Done');
      expect(
        board.stories.first.state,
        'Done',
        reason: 'the planner writes the state of a story moved over its issues once confirmed',
      );
    }
  });

  // Harm: discarding every deferred watch loses a real later board after a refusal.
  test('a complete deferred watch newer than the held board is applied', () async {
    backend.gate = Completer<void>();
    backend.failure = mockRefusal('org.fuin.porelo.Tasks1.NoSuchStory', {
      'story': 'StoryOne',
      'detail': 'Story unavailable',
    });
    final saved = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    final changed = _fixture()..['revision'] = 'changed';
    (changed['stories'] as List<dynamic>).first['state'] = 'Elsewhere';
    backend.changes.add(PlanningSnapshot.from(changed));
    backend.gate!.complete();
    await saved;
    expect(board.snapshot!.revision, 'changed');
    expect(board.stories.first.state, 'Elsewhere');
    expect(board.pending, isEmpty);
    expect(board.moveProblem, contains('Story unavailable'));
  });

  // Harm: malformed save answers replace a complete board with missing data.
  test('a move answered incompletely or unversioned keeps the board and says so', () async {
    for (final broken in <Map<String, dynamic> Function(Map<String, dynamic>)>[
      (reply) => {...reply}..remove('moved'),
      (reply) => {...reply, 'revision': ''},
    ]) {
      backend.data = _fixture();
      backend.changes.add(PlanningSnapshot.from(backend.data));
      backend.reply = broken;
      await board.move('StoryOne', 'Done');
      expect(board.snapshot!.complete, isTrue);
      expect(board.snapshot!.revision, 'initial');
      expect(board.stories.first.state, 'Ready');
      expect(board.pending, isEmpty);
      expect(board.moveProblem, isNotNull);
    }
  });

  // Harm: a negative confirmation is accepted as a saved board.
  test('a negative move confirmation is told and leaves the card where it is reported', () async {
    backend.confirmed = false;
    await board.move('StoryOne', 'Done');
    expect(board.pending, isEmpty);
    expect(board.stories.first.state, 'Ready');
    expect(board.moveProblem, contains('did not confirm'));
    expect(board.cards('Ready').map((story) => story.id), contains('StoryOne'));
  });

  // Harm: reconnects during writes lose the only request to restart the watch.
  test('a reconnect during save restarts its watch after the save finishes', () async {
    backend.gate = Completer<void>();
    final saved = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    await board.changeProject(backend, 'demo');
    expect(backend.watches, 1);
    backend.gate!.complete();
    await saved;
    await Future<void>.delayed(Duration.zero);
    expect(backend.watches, 2);
    backend.changes.add(PlanningSnapshot.from(backend.data));
    expect(board.live, isTrue);
    expect(board.stories.first.issues.first.state, 'Done');
    expect(
      board.stories.first.state,
      'Done',
      reason: 'the planner writes the state of a story moved over its issues once confirmed',
    );
  });

  // Harm: a second move sent while one is saved, or lost without a word.
  test('a move made while another is saved is refused with words and nothing is queued', () async {
    backend.gate = Completer<void>();
    final saved = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    await board.move('StoryTwo', 'Done');
    expect(board.moveProblem, contains('Saving…'));
    expect(backend.calls, hasLength(1));
    expect(board.pending.keys, ['StoryOne']);
    backend.gate!.complete();
    await saved;
    expect(board.pending, isEmpty);
    expect(backend.calls, hasLength(1), reason: 'nothing was queued');
  });

  // Harm: completing a write after a closed window notifies a disposed model.
  test('late save replies do not notify after disposal', () async {
    backend.gate = Completer<void>();
    var notifications = 0;
    final saved = board.move('StoryOne', 'Done');
    await pumpEventQueue();
    board.addListener(() {
      notifications++;
    });
    board.dispose();
    disposed = true;
    backend.gate!.complete();
    await saved;
    expect(notifications, 0);
    expect(backend.changes.hasListener, isFalse);
  });

  // Harm: an unfamiliar state kind authorizes an unsupported move.
  test('unknown state kinds and working states are never moved to', () async {
    await board.move('StoryOne', 'Building');
    expect(board.pending, isEmpty);
    expect(board.moveProblem, isNull);
    await board.move('StoryOne', 'Future');
    expect(board.pending, isEmpty);
    expect(backend.previewed, isEmpty);
    expect(backend.calls, isEmpty);
    expect(board.stories.first.state, 'Ready');
  });

  // Harm: the mock shows a story where the daemon never puts it, so a saved move lands elsewhere than expected.
  test('every story of the mock board without a given state stands in the earliest open state of its issues', () {
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
      if (issues.isEmpty || story['stateGiven'] == true) continue;
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
      'confirm': true,
    });
    final moved = (result['stories'] as List<dynamic>).first as Map<String, dynamic>;
    expect(result['moved'], ['IssueOne']);
    expect((moved['issues'] as List<dynamic>).map((issue) => issue['state']), ['Done', 'Elsewhere']);
  });

  // Harm: the board calls a story move the machine made unconfirmed.
  test('a story move expects its least advanced issues to move, not those in its shown state', () async {
    backend.readFailure = mockRefusal('org.fuin.porelo.Tasks1.PlanningAdapterRefused', {
      'adapter': 'board',
      'detail': 'The store is not reachable',
    });
    await board.move('StoryOne', 'Done');
    // The reread failed, so the card keeps the machine's last answer while its issues stand in Done and Elsewhere.
    expect(board.stories.first.state, 'Ready');
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    backend.readFailure = null;
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final saving = board.move('StoryOne', 'Done');
    await backend.listingStarted!.future;
    expect(backend.calls.last['moves'], [
      {'story': 'StoryOne', 'to': 'Done'},
    ]);
    // The machine moved IssueTwo, the least advanced; the board must take that as its confirmation.
    expect(backend.data['moved'], ['IssueTwo']);
    expect(board.moveProblem, isNull);
    expect(board.pending, isEmpty);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Done']);
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
  });

  // Harm: a story shown closed while one of its issues is still open hides open work after a save.
  test('a saved story with issues keeps its reported state until the machine says its new one', () async {
    backend.listing = Completer<PlanningSnapshot>();
    backend.listingStarted = Completer<void>();
    final saving = board.move('StoryOne', 'Done');
    await backend.listingStarted!.future;
    expect(board.pending, isEmpty);
    expect(board.stories.first.issues.map((issue) => issue.state), ['Done', 'Elsewhere']);
    expect(board.stories.first.state, 'Ready');
    backend.listing!.complete(PlanningSnapshot.from(backend.data));
    await saving;
    expect(
      board.stories.first.state,
      'Done',
      reason: 'the planner writes the state of a story moved over its issues once confirmed',
    );
  });

  // Harm: a reread failing after a made save goes unsaid, or turns off moving for good.
  test('a failed reread after a confirmed save says so and leaves moving offered', () async {
    backend.readFailure = mockRefusal('org.fuin.porelo.Tasks1.NotYetBuilt', {'method': 'ListStories'});
    await board.move('StoryTwo', 'Done');
    expect(board.pending, isEmpty);
    expect(board.moveProblem, isNotNull);
    expect(board.unavailableActions.keys, isNot(contains('SaveMoves')));
  });

  // Harm: a story move the machine did not confirm is told as saved.
  test('an empty answer confirms no move of a story whose issues are unreported or outside the workflow', () async {
    final unreported = _fixture();
    ((unreported['stories'] as List<dynamic>).first as Map<String, dynamic>).remove('issues');
    final unplaced = _fixture();
    ((unplaced['stories'] as List<dynamic>).first as Map<String, dynamic>)['issues'] = [
      {'id': 'IssueOne', 'state': 'Gone'},
    ];
    for (final (index, data) in [unreported, unplaced].indexed) {
      backend.data = {...data, 'revision': 'shown-$index'};
      backend.changes.add(PlanningSnapshot.from(backend.data));
      await Future<void>.delayed(Duration.zero);
      backend.confirmed = false;
      await board.move('StoryOne', 'Done');
      expect(board.moveProblem, contains('did not confirm'), reason: '$index');
      expect(board.moveNotice, isNull, reason: '$index');
    }
  });

  // Harm: the stand-in moves a story by issues an earlier item of the same batch changed, not by the shown board.
  test('the stand-in moves a story by its least advanced issues on the shown board, before explicit issues', () {
    final result = applyPlanningMove(_fixture(), {
      'revision': 'initial',
      'moves': [
        {'issue': 'IssueOne', 'to': 'Elsewhere'},
        {'story': 'StoryOne', 'to': 'Done'},
      ],
      'confirm': true,
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
    final saving = board.move('StoryOne', 'Ready');
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

  PlanIssue issueOne() => board.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == 'IssueOne');

  // Harm: a person's move lost without a word, or staged from a state the issue has already left.
  test('a move chosen from a menu built before the issue moved on is refused with its new state', () async {
    final shown = issueOne();
    final moved = _fixture();
    ((moved['stories'] as List).first['issues'] as List).first['state'] = 'Done';
    moved['revision'] = 'moved by a task';
    backend.changes.add(PlanningSnapshot.from(moved));
    await Future<void>.delayed(Duration.zero);
    await board.moveIssue('IssueOne', 'Elsewhere', reported: shown);
    expect(board.pending, isEmpty, reason: 'nothing is staged from a state the issue has left');
    expect(board.moveProblem, contains('Done'), reason: 'the person reads where the issue stands now');
  });

  // Harm: a person's move lost without a word while a save runs.
  test('a move chosen while a save runs is refused with words', () async {
    backend.gate = Completer<void>();
    final saving = board.move('StoryTwo', 'Done');
    await pumpEventQueue();
    await board.moveIssue('IssueOne', 'Done', reported: issueOne());
    expect(board.pending.keys, isNot(contains('issue IssueOne')), reason: 'nothing is taken while saving');
    expect(board.moveProblem, contains('Saving…'), reason: 'the person reads why the move was not taken');
    backend.gate!.complete();
    await saving;
  });

  // Harm: a person's move lost without a word when its destination is not one the issue may enter.
  test('a move to a state the issue may not enter is refused with words', () async {
    await board.moveIssue('IssueOne', 'Building', reported: issueOne());
    expect(board.pending, isEmpty, reason: 'a working state is entered only by an issue worked by hand');
    expect(board.moveProblem, contains('Building'), reason: 'the person reads which destination was refused');
  });

  // Harm: a move asked from a menu that showed an older state staged from the issue's new state.
  test('a move chosen from a menu that showed another state than the board holds now is refused', () async {
    final moved = _fixture();
    ((moved['stories'] as List).first['issues'] as List).first['state'] = 'Done';
    moved['revision'] = 'moved by a task';
    backend.changes.add(PlanningSnapshot.from(moved));
    await Future<void>.delayed(Duration.zero);
    await board.moveIssue('IssueOne', 'Elsewhere', reported: issueOne(), shown: 'Ready');
    expect(board.pending, isEmpty, reason: 'the menu showed Ready; the issue stands in Done');
    expect(board.moveProblem, contains('Done'), reason: 'the person reads where the issue stands now');
  });

  // Harm: an issue worked by an agent put into a working state on a stale copy that says it is worked by hand.
  test("whether a move may enter a working state is judged on the board's issue, not the page's copy", () async {
    final copy = PlanIssue.from({'id': 'IssueOne', 'state': 'Ready', 'worker': 'MANUAL'});
    await board.moveIssue('IssueOne', 'Building', reported: copy);
    expect(board.pending, isEmpty, reason: 'on the board the issue is not worked by hand');
    expect(board.moveProblem, contains('Building'), reason: 'the person reads which destination was refused');
  });

  // Harm: a move staged for an issue that left the board while the menu stood open.
  test('a move for an issue no longer on the board is refused', () async {
    final shown = issueOne();
    final gone = _fixture();
    ((gone['stories'] as List).first['issues'] as List).removeAt(0);
    gone['revision'] = 'issue gone';
    backend.changes.add(PlanningSnapshot.from(gone));
    await Future<void>.delayed(Duration.zero);
    await board.moveIssue('IssueOne', 'Done', reported: shown);
    expect(board.pending, isEmpty, reason: 'nothing is staged for an issue the board no longer holds');
    expect(board.moveProblem, isNotNull, reason: 'the person reads why');
  });

  // Harm: the window's tests seeing the mock's given story jump to its issues' state, which the planner never does.
  test('the stand-in keeps a given state through a move and names its misfit until it fits', () {
    final saved = applyPlanningMove(mockPlanningBoard(), {
      'revision': 'design-1',
      'moves': [
        {'issue': 'B542', 'to': 'Review'},
      ],
    });
    final kept = mockPlanningStory(saved, 'ST295')!;
    expect(kept['state'], 'Review', reason: 'another issue\'s move leaves the given state');
    expect(kept['misfits'], ['SL38'], reason: 'SL38 still stands in Backlog');
    final fitting = applyPlanningMove(mockPlanningBoard(), {
      'revision': 'design-1',
      'moves': [
        {'issue': 'SL38', 'to': 'Review'},
      ],
    });
    expect(mockPlanningStory(fitting, 'ST295')!['misfits'], isEmpty, reason: 'its issues now give Review');
  });
}

/// A window that records which tasks it was asked to end.
class _Ends implements PlanningTaskChanges {
  final stopped = <String>[];

  @override
  String? unavailable(PlanningBoard board, String task) => null;

  @override
  void open(PlanningBoard board, String task) {}

  @override
  Future<void> stop(PlanningSource source, String project, String story, List<PlanningTaskStarted> tasks) async =>
      stopped.addAll([for (final task in tasks) '$story ${task.task}@${task.container}']);

  @override
  Future<void> started(
    PlanningSource source,
    String project,
    String story,
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
  ) async {}
}
