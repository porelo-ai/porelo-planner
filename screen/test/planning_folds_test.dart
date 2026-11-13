import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

Map<String, dynamic> _story(
  String id,
  String state, {
  String? size,
  bool needsYou = false,
  bool readingStopped = false,
  String issueSize = 'S',
}) => {
  'id': id,
  'title': 'Title of $id',
  'state': state,
  'priority': 'High',
  'size': size,
  'issues': [
    {
      'id': 'I$id',
      'repository': 'repo',
      'title': 'Issue of $id',
      'state': state,
      'size': issueSize,
      'blockedBy': <String>[],
      'needsYou': needsYou,
      'readingStopped': readingStopped,
    },
  ],
};

Map<String, dynamic> _board(List<Map<String, dynamic>> stories) => {
  'states': [
    {'name': 'Ready', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Review', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'Work',
      'states': ['Ready', 'Building', 'Review'],
    },
  ],
  'stories': stories,
  'revision': 'r${stories.length}',
};

Future<(MockPlanningSource, PlanningBoards)> _show(
  WidgetTester tester,
  List<Map<String, dynamic>> stories, {
  Map<String, dynamic>? board,
}) async {
  tester.view
    ..physicalSize = const Size(1920, 2000)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = MockPlanningSource()..boards['demo'] = board ?? _board(stories);
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
      home: Scaffold(body: planningView(boards: boards)),
    ),
  );
  await settle(tester);
  return (backend, boards);
}

Future<void> _press(WidgetTester tester, String key) async {
  tester.widget<IconButton>(find.byKey(Key(key))).onPressed!();
  await settle(tester);
}

