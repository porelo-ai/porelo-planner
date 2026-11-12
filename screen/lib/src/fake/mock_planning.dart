import 'dart:async';

import 'package:flutter/foundation.dart';

import '../client.dart';
import '../hooks.dart';
import '../models.dart';
import '../story_work_order.dart';
import '../task_runs.dart';
import 'planning_data.dart';
import 'planning_moves.dart';
import 'refusal.dart';
import 'story_work.dart';

/// One provider's planning kept in memory: a whole board per project, answering as the daemon answers.
///
/// A test or a rehearsal sets what the provider does next: refuse, hold a save, fail a watch or change a board.
class MockPlanningSource implements PlanningSource {
  /// Takes the provider's name for a person.
  MockPlanningSource([this.label = 'mock']);

  @override
  final String label;

  /// Whether this provider predates planning, so every planning call is unsupported.
  bool unsupported = false;

  /// Whole boards that a test can replace independently per project.
  final Map<String, Map<String, dynamic>> boards = {};
  final _changes = StreamController<(String, Map<String, dynamic>)>.broadcast();

  /// A failure of every planning call, supplied by a test.
  Object? failure;

  /// A refusal of saves without failing the board's watch.
  Object? moveFailure;

  /// A story whose saves are refused while unrelated saves go through.
  String? refusedStory;

  /// Detail attached to that refused story.
  String refusalDetail = '';

  /// A save held until the test completes it, so a write is in flight without a timer.
  Completer<void>? moveGate;

  /// A save answer held until the test completes it.
  Completer<void>? moveHeld;

  /// Saves received, each with its project, moves and revision.
  final List<Map<String, dynamic>> moveCalls = [];

  /// The number of watches open now.
  int watchers = 0;

  /// A refusal of every detail read, independent of the board watch.
  Object? detailFailure;

  /// Failures of single detail reads, by part: `Story`, `Issue list` or `Issue`.
  final Map<String, Object> partFailures = {};

  /// A story read a test answers itself, such as a delayed one.
  Future<PlanStoryDetail> Function(String project, String story)? readStory;

  /// An issue-list read a test answers itself.
  Future<PlanIssueList> Function(String project, String story)? readIssues;

  /// Revision-checked work decisions on the same boards, with their calls observable.
  late final work = MockStoryWork(board, push);

  /// Told each start this provider confirmed, with its project and the work it started.
  void Function(String project, Map<String, dynamic> work)? onStarted;

  /// The current board of [project], a fresh one on first read.
  Map<String, dynamic> board(String project) {
    if (unsupported) throw const PlanningUnsupported('this backend has no planning');
    if (failure case final failure?) throw failure;
    return boards.putIfAbsent(project, mockPlanningBoard);
  }

  /// Replaces [project]'s whole board and shows it on exactly that project's watches.
  void push(String project, Map<String, dynamic> board) {
    boards[project] = board;
    _changes.add((project, board));
  }

  /// Fails every watch with [error].
  void fail(Object error) => _changes.addError(error);

  /// Ends every watch without another board.
  Future<void> end() => _changes.close();

  /// Whether the watches have ended.
  bool get ended => _changes.isClosed;

  @override
  Future<StoryWorkOrder> startStoryWork(StoryWorkRequest request) async {
    final proposal = await proposeStoryWork(request.project, request.story, request.to, issues: request.issues);
    return StoryWorkOrder.from(work.call('StartStoryWork', request.toMap()), request: request, proposal: proposal);
  }

  /// The projects asked to fetch their repositories, in order.
  final List<String> refreshes = [];

  @override
  Future<String> refreshProject(String project) async {
    final current = board(project);
    refreshes.add(project);
    return current['revision'] as String? ?? '';
  }

  /// The ideas offered a discussion, each with its project, in order.
  final List<(String, String)> ideas = [];

  @override
  Future<PlanningTaskOrder> discussIdea(String project, String idea) async {
    board(project);
    ideas.add((project, idea));
    return PlanningTaskOrder.from({
      'offer': work.nextOffer(),
      'issues': <String>[],
      'role': 'DISCUSSION',
      'agent': 'codex',
      'model': 'mock-model',
      'mode': 'AGENT',
      'prompt': 'Discuss this idea with the person, change nothing, and once they agree write idea.json.\n\n$idea',
      'result': 'idea.json',
    });
  }

