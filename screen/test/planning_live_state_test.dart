import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

class _Changes extends ChangeNotifier {
  bool get listening => hasListeners;
  void changed() => notifyListeners();
}

class _Source extends FakePlanningSource implements PlanningReconnect {
  _Source() : super(smallBoard());

  final changes = _Changes();
  final watches = <String, StreamController<PlanningSnapshot>>{};
  final reconnecting = <String>{};
  final issueReads = <(String, String)>[];
  List<String> subIssues = ['I1.1', 'I1.2'];
  String? whatMustBeTrue;

  void reconnect(String project, bool value) {
    value ? reconnecting.add(project) : reconnecting.remove(project);
    changes.changed();
  }

  void send(String project) => watches[project]!.add(PlanningSnapshot.from(board));

  @override
  bool isReconnecting(String project) => reconnecting.contains(project);

  @override
  void addListener(VoidCallback listener) => changes.addListener(listener);

  @override
  void removeListener(VoidCallback listener) => changes.removeListener(listener);

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>();
    watches[project] = watch;
    return watch.stream;
  }

  @override
  Future<PlanIssueDetail> getIssue(String project, String issue) async {
    issueReads.add((project, issue));
    return PlanIssueDetail.from({
      'revision': 'r1',
      'issue': {
        'id': issue,
        'title': 'An issue',
        'state': 'Backlog',
        'repository': 'repo',
        'blockedBy': <String>[],
        'text': 'The issue text',
        'subIssues': issue == 'I1' ? subIssues : <String>[],
        if (whatMustBeTrue != null) 'whatMustBeTrue': whatMustBeTrue,
      },
    });
  }

  @override
  Future<void> close() async {
    for (final watch in watches.values) {
      // A closed watch's future belongs to the widget's fake clock, which teardown no longer pumps.
      if (!watch.isClosed) await watch.close();
    }
    changes.dispose();
    await super.close();
  }
}

Future<(PlanningBoards, TestHost)> _show(WidgetTester tester, _Source source, {bool answer = true}) async {
  tester.view
    ..physicalSize = const Size(1280, 1000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final client = FakePlanningClient([provider('local', source)]);
  final boards = PlanningBoards(client);
  final host = TestHost();
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    host.dispose();
    await source.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: host),
      ),
    ),
  );
  if (answer) source.send('demo');
  await settle(tester);
  return (boards, host);
}

