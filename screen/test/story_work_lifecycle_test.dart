import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

class _Delayed extends MockPlanningSource {
  _Delayed();
  final proposal = Completer<StoryWorkProposal>();
  final started = Completer<PlanningTaskStarted>();
  var findings = Completer<PlanningIssueDetail>();
  bool delayProposal = true;
  int storyStarts = 0;
  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to) =>
      delayProposal ? proposal.future : super.proposeStoryWork(project, story, to);
  @override
  Future<PlanningTaskStarted> simulateStart(String project, String story, PlanningTaskOrder task) {
    storyStarts++;
    return started.future;
  }

  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) => findings.future;
}

void main() {
  // Harm: a closing dialog is notified or receives stale proposal state after disposal.
  test('a late proposal does not notify a disposed form', () async {
    final backend = _Delayed();
    final board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(board, board.stories.first, 'In analysis');
    var notifications = 0;
    work.addListener(() => notifications++);
    final loading = work.load();
    final before = notifications;
    work.dispose();
    backend.proposal.complete(
      StoryWorkProposal.from({
        'modes': ['UNATTENDED'],
        'readingRounds': 3,
      }),
    );
    await loading;
    expect(notifications, before);
    expect(work.proposal, isNull);
    board.dispose();
    await backend.end();
  });

  // Harm: repeated presses create duplicate work and a late start notifies a disposed dialog.
  test('only one pending start is sent and late completion is quiet', () async {
    final backend = _Delayed()..delayProposal = false;
    final board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
    final work = planningStoryWork(board, board.stories.firstWhere((story) => story.id == 'ST291'), 'Implementing');
    await work.load();
    var notifications = 0;
    work.addListener(() => notifications++);
    final starting = work.start();
    await pumpEventQueue();
    expect(await work.start(), isFalse);
    expect(backend.storyStarts, 1);
    final before = notifications;
    work.dispose();
    backend.started.complete(const PlanningTaskStarted(task: 'late-task', container: 'late-container'));
    expect(await starting, isFalse);
    expect(notifications, before);
    board.dispose();
    await backend.end();
  });

  // Harm: missing findings authorize approval, or their late arrival updates a disposed row.
  test('incomplete findings disable approval and a late read is quiet', () async {
    final backend = _Delayed();
    final snapshot = mockPlanningBoard();
    final storyMap = (snapshot['stories'] as List).cast<Map<String, dynamic>>().firstWhere(
      (story) => story['id'] == 'ST291',
    );
    ((storyMap['issues'] as List).first as Map<String, dynamic>).addAll({'readingStopped': true, 'rounds': 3});
    backend.boards['checkout'] = snapshot;
    final board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
    final story = board.stories.firstWhere((story) => story.id == 'ST291');
    final reading = ReadingDecision(PlanningAttention(board, story, issue: story.issues.first));
    final loading = reading.load();
    backend.findings.complete(PlanningIssueDetail.from({}));
    await loading;
    expect(reading.canResolve, isFalse);
    expect(reading.problem, 'The machine did not report the last findings.');
    reading.dispose();
    backend.findings = Completer<PlanningIssueDetail>();
    final late = ReadingDecision(PlanningAttention(board, story, issue: story.issues.first));
    var notifications = 0;
    late.addListener(() => notifications++);
    final pending = late.load();
    final before = notifications;
    late.dispose();
    backend.findings.complete(
      PlanningIssueDetail.from({
        'issue': {'findings': <Map<String, dynamic>>[]},
      }),
    );
    await pending;
    expect(late.detail, isNull);
    expect(notifications, before);
    board.dispose();
    await backend.end();
  });

  // Harm: a delayed Stop confirmation stops a newer run on the same story.
  test('stop rejects a revision that changed during confirmation', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'checkout');
    await Future<void>.delayed(Duration.zero);
    final revision = board.snapshot!.revision;
    final replacement = mockPlanningBoard()..['revision'] = 'replacement-run';
    final story = (replacement['stories'] as List).cast<Map<String, dynamic>>().firstWhere(
      (story) => story['id'] == 'ST291',
    );
    story['work'] = {
      'atWork': true,
      'tasks': [
        {'task': 'new-run', 'role': 'MAIN', 'atWork': true},
      ],
    };
    backend.push('checkout', replacement);
    await Future<void>.delayed(Duration.zero);
    await board.stopWork('ST291', revision: revision, source: backend, sourceProject: 'checkout');
    expect(backend.work.calls.where((call) => call.$1 == 'StopStoryWork'), isEmpty);
    expect(board.stories.firstWhere((story) => story.id == 'ST291').work!.tasks.map((task) => task.task), ['new-run']);
    board.dispose();
    await backend.end();
  });
}
