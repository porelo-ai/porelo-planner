import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning_app/src/app.dart';

Future<void> settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
  await tester.pump(const Duration(milliseconds: 350));
}

Future<WindowStandIn> showApp(WidgetTester tester) async {
  tester.view
    ..physicalSize = const Size(1800, 1200)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final window = WindowStandIn(clock: () => DateTime.utc(2026));
  await tester.pumpWidget(PlanningTestApp(window: window));
  await settle(tester);
  addTearDown(() => window.fake.end());
  return window;
}

Future<void> openLog(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('app-tab-log')));
  await settle(tester);
}

void main() {
  // Harm: a refused Start the app does not show leaves the screen's refusal path untried, or tried without its
  // arguments seen.
  testWidgets('a Start the window refuses is logged with its arguments and the board shows its words', (tester) async {
    final window = await showApp(tester);
    window
      ..startAnswer = StartAnswer.refused
      ..startRefusal = 'No machine can take it.';
    await tester.tap(find.byKey(const Key('planning-board-menu')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-discuss-idea')), warnIfMissed: false);
    await settle(tester);
    await tester.enterText(find.byKey(const Key('planning-idea-words')), 'A board that remembers.');
    await tester.pump();
    await tester.tap(find.byKey(const Key('planning-idea-discuss')));
    await settle(tester);

    final starts = window.log.calls.where((HookCall call) => call.hook == 'startTask').toList();
    expect(starts, hasLength(1), reason: 'the screen called the Start hook once');
    expect(
      [starts.single.arguments['project'], starts.single.arguments['story'], starts.single.arguments['kind']],
      ['demo', '', 'discussion'],
      reason: 'the log names the Start\'s project, story and order',
    );
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-action-error'))).data,
      contains('No machine can take it.'),
      reason: 'the board shows the window\'s words',
    );
    await openLog(tester);
    expect(find.textContaining('"kind":"discussion"'), findsOneWidget, reason: 'the log shows the call');
  });

  // Harm: an end fired by hand that does not reach the board as the window's would leaves the screen's handling of
  // a failed task untried.
  testWidgets('an unfinished end fired for a started task raises it, and Needs you is logged', (tester) async {
    final window = await showApp(tester);
    await tester.tap(find.byKey(const Key('planning-menu ST15')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-start ST15')), warnIfMissed: false);
    await settle(tester);
    for (var step = 0; step < 5 && find.byKey(const Key('story-work-start')).evaluate().isEmpty; step++) {
      await tester.tap(find.byKey(const Key('story-work-next')));
      await settle(tester);
    }
    await tester.tap(find.byKey(const Key('story-work-start')));
    await settle(tester);
    expect(window.started, isNotEmpty, reason: 'the window reported a task started');
    expect(
      window.log.calls.map((HookCall call) => call.hook),
      containsAllInOrder(['agents.on', 'startTask', 'taskChanges.started']),
      reason: 'the start went through the window\'s hooks, each logged',
    );
    // Harm: the stand-in hands its Start a role, which Porelo's Start is never handed, so a screen that relies on it
    // passes here and fails at the window's.
    final starts = window.log.calls.where((HookCall call) => call.hook == 'startTask').toList();
    expect(starts.map((call) => call.arguments['kind']), everyElement('work'), reason: 'work was started');
    expect(
      starts.where((call) => call.arguments.containsKey('role')),
      isEmpty,
      reason: 'Start is handed the members of a role, never the role',
    );

    await tester.tap(find.byKey(const Key('app-tab-events')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('app-event-started')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('app-ended-finished')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('app-event-ended')));
    await settle(tester);
    expect(
      window.log.calls.where((HookCall call) => call.hook == 'window.taskEvent refused'),
      isEmpty,
      reason: 'the board took both events',
    );

    await tester.tap(find.byKey(const Key('planning-warning ST15')));
    await settle(tester);
    expect(
      window.log.calls.where((HookCall call) => call.hook == 'host.openNeedsYou'),
      hasLength(1),
      reason: 'the raised card leads to Needs you, as the window is asked',
    );
  });
}
