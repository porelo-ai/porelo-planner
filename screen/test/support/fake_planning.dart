import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:porelo_planning/planning_fake.dart' show applyPlanningWorker;
import 'package:porelo_planning/porelo_planning.dart';

/// A small board: one waiting, one working and one closed state, and two stories with an issue each.
Map<String, dynamic> smallBoard({String revision = 'r1'}) => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'Plan',
      'states': ['Backlog'],
    },
    {
      'name': 'Build',
      'states': ['Building', 'Review'],
    },
    {
      'name': 'Done',
      'states': ['Finished'],
    },
  ],
  'stories': [
    {
      'id': 'S1',
      'title': 'First story',
      'state': 'Backlog',
      'priority': 'High',
      'issues': [
        {
          'id': 'I1',
          'repository': 'repo',
          'title': 'First issue',
          'state': 'Backlog',
          'blockedBy': <String>[],
          'worker': 'AGENT',
        },
      ],
    },
    {
      'id': 'S2',
      'title': 'Second story',
      'state': 'Review',
      'priority': 'Medium',
      'issues': [
        {
          'id': 'I2',
          'repository': 'repo',
          'title': 'Second issue',
          'state': 'Review',
          'blockedBy': <String>[],
          'worker': 'AGENT',
        },
      ],
    },
  ],
  'revision': revision,
  'repositories': ['repo', 'porelo-other'],
};

/// A provider that answers from a board held in memory and records what it was asked to change.
///
/// What it does not serve it refuses as a provider without that call would: [PlanningUnsupported].
class FakePlanningSource implements PlanningSource {
  /// Serves [board] under [label].
  FakePlanningSource(this.board, {this.label = 'fake'});

  @override
  final String label;

  /// The board as the provider holds it now.
  Map<String, dynamic> board;

  /// Every save asked of it: the project, the moves and the revision they were made against.
  final List<(String, List<PlanningMove>, String)> saves = [];

  final _changes = StreamController<Map<String, dynamic>>.broadcast();

  PlanningSnapshot _stories() => PlanningSnapshot.from({
    'stories': board['stories'],
    'revision': board['revision'],
    'repositories': ?board['repositories'],
  });

  /// Replaces the board, as another person's change would, and tells every watch.
  void replace(Map<String, dynamic> next) {
    board = next;
    _changes.add(next);
  }

  /// Ends every watch.
  Future<void> close() => _changes.close();

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    late final StreamController<PlanningSnapshot> replies;
    StreamSubscription<Map<String, dynamic>>? changes;
    replies = StreamController<PlanningSnapshot>(
      onListen: () {
        replies.add(_stories());
        changes = _changes.stream.listen((_) => replies.add(_stories()), onDone: replies.close);
      },
      onCancel: () => changes?.cancel(),
    );
    return replies.stream;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async =>
      PlanningSnapshot.from({'states': board['states'], 'phases': board['phases']});

  @override
  Future<PlanningSnapshot> stories(String project) async => _stories();

  @override
  Future<PlanningMoveResult> saveMoves(String project, List<PlanningMove> moves, String revision) async {
    saves.add((project, moves, revision));
    if (revision != board['revision']) throw PlanningRefusal('StaleRevision', {'revision': board['revision']});
    final moved = <String>[];
    final stories = [
      for (final story in (board['stories'] as List).cast<Map<String, dynamic>>())
        {
          ...story,
          'issues': [
            for (final issue in (story['issues'] as List).cast<Map<String, dynamic>>())
              if (moves.whereType<PlanningIssueMove>().where((move) => move.issue == issue['id']).firstOrNull
                  case final move?)
                {...issue, 'state': move.to}
              else if (moves.whereType<PlanningStoryMove>().where((move) => move.story == story['id']).firstOrNull
                  case final move?)
                {...issue, 'state': move.to}
              else
                issue,
          ],
        },
    ];
    for (final move in moves) {
      if (move is PlanningIssueMove) moved.add(move.issue);
      if (move is PlanningStoryMove) {
        final story = stories.firstWhere((story) => story['id'] == move.story);
        moved.addAll([
          for (final issue in (story['issues'] as List).cast<Map<String, dynamic>>()) issue['id'] as String,
        ]);
        story['state'] = move.to;
      }
    }
    final next = 'r${saves.length + 1}';
    replace({...board, 'stories': stories, 'revision': next});
    return PlanningMoveResult.from({'revision': next, 'moved': moved});
  }

  /// Every new issue asked of it: the project, repository, title, what must be true, size and revision.
  final List<(String, String, String, String, String?, String)> added = [];

  /// The worker of every new issue asked of it, in the order of [added].
  final List<IssueWorker> addedWorkers = [];

  /// Every worker change asked of it: the project, issue, worker and revision.
  final List<(String, String, IssueWorker, String)> workers = [];

