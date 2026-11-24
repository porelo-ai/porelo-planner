import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/src/ui/actions.dart';
import 'package:porelo_planning/src/ui/planning_menu.dart';

import 'support/story_work_support.dart';

/// Shows a menu of [count] entries, each telling [chosen] its number and the revision when the menu opened, and gives
/// back what builds the screen anew at another revision.
Future<void Function(String)> _show(WidgetTester tester, List<(int, String)> chosen, {int count = 3}) async {
  var revision = 'r1';
  late StateSetter rebuild;
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) {
            rebuild = setState;
            return Column(
              children: [
                Text('Revision $revision'),
                PlanningMenu(
                  entries: (_) {
                    final shown = revision;
                    return [
                      for (var at = 0; at < count; at++)
                        MenuItemButton(
                          key: Key('entry $at'),
                          onPressed: () => chosen.add((at, shown)),
                          child: Text('Entry $at'),
                        ),
                    ];
                  },
                  builder: (_, toggle) =>
                      TextButton(key: const Key('open'), onPressed: toggle, child: const Text('Open')),
                ),
              ],
            );
          },
        ),
      ),
    ),
  );
  return (String next) => rebuild(() => revision = next);
}

void main() {
  // Harm: a board that changed while the menu stood open moves what the person never saw.
  testWidgets('an entry runs with what stood when its menu opened, though the screen was built anew under it', (
    tester,
  ) async {
    final chosen = <(int, String)>[];
    final revise = await _show(tester, chosen);

    await tester.tap(find.byKey(const Key('open')));
    await settle(tester);
    revise('r2');
    await settle(tester);
    expect(find.text('Revision r2'), findsOneWidget, reason: 'the screen was built anew while the menu stood open');

    await tester.tap(find.byKey(const Key('entry 1')));
    await settle(tester);

    expect(chosen, [(1, 'r1')], reason: 'the entry carries the revision shown when the menu opened');
    expect(find.byKey(const Key('entry 1')), findsNothing, reason: 'choosing closes the menu');
  });

  // Harm: a menu longer than the window runs past its edge, so its last entries cannot be reached.
  testWidgets('a menu longer than the window scrolls inside it to its last entry', (tester) async {
    tester.view
      ..physicalSize = const Size(800, 400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final chosen = <(int, String)>[];
    await _show(tester, chosen, count: 40);

    await tester.tap(find.byKey(const Key('open')));
    await settle(tester);
    expect(
      tester.getRect(find.byType(Scrollable).last).bottom,
      lessThanOrEqualTo(400),
      reason: 'the menu ends inside the window',
    );

    await tester.scrollUntilVisible(find.byKey(const Key('entry 39')), 100, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.byKey(const Key('entry 39')));
    await settle(tester);

    expect(chosen, [(39, 'r1')], reason: 'the last entry is reached and runs');
  });

  // Harm: an action the board has meanwhile made unavailable, such as a second discuss while one runs, is run from a
  // menu that opened before.
  testWidgets('an action menu runs an action only as its owner holds it when the entry is chosen', (tester) async {
    var runs = 0;
    var busy = false;
    late StateSetter rebuild;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              rebuild = setState;
              return PlanningActionMenu(
                tooltip: 'Board actions',
                actions: [
                  PlanningAction(
                    id: 'discuss',
                    label: 'Discuss an idea…',
                    run: () => runs++,
                    unavailable: busy ? 'A discussion is under way.' : null,
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
    await tester.tap(find.byTooltip('Board actions'));
    await settle(tester);
    rebuild(() => busy = true);
    await settle(tester);

    await tester.tap(find.byKey(const ValueKey<String>('discuss')), warnIfMissed: false);
    await settle(tester);

    expect(runs, 0, reason: 'the action unavailable when it was chosen does not run');
  });
}
