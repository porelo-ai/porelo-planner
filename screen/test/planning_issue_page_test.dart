import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/story_work_support.dart';

const _revision = 'a62040bff77614f3b4c6a5d6d5bdfd33abcfc215';

Map<String, dynamic> _issue(String id, {List<String> blockedBy = const [], Map<String, dynamic> more = const {}}) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': 'Backlog',
  'size': 'S',
  'blockedBy': blockedBy,
  'parent': null,
  'link': 'https://example.org/issues/$id',
  'worker': 'AGENT',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
  'findings': <Map<String, dynamic>>[],
  ...more,
};

Map<String, dynamic> _board({
  String revision = _revision,
  List<Map<String, dynamic>> first = const [],
  List<Map<String, dynamic>>? second,
}) => {
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
  ],
  'phases': [
    {
      'name': 'All',
      'states': ['Backlog'],
    },
  ],
  'work': <dynamic>[],
  'faults': <dynamic>[],
  'stories': [
    {
      'id': 'ST1',
      'title': 'A story',
      'state': 'Backlog',
      'priority': 'High',
      'link': 'https://example.org/stories/ST1',
      'issues': first.isEmpty ? [_issue('PL1')] : first,
    },
    if (second != null)
      {
        'id': 'ST2',
        'title': 'Another story',
        'state': 'Backlog',
        'priority': 'High',
        'link': 'https://example.org/stories/ST2',
        'issues': second,
      },
  ],
  'revision': revision,
  'repositories': ['repo'],
};

/// The issue page of [issue] under the story [story], and each issue it opened, with the story it named.
Future<List<(String?, String)>> _page(
  WidgetTester tester,
  Map<String, dynamic> data, {
  String story = 'ST1',
  String issue = 'PL1',
}) async {
  final opened = <(String?, String)>[];
  final source = MockPlanningSource()..boards['demo'] = data;
  final board = PlanningBoard(source, 'demo');
  await tester.runAsync(() => Future<void>.delayed(Duration.zero));
  final detail = PlanningDetail(board, story, issue);
  addTearDown(() async {
    detail.dispose();
    board.dispose();
    await tester.runAsync(source.end);
  });
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: ListenableBuilder(
            listenable: detail,
            builder: (_, _) => PlanningIssueView(
              detail: detail,
              onStory: (_) {},
              onIssue: (id) => opened.add((null, id)),
              onIssueOf: (story, id) => opened.add((story, id)),
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
  return opened;
}

List<String> _copies(WidgetTester tester) {
  final copied = <String>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == 'Clipboard.setData') copied.add((call.arguments as Map)['text'] as String);
    return null;
  });
  addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
  return copied;
}

void main() {
  // Harm: the person copies a shortened revision, which can name another commit, and checks out the wrong work.
  testWidgets('the copy button puts the whole revision on the clipboard', (tester) async {
    final copied = _copies(tester);
    await _page(tester, _board());
    expect(tester.widget<Text>(find.byKey(const Key('planning-revision'))).data, _revision.substring(0, 7));
    await tester.tap(find.byKey(const Key('planning-revision-copy')));
    await tester.pump();
    expect(copied, [_revision], reason: 'the whole revision, not the seven characters shown');
  });

  // Harm: a revision carrying a line break or a direction character acts where the person pastes it.
  testWidgets('the copied revision carries no control or direction character', (tester) async {
    final copied = _copies(tester);
    await _page(tester, _board(revision: 'a62040bff7\u202e\nrm -rf x\x1b'));
    await tester.tap(find.byKey(const Key('planning-revision-copy')));
    await tester.pump();
    expect(copied, hasLength(1));
    expect(copied.single, isNot(matches(RegExp('[\u202e\n\x1b]'))), reason: 'cleaned before it is copied');
    expect(copied.single, startsWith('a62040bff7'));
  });

  // Harm: a code block in what must be true loses its lines, so its commands are not shown.
  testWidgets('a code block in what must be true keeps its lines while a paragraph flows', (tester) async {
    await _page(
      tester,
      _board(
        first: [
          _issue('PL1', more: {'whatMustBeTrue': 'It holds,\nin two lines.\n\n```\ngit status\ngit diff\n```'}),
        ],
      ),
    );
    final shown = [for (final text in tester.widgetList<RichText>(find.byType(RichText))) text.text.toPlainText()]
        .join('\n');
    expect(shown, contains('It holds, in two lines.'), reason: 'the wrapped paragraph flows');
    expect(shown, contains('git status\ngit diff'), reason: 'the code keeps its lines');
  });

  // Harm: a waited-on issue of another story opens under this page's story, where it is not.
  testWidgets('a waited-on issue opens under the story the board holds it in', (tester) async {
    final opened = await _page(
      tester,
      _board(
        first: [_issue('PL3')],
        second: [
          _issue('PL1', blockedBy: ['PL2']),
          _issue('PL2'),
        ],
      ),
    );
    await tester.tap(find.byKey(const Key('planning-blocked-by PL2')));
    expect(opened, [('ST2', 'PL2')], reason: 'opened under ST2, which holds it');
  });

  // Harm: a description loses its first line when that line is not the heading the page's header repeats.
  test('only a first Markdown heading opening with the issue is left out of its description', () {
    expect(planningWithoutHeading('# AB1 — A title\n\nThe text.', 'AB1', 'MARKDOWN'), '\nThe text.');
    const longer = '# AB12 — Another\n\nText.'; // not-a-citation
    for (final text in [
      longer,
      '# Why\n\nText.',
      'Text first.\n# AB1 — Later',
      '## AB1x',
      '# AB1.1 — Sub',
      '# Why AB1',
    ]) {
      expect(planningWithoutHeading(text, 'AB1', 'MARKDOWN'), text, reason: 'kept: $text');
    }
    expect(planningWithoutHeading('# Context — x\n\nText.', '', 'MARKDOWN'), '# Context — x\n\nText.');
    expect(planningWithoutHeading('# AB1 note\nBody.', 'AB1', 'JIRA_WIKI'), '# AB1 note\nBody.');
  });

  // Harm: a direction override in an id or a task's name reorders what a tooltip or a link says.
  testWidgets('a task link and a given state\'s tooltip show no control or direction character', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              PlanningTaskLink(name: 'build\u202e\x1b', onOpen: () {}, unavailable: 'gone\u202e'),
              planningGivenMark(
                PlanStory.from({
                  'id': 'ST1',
                  'title': 'A story',
                  'state': 'Review',
                  'stateGiven': true,
                  'misfits': ['PL1\u202e'],
                  'priority': 'High',
                  'issues': [_issue('PL1\u202e')],
                }),
              )!,
            ],
          ),
        ),
      ),
    );
    final said = [
      for (final tip in tester.widgetList<Tooltip>(find.byType(Tooltip))) tip.message ?? '',
      for (final text in tester.widgetList<Text>(find.byType(Text))) text.data ?? '',
    ];
    expect(said.where((text) => RegExp('[\u202e\x1b]').hasMatch(text)), isEmpty);
  });
}
