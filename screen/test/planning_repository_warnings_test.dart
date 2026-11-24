import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

/// A repository whose labels the store offers to bring to the topic labels: one default to delete, one topic label
/// to create and one to update.
Map<String, dynamic> _labels([String repository = 'repo-a']) => {
  'repository': repository,
  'kind': 'TopicLabels',
  'missing': ['documentation'],
  'defaults': ['bug'],
  'differing': ['tests'],
  'correction': {'kind': 'Labels', 'choices': <String>[]},
};

/// A source that counts the board's reads, so a test sees the board read again.
class _Counting extends MockPlanningSource {
  int reads = 0;

  @override
  Future<PlanningSnapshot> stories(String project) {
    reads++;
    return super.stories(project);
  }
}

/// Shows a board without stories whose repositories carry [warnings].
Future<PlanningBoards> _show(
  WidgetTester tester,
  MockPlanningSource backend,
  List<Object> warnings, {
  double width = 2400,
}) async {
  tester.view
    ..physicalSize = Size(width, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  backend.boards['demo'] = {
    'states': [
      {'name': 'Ready', 'kind': 'WAITING'},
      {'name': 'Finished', 'kind': 'CLOSED'},
    ],
    'phases': [
      {
        'name': 'Work',
        'states': ['Ready', 'Finished'],
      },
    ],
    'stories': <Object>[],
    'issues': <Object>[],
    'repositories': ['repo-a', 'repo-b'],
    'repositoryWarnings': warnings,
    'revision': 'r1',
  };
  final client = MockPlanningClient(
    () => [
      PlanningProvider(name: 'local', connected: true, projects: ['demo'], source: backend),
    ],
    currentProvider: () => 'local',
  );
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: TestHost()),
      ),
    ),
  );
  await settle(tester);
  return boards;
}

