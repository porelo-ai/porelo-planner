import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

Map<String, dynamic> _issue(String id, List<String> labels) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': 'Backlog',
  'size': 'S',
  'blockedBy': <String>[],
  'parent': null,
  'link': 'https://example.org/issues/$id',
  'worker': 'AGENT',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
  'labels': labels,
};

/// One story carrying a topic label, its issue PL1 [labels]: by default a topic label and a label of the person's own.
Map<String, dynamic> _board({List<String> labels = const ['documentation', 'needs-design']}) => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Implementing', 'kind': 'WORKING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog', 'Implementing', 'Finished'],
    },
  ],
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'Labelled story',
      'state': 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'labels': ['usability'],
      'issues': [_issue('PL1', labels)],
      'work': null,
    },
  ],
  'revision': 'r1',
  'repositories': ['repo'],
};

Future<(MockPlanningSource, PlanningBoard)> _open(WidgetTester tester, {Map<String, dynamic>? data}) async {
  final source = MockPlanningSource()..boards['demo'] = data ?? _board();
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  addTearDown(() async {
    board.dispose();
    await tester.runAsync(source.end);
  });
  return (source, board);
}

Future<PlanningDetail> _show(WidgetTester tester, PlanningBoard board, {String? issue}) async {
  tester.view
    ..physicalSize = const Size(1280, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final detail = PlanningDetail(board, 'ST1', issue);
  addTearDown(detail.dispose);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ListenableBuilder(
            listenable: detail,
            builder: (_, _) => issue == null
                ? PlanningStoryView(
                    detail: detail,
                    onIssue: (_) {},
                    onTask: (_) {},
                    taskUnavailable: (_) => null,
                    openLink: (_) async {},
                  )
                : PlanningIssueView(
                    detail: detail,
                    onStory: (_) {},
                    onIssue: (_) {},
                    onTask: (_) {},
                    taskUnavailable: (_) => null,
                    openLink: (_) async {},
                  ),
          ),
        ),
      ),
    ),
  );
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  await settle(tester);
  return detail;
}

/// Opens the labels of [item], turns each of [toggled] over and saves.
Future<void> _choose(WidgetTester tester, String item, List<String> toggled) async {
  await tester.tap(find.byKey(Key('planning-labels-edit $item')));
  await settle(tester);
  for (final label in toggled) {
    await tester.tap(find.byKey(Key('planning-label-choice $label')));
    await settle(tester);
  }
  await tester.tap(find.byKey(const Key('planning-labels-save')));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
  await settle(tester);
}

List<String> _labelsOf(Map<String, dynamic>? item) => (item!['labels'] as List).cast<String>();

