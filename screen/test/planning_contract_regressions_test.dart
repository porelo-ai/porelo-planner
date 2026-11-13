import 'dart:io';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Future<void> _turn() => Future<void>.delayed(Duration.zero);

/// The state the board shows an issue in, its move in flight included.
String? _shownState(PlanningBoard board, String id) =>
    board.pendingIssues[id]?.to ??
    board.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == id).state;

class _HeldListing extends MockPlanningSource {
  _HeldListing();
  Completer<PlanningSnapshot>? listing;
  @override
  Future<PlanningSnapshot> stories(String project) => listing?.future ?? super.stories(project);
}

void main() {
  // Harm: a refused, unsupported or lost save leaves the card looking moved, and the person believes it was.
  test('every failed save returns the card to its reported state and says why', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    for (final error in <Object>[
      mockRefusal('StateNotAllowed', {'item': 'B542', 'reason': 'Not now'}),
      const PlanningUnsupported('this backend has no SaveMoves'),
      const SocketException('Connection lost'),
    ]) {
      backend.moveFailure = error;
      await board.moveIssue('B542', 'Review');
      expect(board.question, isNull, reason: 'the move asks nothing, so it was sent at once');
      expect(_shownState(board, 'B542'), 'Backlog', reason: '$error');
      expect(board.pending, isEmpty, reason: '$error');
      expect(board.moveProblem, contains('B542 was not moved'), reason: '$error');
    }
    expect(backend.moveCalls, hasLength(3), reason: 'each move was sent once, and none again');
  });

  // Harm: a watch masks missing application of the save acknowledgement.
  test('a save applies confirmed issue identities before a later listing or watch', () async {
    final backend = _HeldListing();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    backend.listing = Completer<PlanningSnapshot>();
    final saving = board.moveIssue('B540', 'Review');
    await _turn();
    await _turn();
    expect(backend.moveCalls, hasLength(1));
    expect(board.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == 'B540').state, 'Review');
    expect(board.pending, isEmpty);
    backend.listing!.complete(PlanningSnapshot.from(backend.boards['demo']!));
    await saving;
  });

  // Harm: a failed parent read prevents a successful issue refresh from reaching the screen.
  test('successful issue changes survive a failed parent refresh', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    final detail = PlanningDetail(board, 'ST291', 'B541');
    addTearDown(detail.dispose);
    await _turn();
    final stories = (backend.boards['demo']!['stories'] as List).cast<Map<String, dynamic>>();
    final story = stories.firstWhere((story) => story['id'] == 'ST291');
    ((story['issues'] as List)[1] as Map)['title'] = 'Updated issue';
    backend.partFailures['Story'] = mockRefusal('Unreachable', {'detail': 'Parent unavailable', 'retryAfter': 4});
    await detail.reload();
    expect(detail.issue!.issue!.title, 'Updated issue');
    expect(detail.storyProblem, contains('try again in 4 s'));
  });

  // Harm: switching to another workflow crashes a dropdown or displays a rejected machine.
  testWidgets('machine selection renders the newly selected working state', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final first = MockPlanningSource(), second = MockPlanningSource();
    // The second machine's workflow is its own from the start; WatchStories carries no workflow.
    final data = second.boards['demo'] = mockPlanningBoard();
    (data['states'] as List).add({'name': 'Investigating', 'kind': 'WORKING'});
    (data['states'] as List).removeWhere(
      (state) => (state as Map)['kind'] == 'WORKING' && state['name'] != 'Investigating',
    );
    final a = PlanningBoard(first, 'demo'), b = PlanningBoard(second, 'demo');
    await tester.runAsync(_turn);
    final work = planningStoryWork(
      a,
      a.stories.firstWhere((story) => story.id == 'ST291'),
      'In analysis',
      machines: {a: 'first', b: 'second'},
    );
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: StoryWorkDialog(work))));
    await settle(tester);
    work.changeMachine(b);
    await settle(tester);
    expect(tester.widget<DropdownButton<String>>(find.byKey(const Key('story-work-target'))).value, 'Investigating');
    expect(tester.widget<DropdownButton<PlanningBoard>>(find.byKey(const Key('story-work-machine'))).value, same(b));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    work.dispose();
    a.dispose();
    b.dispose();
    await tester.runAsync(() async {
      await first.end();
      await second.end();
    });
  });
}
