import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

const _outside = 'Its state is none of the workflow\'s states, so no work starts on it.';
const _noPriority = 'It has no priority, so it is sorted as Medium.';
const _unsectioned = 'Its text has no sections the planner can read; it is shown as it stands.';

Map<String, dynamic> _warning(String kind, String text, {bool holds = false, Object? correction}) => {
  'kind': kind,
  'text': text,
  'holds': holds,
  'correction': correction,
};

Map<String, dynamic> _move(List<String> choices) => {'kind': 'Move', 'choices': choices};

/// The priorities GitHub's issue field holds, as the planner offers them: it has no Lowest.
Map<String, dynamic> _priority() => {
  'kind': 'Priority',
  'choices': ['Highest', 'High', 'Medium', 'Low'],
};

Map<String, dynamic> _issue(String id, String state, {List<Object> warnings = const []}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': 'S',
  'blockedBy': <String>[],
  'link': 'https://example.org/issues/$id',
  'warnings': warnings,
};

/// A source whose correction previews answer another choice than asked: Low.
class _OtherChoice extends MockPlanningSource {
  @override
  Future<PlanningCorrectionPreview> previewCorrection(String project, PlanningCorrection correction) async {
    final asked = await super.previewCorrection(project, correction);
    return PlanningCorrectionPreview(
      revision: asked.revision,
      digest: asked.digest,
      from: asked.from,
      to: 'Low',
      story: asked.story,
      warning: asked.warning,
      targets: asked.targets,
    );
  }
}

/// Drags [id]'s card onto the column of [state] and lets it go.
Future<void> _drop(WidgetTester tester, String id, String state) async {
  final source = find.byKey(Key('planning-card $id'));
  final target = find.byKey(Key('planning-state $state'));
  final drag = await tester.startGesture(tester.getTopLeft(source) + const Offset(20, 20));
  await drag.moveBy(const Offset(0, -20));
  await tester.pump();
  await drag.moveTo(tester.getCenter(find.descendant(of: target, matching: find.text(state))));
  await tester.pump();
  await drag.up();
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

/// A source that counts the board's reads, so a test sees the board read again.
class _Counting extends MockPlanningSource {
  int reads = 0;

  @override
  Future<PlanningSnapshot> stories(String project) {
    reads++;
    return super.stories(project);
  }
}

/// Shows a board of [stories] and the issues in no story [issues] in the states Ready, Building and Finished.
Future<(PlanningBoards, TestHost)> _show(
  WidgetTester tester,
  MockPlanningSource backend,
  List<Map<String, dynamic>> stories, {
  List<Map<String, dynamic>> issues = const [],
}) async {
  tester.view
    ..physicalSize = const Size(2400, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  backend.boards['demo'] = {
    'states': [
      {'name': 'Ready', 'kind': 'WAITING'},
      {'name': 'Building', 'kind': 'WORKING'},
      {'name': 'Finished', 'kind': 'CLOSED'},
    ],
    'phases': [
      {
        'name': 'Work',
        'states': ['Ready', 'Building', 'Finished'],
      },
    ],
    'stories': stories,
    'issues': issues,
    'revision': 'r1',
  };
  final client = MockPlanningClient(
    () => [
      PlanningProvider(name: 'local', connected: true, projects: ['demo'], source: backend),
    ],
    currentProvider: () => 'local',
  );
  final boards = PlanningBoards(client);
  final host = TestHost();
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await backend.end();
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: planningView(boards: boards, host: host),
      ),
    ),
  );
  await settle(tester);
  return (boards, host);
}