void main() {
  // Harm: a greyed Labels button whose reason only a hover shows, so a person on touch or keyboard never learns why.
  testWidgets('a greyed Labels button says why in text beside it, and nothing overflows at 390 px', (tester) async {
    tester.view
      ..physicalSize = const Size(390, 800)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const why = 'A warning holds this issue back, so its labels cannot be changed now.';
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlanningLabelsEdit(item: 'PL1', labels: const ['tests'], unavailable: why, onSave: (_) {}),
        ),
      ),
    );
    expect(find.text(why), findsOneWidget, reason: 'the reason stands as text, not on hover alone');
    expect(
      tester.widget<TextButton>(find.byKey(const Key('planning-labels-edit PL1'))).onPressed,
      isNull,
      reason: 'the button is greyed',
    );
    expect(tester.takeException(), isNull, reason: 'nothing overflows');

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PlanningLabelsEdit(item: 'PL1', labels: const ['tests'], unavailable: null, onSave: (_) {}),
        ),
      ),
    );
    expect(find.text(why), findsNothing, reason: 'an open button needs no reason');
  });

  // Harm: the labels the person reads differ from those the planner reported, a label of the person's own lost on the
  // way, so a choice of topic labels is made over labels the item does not carry.
  test('an issue and a story read the labels the planner reports, and none when it reports none', () {
    final issue = PlanIssue.from(_issue('PL1', ['documentation', 'needs-design']));
    expect(issue.labels, ['documentation', 'needs-design'], reason: 'every label, the person\'s own among them');
    expect(PlanIssue.withState(issue, 'Finished').labels, issue.labels, reason: 'a move keeps them');
    final story = PlanStory.from(_board()['stories'][0] as Map<String, dynamic>);
    expect(story.labels, ['usability']);
    expect(story.issues.single.labels, ['documentation', 'needs-design'], reason: 'its issues\' own');
    expect(PlanStory.withMoves(story, const {}, 'Finished').labels, ['usability'], reason: 'a move keeps them');
    expect(PlanIssue.from({'id': 'PL2'}).labels, isEmpty, reason: 'none reported reads as none');
    expect(PlanStory.from({'id': 'ST2'}).labels, isEmpty, reason: 'none reported reads as none');
  });

  // Harm: a label of the person's own hidden on the board or the page, so the person sets topic labels without seeing
  // what the item carries.
  testWidgets('a card, an issue row and both pages show every label, the person\'s own among them', (tester) async {
    tester.view
      ..physicalSize = const Size(1280, 1400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = FakePlanningSource(_board());
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
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await settle(tester);
    expect(find.byKey(const Key('planning-label ST1 usability')), findsOneWidget, reason: 'on the story\'s card');
    await tester.tap(find.byKey(const Key('planning-expand ST1')));
    await settle(tester);
    for (final label in ['documentation', 'needs-design']) {
      expect(find.byKey(Key('planning-label PL1 $label')), findsOneWidget, reason: '$label on the issue\'s row');
    }

    final (_, board) = await _open(tester);
    await _show(tester, board);
    expect(find.byKey(const Key('planning-label ST1 usability')), findsOneWidget, reason: 'on the story\'s page');
    expect(find.byKey(const Key('planning-label PL1 needs-design')), findsOneWidget, reason: 'in its issue list');
    await tester.pumpWidget(const SizedBox.shrink());
    await _show(tester, board, issue: 'PL1');
    for (final label in ['documentation', 'needs-design']) {
      expect(find.byKey(Key('planning-label PL1 $label')), findsOneWidget, reason: '$label on the issue\'s page');
    }
  });

  // Harm: a labels edit sent for another item, without the version shown, with a label of the person's own the
  // planner would refuse, or one that takes the person's own label off the item.
  testWidgets('the issue page saves the topic labels chosen against the version shown, the person\'s own kept', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    await _show(tester, board, issue: 'PL1');
    expect(
      find.byKey(const Key('planning-label-choice needs-design')),
      findsNothing,
      reason: 'the edit is not open yet',
    );
    await tester.tap(find.byKey(const Key('planning-labels-edit PL1')));
    await settle(tester);
    for (final label in planningTopicLabels) {
      expect(find.byKey(Key('planning-label-choice $label')), findsOneWidget, reason: '$label is offered');
    }
    expect(
      find.byKey(const Key('planning-label-choice needs-design')),
      findsNothing,
      reason: 'the person\'s own label is not the planner\'s to set',
    );
    expect(find.byKey(const Key('planning-labels-kept')), findsOneWidget, reason: 'it is shown as kept');
    await tester.tap(find.byKey(const Key('planning-labels-cancel')));
    await settle(tester);

    await _choose(tester, 'PL1', ['documentation', 'tests']);
    expect(source.labelsCalls, [
      {
        'project': 'demo',
        'item': 'PL1',
        'story': false,
        'labels': ['tests'],
        'seen': {'PL1': isA<String>()},
      },
    ], reason: 'one call naming the topic labels after the save, against the version of PL1 shown');
    expect(
      _labelsOf(mockPlanningIssue(source.boards['demo']!, 'PL1')),
      unorderedEquals(['needs-design', 'tests']),
      reason: 'the planner saved them and kept the person\'s own',
    );
    expect(find.byKey(const Key('planning-labels-problem')), findsNothing, reason: 'nothing was refused');
  });

  // Harm: a topic label GitHub holds as Tests cannot be taken off, or is sent beside tests, since the screen took it
  // for a label of the person's own.
  testWidgets('a label carried as Tests is the topic label tests: shown chosen, and taken off when left out', (
    tester,
  ) async {
    final (source, board) = await _open(tester, data: _board(labels: ['Tests', 'needs-design']));
    await _show(tester, board, issue: 'PL1');
    expect(find.byKey(const Key('planning-label PL1 Tests')), findsOneWidget, reason: 'shown as the item carries it');
    await tester.tap(find.byKey(const Key('planning-labels-edit PL1')));
    await settle(tester);
    expect(
      tester.widget<FilterChip>(find.byKey(const Key('planning-label-choice tests'))).selected,
      isTrue,
      reason: 'the topic label it carries is chosen',
    );
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-labels-kept'))).data,
      isNot(contains('Tests')),
      reason: 'it is not kept as a label of the person\'s own',
    );
    await tester.tap(find.byKey(const Key('planning-labels-cancel')));
    await settle(tester);

    await _choose(tester, 'PL1', ['tests']);
    expect(source.labelsCalls.single['labels'], isEmpty, reason: 'the topic label left out');
    expect(_labelsOf(mockPlanningIssue(source.boards['demo']!, 'PL1')), [
      'needs-design',
    ], reason: 'taken off, the person\'s own kept');
  });

  // Harm: the mock saves a new revision where the planner saves nothing, so the window's tests pass on a change the
  // planner never makes.
  test('the mock board takes a topic label carried in another case as carried', () async {
    final source = MockPlanningSource()..boards['demo'] = _board(labels: ['Tests', 'needs-design']);
    addTearDown(source.end);
    final versions = mockItemVersions(source.boards['demo']!);
    expect(
      await source.setLabels('demo', 'PL1', ['tests'], {'PL1': versions['PL1']!}, story: false),
      'r1',
      reason: 'the topic labels it carries save nothing',
    );
  });

  // Harm: a label name that hides or turns text is shown or named back as another label than the one it is.
  test('a label name holds no character that hides or turns text, a tag character among them', () async {
    for (final hidden in [
      0x00,
      0x1f,
      0x7f,
      0x85,
      0x9f,
      0xad,
      0x034f,
      0x061c,
      0x180e,
      0x200b,
      0x200f,
      0x202a,
      0x202e,
      0x2060, //
      0x2064,
      0x2066,
      0x206f,
      0xfeff,
      0x2028,
      0x2029,
      0xe0000,
      0xe0001,
      0xe0041,
      0xe007f,
    ]) {
      expect(planningLabelName('a${String.fromCharCode(hidden)}b'), isFalse, reason: 'U+${hidden.toRadixString(16)}');
    }
    for (final name in ['needs-design', 'Größe 🙂', 'a${String.fromCharCode(0xe0080)}b']) {
      expect(planningLabelName(name), isTrue, reason: name);
    }
    final source = HttpPlanningSource(
      Uri.parse('http://127.0.0.1:9'),
      'tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz',
      (project) => PlannerProject('https://example.org/$project.git', 'main'),
    );
    addTearDown(source.close);
    try {
      await source.setLabels('demo', 'repo#7', ['a${String.fromCharCode(0xe0041)}b'], const {}, story: false);
      fail('the label was not refused');
    } on PlanningRefusal catch (refused) {
      expect((refused.name, refused.parameters['label']), ('LabelNotAllowed', ''), reason: 'never named back');
    }
  });

  // Harm: a story's labels written to an issue of the same reference, or not at all.
  testWidgets('the story page saves the story\'s topic labels as a story\'s', (tester) async {
    final (source, board) = await _open(tester);
    await _show(tester, board);
    await _choose(tester, 'ST1', ['security']);
    expect(source.labelsCalls, [
      {
        'project': 'demo',
        'item': 'ST1',
        'story': true,
        'labels': ['security', 'usability'],
        'seen': {'ST1': isA<String>()},
      },
    ], reason: 'one call for the story, against its version shown');
    expect(
      _labelsOf(mockPlanningStory(source.boards['demo']!, 'ST1')),
      unorderedEquals(['security', 'usability']),
      reason: 'the planner saved them',
    );
  });

  // Harm: labels GitHub dropped are taken as saved, or the planner's refusal is lost, so the person believes the item
  // carries labels it does not.
  testWidgets('labels the tracker did not keep, or a label the planner does not set, stand in the screen\'s words', (
    tester,
  ) async {
    final (source, board) = await _open(tester);
    source.labelsFailure = mockRefusal('LabelsNotKept', {'repository': 'repo', 'item': 'PL1'});
    await _show(tester, board, issue: 'PL1');
    await _choose(tester, 'PL1', ['tests']);
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-labels-problem'))).data,
      allOf(contains('repo'), contains('PL1'), contains('did not keep')),
      reason: 'the screen says the labels were not kept, and where',
    );
    expect(_labelsOf(mockPlanningIssue(source.boards['demo']!, 'PL1')), [
      'documentation',
      'needs-design',
    ], reason: 'nothing saved');

    source.labelsFailure = mockRefusal('LabelNotAllowed', {'item': 'PL1', 'label': 'bug'});
    await _choose(tester, 'PL1', ['tests']);
    expect(
      tester.widget<Text>(find.byKey(const Key('planning-labels-problem'))).data,
      allOf(contains('bug'), contains('topic labels')),
      reason: 'the screen names the label refused',
    );
  });

  // Harm: the mock board takes a label the planner refuses, takes the person's own label off, or saves over a change
  // made meanwhile, so the window's tests pass on what the planner never does.
  test('the mock board sets only topic labels, keeps the person\'s own, and checks the version seen', () async {
    final source = MockPlanningSource()..boards['demo'] = _board();
    addTearDown(source.end);
    final versions = mockItemVersions(source.boards['demo']!);
    Future<PlanningRefusal> refusal(Future<Object?> call) async {
      try {
        await call;
      } on PlanningRefusal catch (refused) {
        return refused;
      }
      fail('the call was not refused');
    }

    final refused = await refusal(source.setLabels('demo', 'PL1', ['bug'], {'PL1': versions['PL1']!}, story: false));
    expect((refused.name, refused.parameters['label']), ('LabelNotAllowed', 'bug'), reason: 'outside the topics');
    expect(
      (await refusal(source.setLabels('demo', 'PL1', ['tests'], {'PL1': 'stale'}, story: false))).name,
      'ItemChanged',
      reason: 'a version other than the one it stands at',
    );
    expect(_labelsOf(mockPlanningIssue(source.boards['demo']!, 'PL1')), ['documentation', 'needs-design']);

    final revision = await source.setLabels('demo', 'PL1', ['tests'], {'PL1': versions['PL1']!}, story: false);
    expect(revision, isNot('r1'), reason: 'a change is a new revision');
    expect(_labelsOf(mockPlanningIssue(source.boards['demo']!, 'PL1')), unorderedEquals(['needs-design', 'tests']));
    final again = mockItemVersions(source.boards['demo']!)['PL1']!;
    expect(
      await source.setLabels('demo', 'PL1', ['tests'], {'PL1': again}, story: false),
      revision,
      reason: 'the same labels save nothing',
    );
  });
}
