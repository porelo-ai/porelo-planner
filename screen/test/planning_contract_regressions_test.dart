import 'dart:io';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Future<void> _turn() => Future<void>.delayed(Duration.zero);

class _HeldListing extends MockPlanningSource {
  _HeldListing();
  Completer<PlanningSnapshot>? listing;
  @override
  Future<PlanningSnapshot> stories(String project) => listing?.future ?? super.stories(project);
}

void main() {
  // Harm: a stale refusal keeps an applicable card in the old column and hides unsaved intent.
  test('stale reread clears a retained move refusal and preserves its destination', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    board.move('ST291', 'Review');
    backend.moveFailure = mockRefusal('StateNotAllowed', {'item': 'ST291', 'reason': 'Old refusal'});
    await board.save();
    backend.moveFailure = null;
    backend.boards['demo']!['revision'] = 'new';
    await board.save();
    expect(board.pending['ST291']!.problem, isNull);
    expect(board.displayedState(board.stories.firstWhere((story) => story.id == 'ST291')), 'Review');
    expect(board.cards('Review').any((story) => story.id == 'ST291'), isTrue);
  });

  // Harm: a transport or unsupported-action failure leaves a refused card looking moved.
  test('all save failures return unconfirmed cards to their reported state', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    for (final error in <Object>[
      const PlanningUnsupported('this backend has no SaveMoves'),
      const SocketException('Connection lost'),
    ]) {
      board.move('ST291', 'Review');
      backend.moveFailure = error;
      await board.save();
      final story = board.stories.firstWhere((story) => story.id == 'ST291');
      expect(board.displayedState(story), story.state);
      expect(board.pending['ST291']!.problem, isNotNull);
    }
  });

  // Harm: a watch masks missing application of the save acknowledgement.
  test('save applies confirmed issue identities before a later listing or watch', () async {
    final backend = _HeldListing();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    backend.listing = Completer<PlanningSnapshot>();
    board.moveIssue('B540', 'Review');
    final saving = board.save();
    await _turn();
    expect(board.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == 'B540').state, 'Review');
    expect(board.pending, isEmpty);
    backend.listing!.complete(PlanningSnapshot.from(backend.boards['demo']!));
    await saving;
  });

  // Harm: detail navigation loses a staged move, or a working issue cannot be unstaged individually.
  test('issue drafts outlive detail screens and undo a working source individually', () async {
    final backend = MockPlanningSource();
    final board = PlanningBoard(backend, 'demo');
    addTearDown(() async {
      board.dispose();
      await backend.end();
    });
    await _turn();
    final detail = PlanningDetail(board, 'ST291', null);
    await _turn();
    detail.queueMove('B540', 'Review');
    detail.queueMove('B541', 'Review');
    detail.dispose();
    final issue = PlanningDetail(board, 'ST291', 'B540');
    await _turn();
    expect(board.pendingIssues.keys, ['B540', 'B541']);
    issue.dispose();
    final reopened = PlanningDetail(board, 'ST291', null);
    await _turn();
    reopened.queueMove('B540', 'Implementing');
    expect(board.pendingIssues.keys, ['B541']);
    reopened.dispose();
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
