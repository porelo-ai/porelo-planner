import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

const _kept = '/home/person/.local/share/porelo-planner/demo';
const _chosen = '/home/person/planning';

/// A source whose watches the test feeds and whose directory calls it answers.
class _Source extends Fake implements PlanningSource {
  final watches = <StreamController<PlanningSnapshot>>[];
  final fetches = <String>[];
  final reads = <String>[];
  final calls = <(String, String?, Map<String, String>)>[];

  /// A read waits for it while set, as a save under way holds the board.
  Completer<void>? hold;

  /// What the next directory call answers; held back while set.
  Completer<void>? answer;

  /// What the next directory call throws instead of answering, while set.
  Object? refuse;

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>(sync: true);
    watches.add(watch);
    return watch.stream;
  }

  @override
  Future<String?> refreshProject(String project) async {
    fetches.add(project);
    return 'r2';
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) async {
    reads.add('workflow');
    await hold?.future;
    return _board(_local(_kept));
  }

  @override
  Future<PlanningSnapshot> stories(String project) async {
    reads.add('stories');
    await hold?.future;
    return _board(_local(_kept));
  }

  @override
  Future<PlanningTracker> setItemsDirectory(String project, String? directory, Map<String, String> seen) async {
    calls.add((project, directory, seen));
    await answer?.future;
    if (refuse case final refusal?) throw refusal;
    return PlanningTracker.local(directory ?? _kept, _kept);
  }
}

Map<String, dynamic> _local(String items) => {'kind': 'local', 'items': items, 'default': _kept};

PlanningSnapshot _board(Map<String, dynamic> tracker) => PlanningSnapshot.from({
  'revision': 'r1',
  'tracker': tracker,
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

PlanningRefusal _refused(String directory) => PlanningRefusal('ItemsDirectoryRefused', {
  'project': 'demo',
  'directory': directory,
}, 'The directory does not exist.');

const _choose = Key('items-directory-choose');

/// A board on a fresh source, its watch answered with [tracker]'s board.
(_Source, PlanningBoard) _shown(Map<String, dynamic> tracker) {
  final source = _Source();
  final board = PlanningBoard(source, 'demo');
  addTearDown(board.dispose);
  source.watches.single.add(_board(tracker));
  return (source, board);
}

Future<void> _show(WidgetTester tester, PlanningBoard board, {PlanningChooseDirectory? choose}) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(
      body: ItemsDirectoryHeader(board: board, choose: choose),
    ),
  ),
);

