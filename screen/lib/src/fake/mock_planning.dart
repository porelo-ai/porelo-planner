import 'dart:async';

import 'package:flutter/foundation.dart';

import '../client.dart';
import '../hooks.dart';
import '../models.dart';
import '../story_work_order.dart';
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
    final proposal = await proposeStoryWork(request.project, request.story, request.to);
    return StoryWorkOrder.from(work.call('StartStoryWork', request.toMap()), request: request, proposal: proposal);
  }

  /// Simulates the window's confirmed starts separately from preparing a planner order.
  Future<PlanningTaskStarted> simulateStart(String project, String story, PlanningTaskOrder task) async {
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
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to) async =>
      StoryWorkProposal.from(work.call('ProposeStoryWork', {'project': project, 'story': story, 'to': to}));

  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) async =>
      StoryWorkStatus.from(work.call('GetStoryWork', {'project': project, 'story': story}));

  @override
  Future<StoryWorkStatus> stopStoryWork(String project, String story) async =>
      StoryWorkStatus.from(work.call('StopStoryWork', {'project': project, 'story': story}));

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
  }) async => ReadingResolution.from(
    work.call('ResolveIssueReading', {
      'project': project,
      'story': story,
      'issue': issue,
      'round': round,
      'choice': choice,
      'revision': revision,
      'comment': comment ?? '',
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
class MockPlanningClient extends ChangeNotifier implements PlanningClient {
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
  final List<(String project, String task, PlanningTaskEvent event)> events = [];

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
  Future<void> taskEvent(String project, String task, PlanningTaskEvent event) async =>
      events.add((project, task, event));
}