  /// The ideas handed in, each with its project and offer, in order.
  final List<(String, String, Map<String, Object?>)> handedIdeas = [];

  final Map<(String, String), String> _handed = {};

  /// Answers one story per offer, numbered after the board's stories; the board itself is left as it is.
  @override
  Future<String> handIdea(String project, String offer, Map<String, Object?> idea) async {
    final current = board(project);
    handedIdeas.add((project, offer, idea));
    if (moveFailure case final failure?) throw failure;
    return _handed.putIfAbsent((project, offer), () {
      var highest = 0;
      for (final story in (current['stories'] as List).cast<Map<String, dynamic>>()) {
        final number = int.tryParse((story['id'] as String).substring(2)) ?? 0;
        if (number > highest) highest = number;
      }
      return 'ST${highest + 1 + _handed.length}';
    });
  }

  /// Simulates the window's confirmed starts separately from preparing a planner order.
  Future<PlanningTaskStarted> simulateStart(String project, String story, PlanningTaskOrder task) async {
    // A discussion belongs to no story, so no run on the board records it.
    if (task.role == 'DISCUSSION') {
      return PlanningTaskStarted(task: 'discussion-${task.offer}', container: 'mock-discussion-${task.offer}');
    }
    final result = work.startTask(project, story, task);
    onStarted?.call(project, result['work'] as Map<String, dynamic>);
    return PlanningTaskStarted(task: result['task'] as String, container: result['container'] as String);
  }

  @override
  Future<PlanningMoveResult> saveMoves(String project, List<PlanningMove> moves, String revision) async {
    final listed = moves.map((move) => move.toMap()).toList();
    moveCalls.add({'project': project, 'moves': listed, 'revision': revision});
    if (moveGate case final gate?) await gate.future;
    if (moveHeld case final held?) await held.future;
    if (refusedStory != null && listed.any((move) => move['story'] == refusedStory)) {
      throw mockRefusal('org.fuin.porelo.Tasks1.WorkNotAllowed', {'story': refusedStory, 'detail': refusalDetail});
    }
    if (moveFailure case final failure?) throw failure;
    final moved = applyPlanningMove(board(project), {'moves': listed, 'revision': revision});
    boards[project] = moved;
    // A save answers apart from the watch, so the screen must apply its confirmation itself.
    return PlanningMoveResult.from({'revision': moved['revision'], 'moved': moved['moved']});
  }

  /// New issues asked of it, each with its project, repository, title, what must be true, size, worker and revision.
  final List<Map<String, dynamic>> issueCalls = [];