void main() {
  // Harm: the person cannot see where the planner keeps a local project's planning, or is offered a choice that
  // means nothing for a project GitHub or the Git store keeps.
  testWidgets('a local project shows its directory and a choice; one GitHub or the Git store keeps shows neither', (
    tester,
  ) async {
    final (_, local) = _shown(_local(_kept));
    await _show(tester, local);
    expect(find.text('Planning kept in $_kept'), findsOne);
    expect(find.byKey(_choose), findsOne);

    for (final kind in ['github', 'git']) {
      final (_, other) = _shown({'kind': kind});
      await _show(tester, other);
      expect(find.byKey(const Key('items-directory')), findsNothing, reason: 'no directory for $kind');
      expect(find.byKey(_choose), findsNothing, reason: 'no choice for $kind');
    }
  });

  // Harm: a directory chosen but the board still shown from the old one, or the dialog opened somewhere unrelated.
  testWidgets('a directory chosen in the window\'s dialog is kept by the planner and the board read again', (
    tester,
  ) async {
    final (source, board) = _shown(_local(_kept));
    final opened = <String?>[];
    await _show(
      tester,
      board,
      choose: (initial) async {
        opened.add(initial);
        return _chosen;
      },
    );

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();

    expect(opened, [_kept], reason: 'the dialog opens at the directory in use');
    expect(source.calls, [('demo', _chosen, const <String, String>{})], reason: 'kept with an empty seen');
    expect(source.reads, isNotEmpty, reason: 'the board is read again');
    expect(source.fetches, isEmpty, reason: 'from the store, with no fetch of the repositories');
  });

  // Harm: a dialog put away still changes where the planner keeps the planning.
  testWidgets('a dialog put away, answered as null or as an empty text, keeps the directory as it is', (tester) async {
    for (final answer in [null, '']) {
      final (source, board) = _shown(_local(_kept));
      await _show(tester, board, choose: (_) async => answer);

      await tester.tap(find.byKey(_choose));
      await tester.pumpAndSettle();

      expect(source.calls, isEmpty, reason: 'put away as ${answer == null ? 'null' : 'an empty text'}');
      expect(find.byKey(const Key('items-directory-refused')), findsNothing, reason: 'a cancel, not a refused path');
      expect(source.reads, isEmpty);
    }
  });

  // Harm: a refused choice said without its path, read as kept, or the board read from a directory never taken.
  testWidgets('a refused choice stands with its path and the planner\'s words, and the dialog opens there again', (
    tester,
  ) async {
    final (source, board) = _shown(_local(_kept));
    source.refuse = _refused(_chosen);
    final opened = <String?>[];
    await _show(
      tester,
      board,
      choose: (initial) async {
        opened.add(initial);
        return _chosen;
      },
    );

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();

    expect(
      find.text(
        'The planner cannot keep planning in $_chosen: The directory does not exist. Planning stays in $_kept.',
      ),
      findsOne,
      reason: 'the path and the planner\'s words, beside where planning is kept now',
    );
    expect(source.reads, isEmpty, reason: 'nothing was kept, so nothing is read again');

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();
    expect(opened, [_kept, _kept], reason: 'the second dialog opens at the directory in use');
  });

  // Harm: a kept directory moved away shown as a failed read the person cannot mend from the planning screen.
  testWidgets('a kept directory the planner refuses at the read is named, with a choice that opens there', (
    tester,
  ) async {
    final source = _Source();
    final board = PlanningBoard(source, 'demo');
    addTearDown(board.dispose);
    source.watches.single.addError(_refused(_kept));
    final opened = <String?>[];
    await _show(
      tester,
      board,
      choose: (initial) async {
        opened.add(initial);
        return _chosen;
      },
    );

    expect(find.text('The planner cannot keep planning in $_kept: The directory does not exist.'), findsOne);
    expect(board.snapshot, isNull, reason: 'no board is shown meanwhile');
    expect(board.problem, isNot(contains('The directory does not exist.')), reason: 'the header alone says it');

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();
    expect(opened, [_kept]);
    expect(source.calls.single.$2, _chosen);
    expect(source.watches, hasLength(2), reason: 'the ended watch is opened anew on the directory chosen');
    expect(source.fetches, isEmpty, reason: 'with no fetch of the repositories');
  });

  // Harm: a kept directory moved away after the board was read, told only as words the person cannot act on.
  testWidgets('a read after the first board that refuses the kept directory is named over the board', (tester) async {
    final source = _Source();
    final readFailed = ValueNotifier<PlanningRefusal?>(null);
    final watch = StreamController<PlanningSnapshot>(sync: true);
    final board = PlanningBoard(
      _Watched(source, PlanningWatch(watch.stream, ValueNotifier(false), readFailed: readFailed)),
      'demo',
    );
    addTearDown(board.dispose);
    watch.add(_board(_local(_kept)));
    await _show(tester, board);
    expect(find.text('Planning kept in $_kept'), findsOne);

    readFailed.value = _refused(_kept);
    await tester.pump();
    expect(board.readProblem, isNot(contains('The directory does not exist.')), reason: 'the header alone says it');

    expect(find.text('The planner cannot keep planning in $_kept: The directory does not exist.'), findsOne);
  });

  // Harm: a window without a folder dialog leaves the person no way to choose a directory, or a typed path is
  // trimmed into another than the one typed.
  testWidgets('without the window\'s dialog a typed path is taken as typed, and one outside the rule refused', (
    tester,
  ) async {
    final (source, board) = _shown(_local(_kept));
    await _show(tester, board);

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('items-directory-typed')), ' $_chosen ');
    await tester.tap(find.byKey(const Key('items-directory-use')));
    await tester.pumpAndSettle();
    expect(source.calls, isEmpty, reason: 'white space at its ends is refused, not trimmed');
    expect(
      find.text(
        'The directory for this project’s planning was refused: $planningDirectoryNotNormal Planning stays in $_kept.',
      ),
      findsOne,
    );

    await tester.enterText(find.byKey(const Key('items-directory-typed')), _chosen);
    await tester.tap(find.byKey(const Key('items-directory-use')));
    await tester.pumpAndSettle();

    expect(source.calls, [('demo', _chosen, const <String, String>{})]);
    expect(source.reads, isNotEmpty);
  });

  // Harm: the person cannot go back to the default directory, or is offered it while it is the one in use.
  testWidgets('the default is offered back while another directory is kept, and sends null', (tester) async {
    final (source, board) = _shown(_local(_kept));
    await _show(tester, board);
    expect(find.byKey(const Key('items-directory-default')), findsNothing, reason: 'the default is in use');

    final (away, other) = _shown(_local(_chosen));
    await _show(tester, other);
    await tester.tap(find.byKey(const Key('items-directory-default')));
    await tester.pumpAndSettle();

    expect(away.calls, [('demo', null, const <String, String>{})]);
    expect(source.calls, isEmpty);
  });

  // Harm: the board read after a choice races a save under way and shows the old directory's board over the save.
  testWidgets('after a choice on a live board the board is read once a save under way has ended', (tester) async {
    final (source, board) = _shown(_local(_kept));
    await _show(tester, board, choose: (_) async => _chosen);
    await board.reload();
    final one = source.reads.length;
    source.reads.clear();
    final held = Completer<void>();
    source.hold = held;
    final reloading = board.reload();
    await tester.pump();
    expect(board.saving, isTrue, reason: 'a read that holds the board as a save does');
    final before = source.reads.length;

    await tester.tap(find.byKey(_choose));
    await tester.pump();
    await tester.pump();
    expect(source.calls.single.$2, _chosen);
    expect(source.reads, hasLength(before), reason: 'nothing read while the save is under way');

    source.hold = null;
    held.complete();
    await reloading;
    await tester.pumpAndSettle();
    expect(source.reads, hasLength(2 * one), reason: 'the read under way, then one more once it ended');
    expect(source.fetches, isEmpty, reason: 'with no fetch');
  });

  // Harm: an answer for a project no longer shown speaks beside another project's directory.
  testWidgets('an answer that comes after another project is shown changes nothing there', (tester) async {
    final (source, board) = _shown(_local(_kept));
    source.refuse = _refused(_chosen);
    final answered = Completer<void>();
    source.answer = answered;
    await _show(tester, board, choose: (_) async => _chosen);

    await tester.tap(find.byKey(_choose));
    await tester.pump();
    await tester.runAsync(() => board.changeProject(source, 'other'));
    expect(board.project, 'other', reason: 'another project is shown');
    source.watches.last.add(_board(_local(_kept)));
    await tester.pump();
    answered.complete();
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('items-directory-refused')), findsNothing, reason: 'the old answer is dropped');
    expect(find.text('Planning kept in $_kept'), findsOne);
  });

  // Harm: another refusal of the call taken for a refused directory, or not said at all.
  testWidgets('a refusal other than of the directory is said in its words and names no directory', (tester) async {
    final (source, board) = _shown(_local(_kept));
    source.refuse = const PlanningRefusal('Busy', {
      'repository': '',
    }, 'The project is busy with another save; try again.');
    await _show(tester, board, choose: (_) async => _chosen);

    await tester.tap(find.byKey(_choose));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('items-directory-refused')), findsNothing);
    expect(find.byKey(const Key('items-directory-failed')), findsOne);
    expect(find.textContaining('busy with another save'), findsOne);
    expect(source.reads, isEmpty);
  });
}

/// [source] with its one watch given by the test.
class _Watched extends Fake implements PlanningSource {
  _Watched(this.source, this.watch);

  final _Source source;
  final PlanningWatch watch;

  @override
  Stream<PlanningSnapshot> watchStories(String project) => watch;

  @override
  String get label => 'watched';
}
