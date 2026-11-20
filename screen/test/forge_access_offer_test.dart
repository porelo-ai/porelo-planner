import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// A source whose fetches the test answers one by one and whose every watch it holds.
class _Source extends Fake implements PlanningSource {
  final fetches = <Completer<String>>[];
  final watches = <StreamController<PlanningSnapshot>>[];

  @override
  Future<String> refreshProject(String project) {
    final fetch = Completer<String>();
    fetches.add(fetch);
    return fetch.future;
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>(sync: true);
    watches.add(watch);
    return watch.stream;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async => _board();

  @override
  Future<PlanningSnapshot> stories(String project) async => _board();
}

/// A window that hands a token on when the test answers.
class _Window extends Fake implements PlanningHost, PlanningForgeAccess {
  final calls = <(String, String)>[];
  final answers = <Completer<bool>>[];

  @override
  Future<bool> handOnForgeToken({required String provider, required String address}) {
    calls.add((provider, address));
    final answer = Completer<bool>();
    answers.add(answer);
    return answer.future;
  }
}

/// A window that cannot hand a token on.
class _PlainWindow extends Fake implements PlanningHost {}

PlanningSnapshot _board() => PlanningSnapshot.from({
  'revision': 'r1',
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready'],
    },
  ],
  'faults': <String>[],
  'stories': [
    {'id': 'S1', 'state': 'Ready', 'title': 'A story', 'priority': 'High'},
  ],
});

const _missing = PlanningRefusal('NoForgeAccess', {
  'address': 'https://github.com',
  'right': 'Projects',
}, 'The person\'s access to https://github.com lacks the right Projects');

const _offer = Key('forge-access-offer');

/// A board whose watch the planner refused with [refusal].
(_Source, PlanningBoard) _refused(PlanningRefusal refusal) {
  final source = _Source();
  final board = PlanningBoard(source, 'demo');
  addTearDown(board.dispose);
  source.watches.single.addError(refusal);
  return (source, board);
}

Future<void> _show(WidgetTester tester, PlanningBoard board, PlanningHost host) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(
      body: ForgeAccessOffer(board: board, host: host, provider: 'workstation'),
    ),
  ),
);