  /// Adds the issue as the planner does, numbered after its repository's issues on the board, and shows the board at a
  /// new revision; the mock board lists no issue outside a story, so the new one is not shown.
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
    issueCalls.add({
      'project': project,
      'repository': repository,
      'title': title,
      'mustBeTrue': mustBeTrue,
      'size': size,
      'worker': worker.wire,
      'revision': revision,
    });
    final current = board(project);
    if (revision != current['revision']) {
      throw mockRefusal('StaleRevision', {'revision': current['revision']});
    }
    if (moveFailure case final failure?) throw failure;
    final numbers = <String, int>{};
    for (final story in (current['stories'] as List).cast<Map<String, dynamic>>()) {
      for (final issue in (story['issues'] as List).cast<Map<String, dynamic>>()) {
        if (issue['repository'] != repository) continue;
        final match = RegExp(r'^([A-Z]{1,4})([0-9]{1,5})').firstMatch(issue['id'] as String);
        if (match == null) continue;
        final number = int.parse(match.group(2)!);
        if (number > (numbers[match.group(1)!] ?? 0)) numbers[match.group(1)!] = number;
      }
    }
    if (numbers.isEmpty) throw mockRefusal('IssueNotAllowed', {'field': '', 'reason': 'no prefix for $repository'});
    final prefix = numbers.keys.first;
    final id = '$prefix${(numbers[prefix]! + 1).toString().padLeft(2, '0')}';
    final next = '${current['revision']}:added';
    push(project, {...current, 'revision': next});
    return PlanningIssueAdded(id: id, revision: next);
  }

  /// Priorities asked of it, each with its project, story, priority and revision.
  final List<Map<String, dynamic>> priorityCalls = [];

  /// Sets the story's priority as the planner does and shows the board at a new revision; the same priority answers
  /// the revision it was asked at.
  @override
  Future<String> setPriority(String project, String story, String priority, String revision) async {
    priorityCalls.add({'project': project, 'story': story, 'priority': priority, 'revision': revision});
    final current = board(project);
    if (moveGate case final gate?) await gate.future;
    if (revision != current['revision']) {
      throw mockRefusal('StaleRevision', {'revision': current['revision']});
    }
    if (!planningPriorities.contains(priority)) throw mockRefusal('PriorityNotAllowed', {'story': story});
    if (moveFailure case final failure?) throw failure;
    final stories = (current['stories'] as List).cast<Map<String, dynamic>>();
    final found = stories.where((record) => record['id'] == story).firstOrNull;
    if (found == null) throw mockRefusal('NoSuchStory', {'story': story});
    if (found['priority'] == priority) return revision;
    final next = '${current['revision']}:priority';
    push(project, {
      ...current,
      'revision': next,
      'stories': [
        for (final record in stories)
          if (identical(record, found)) {...record, 'priority': priority} else record,
      ],
    });
    return next;
  }

  /// Worker changes asked of it, each with its project, issue, worker and revision.
  final List<Map<String, dynamic>> workerCalls = [];

  @override
  Future<PlanningWorkerSaved> setWorker(String project, String issue, IssueWorker worker, String revision) async {
    workerCalls.add({'project': project, 'issue': issue, 'worker': worker.wire, 'revision': revision});
    if (moveFailure case final failure?) throw failure;
    final current = board(project);
    final next = applyPlanningWorker(current, issue, worker.wire, revision);
    if (next['revision'] != current['revision']) push(project, next);
    return PlanningWorkerSaved(revision: next['revision'] as String);
  }

  @override
  Future<PlanStoryDetail> getStory(String project, String story) async {
    if (detailFailure case final failure?) throw failure;
    if (partFailures['Story'] case final failure?) throw failure;
    if (readStory case final read?) return read(project, story);
    final record = mockPlanningStory(board(project), story);
    if (record == null) throw mockRefusal('NoSuchStory', {'story': story});
    return PlanStoryDetail.from({'story': record, 'revision': board(project)['revision']});
  }

  @override
  Future<PlanIssueList> listStoryIssues(String project, String story) async {
    if (detailFailure case final failure?) throw failure;
    if (partFailures['Issue list'] case final failure?) throw failure;
    if (readIssues case final read?) return read(project, story);
    final record = mockPlanningStory(board(project), story);
    if (record == null) throw mockRefusal('NoSuchStory', {'story': story});
    return PlanIssueList.from({'issues': record['issues'], 'revision': board(project)['revision']});
  }

  @override
  Future<PlanIssueDetail> getIssue(String project, String issue) async {
    if (detailFailure case final failure?) throw failure;
    if (partFailures['Issue'] case final failure?) throw failure;
    final record = mockPlanningIssue(board(project), issue);
    if (record == null) throw mockRefusal('NoSuchIssue', {'issue': issue});
    return PlanIssueDetail.from({'issue': record, 'revision': board(project)['revision']});
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async {
    final current = board(project);
    return PlanningSnapshot.from({
      'states': current['states'],
      'phases': current['phases'],
      'work': current['work'] ?? <dynamic>[],
      'faults': current['faults'] ?? <dynamic>[],
    });
  }

  @override
  Future<PlanningSnapshot> stories(String project) async {
    final current = board(project);
    return PlanningSnapshot.from({'stories': current['stories'], 'revision': current['revision']});
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final replies = StreamController<PlanningSnapshot>();
    StreamSubscription<(String, Map<String, dynamic>)>? subscription;
    replies.onListen = () {
      watchers++;
      try {
        final first = board(project);
        subscription = _changes.stream.listen(
          (change) {
            if (change.$1 == project) replies.add(PlanningSnapshot.from(planningSummary(change.$2)));
          },
          onError: replies.addError,
          onDone: replies.close,
        );
        replies.add(PlanningSnapshot.from(planningSummary(first)));
      } catch (error, stack) {
        replies.addError(error, stack);
      }
    };
    replies.onCancel = () {
      watchers--;
      // A broadcast listener detaches at once; its cached future may belong to another test clock.
      unawaited(subscription?.cancel());
    };
    return replies.stream;
  }

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to, {List<String>? issues}) async =>
      StoryWorkProposal.from(
        work.call('ProposeStoryWork', {'project': project, 'story': story, 'to': to, 'issues': ?issues}),
      );

  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) async =>
      StoryWorkStatus.from(work.call('GetStoryWork', {'project': project, 'story': story}));

  @override
  Future<StoryWorkStatus> stopStoryWork(String project, String story) async =>
      StoryWorkStatus.from(work.call('StopStoryWork', {'project': project, 'story': story}));

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
  }) async {
    checkLanded(event, landed);
    return TaskEventResolution.from(
      work.call('TaskEvent', {
        'project': project,
        'task': task,
        'event': event.name.toUpperCase(),
        'offer': offer,
        'container': container,
        'machine': machine,
        if (ended != null) 'ended': ended.toMap(),
        if (event == PlanningTaskEvent.landed) 'landed': [for (final landing in landed) landing.toMap()],
      }),
    );
  }

  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) async =>
      PlanningIssueDetail.from(work.call('GetIssue', {'project': project, 'issue': issue}));

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
  }) async => ReadingResolution.from(
    work.call('ResolveIssueReading', {
      'project': project,
      'story': story,
      'issue': issue,
      'round': round,
      'choice': choice,
      'revision': revision,
      'comment': comment ?? '',
      ...?rework?.wire,
    }),
  );
}

