import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

class _Source extends FakePlanningSource {
  _Source([Map<String, dynamic>? board]) : super(board ?? smallBoard());

  final watches = <String, StreamController<PlanningSnapshot>>{};

  void send(String project) => watches[project]!.add(PlanningSnapshot.from(board));

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final watch = StreamController<PlanningSnapshot>();
    watches[project] = watch;
    return watch.stream;
  }

  @override
  Future<void> close() async {
    for (final watch in watches.values) {
      if (!watch.isClosed) await watch.close();
    }
    await super.close();
  }
}

/// A source that holds the answer to a new issue until [answer] completes.
class _HeldSource extends _Source {
  final answer = Completer<void>();

  @override
  Future<PlanningIssueAdded> createIssue(
    String project,
    String repository,
    String title,
    String mustBeTrue,
    String? size,
    String revision, {
    IssueWorker worker = IssueWorker.agent,
    String? task,
  }) async {
    await answer.future;
    return super.createIssue(project, repository, title, mustBeTrue, size, revision, worker: worker, task: task);
  }
}

Future<PlanningBoards> _show(WidgetTester tester, _Source source) async {
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
  source.send('demo');
  await settle(tester);
  return boards;
}

Future<void> _openForm(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('planning-board-menu')));
  await settle(tester);
  await tester.tap(find.byKey(const ValueKey<String>('planning-new-issue')));
  await settle(tester);
}

Future<void> _pick(WidgetTester tester, String repository) async {
  await tester.tap(find.byKey(const Key('new-issue-repository')));
  await settle(tester);
  await tester.tap(find.text(repository).last);
  await settle(tester);
}

FilledButton _createButton(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('new-issue-create')));

Future<void> _fill(WidgetTester tester) async {
  await tester.enterText(find.byKey(const Key('new-issue-title')), 'An idea');
  await tester.enterText(find.byKey(const Key('new-issue-must-be-true')), 'It holds.');
  await settle(tester);
}

void main() {
  // Harm: an idea sent against another board than the one shown could take a number someone took since.
  testWidgets('the form sends the new issue with the revision of the board it was written on', (tester) async {
    final source = _Source();
    await _show(tester, source);

    await _openForm(tester);
    await _pick(tester, 'repo');
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(source.added, [
      ('demo', 'repo', 'An idea', 'It holds.', null, 'r1'),
    ], reason: 'one call, the board revision');
    expect(find.byKey(const Key('new-issue-create')), findsNothing, reason: 'the form closes once it is written');
    expect(find.text('Added I3 in state Idea.'), findsOneWidget, reason: 'the person learns the new id');
  });

  // Harm: a repository without an issue on the board could get no idea at all, and one chosen is the one written to.
  testWidgets('every repository of the board can be chosen, one that no story\'s issue names too', (tester) async {
    final source = _Source();
    await _show(tester, source);

    await _openForm(tester);
    await tester.tap(find.byKey(const Key('new-issue-repository')));
    await settle(tester);
    expect(find.text('porelo-other'), findsWidgets, reason: 'offered though no story names an issue of it');
    await tester.tap(find.text('porelo-other').last);
    await settle(tester);
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(source.added.single.$2, 'porelo-other', reason: 'the chosen repository is sent');
  });

  // Harm: a new issue goes to a person, or to an agent, other than the one the form shows.
  testWidgets('a new issue is an agent\'s unless the person chooses Manual', (tester) async {
    final source = _Source();
    await _show(tester, source);

    await _openForm(tester);
    await _pick(tester, 'repo');
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    await _openForm(tester);
    await _pick(tester, 'repo');
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-worker')));
    await settle(tester);
    await tester.tap(find.text('Manual').last);
    await settle(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(source.addedWorkers, [IssueWorker.agent, IssueWorker.manual], reason: 'Agent unchanged, then Manual');
  });

  // Harm: an idea numbered on a board that is no longer live is written against what the planner no longer holds.
  testWidgets('the new issue is refused in words while the board is not live', (tester) async {
    final source = _Source();
    final boards = await _show(tester, source);
    await source.watches['demo']!.close();
    await settle(tester);
    expect(boards.selected!.live, isFalse, reason: 'the ended watch leaves the board not live');

    await tester.tap(find.byKey(const Key('planning-board-menu')));
    await settle(tester);

    expect(find.text('Unavailable: the planning board is not live'), findsOneWidget, reason: 'the reason in words');
    await tester.tap(find.byKey(const ValueKey<String>('planning-new-issue')), warnIfMissed: false);
    await settle(tester);
    expect(find.byKey(const Key('new-issue-title')), findsNothing, reason: 'no form opens');
    expect(
      await boards.selected!.createIssue('repo', 'An idea', 'It holds.', null),
      isNull,
      reason: 'the board itself refuses it too',
    );
    expect(source.added, isEmpty, reason: 'nothing reaches the planner');
  });

  // Harm: a refusal that is not shown leaves the person believing the idea was written.
  testWidgets('a refusal shows the planner\'s words and keeps what was written', (tester) async {
    final source = _Source()
      ..addFailure = const PlanningRefusal('IssueNotAllowed', {
        'field': 'title',
        'reason': 'The title must have 1 to 120 characters.',
      }, 'The title must have 1 to 120 characters.');
    await _show(tester, source);

    await _openForm(tester);
    await _pick(tester, 'repo');
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(
      tester.widget<Text>(find.byKey(const Key('new-issue-error'))).data,
      'The title must have 1 to 120 characters.',
      reason: 'the words in the form',
    );
    expect(find.byKey(const Key('planning-action-error')), findsOneWidget, reason: 'and on the board, as its refusals');
    expect(find.text('An idea'), findsOneWidget, reason: 'the title stays written');
  });

  // Harm: an issue is added to a repository the person never chose.
  testWidgets('with several repositories none is chosen until the person picks one', (tester) async {
    final source = _Source();
    await _show(tester, source);

    await _openForm(tester);
    await _fill(tester);
    expect(_createButton(tester).onPressed, isNull, reason: 'no repository is chosen, so nothing can be added');

    await _pick(tester, 'porelo-other');
    expect(_createButton(tester).onPressed, isNotNull, reason: 'a picked repository completes the form');
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(source.added.single.$2, 'porelo-other', reason: 'the issue goes to the picked repository');
  });

  // Harm: a board of one repository would ask for a choice that has only one answer before anything can be added.
  testWidgets('the only repository of a board is chosen without asking', (tester) async {
    final source = _Source({
      ...smallBoard(),
      'repositories': ['repo'],
    });
    await _show(tester, source);

    await _openForm(tester);
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);

    expect(source.added.single.$2, 'repo', reason: 'the one repository is sent');
  });

  // Harm: an added issue or a refusal is never seen when the form can be closed while it is sent.
  testWidgets('the form cannot be closed while the new issue is sent, and shows the answer once it comes', (
    tester,
  ) async {
    final source = _HeldSource();
    await _show(tester, source);

    await _openForm(tester);
    await _pick(tester, 'repo');
    await _fill(tester);
    await tester.tap(find.byKey(const Key('new-issue-create')));
    await settle(tester);
    await Navigator.of(tester.element(find.byKey(const Key('new-issue-create')))).maybePop();
    await settle(tester);
    expect(find.byKey(const Key('new-issue-create')), findsOneWidget, reason: 'a dismiss leaves the sending form open');

    source.answer.complete();
    await settle(tester);

    expect(find.text('Added I3 in state Idea.'), findsOneWidget, reason: 'the answer is shown once it comes');
  });
}