void main() {
  // Harm: the person told the planner lacks their token, with no way to hand it on from the board.
  testWidgets('a missing forge access is offered, naming the machine, the forge and the right', (tester) async {
    final (_, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);

    expect(board.refusal?.name, 'NoForgeAccess', reason: 'the board keeps the refusal whole');
    expect(find.textContaining('workstation has no access to https://github.com with the right Projects'), findsOne);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    expect(window.calls, [('workstation', 'https://github.com')], reason: 'the window is asked for that forge');
  });

  // Harm: a token offered for a refusal that no token mends, or handed on to an address an answer slipped in.
  testWidgets('another refusal, or an address that is no plain https origin, is offered nothing', (tester) async {
    for (final refusal in [
      const PlanningRefusal('Unreachable', {'repository': 'store'}, 'The repository could not be reached.'),
      const PlanningRefusal('NoForgeAccess', {'address': 'https://person@github.com'}),
      const PlanningRefusal('NoForgeAccess', {'address': 'https://github.com/elsewhere'}),
      const PlanningRefusal('NoForgeAccess', {'address': 'http://github.com'}),
      const PlanningRefusal('NoForgeAccess', {'address': 'https://github.com:8443'}),
    ]) {
      final (_, board) = _refused(refusal);
      await _show(tester, board, _Window());
      expect(find.byKey(_offer), findsNothing, reason: 'no offer for ${refusal.name} ${refusal.parameters}');
    }
  });

  // Harm: an offer the window cannot keep, which does nothing when pressed.
  testWidgets('a window that cannot hand a token on is offered nothing', (tester) async {
    final (_, board) = _refused(_missing);
    await _show(tester, board, _PlainWindow());
    expect(find.byKey(_offer), findsNothing);
  });

  // Harm: the board stays refused after the token is in the vault, so the person must find Reload planning.
  testWidgets('a token handed on reads the board again', (tester) async {
    final (source, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    window.answers.single.complete(true);
    await tester.pump();
    expect(source.fetches, hasLength(1), reason: 'the planner fetches the project anew');
    source.fetches.single.complete('r2');
    await tester.pump();
    expect(board.refusal, isNotNull, reason: 'kept until a board read shows the access back');
    source.watches.last.add(_board());
    await tester.pump();
    expect(board.refusal, isNull, reason: 'the board read anew clears the refusal');
    expect(find.byKey(_offer), findsNothing, reason: 'the offer goes with it');
  });

  // Harm: the board stays refused after the token is in the vault, because its watch failed again meanwhile.
  testWidgets('a token handed on while the watch failed meanwhile reads the board again', (tester) async {
    final source = _Source();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    source.watches.single.add(_board());
    board.reportActionProblem(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    source.watches.single.addError(_missing);
    window.answers.single.complete(true);
    await tester.pump();
    expect(source.fetches, hasLength(1), reason: 'the same project is fetched anew');
  });

  // Harm: an offer held disabled, or a failure told, beside another project's refusal by a call made for the first.
  testWidgets('a call for one project neither holds nor speaks beside the next project\'s offer', (tester) async {
    final (_, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    final other = _Source();
    await board.changeProject(other, 'other');
    other.watches.single.addError(_missing);
    await tester.pump();
    expect(
      tester.widget<ButtonStyleButton>(find.byKey(_offer)).onPressed,
      isNotNull,
      reason: 'the other project is offered while the first call runs',
    );
    window.answers.single.completeError(StateError('the vault is shut'));
    await tester.pump();
    expect(find.byKey(const Key('forge-access-failed')), findsNothing, reason: 'no failure of the first project told');
  });

  // Harm: the action's refusal words left above a board read anew, saying a refusal the token already mended.
  testWidgets('a token handed on after an action was refused clears the action\'s words', (tester) async {
    final source = _Source();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    source.watches.single.add(_board());
    await tester.pump();
    board.reportActionProblem(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    window.answers.single.complete(true);
    await tester.pump();
    source.fetches.single.complete('r2');
    await tester.pump();
    expect(board.refusal, isNull, reason: 'the board read anew clears the refusal');
    expect(board.actionProblem, isNull, reason: 'the action\'s words go with it');
  });

  // Harm: a board read again for nothing, hiding why the person's token is still missing.
  testWidgets('a token not handed on reads nothing again', (tester) async {
    final (source, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    window.answers.single.complete(false);
    await tester.pump();
    expect(source.fetches, isEmpty, reason: 'no fetch');
    expect(find.byKey(_offer), findsOne, reason: 'the offer stays');
  });

  // Harm: two prompts for the same token, or two tokens written into the vault at once.
  testWidgets('no second hand-on is asked while one runs', (tester) async {
    final (_, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    await tester.tap(find.byKey(_offer), warnIfMissed: false);
    await tester.pump();
    expect(window.calls, hasLength(1), reason: 'one call while it runs');
    expect(tester.widget<ButtonStyleButton>(find.byKey(_offer)).onPressed, isNull, reason: 'the offer is disabled');
    window.answers.single.complete(false);
    await tester.pump();
    expect(tester.widget<ButtonStyleButton>(find.byKey(_offer)).onPressed, isNotNull, reason: 'offered again');
  });

  // Harm: a board the person switched away from read again, its answers shown for the project chosen since.
  testWidgets('a token handed on after the board changed its project reads nothing again', (tester) async {
    final (source, board) = _refused(_missing);
    final window = _Window();
    await _show(tester, board, window);
    await tester.tap(find.byKey(_offer));
    await tester.pump();
    final other = _Source();
    await board.changeProject(other, 'other');
    window.answers.single.complete(true);
    await tester.pump();
    expect([...source.fetches, ...other.fetches], isEmpty, reason: 'no fetch for the board changed meanwhile');
  });

  // Harm: an offer kept above a board that reads again, or lost while the refusal still holds.
  test('the board keeps a refusal of an action until a board read after it', () async {
    final source = _Source();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    source.watches.single.add(_board());
    await Future<void>.delayed(Duration.zero);
    board.reportActionProblem(_missing);
    expect(board.refusal, same(_missing), reason: 'the action refusal kept whole');
    source.watches.single.add(_board());
    await Future<void>.delayed(Duration.zero);
    expect(board.refusal, isNull, reason: 'a board read after it clears it');
    board.reportActionProblem(_missing);
    await board.changeProject(_Source(), 'other');
    expect(board.refusal, isNull, reason: 'another project clears it');
  });
}