Future<void> _answered(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

const _offer = Key('planning-correct-repository repo-a TopicLabels');

void main() {
  // Harm: labels deleted, created or rewritten on a repository's forge without the person seeing each call, or a
  // save bound to versions a repository does not have, so it is refused or applied over another preview.
  testWidgets('a repository\'s labels are previewed call by call and saved only on confirm, with no versions', (
    tester,
  ) async {
    final backend = MockPlanningSource();
    final boards = await _show(tester, backend, [_labels()]);
    expect(
      find.byKey(const Key('planning-repository-warning repo-a TopicLabels')),
      findsOneWidget,
      reason: 'the warning stands in the header',
    );

    await tester.tap(find.byKey(_offer));
    await _answered(tester);

    expect(backend.correctionPreviews.single['correction'], {'repository': 'repo-a', 'warning': 'TopicLabels'});
    expect(backend.correctionCalls, isEmpty, reason: 'nothing is saved before the person confirms');
    expect(find.byKey(const Key('planning-correction')), findsOneWidget, reason: 'the preview is shown');
    expect(
      [for (final line in tester.widgetList<Text>(find.byKey(const Key('planning-correction-change')))) line.data],
      [
        'Delete the label bug, which takes it off every issue that carries it',
        'Create the label documentation, color 0075ca: Guides, references and decisions',
        'Set the label tests to color fbca04: Tests and what runs them',
      ],
      reason: 'every call it makes on the forge, in its order, a Delete saying it takes the label off every issue',
    );
    final asked = boards.selected!.correctionQuestion!;

    await tester.tap(find.byKey(const Key('planning-correction-confirm')));
    await _answered(tester);

    final saved = backend.correctionCalls.single;
    expect(saved['correction'], {'repository': 'repo-a', 'warning': 'TopicLabels'});
    expect(saved['seen'], isEmpty, reason: 'a repository has no item version');
    expect(saved['digest'], asked.preview.digest, reason: 'the save is bound to the preview the person saw');
    expect(
      find.byKey(const Key('planning-repository-warning repo-a TopicLabels')),
      findsNothing,
      reason: 'the warning it set right is gone',
    );
  });

  // Harm: a repository warning with its offer overflows a narrow window.
  testWidgets('a repository warning and its offer fit a window 390 pixels wide', (tester) async {
    await _show(tester, MockPlanningSource(), [_labels('a-repository-with-a-rather-long-name')], width: 390);
    expect(tester.takeException(), isNull, reason: 'nothing overflows');
    expect(
      find.byKey(const Key('planning-correct-repository a-repository-with-a-rather-long-name TopicLabels')),
      findsOneWidget,
    );
  });

  // Harm: a greyed offer tells why only on hover, which a person on a touch screen never sees.
  testWidgets('an offer that cannot be taken says why in text beside it', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [_labels()]);
    await tester.tap(find.byKey(_offer));
    await _answered(tester);
    expect(tester.widget<TextButton>(find.byKey(_offer)).onPressed, isNull, reason: 'the preview waits');
    expect(
      find.descendant(
        of: find.byKey(const Key('planning-repository-warning repo-a TopicLabels')),
        matching: find.text('another change is being saved; set it right once it is saved'),
      ),
      findsOneWidget,
    );
  });

  // Harm: a correction the person cancels is written on the forge anyway.
  testWidgets('a cancelled repository correction sends nothing', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [_labels()]);

    await tester.tap(find.byKey(_offer));
    await _answered(tester);
    await tester.tap(find.byKey(const Key('planning-correction-cancel')));
    await _answered(tester);

    expect(backend.correctionCalls, isEmpty);
    expect(find.byKey(const Key('planning-correction')), findsNothing);
  });

  // Harm: a second run, once the warning is gone, is taken as saved, or keeps being offered over a board the person
  // did not see.
  testWidgets('a repository correction refused as no longer standing is said and the board read again', (tester) async {
    final backend = _Counting();
    final boards = await _show(tester, backend, [_labels()]);
    await tester.tap(find.byKey(_offer));
    await _answered(tester);
    backend.moveFailure = mockRefusal('CorrectionRefused', {
      'item': 'repo-a',
      'warning': 'TopicLabels',
      'reason': 'The warning no longer stands on it.',
    });
    final reads = backend.reads;

    await tester.tap(find.byKey(const Key('planning-correction-confirm')));
    await _answered(tester);

    final said = boards.selected!.actionProblem;
    expect(said, startsWith('repo-a was not set right: '), reason: 'said as the screen words it');
    expect(said, isNot(contains('urn:')));
    expect(backend.reads, greaterThan(reads), reason: 'the board is read again');
    expect(boards.selected!.correctionQuestion, isNull, reason: 'nothing is left asked about');
  });

  // Harm: a correction of a kind the window cannot show is offered as a write on the forge.
  testWidgets('a repository warning without a Labels correction is shown without an offer', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [
      {..._labels(), 'correction': null},
      {
        ..._labels('repo-b'),
        'correction': {
          'kind': 'Rewrite',
          'choices': ['all'],
        },
      },
    ]);
    await tester.tap(find.byKey(const Key('planning-repository-warnings-fold')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('planning-repository-warning repo-a TopicLabels')), findsOneWidget);
    expect(find.byKey(const Key('planning-repository-warning repo-b TopicLabels')), findsOneWidget);
    expect(find.byKey(_offer), findsNothing, reason: 'no correction');
    expect(
      find.byKey(const Key('planning-correct-repository repo-b TopicLabels')),
      findsNothing,
      reason: 'an unknown kind',
    );
  });

  // Harm: a correction without a choice is offered for an item, whose correction is then sent with none; or an
  // answer outside its bounds reaches the header.
  test('only a Labels offers no choice, and a repository warning is read only within its bounds', () {
    expect(PlanCorrection.read({'kind': 'Labels', 'choices': <String>[]})?.choices, isEmpty);
    expect(PlanCorrection.read({'kind': 'Priority', 'choices': <String>[]}), isNull, reason: 'a Priority picks one');
    expect(PlanCorrection.read({'kind': 'Move', 'choices': <String>[]}), isNull, reason: 'a Move picks one');

    expect(PlanRepositoryWarning.read(_labels())?.words, contains('it lacks documentation'));
    for (final (value, why) in [
      ({..._labels(), 'repository': '/home/x:pw'}, 'a repository that is no name'),
      ({..._labels(), 'kind': 'topic labels'}, 'a kind that is no name'),
      (
        {
          ..._labels(),
          'missing': ['documentation', 'documentation'],
        },
        'a label twice',
      ),
      (
        {
          ..._labels(),
          'defaults': ['bug\u202e'],
        },
        'a label that is no name',
      ),
      (
        {
          ..._labels(),
          'defaults': ['x' * 51],
        },
        'a label too long',
      ),
      (
        {
          ..._labels(),
          'missing': [for (var at = 0; at <= PlanRepositoryWarning.mostTopicLabels; at++) 'l$at'],
        },
        'more labels than there are topic labels',
      ),
      ({..._labels(), 'differing': null}, 'no list'),
    ]) {
      expect(PlanRepositoryWarning.read(value), isNull, reason: why);
    }
    expect(
      PlanRepositoryWarning.readAll([for (var at = 0; at <= PlanRepositoryWarning.mostWarnings; at++) _labels()]),
      isEmpty,
      reason: 'more warnings than a board sends',
    );
    expect(PlanRepositoryWarning.readAll([_labels(), 'no warning', _labels('repo-b')]).map((each) => each.repository), [
      'repo-a',
      'repo-b',
    ], reason: 'one that cannot be read is left out');
  });
}