void main() {
  // Harm: reconnecting planning continues to authorize moves and work against an obsolete board.
  testWidgets('catching up lasts until a replacement board and belongs to its project', (tester) async {
    final source = _Source();
    final (boards, _) = await _show(tester, source);
    final board = boards.selected!;
    expect(board.live, isTrue);
    source.reconnect('other', true);
    await settle(tester);
    expect(find.byKey(const Key('planning-catching-up')), findsNothing);
    expect(board.live, isTrue);

    source.reconnect('demo', true);
    await settle(tester);
    expect(find.byKey(const Key('planning-catching-up')), findsOneWidget);
    expect(board.live, isFalse);
    board.move('S1', 'Review');
    expect(board.pending, isEmpty);
    expect(board.startUnavailable(board.stories.first), isNotNull);
    source.reconnect('demo', false);
    await settle(tester);
    expect(find.byKey(const Key('planning-catching-up')), findsOneWidget);
    expect(board.live, isFalse);

    source.send('demo');
    await settle(tester);
    expect(find.byKey(const Key('planning-catching-up')), findsNothing);
    expect(board.live, isTrue);
    expect(board.startUnavailable(board.stories.first), isNull);
    board.move('S1', 'Review');
    expect(board.pending.keys, ['S1']);
  });

  // Harm: a closed watch looks current and hides how to retrieve the planner's current state.
  testWidgets('an ended watch retains its board and offers a reload with an explanation', (tester) async {
    final source = _Source();
    final (boards, _) = await _show(tester, source);
    source.reconnect('demo', true);
    await settle(tester);
    await source.watches['demo']!.close();
    await settle(tester);
    expect(source.watches['demo']!.hasListener, isFalse);
    expect(source.changes.listening, isFalse);
    expect(find.byKey(const Key('planning-catching-up')), findsNothing);
    expect(boards.selected!.live, isFalse);
    boards.selected!.move('S1', 'Review');
    expect(boards.selected!.pending, isEmpty);
    expect(boards.selected!.startUnavailable(boards.selected!.stories.first), isNotNull);
    expect(find.byKey(const Key('planning-card S1')), findsOneWidget);
    final stale = tester.widget<Text>(find.byKey(const Key('planning-not-live'))).data!.toLowerCase();
    expect(stale, contains('reload'));
    expect(stale, contains('current'));
    expect(find.byKey(const Key('planning-reload')), findsOneWidget);
    source.board = smallBoard(revision: 'r2');
    await tester.tap(find.byKey(const Key('planning-reload')));
    await settle(tester);
    expect(boards.selected!.snapshot!.revision, 'r2');
    expect(boards.selected!.live, isFalse);
  });

  for (final answered in [false, true]) {
    // Harm: a refusal conceals the board's recovery action or displays control characters from the source.
    testWidgets('reload guidance stays beside the cleaned refusal, prior board: $answered', (tester) async {
      final source = _Source();
      await _show(tester, source, answer: answered);
      source.watches['demo']!.addError(const PlanningRefusal('Unreadable', {}, 'store\x07 unavailable\u202e'));
      await settle(tester);
      expect(find.byKey(const Key('planning-not-live')), findsOneWidget);
      final error = tester.widget<Text>(find.byKey(const Key('planning-error'))).data!;
      expect(error, contains('store'));
      expect(error, contains('unavailable'));
      expect(error, isNot(contains('\x07')));
      expect(error, isNot(contains('\u202e')));
      expect(find.byKey(const Key('planning-reload')), findsOneWidget);
    });
  }

  // Harm: an ended watch's delayed workflow answer makes stale planning authorize actions again.
  test('a workflow read finishing after stream completion cannot restore live state', () async {
    final workflow = Completer<PlanningSnapshot>();
    final source = _DelayedWorkflow(workflow.future);
    final board = PlanningBoard(source, 'demo');
    addTearDown(() async {
      board.dispose();
      await source.close();
    });
    source.watches['demo']!.add(PlanningSnapshot.from({'stories': smallBoard()['stories'], 'revision': 'r1'}));
    await pumpEventQueue();
    await source.watches['demo']!.close();
    workflow.complete(PlanningSnapshot.from(smallBoard()));
    await pumpEventQueue();
    expect(board.snapshot, isNotNull);
    expect(board.live, isFalse);
  });

  // Harm: old sources keep listeners and change another project's live state after navigation or disposal.
  test('reconnect listeners follow source replacement and are released', () async {
    final first = _Source();
    final next = _Source();
    final board = PlanningBoard(first, 'first');
    expect(first.changes.listening, isTrue);
    await board.changeProject(next, 'second');
    expect(first.changes.listening, isFalse);
    expect(first.watches['first']!.hasListener, isFalse);
    expect(next.changes.listening, isTrue);
    first.reconnect('first', true);
    expect(board.catchingUp, isFalse);
    next.reconnect('second', true);
    expect(board.catchingUp, isTrue);
    board.dispose();
    expect(next.changes.listening, isFalse);
    expect(next.watches['second']!.hasListener, isFalse);
    next.reconnect('second', false);
    await first.close();
    await next.close();
  });

  // Harm: control or directional characters disguise a sub-issue link's identity before it is opened.
  testWidgets('sub-issue labels are cleaned without changing the navigation identifier', (tester) async {
    const id = 'I1.1\x07\u202e';
    final source = _Source()..subIssues = [id];
    final (_, host) = await _show(tester, source);
    host.go(const PlanningPlace(project: 'demo', source: 'local', story: 'S1', issue: 'I1'));
    await settle(tester);
    final link = find.byKey(const Key('planning-sub-issue $id'));
    final label = tester.widget<Text>(find.descendant(of: link, matching: find.byType(Text))).data!;
    expect(label, isNot(contains('\x07')));
    expect(label, isNot(contains('\u202e')));
    await tester.ensureVisible(link);
    await tester.tap(link);
    await settle(tester);
    expect(host.place.issue, id);
    expect(source.issueReads.last, ('demo', id));
  });

  // Harm: source control characters disguise issue identities, acceptance criteria and workflow faults on screen.
  testWidgets('shown issue details and workflow faults are cleaned while navigation keeps raw ids', (tester) async {
    const story = 'S1\x07\u202e';
    const child = 'I1.1\x07\u202e';
    final source = _Source()
      ..subIssues = [child]
      ..whatMustBeTrue = 'First criterion\x07\u202e\nSecond criterion';
    source.board['faults'] = ['Workflow fault\x07\u202e'];
    final (_, host) = await _show(tester, source);
    List<String> shownText() => [
      for (final text in tester.widgetList<Text>(find.byType(Text))) text.data ?? text.textSpan?.toPlainText() ?? '',
      for (final text in tester.widgetList<SelectableText>(find.byType(SelectableText)))
        text.data ?? text.textSpan?.toPlainText() ?? '',
    ];

    expect(find.byKey(const Key('planning-error')), findsOneWidget);
    final shown = shownText();
    expect(shown.any((text) => text.contains('Workflow fault')), isTrue);
    host.go(const PlanningPlace(project: 'demo', source: 'local', story: story, issue: 'I1'));
    await settle(tester);
    shown.addAll(shownText());
    final link = find.byKey(const Key('planning-sub-issue $child'));
    await tester.ensureVisible(link);
    await tester.tap(link);
    await settle(tester);
    expect(host.place.story, story);
    expect(host.place.issue, child);
    expect(source.issueReads.last, ('demo', child));
    expect(find.byKey(const Key('planning-crumb 1')), findsOneWidget);
    expect(find.byKey(const Key('planning-crumb 2')), findsOneWidget);
    final details = shownText();
    expect(details.where((text) => text.contains('I1.1')).length, 2);
    expect(details.any((text) => text.contains('First criterion') && text.contains('\nSecond criterion')), isTrue);
    shown.addAll(details);
    final unsafe = RegExp(r'[\u202e\x00-\x09\x0b-\x1f]');
    expect(shown.where(unsafe.hasMatch), isEmpty);
  });

  // Harm: sub-issues absent from story summaries cannot be discovered or opened from their parent issue.
  testWidgets('standalone issue sub-issues each open on the original source and project', (tester) async {
    final source = _Source();
    final (_, host) = await _show(tester, source);
    for (final child in ['I1.1', 'I1.2']) {
      host.go(const PlanningPlace(project: 'demo', source: 'local', story: 'S1', issue: 'I1'));
      await settle(tester);
      final link = find.byKey(Key('planning-sub-issue $child'));
      expect(link, findsOneWidget);
      await tester.ensureVisible(link);
      await tester.tap(link);
      await settle(tester);
      expect(host.place, PlanningPlace(project: 'demo', source: 'local', story: 'S1', issue: child));
      expect(source.issueReads.last, ('demo', child));
    }
  });
}

class _DelayedWorkflow extends _Source {
  _DelayedWorkflow(this.workflow);
  final Future<PlanningSnapshot> workflow;

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) => workflow;
}