/// Keeps mock runtime answers separate from the planner's prepared orders.
Future<PlanningTaskStarted> mockStartTask(
  PlanningSource source,
  String project,
  String story,
  PlanningTaskOrder task,
) => (source as MockPlanningSource).simulateStart(project, story, task);

/// A planning client whose providers a test supplies, recording what the window tells them.
class MockPlanningClient extends ChangeNotifier with PlanningTaskReports {
  /// Answers [providers] as they are now; [changes] says when they changed, as [notifyListeners] also does.
  MockPlanningClient(
    this._providers, {
    this.changes,
    String? Function()? currentProvider,
    String? Function()? chosenProject,
  }) : _current = currentProvider,
       _chosen = chosenProject;

  /// One connected provider named [name], serving [projects] from [source].
  factory MockPlanningClient.of(
    MockPlanningSource source, {
    String name = 'mock',
    List<String> projects = const ['demo'],
  }) {
    final provider = PlanningProvider(name: name, connected: true, projects: projects, source: source);
    return MockPlanningClient(() => [provider], currentProvider: () => name);
  }

  final List<PlanningProvider> Function() _providers;
  final String? Function()? _current;
  final String? Function()? _chosen;

  /// What else announces a change of the providers, such as the machines they stand for.
  final Listenable? changes;

  /// The task events the window told, in order.
  final List<
    (
      String project,
      String task,
      PlanningTaskEvent event,
      String offer,
      String container,
      String machine,
      PlanningTaskEnded? ended,
    )
  >
  events = [];

  @override
  void addListener(VoidCallback listener) {
    super.addListener(listener);
    changes?.addListener(listener);
  }

  @override
  void removeListener(VoidCallback listener) {
    super.removeListener(listener);
    changes?.removeListener(listener);
  }

  /// Tells the listeners that the providers, or the window's choice among them, changed.
  void changed() => notifyListeners();

  @override
  List<PlanningProvider> get providers => _providers();

  @override
  String? get currentProvider => _current?.call();

  @override
  String? get chosenProject => _chosen?.call();

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