void main() {
  // Harm: a person who opens Planning sees no story and takes the project for having no work.
  testWidgets('a first open shows the cards of every column that holds open stories at full width', (tester) async {
    await _show(
      tester,
      const [],
      board: {
        'states': [
          {'name': 'Idea', 'kind': 'WAITING'},
          {'name': 'Ready', 'kind': 'WAITING'},
          {'name': 'Finished', 'kind': 'CLOSED'},
        ],
        'phases': [
          {
            'name': 'Ideas',
            'states': ['Idea'],
          },
          {
            'name': 'Work',
            'states': ['Ready'],
          },
          {
            'name': 'Done',
            'states': ['Finished'],
          },
        ],
        'stories': [_story('S1', 'Idea'), _story('S2', 'Ready')],
        'revision': 'r2',
      },
    );

    expect(
      tester.getSize(find.byKey(const Key('planning-phase Ideas'))).width,
      tester.getSize(find.byKey(const Key('planning-phase Work'))).width,
      reason: 'the Ideas column opens as wide as any other, its cards whole',
    );
    expect(find.text('Title of S1'), findsOneWidget, reason: 'the idea shows its title on its card');
  });

  // Harm: a fold that hides nothing or narrows the column, or one that hides that a story needs the person.
  testWidgets('a box folded by its own chevron shows its heading alone at the column width, counting who needs you', (
    tester,
  ) async {
    final (_, boards) = await _show(tester, [
      _story('S1', 'Ready', needsYou: true),
      _story('S2', 'Ready'),
      _story('S3', 'Building'),
    ]);
    expect(find.byKey(const Key('planning-card S1')), findsOneWidget, reason: 'every box starts open');
    expect(boards.selected!.preferences.foldedStates, isEmpty, reason: 'In analysis too');
    final open = tester.getSize(find.byKey(const Key('planning-phase Work'))).width;

    await _press(tester, 'planning-fold-state Ready');

    expect(find.byKey(const Key('planning-card S1')), findsNothing, reason: 'the folded box shows no card');
    expect(find.byKey(const Key('planning-card S2')), findsNothing, reason: 'nor a tile');
    expect(find.byKey(const Key('planning-card S3')), findsOneWidget, reason: 'the other box stays open');
    expect(find.byKey(const Key('planning-state-raised Ready')), findsOneWidget, reason: 'S1 still needs you');
    expect(
      tester.getSize(find.byKey(const Key('planning-phase Work'))).width,
      open,
      reason: 'the column keeps its width',
    );

    await _press(tester, 'planning-fold-phase Work');
    expect(find.byKey(const Key('planning-card S1')), findsOneWidget, reason: 'a narrowed column shows its tiles');
  });

  // Harm: a reading that waits on the person stays unseen behind a fold.
  testWidgets('a stopped reading is marked on a folded box, a folded card and a narrowed tile', (tester) async {
    await _show(tester, [_story('S1', 'Ready', readingStopped: true), _story('S2', 'Building')]);

    await _press(tester, 'planning-fold-state Ready');
    expect(find.byKey(const Key('planning-state-raised Ready')), findsOneWidget, reason: 'the folded box counts it');
    await _press(tester, 'planning-fold-state Ready');

    await _press(tester, 'planning-fold-story S1');
    expect(find.byKey(const Key('planning-warning S1')), findsOneWidget, reason: 'the folded card marks it');
    await _press(tester, 'planning-fold-story S1');

    await _press(tester, 'planning-fold-phase Work');
    expect(find.byKey(const Key('planning-needs-you S1')), findsOneWidget, reason: 'the narrowed tile marks it');
    expect(find.byKey(const Key('planning-needs-you S2')), findsNothing, reason: 'and no other');
  });

  // Harm: a person's move refused without a word because the box it came from is folded.
  testWidgets('a move refused out of a folded box is said above the board', (tester) async {
    final (backend, boards) = await _show(tester, [_story('S1', 'Ready'), _story('S2', 'Ready')]);
    final board = boards.selected!;
    await _press(tester, 'planning-fold-state Ready');
    // The story's issue is the project's own and waits, so its move asks nothing.
    backend.boards['demo']!['repositories'] = ['repo'];
    backend
      ..refusedStory = 'S1'
      ..refusalDetail = 'Policy refuses';

    await tester.runAsync(() => board.move('S1', 'Review'));
    await settle(tester);

    expect(backend.moveCalls, hasLength(1), reason: 'the move was sent at once');
    expect(board.pending, isEmpty, reason: 'nothing waits on a refused move');
    expect(
      find.descendant(
        of: find.byKey(const Key('planning-move-error')),
        matching: find.textContaining('Policy refuses'),
        matchRoot: true,
      ),
      findsOneWidget,
      reason: 'the refusal and why',
    );
  });

  // Harm: a fold that reaches other cards hides their work, a stale card hides the unfold, or a fold hides a need.
  testWidgets('folding one card hides its title alone, keeps its need of you, and unfolds again', (tester) async {
    await _show(tester, [_story('S1', 'Ready', needsYou: true), _story('S2', 'Ready')]);

    await _press(tester, 'planning-fold-story S1');

    expect(find.text('Title of S1'), findsNothing, reason: 'the folded card hides its title');
    expect(find.text('Title of S2'), findsOneWidget, reason: 'the card beside it stays open');
    expect(find.byKey(const Key('planning-warning S1')), findsOneWidget, reason: 'it still says it needs you');

    await _press(tester, 'planning-fold-story S1');
    expect(find.text('Title of S1'), findsOneWidget, reason: 'the card is built anew, not served folded');
  });

  // Harm: a fold kept for a story gone from the board folds another story saved under its id, or another project's.
  testWidgets('a fold is kept only for a story the board shows', (tester) async {
    final (backend, boards) = await _show(tester, [_story('S1', 'Ready'), _story('S2', 'Ready')]);
    final board = boards.selected!;

    board.foldStory('S9');
    await _press(tester, 'planning-fold-story S1');
    expect(board.preferences.foldedStories, {'S1'}, reason: 'an id the board does not show is not folded');

    backend.push('demo', _board([_story('S2', 'Ready')]));
    await settle(tester);
    expect(board.preferences.foldedStories, isEmpty, reason: 'the story left the board, and its fold with it');

    await _press(tester, 'planning-fold-story S2');
    backend.boards['other'] = _board([_story('S2', 'Ready')]);
    await tester.runAsync(() => board.changeProject(backend, 'other'));
    expect(board.preferences.foldedStories, isEmpty, reason: 'another project\'s S2 is another story');
  });

  // Harm: a wrong size is planned on, or a server's text is shown as if it were a size.
  testWidgets('a story and an issue show their size as a badge, and nothing else as one', (tester) async {
    final (_, boards) = await _show(tester, [
      _story('S1', 'Ready', size: 'M', issueSize: 'M'),
      _story('S2', 'Ready', size: '-', issueSize: ''),
      _story('S3', 'Ready', size: 'HUGE', issueSize: 'HUGE'),
      _story('Story294', 'Ready', size: 'XL'),
    ]);
    for (final id in ['S1', 'S2', 'S3']) {
      boards.selected!.expand(id);
    }
    await settle(tester);

    String badge(String key) =>
        tester.widget<Text>(find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text))).data!;
    expect(badge('planning-size S1'), 'M', reason: 'the story of size M');
    expect(badge('planning-issue-size IS1'), 'M', reason: 'the issue of size M');
    expect(find.byKey(const Key('planning-size S2')), findsNothing, reason: 'no size, no badge');
    expect(find.byKey(const Key('planning-issue-size IS2')), findsNothing, reason: 'nor for the issue');
    expect(badge('planning-size S3'), '?', reason: 'an unknown size is not shown as one');
    expect(find.textContaining('HUGE'), findsNothing, reason: 'nor is its text');

    await _press(tester, 'planning-fold-phase Work');
    expect(badge('planning-size S1'), 'M', reason: 'the narrowed tile shows it too');
    expect(badge('planning-size Story294'), 'XL', reason: 'beside the longest number, with no overflow');
  });
}