Future<void> _answered(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

/// A story with no priority whose store offers to set one.
Map<String, dynamic> _unprioritized() => {
  'id': 'S1',
  'title': 'No priority',
  'state': 'Ready',
  'issues': <Object>[],
  'link': 'https://example.org/stories/S1',
  'warnings': [_warning('NoPriority', _noPriority, correction: _priority())],
};

/// An issue in no story in a state the workflow lacks whose store offers to move it into Ready.
Map<String, dynamic> _parked() => _issue(
  'I9',
  'Parked',
  warnings: [
    _warning('StateOutsideWorkflow', _outside, holds: true, correction: _move(['Ready'])),
  ],
);

/// Opens [id]'s correction menu and picks [choice], from the group of [kind] for a move's.
Future<void> _pick(WidgetTester tester, String id, String choice, {String? kind}) async {
  await tester.tap(find.byKey(Key('planning-correct $id')));
  await settle(tester);
  if (kind != null) {
    await tester.tap(find.widgetWithText(SubmenuButton, kind));
    await settle(tester);
  }
  await tester.tap(find.byKey(Key('planning-correct-to $id $choice')).last);
  await _answered(tester);
}

/// The labels of the groups the open menu shows, in order.
List<String?> _groups(WidgetTester tester) => [
  for (final group in tester.widgetList<SubmenuButton>(find.byType(SubmenuButton))) (group.child! as Text).data,
];

void main() {
  // Harm: an issue's state set without the person seeing what changes, or saved over a version the person never saw.
  testWidgets('a Move correction shows its preview and saves only on confirm, with the versions it showed', (
    tester,
  ) async {
    final backend = MockPlanningSource();
    final (boards, _) = await _show(tester, backend, const [], issues: [_parked()]);
    await tester.tap(find.byKey(const Key('planning-no-story-expand Parked')));
    await settle(tester);

    await _pick(tester, 'I9', 'Ready', kind: 'Waiting');

    expect(backend.previewCalls, hasLength(1), reason: 'the correction is previewed');
    expect(backend.moveCalls, isEmpty, reason: 'nothing is saved before the person confirms');
    expect(find.byKey(const Key('planning-move-warning')), findsOneWidget, reason: 'the preview is shown');
    expect(find.byKey(const Key('planning-correction-warning')), findsOneWidget, reason: 'it says what it sets right');
    final asked = boards.selected!.question!;
    expect(asked.corrects?.kind, 'StateOutsideWorkflow');

    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await _answered(tester);

    final saved = backend.moveCalls.single;
    expect(saved['moves'], [
      {'issue': 'I9', 'to': 'Ready'},
    ]);
    expect(saved['seen'], asked.preview.targets, reason: 'the save sends back the versions its preview showed');
    expect(saved['seen'], contains('I9'));
    expect(saved['digest'], asked.preview.digest, reason: 'the save is bound to the preview the person saw');
  });

  // Harm: a move correction offers its states in one long list, or one the workflow lacks, or picks another state.
  testWidgets('a Move correction groups its states by their kind and moves into the state picked', (tester) async {
    final backend = MockPlanningSource();
    await _show(
      tester,
      backend,
      const [],
      issues: [
        _issue(
          'I9',
          'Parked',
          warnings: [
            _warning(
              'StateOutsideWorkflow',
              _outside,
              holds: true,
              correction: _move(['Finished', 'Nowhere', 'Building', 'Ready']),
            ),
          ],
        ),
      ],
    );
    await tester.tap(find.byKey(const Key('planning-no-story-expand Parked')));
    await settle(tester);

    await tester.tap(find.byKey(const Key('planning-correct I9')));
    await settle(tester);
    expect(_groups(tester), ['Waiting', 'Working', 'Closed'], reason: 'one group per kind; Nowhere is in none');
    expect(find.byKey(const Key('planning-correct-to I9 Ready')), findsNothing, reason: 'the states wait in groups');

    await tester.tap(find.widgetWithText(SubmenuButton, 'Working'));
    await settle(tester);
    expect(find.byKey(const Key('planning-correct-to I9 Building')), findsOneWidget, reason: 'Working holds Building');
    expect(find.byKey(const Key('planning-correct-to I9 Ready')), findsNothing, reason: 'and not a waiting state');
    expect(find.byKey(const Key('planning-correct-to I9 Nowhere')), findsNothing, reason: 'the workflow lacks it');

    await tester.tap(find.widgetWithText(SubmenuButton, 'Waiting'));
    await settle(tester);
    await tester.tap(find.byKey(const Key('planning-correct-to I9 Ready')));
    await _answered(tester);

    expect(backend.previewCalls.single['moves'], [
      {'issue': 'I9', 'to': 'Ready'},
    ], reason: 'the state picked is previewed for that issue');
  });

  // Harm: a priority correction's few choices hidden behind a group.
  testWidgets('a Priority correction lists its choices without a group', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [_unprioritized()]);

    await tester.tap(find.byKey(const Key('planning-correct S1')));
    await settle(tester);

    expect(_groups(tester), isEmpty, reason: 'no group');
    expect(find.byKey(const Key('planning-correct-to S1 High')), findsOneWidget, reason: 'the choices are listed');
  });

  // Harm: a story's priority set without the person seeing what changes, or saved over a version never seen.
  testWidgets('a Priority correction shows its preview and saves only on confirm, with the versions it showed', (
    tester,
  ) async {
    final backend = MockPlanningSource();
    final (boards, _) = await _show(tester, backend, [_unprioritized()]);

    await _pick(tester, 'S1', 'High');

    expect(backend.correctionPreviews.single['correction'], {'story': 'S1', 'warning': 'NoPriority', 'choice': 'High'});
    expect(backend.correctionCalls, isEmpty, reason: 'nothing is saved before the person confirms');
    expect(find.byKey(const Key('planning-correction')), findsOneWidget, reason: 'the preview is shown');
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-correction-change'))).data,
      'S1: none → High',
      reason: 'the planner tells no priority before, so neither does the screen',
    );
    final asked = boards.selected!.correctionQuestion!;

    await tester.tap(find.byKey(const Key('planning-correction-confirm')));
    await _answered(tester);

    final saved = backend.correctionCalls.single;
    expect(saved['correction'], {'story': 'S1', 'warning': 'NoPriority', 'choice': 'High'});
    expect(saved['seen'], asked.preview.targets, reason: 'the save sends back the versions its preview showed');
    expect(saved['seen'], contains('S1'));
    expect(saved['digest'], asked.preview.digest, reason: 'the save is bound to the preview the person saw');
    expect(boards.selected!.stories.single.priority, 'High', reason: 'the board shows the story set right');
    expect(find.byKey(const Key('planning-correct S1')), findsNothing, reason: 'the warning it set right is gone');
  });

  // Harm: the fake tells a priority before that the planner never tells, so the window's tests pass on a line the
  // person never sees.
  testWidgets('a Priority preview tells no priority before, as the planner answers it', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [
      {
        ..._unprioritized(),
        'priority': 'Someday',
        'warnings': [_warning('UnknownPriority', 'Its priority is none the planner knows.', correction: _priority())],
      },
    ]);

    await _pick(tester, 'S1', 'Low');

    expect(tester.widget<Text>(find.byKey(const Key('planning-correction-change'))).data, 'S1: none → Low');
  });

  // Harm: a correction the person cancels is written anyway.
  testWidgets('a cancelled correction sends nothing', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [_unprioritized()]);

    await _pick(tester, 'S1', 'Low');
    await tester.tap(find.byKey(const Key('planning-correction-cancel')));
    await _answered(tester);

    expect(backend.correctionCalls, isEmpty);
    expect(find.byKey(const Key('planning-correction')), findsNothing);
  });

  // Harm: a refused correction leaves the person believing it was saved, or keeps offering it on a board that
  // changed, so the person corrects again over what they did not see.
  for (final (name, parameters) in [
    (
      'ItemChanged',
      <String, dynamic>{
        'changed': ['S1'],
      },
    ),
    (
      'BasisIncomplete',
      <String, dynamic>{
        'missing': ['S1'],
      },
    ),
    ('NotOffered', <String, dynamic>{'project': 'demo'}),
    (
      'CorrectionRefused',
      <String, dynamic>{'item': 'S1', 'warning': 'NoPriority', 'reason': 'The warning no longer stands on it.'},
    ),
  ]) {
    testWidgets('a correction refused as $name is said in the screen\'s words and the board read again', (
      tester,
    ) async {
      final backend = _Counting();
      final (boards, _) = await _show(tester, backend, [_unprioritized()]);
      await _pick(tester, 'S1', 'High');
      backend.moveFailure = mockRefusal(name, parameters);
      final reads = backend.reads;

      await tester.tap(find.byKey(const Key('planning-correction-confirm')));
      await _answered(tester);

      final said = boards.selected!.actionProblem;
      expect(said, startsWith('S1 was not set right: '), reason: 'said as the screen words it');
      expect(said, isNot(contains('urn:')), reason: 'in words, not the refusal\'s raw name');
      expect(find.byKey(const Key('planning-action-error')), findsOneWidget, reason: 'shown on the board');
      expect(backend.reads, greaterThan(reads), reason: 'the board is read again');
      expect(boards.selected!.correctionQuestion, isNull, reason: 'nothing is left asked about');
      expect(boards.selected!.stories.single.priority, isNull, reason: 'the board shows nothing saved');
    });
  }

  // Harm: a refused Move correction is said as an ordinary move, or the board is left as the person last saw it.
  testWidgets('a refused Move correction is said as a correction and the board read again', (tester) async {
    final backend = _Counting();
    final (boards, _) = await _show(tester, backend, const [], issues: [_parked()]);
    await tester.tap(find.byKey(const Key('planning-no-story-expand Parked')));
    await settle(tester);
    await _pick(tester, 'I9', 'Ready', kind: 'Waiting');
    backend.moveFailure = mockRefusal('MoveNotAllowed', {
      'item': 'I9',
      'from': 'Parked',
      'to': 'Ready',
      'reason': 'The body is no correction.',
    });
    final reads = backend.reads;

    await tester.tap(find.byKey(const Key('planning-move-confirm')));
    await _answered(tester);

    expect(boards.selected!.moveProblem, contains('I9 was not set right: The body is no correction.'));
    expect(backend.reads, greaterThan(reads), reason: 'the board is read again');
  });

  // Harm: the planner rewrites a person's text, or offers a write for a text it can only show.
  testWidgets('a text without sections offers only the link to where the item is kept', (tester) async {
    final backend = MockPlanningSource();
    final (_, host) = await _show(tester, backend, [
      {
        'id': 'S1',
        'title': 'Unsectioned',
        'state': 'Ready',
        'priority': 'High',
        'issues': <Object>[],
        'link': 'https://example.org/stories/S1',
        'warnings': [_warning('BodyNotSectioned', _unsectioned)],
      },
    ]);

    expect(find.byKey(const Key('planning-correct S1')), findsNothing, reason: 'no write is offered');
    await tester.tap(find.byKey(const Key('planning-repair S1')));
    await _answered(tester);

    expect(host.opened, [Uri.parse('https://example.org/stories/S1')]);
    expect(backend.correctionPreviews, isEmpty);
    expect(backend.previewCalls, isEmpty);
  });

  // Harm: a correction of a kind the window does not know, or one it cannot read, is offered as a write it cannot
  // show the person.
  testWidgets('a correction of an unknown kind, or one the window cannot read, is offered as none', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [
      {
        ..._unprioritized(),
        'warnings': [
          _warning('NoPriority', _noPriority, correction: {'kind': 'Rewrite', 'choices': planningPriorities}),
        ],
      },
      {
        ..._unprioritized(),
        'id': 'S2',
        'warnings': [
          _warning(
            'NoPriority',
            _noPriority,
            correction: {
              'kind': 'Priority',
              'choices': ['High', 'High'],
            },
          ),
        ],
      },
    ]);

    expect(find.byKey(const Key('planning-correct S1')), findsNothing, reason: 'an unknown kind');
    expect(find.byKey(const Key('planning-correct S2')), findsNothing, reason: 'a choice named twice');
  });

  // Harm: a priority confirmed that the person never asked for, because the preview answered another one.
  testWidgets('a preview of another choice than asked is refused and nothing is saved', (tester) async {
    final backend = _OtherChoice();
    final (boards, _) = await _show(tester, backend, [_unprioritized()]);

    await _pick(tester, 'S1', 'High');

    expect(boards.selected!.correctionQuestion, isNull, reason: 'nothing is asked about');
    expect(find.byKey(const Key('planning-correction-confirm')), findsNothing, reason: 'nothing to confirm');
    expect(boards.selected!.actionProblem, startsWith('S1 was not set right: '));
    expect(backend.correctionCalls, isEmpty, reason: 'nothing is saved');
  });

  // Harm: a correction confirmed on a board a move has already changed, so its preview no longer says what it does.
  testWidgets('a move made while a correction is asked about drops the correction unsaved', (tester) async {
    final backend = MockPlanningSource();
    final (boards, _) = await _show(tester, backend, [
      _unprioritized(),
      {
        'id': 'S2',
        'title': 'Other',
        'state': 'Ready',
        'priority': 'High',
        'issues': <Object>[],
        'link': 'https://example.org/stories/S2',
        'warnings': <Object>[],
      },
    ]);
    await _pick(tester, 'S1', 'High');
    expect(boards.selected!.correctionQuestion, isNotNull);

    await _drop(tester, 'S2', 'Finished');
    if (find.byKey(const Key('planning-move-confirm')).evaluate().isNotEmpty) {
      await tester.tap(find.byKey(const Key('planning-move-confirm')));
      await _answered(tester);
    }

    expect(boards.selected!.correctionQuestion, isNull, reason: 'the correction is dropped');
    expect(find.byKey(const Key('planning-correction')), findsNothing, reason: 'its bar is gone');
    expect(backend.correctionCalls, isEmpty, reason: 'its save is never sent');
  });

  // Harm: a correction confirmed on a board that changed under it, whose preview no longer says what it does.
  testWidgets('a newer board drops the correction asked about unsaved', (tester) async {
    final backend = MockPlanningSource();
    final (boards, _) = await _show(tester, backend, [_unprioritized()]);
    await _pick(tester, 'S1', 'High');
    expect(boards.selected!.correctionQuestion, isNotNull);

    backend.push('demo', {...backend.board('demo'), 'revision': 'r2'});
    await _answered(tester);

    expect(boards.selected!.snapshot!.revision, 'r2', reason: 'the newer board is shown');
    expect(boards.selected!.correctionQuestion, isNull, reason: 'the correction is dropped');
    expect(backend.correctionCalls, isEmpty, reason: 'its save is never sent');
  });

  // Harm: a priority previewed for a choice the person made on a board that changed since the menu opened.
  testWidgets('a story correction chosen on a board that changed since the menu opened is refused', (tester) async {
    final backend = MockPlanningSource();
    final (boards, _) = await _show(tester, backend, [_unprioritized()]);
    final board = boards.selected!;

    await board.correctStory('S1', 'NoPriority', 'High', revision: 'r0');
    await _answered(tester);

    expect(backend.correctionPreviews, isEmpty, reason: 'nothing is previewed');
    expect(board.correctionQuestion, isNull);
    expect(board.actionProblem, startsWith('S1 was not set right: '));
  });

  // Harm: a text the planner cannot read stays without the way to repair it while a correction is offered too.
  testWidgets('a story with a correction and a text without sections shows both the menu and the link', (tester) async {
    final backend = MockPlanningSource();
    await _show(tester, backend, [
      {
        ..._unprioritized(),
        'warnings': [
          _warning('NoPriority', _noPriority, correction: _priority()),
          _warning('BodyNotSectioned', _unsectioned),
        ],
      },
    ]);

    expect(find.byKey(const Key('planning-correct S1')), findsWidgets, reason: 'the correction is offered');
    expect(find.byKey(const Key('planning-repair S1')), findsWidgets, reason: 'and the link to repair its text');
  });

  // Harm: a correction the planner offers within the description's bounds is dropped by the window.
  test('a correction with valid choices is kept', () {
    final kept = PlanCorrection.read({
      'kind': 'Move',
      'choices': [for (var at = 0; at < PlanCorrection.mostChoices; at++) 'State $at'],
    });
    expect(kept?.choices, hasLength(PlanCorrection.mostChoices));
    expect(PlanCorrection.read(_priority())?.choices, ['Highest', 'High', 'Medium', 'Low']);
  });

  // Harm: an answer that names more choices than any workflow holds, or a choice that is no name, reaches a menu.
  test('a correction is read only within its bounds', () {
    PlanCorrection? read(Object? choices, [Object? kind = 'Move']) =>
        PlanCorrection.read({'kind': kind, 'choices': choices});

    expect(read(['Ready', 'In review'])?.choices, ['Ready', 'In review']);
    expect(read(<String>[]), isNull, reason: 'no choice');
    expect(read([for (var at = 0; at <= PlanCorrection.mostChoices; at++) 'S$at']), isNull, reason: 'too many');
    expect(read(['Ready\u202e']), isNull, reason: 'no name');
    expect(read(['x' * 65]), isNull, reason: 'too long');
    expect(read(['Ready'], 'move'), isNull, reason: 'no kind');
    expect(read('Ready'), isNull, reason: 'no list');
    expect(PlanCorrection.read(null), isNull);
  });
}
