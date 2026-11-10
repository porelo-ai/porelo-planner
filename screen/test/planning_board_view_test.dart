import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Future<(MockPlanningSource, PlanningBoards)> _showBoard(
  WidgetTester tester, {
  bool twoProjects = false,
  MockPlanningSource? otherProvider,
}) async {
  tester.view
    ..physicalSize = const Size(1280, 800)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = MockPlanningSource();
  backend.boards['checkout'] = {
    'states': [
      {'name': 'Ready', 'kind': 'WAITING'},
      {'name': 'Building', 'kind': 'WORKING'},
      {'name': 'Done', 'kind': 'CLOSED'},
      {'name': 'Review', 'kind': 'WAITING'},
    ],
    'phases': [
      {
        'name': 'Work',
        'states': ['Ready', 'Building', 'Done', 'Review'],
      },
    ],
    'stories': [
      {'id': 'StoryOne', 'title': 'A story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
    ],
    'revision': 'initial',
  };
  if (twoProjects) {
    backend.boards['other'] = {
      ...backend.boards['checkout']!,
      'stories': [
        {'id': 'StoryOne', 'title': 'Second project story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
      ],
    };
  }
  final client = _Providers([
    PlanningProvider(name: 'local', connected: true, projects: ['checkout', if (twoProjects) 'other'], source: backend),
  ]);
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(body: planningView(boards: boards)),
    ),
  );
  await settle(tester);
  return (backend, boards);
}

/// Providers a test adds and chooses among, as the window's machines are.
class _Providers extends MockPlanningClient {
  _Providers(List<PlanningProvider> listed) : this._(listed, _Choice());
  _Providers._(this.listed, this.choice) : super(() => listed, currentProvider: () => choice.name);
  final List<PlanningProvider> listed;
  final _Choice choice;

  /// Chooses the provider named [name].
  void choose(String name) {
    choice.name = name;
    changed();
  }
}

class _Choice {
  String name = 'local';
}