  @override
  Future<PlanningWorkerSaved> setWorker(String project, String issue, IssueWorker worker, String revision) async {
    workers.add((project, issue, worker, revision));
    final next = applyPlanningWorker(board, issue, worker.wire, revision);
    if (next['revision'] != board['revision']) replace(next);
    return PlanningWorkerSaved(revision: next['revision'] as String);
  }

  /// A refusal of the next new issue, set by a test.
  Object? addFailure;

  @override
  Future<PlanningIssueAdded> createIssue(
    String project,
    String repository,
    String title,
    String mustBeTrue,
    String? size,
    String revision, {
    IssueWorker worker = IssueWorker.agent,
  }) async {
    added.add((project, repository, title, mustBeTrue, size, revision));
    addedWorkers.add(worker);
    if (addFailure case final failure?) throw failure;
    if (revision != board['revision']) throw PlanningRefusal('StaleRevision', {'revision': board['revision']});
    final next = 'r${saves.length + added.length + 1}';
    replace({...board, 'revision': next});
    return PlanningIssueAdded(id: 'I${added.length + 2}', revision: next);
  }

  /// Every priority asked of it: the project, story, priority and revision.
  final List<(String, String, String, String)> priorities = [];

  /// A refusal of the next priority, set by a test.
  Object? priorityFailure;

  /// Held until a test completes it, so a priority answers only once the test lets it.
  Completer<void>? priorityHeld;

  @override
  Future<String> setPriority(String project, String story, String priority, String revision) async {
    priorities.add((project, story, priority, revision));
    if (priorityHeld case final held?) await held.future;
    if (priorityFailure case final failure?) throw failure;
    if (revision != board['revision']) throw PlanningRefusal('StaleRevision', {'revision': board['revision']});
    final next = 'r${saves.length + added.length + priorities.length + 1}';
    replace({
      ...board,
      'revision': next,
      'stories': [
        for (final record in (board['stories'] as List).cast<Map<String, dynamic>>())
          record['id'] == story ? {...record, 'priority': priority} : record,
      ],
    });
    return next;
  }

  Never _notServed(String call) => throw PlanningUnsupported('this provider has no $call');

  @override
  Future<PlanStoryDetail> getStory(String project, String story) async => _notServed('GetStory');

  @override
  Future<PlanIssueList> listStoryIssues(String project, String story) async => _notServed('ListStoryIssues');

  @override
  Future<PlanIssueDetail> getIssue(String project, String issue) async => _notServed('GetIssue');

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues}) async =>
      _notServed('ProposeStoryWork');

  @override
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request) async => _notServed('StartStoryWork');

  /// Its [board] is what the repositories hold, so a fetch only answers its revision.
  @override
  Future<String> refreshProject(String project) async => board['revision'] as String;

  @override
  Future<PlanningTaskOrder> discussIdea(String project, String idea) async => _notServed('DiscussIdea');

  @override
  Future<String> handIdea(String project, String offer, Map<String, Object?> idea) async => _notServed('HandIdea');

  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) async => _notServed('GetStoryWork');

  @override
  Future<StoryWorkStatus> stopStoryWork(String project, String story) async => _notServed('StopStoryWork');

  @override
  Future<TaskEventResolution> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
    List<PlanningLanding> landed = const [],
  }) async => _notServed('TaskEvent');

  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) async => _notServed('GetIssue');

  @override
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    String revision, {
    String? comment,
    PlanningRework? rework,
  }) async => _notServed('ResolveIssueReading');
}

/// A client with the providers a test gives it, telling its boards when they change.
class FakePlanningClient extends ChangeNotifier with PlanningTaskReports {
  /// Starts with [providers], the first one current.
  FakePlanningClient(this._providers) : currentProvider = _providers.firstOrNull?.name;

  List<PlanningProvider> _providers;

  /// Every task change the window reported.
  final List<(String, String, PlanningTaskEvent, String, String, String, PlanningTaskEnded?)> events = [];

  @override
  List<PlanningProvider> get providers => _providers;

  /// Replaces the providers, as a machine added, lost or reconnected would.
  set providers(List<PlanningProvider> next) {
    _providers = next;
    notifyListeners();
  }

  @override
  String? currentProvider;

  @override
  String? chosenProject;

  @override
  Future<void> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
    List<PlanningLanding> landed = const [],
  }) async {
    events.add((project, task, event, offer, container, machine, ended));
    await super.taskEvent(
      project,
      task,
      event,
      offer: offer,
      container: container,
      machine: machine,
      ended: ended,
      landed: landed,
    );
  }
}

/// One connected provider named [name] serving [source] for [project].
PlanningProvider provider(String name, PlanningSource source, {String project = 'demo', bool connected = true}) =>
    PlanningProvider(name: name, connected: connected, projects: [project], source: source);