void main() {
  // Harm: a WORKING drop starts without the shell's request or silently does nothing.
  testWidgets('an unfolded working drop opens the shipped start dialog', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final source = find.byKey(const Key('planning-card StoryOne'));
    final target = find.byKey(const Key('planning-state Building'));
    final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
    await drag.moveBy(const Offset(0, -20));
    await tester.pump();
    await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text('Building'))));
    await tester.pump();
    await drag.up();
    await settle(tester);
    expect(find.byKey(const Key('story-work-dialog')), findsOneWidget);
    await tester.tap(find.byKey(const Key('story-work-cancel')));
    await settle(tester);
    expect(find.descendant(of: find.byKey(const Key('planning-state Ready')), matching: source), findsOneWidget);
    expect(find.byKey(const Key('planning-pending StoryOne')), findsNothing);
    expect(find.byKey(const Key('planning-move-error')), findsNothing);
    expect(backend.moveCalls, isEmpty);
    expect(boards.selected!.pending, isEmpty);
  });

  // Harm: dropping a card back on its own state requests another start or offers a misleading drop.
  testWidgets('a card over its current waiting or working state has no hint or start request', (tester) async {
    final (backend, _) = await _showBoard(tester);
    for (final state in ['Ready', 'Building']) {
      final data = backend.boards['checkout']!;
      (data['stories'] as List<dynamic>).first['state'] = state;
      backend.push('checkout', data);
      await settle(tester);
      final source = find.byKey(const Key('planning-card StoryOne'));
      final target = find.byKey(Key('planning-state $state'));
      final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
      await drag.moveBy(const Offset(0, -20));
      await tester.pump();
      await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text(state))));
      await tester.pump();
      expect(find.text('Drop here for $state'), findsNothing);
      await drag.up();
      await settle(tester);
      expect(find.byKey(const Key('story-work-dialog')), findsNothing);
      expect(find.descendant(of: target, matching: source), findsOneWidget);
      expect(find.byKey(const Key('planning-pending StoryOne')), findsNothing);
    }
  });

  // Harm: a canceled project switch loses pending work or leaves the selector displaying another project.
  testWidgets('two projects can be switched only after confirming and cancel restores the selector', (tester) async {
    final (_, boards) = await _showBoard(tester, twoProjects: true);
    boards.selected!.move('StoryOne', 'Done');
    await settle(tester);
    final selector = find.byKey(const Key('planning-project'));
    Future<void> select(String name) async {
      await tester.tap(selector);
      await settle(tester);
      await tester.tap(find.text(name).last);
      await settle(tester);
    }

    await select('other');
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-stay')));
    await settle(tester);
    expect(find.descendant(of: selector, matching: find.text('checkout')), findsOneWidget);
    expect(find.descendant(of: selector, matching: find.text('other')), findsNothing);
    expect(find.byKey(const Key('planning-pending StoryOne')), findsOneWidget);
    await select('other');
    await tester.tap(find.byKey(const Key('planning-leave')));
    await settle(tester);
    expect(find.text('Second project story'), findsOneWidget);
    expect(find.descendant(of: selector, matching: find.text('other')), findsOneWidget);
    await select('checkout');
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.byKey(const Key('planning-pending StoryOne')), findsOneWidget);
    expect(find.descendant(of: selector, matching: find.text('checkout')), findsOneWidget);
  });

  // Harm: switching machines hides unsaved work or chooses another pending provider over the preferred one.
  testWidgets('pending providers stay visible and the current provider wins when both have pending moves', (
    tester,
  ) async {
    final other = MockPlanningSource();
    addTearDown(other.end);
    final (backend, boards) = await _showBoard(tester, otherProvider: other);
    other.boards['checkout'] = {
      ...backend.boards['checkout']!,
      'stories': [
        {'id': 'StoryOne', 'title': 'Other provider story', 'state': 'Ready', 'issues': <Map<String, dynamic>>[]},
      ],
    };
    final client = boards.client as _Providers;
    final localBoard = boards.selected!;
    client.listed.add(PlanningProvider(name: 'other-provider', connected: true, projects: ['checkout'], source: other));
    client.choose('other-provider');
    await settle(tester);
    final otherBoard = boards.selected!;
    expect(find.text('Other provider story'), findsOneWidget);
    localBoard.move('StoryOne', 'Review');
    await settle(tester);
    expect(find.text('A story'), findsOneWidget);
    expect(find.text('Other provider story'), findsNothing);
    expect(find.byKey(const Key('planning-pending StoryOne')), findsOneWidget);
    otherBoard.move('StoryOne', 'Review');
    await settle(tester);
    expect(find.text('Other provider story'), findsOneWidget);
    expect(find.text('A story'), findsNothing);
    expect(find.byKey(const Key('planning-pending StoryOne')), findsOneWidget);
    client.choose('local');
    await settle(tester);
    expect(find.text('A story'), findsOneWidget);
    expect(find.text('Other provider story'), findsNothing);
  });

  // Harm: a leave question opened during a save stays blocked after the machine answers.
  testWidgets('a leave question updates when its in-flight save finishes', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final board = boards.selected!;
    backend.moveGate = Completer<void>();
    board.move('StoryOne', 'Done');
    final saved = board.save();
    await tester.pump();
    final leaving = confirmPlanningLeave(tester.element(find.byType(PlanningScreen)), [board]);
    await settle(tester);
    expect(find.text('Planning is saving'), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsNothing);
    backend.moveGate!.complete();
    await saved;
    await settle(tester);
    expect(find.text('Planning saved'), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-leave')));
    await settle(tester);
    expect(await leaving, isTrue);
    expect(find.byType(AlertDialog), findsNothing);
  });

  // Harm: simultaneous leave requests stack duplicate questions and resume two actions.
  testWidgets('a second leave request keeps exactly one question open', (tester) async {
    final (_, boards) = await _showBoard(tester);
    final board = boards.selected!;
    board.move('StoryOne', 'Done');
    final context = tester.element(find.byType(PlanningScreen));
    final first = confirmPlanningLeave(context, [board]);
    await settle(tester);
    final second = await confirmPlanningLeave(context, [board], quitting: true);
    expect(second, isFalse);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.byKey(const Key('planning-stay')), findsOneWidget);
    expect(find.byKey(const Key('planning-leave')), findsOneWidget);
    await tester.tap(find.byKey(const Key('planning-stay')));
    await settle(tester);
    expect(await first, isFalse);
  });

  // Harm: an unplaced story's pending summary conceals its unreported source.
  testWidgets('an unreported source is visible in the pending summary', (tester) async {
    final (backend, boards) = await _showBoard(tester);
    final data = backend.boards['checkout']!;
    (data['stories'] as List<dynamic>).first['state'] = '';
    backend.push('checkout', data);
    await settle(tester);
    boards.selected!.move('StoryOne', 'Ready');
    await settle(tester);
    final pending = find.byKey(const Key('planning-pending StoryOne'));
    expect(find.descendant(of: pending, matching: find.text('StoryOne not reported → Ready')), findsOneWidget);
  });
}
