import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart' show mockVersionedStories;
import 'package:porelo_planning/porelo_planning.dart';

import 'support/fake_planning.dart';
import 'support/story_work_support.dart';

const _status = '11111111-1111-4111-8111-111111111111';
const _size = '22222222-2222-4222-8222-222222222222';
const _halted = '33333333-3333-4333-8333-333333333333';
const _older = '44444444-4444-4444-8444-444444444444';
const _oldest = '55555555-5555-4555-8555-555555555555';

const _desktop = Size(1400, 1600);

Map<String, dynamic> _unreachable(int at) => {
  'at': '2026-11-25T15:5$at:54Z',
  'result': 'HELD',
  'reason': 'GitHub could not be reached.',
};

Map<String, dynamic> _command(String save, String target, String reason) => {
  'id': '$save/0',
  'kind': 'WRITE',
  'target': target,
  'state': 'HELD',
  'reason': reason,
  'subject': '',
};

/// A status save GitHub could not take, with GitHub's answer and request id; a size save folded three times; and a
/// halted run's save, which is never resent.
List<Map<String, dynamic>> _held() => [
  {
    'id': _status,
    'saves': [_status],
    'reason': 'GitHub could not be reached.',
    'changes': [
      {'target': 'I1', 'field': 'state', 'now': 'Backlog', 'nowAt': '2026-11-25T15:50:00Z', 'saved': 'Review'},
    ],
    'answer': {
      'status': 502,
      'text': 'Something went wrong while executing your query.',
      'requestId': 'FC0C:CCC8A:10A57B:1BDE8A:6B0705F7',
    },
    'tries': [_unreachable(9)],
    'run': false,
    'earlier': false,
    'resendable': true,
    'commands': [_command(_status, 'I1', 'Unreachable')],
  },
  {
    'id': _size,
    'saves': [_oldest, _older, _size],
    'reason': 'GitHub could not be reached.',
    'changes': [
      {'target': 'I2', 'field': 'size', 'saved': 'M'},
    ],
    'tries': [_unreachable(1), _unreachable(2), _unreachable(3)],
    'run': false,
    'earlier': false,
    'resendable': true,
    'commands': [_command(_size, 'I2', 'Unreachable')],
  },
  {
    'id': _halted,
    'saves': [_halted],
    'reason': 'Only part of the save may have been written to S1.',
    'changes': [
      {'target': 'S1', 'field': 'state', 'saved': 'Finished'},
    ],
    'tries': [
      {'at': '2026-11-25T16:00:00Z', 'result': 'REFUSED', 'reason': 'Only part of the save may have been written.'},
    ],
    'run': true,
    'earlier': false,
    'resendable': false,
    'commands': [_command(_halted, 'S1', 'PartlySaved')],
  },
];

/// The small board with a link on each issue, so a held save names its issue as a link.
Map<String, dynamic> _linkedBoard() {
  final board = smallBoard();
  return {
    ...board,
    'stories': [
      for (final story in (board['stories'] as List).cast<Map<String, dynamic>>())
        {
          ...story,
          'issues': [
            for (final issue in (story['issues'] as List).cast<Map<String, dynamic>>())
              {...issue, 'link': 'https://github.com/o/repo/issues/${(issue['id'] as String).substring(1)}'},
          ],
        },
    ],
  };
}

class _Source extends FakePlanningSource {
  _Source(super.board);

  final resent = <String>[];
  final dropped = <String>[];
  final _whole = StreamController<PlanningSnapshot>.broadcast();

  PlanningSnapshot _snapshot() => PlanningSnapshot.from({...board, 'stories': mockVersionedStories(board)});

  void _without(String save) {
    board = {
      ...board,
      'heldSaves': [
        for (final held in (board['heldSaves'] as List).cast<Map<String, dynamic>>())
          if (held['id'] != save) held,
      ],
    };
    _whole.add(_snapshot());
  }

  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    late final StreamController<PlanningSnapshot> replies;
    StreamSubscription<PlanningSnapshot>? changes;
    replies = StreamController<PlanningSnapshot>(
      onListen: () {
        replies.add(_snapshot());
        changes = _whole.stream.listen(replies.add);
      },
      onCancel: () => changes?.cancel(),
    );
    return replies.stream;
  }

  @override
  Future<String> resendSave(String project, String save) async {
    resent.add(save);
    _without(save);
    return board['revision'] as String;
  }

  @override
  Future<void> dropSave(String project, String save) async {
    dropped.add(save);
    _without(save);
  }

  @override
  Future<void> close() async {
    await _whole.close();
    await super.close();
  }
}

Future<({_Source source, List<Uri> opened})> _shown(
  WidgetTester tester, {
  List<Map<String, dynamic>>? held,
  Size size = _desktop,
  Map<String, dynamic>? board,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final source = _Source({...(board ?? _linkedBoard()), 'heldSaves': held ?? _held()});
  final client = FakePlanningClient([provider('local', source)]);
  final boards = PlanningBoards(client);
  addTearDown(() async {
    boards.dispose();
    client.dispose();
    await source.close();
  });
  final opened = <Uri>[];
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: PlanningHeldSavesView(board: boards.sources.single, openLink: (link) async => opened.add(link)),
        ),
      ),
    ),
  );
  await settle(tester);
  return (source: source, opened: opened);
}

String _text(WidgetTester tester, String key) {
  final widget = tester.widget(find.byKey(Key(key)));
  return switch (widget) {
    final Text text => text.data ?? text.textSpan!.toPlainText(),
    final SelectableText text => text.data ?? text.textSpan!.toPlainText(),
    _ => throw StateError('$key is no text'),
  };
}

void main() {
  // Harm: the person sees the held saves run on as plain lines, cannot tell what each changes or why it waits, and has
  // to act on each one alone, as the operator found on the built screen.
  testWidgets('the section explains itself and offers Resend all and Drop all at its top right', (tester) async {
    await _shown(tester);

    expect(
      _text(tester, 'held-saves-explanation demo'),
      'Saves made on the board that GitHub did not accept. Nothing is lost: each waits here until you resend or '
      'drop it.',
    );
    expect(
      find.text('Resend all (2)'),
      findsOneWidget,
      reason: 'the two saves the planner resends, not the halted one',
    );
    expect(find.text('Drop all'), findsOneWidget);
    final title = tester.getRect(find.text('Held saves · demo'));
    final resendAll = tester.getRect(find.byKey(const Key('held-saves-resend-all demo')));
    final dropAll = tester.getRect(find.byKey(const Key('held-saves-drop-all demo')));
    final firstCard = tester.getRect(find.byKey(const Key('held-save $_status')));
    expect(resendAll.left, greaterThan(title.right), reason: 'right of the title');
    expect(dropAll.left, greaterThan(resendAll.right), reason: 'Drop all last');
    expect(dropAll.right, greaterThan(firstCard.right - 40), reason: 'at the section\'s right edge');
    expect(resendAll.bottom, lessThan(firstCard.top), reason: 'above the saves');
  });

  // Harm: the saves run into each other, and the person cannot tell where one ends.
  testWidgets('each held save is a bordered card titled by its change, with its issue as a link', (tester) async {
    final shown = await _shown(tester);

    for (final save in [_status, _size, _halted]) {
      final card = tester.widget<Container>(find.byKey(Key('held-save $save')));
      expect((card.decoration! as BoxDecoration).border, isNotNull, reason: 'a card of its own');
    }
    expect(_text(tester, 'held-save-title $_status'), 'Status → Review');
    expect(_text(tester, 'held-save-title $_size'), 'Size → M');
    expect(_text(tester, 'held-save-issue $_size'), 'I2');
    expect(
      tester.getRect(find.byKey(const Key('held-save $_status'))).bottom,
      lessThanOrEqualTo(tester.getRect(find.byKey(const Key('held-save $_size'))).top),
      reason: 'one card under the other',
    );

    await tester.tap(find.byKey(const Key('held-save-issue $_size')));
    await settle(tester);
    expect(shown.opened, [Uri.parse('https://github.com/o/repo/issues/2')], reason: 'the issue opens on GitHub');
  });

  // Harm: why a save waits and how often it was tried hide in sentences the person must read to the end.
  testWidgets('the chips name Held, the short reason and the tries', (tester) async {
    await _shown(tester);

    expect(_text(tester, 'held-save-chip $_status held'), 'Held');
    expect(_text(tester, 'held-save-chip $_status reason'), 'GitHub unreachable');
    expect(_text(tester, 'held-save-chip $_status tries'), '1 try');
    expect(_text(tester, 'held-save-chip $_size tries'), '3 tries, same change');
    expect(_text(tester, 'held-save-chip $_halted reason'), 'Partly saved');
    final title = tester.getRect(find.byKey(const Key('held-save-title $_status')));
    final chip = tester.getRect(find.byKey(const Key('held-save-chip $_status held')));
    expect(chip.top, greaterThanOrEqualTo(title.top - 4), reason: 'on the card\'s head');
    expect(
      find.byKey(const Key('held-save-resend $_status')),
      findsOneWidget,
      reason: 'Resend stays on the closed card',
    );
    expect(find.text('Resend once'), findsNothing, reason: 'a folded card says Resend, its fold note says the rest');
  });

  // Harm: the details are flat lines the person cannot read: what GitHub holds against what they saved, GitHub's own
  // words, the request id GitHub's support asks for, and what Resend and Drop do.
  testWidgets('Details open four blocks: the change, why it is held, the tries and what you can do', (tester) async {
    await _shown(tester);
    await tester.tap(find.byKey(const Key('held-save-open $_status')));
    await settle(tester);

    expect(
      [
        for (final block in ['change', 'why', 'tries', 'do']) _text(tester, 'held-save-block $_status $block'),
      ],
      ['What this save changes', 'Why it is held', 'Tries', 'What you can do'],
    );
    final blocks = [
      for (final block in ['change', 'why', 'tries', 'do'])
        tester.getRect(find.byKey(Key('held-save-block $_status $block'))).top,
    ];
    expect(blocks, orderedEquals([...blocks]..sort()), reason: 'in this order, one under the other');

    expect(find.text('Field'), findsOneWidget);
    expect(find.text('On GitHub now'), findsOneWidget);
    expect(find.text('Your save'), findsOneWidget);
    expect(_text(tester, 'held-save-field $_status 0'), 'Status');
    expect(_text(tester, 'held-save-before $_status 0'), 'Backlog');
    expect(
      tester.widget<Text>(find.byKey(const Key('held-save-before $_status 0'))).style?.decoration,
      TextDecoration.lineThrough,
      reason: 'GitHub\'s value struck through',
    );
    expect(_text(tester, 'held-save-after $_status 0'), 'Review');
    expect(_text(tester, 'held-save-where $_status'), 'Moves I1 on the board of project demo.');
    final field = tester.getRect(find.byKey(const Key('held-save-field $_status 0')));
    final after = tester.getRect(find.byKey(const Key('held-save-after $_status 0')));
    expect((field.center.dy - after.center.dy).abs(), lessThan(4), reason: 'a table row');

    expect(_text(tester, 'held-save-why $_status'), 'GitHub could not be reached.');
    final raw = tester.widget<SelectableText>(find.byKey(const Key('held-save-answer $_status')));
    expect(raw.data, 'HTTP 502 · Something went wrong while executing your query.');
    expect(raw.style?.fontFamily, 'monospace', reason: 'GitHub\'s raw answer in monospace');
    expect(find.text('GitHub request id'), findsOneWidget);
    expect(_text(tester, 'held-save-request $_status'), 'FC0C:CCC8A:10A57B:1BDE8A:6B0705F7');
    expect(find.byKey(const Key('held-save-copy-request $_status')), findsOneWidget);

    expect(_text(tester, 'held-save-try $_status 0'), endsWith(' · saved on the board · held'));
    expect(
      _text(tester, 'held-save-help $_status'),
      'Resend once GitHub answers again; the save is sent as it is shown above. Drop it if the change is no longer '
      'wanted: the board then shows GitHub\'s value again.',
    );
    expect(find.byKey(const Key('held-save-copy $_status')), findsOneWidget, reason: 'Copy all details');
  });

  // Harm: the person cannot see that one card stands for several saves of the same change, or what Resend and Drop do
  // to them.
  testWidgets('a folded card names its tries and what Resend and Drop do to the saves it folds', (tester) async {
    await _shown(tester);
    await tester.tap(find.byKey(const Key('held-save-open $_size')));
    await settle(tester);

    expect(
      [for (var at = 0; at < 3; at++) _text(tester, 'held-save-try $_size $at').split(' · ')[1]],
      ['saved on the board', 'saved again', 'saved again'],
    );
    expect(
      _text(tester, 'held-save-fold $_size'),
      'Three saves of the same change are folded into this line. Resend sends it once; Drop drops all three.',
    );
    expect(_text(tester, 'held-save-before $_size 0'), 'not read', reason: 'a value never read says so');
  });

  // Harm: the person must resend or drop each held save alone, or Resend all also sends what the planner never
  // resends, or Drop all drops every save at one slip.
  testWidgets('Resend all resends what the planner resends, and Drop all asks first', (tester) async {
    final shown = await _shown(tester);

    await tester.tap(find.byKey(const Key('held-saves-resend-all demo')));
    await settle(tester);
    expect(shown.source.resent, [_status, _size]);
    expect(find.byKey(const Key('held-save $_halted')), findsOneWidget, reason: 'the halted run\'s save stays');

    await tester.tap(find.byKey(const Key('held-saves-drop-all demo')));
    await settle(tester);
    expect(shown.source.dropped, isEmpty, reason: 'nothing dropped before the person agrees');
    await tester.tap(find.byKey(const Key('held-saves-drop-all-cancel demo')));
    await settle(tester);
    expect(shown.source.dropped, isEmpty);

    await tester.tap(find.byKey(const Key('held-saves-drop-all demo')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('held-saves-drop-all-confirm demo')));
    await settle(tester);
    expect(shown.source.dropped, [_halted]);
    expect(find.byKey(const Key('held-saves demo')), findsNothing, reason: 'the section goes once nothing is held');
  });

  // Harm: on a phone the card's buttons squeeze its title into a column of letters, or its chips overflow.
  testWidgets('at phone width a card keeps its title on the card\'s width, its buttons under it', (tester) async {
    await _shown(tester, size: const Size(390, 2400));
    await tester.tap(find.byKey(const Key('held-save-open $_status')));
    await settle(tester);

    expect(tester.takeException(), isNull, reason: 'nothing overflows');
    final card = tester.getRect(find.byKey(const Key('held-save $_status')));
    final title = tester.getRect(find.byKey(const Key('held-save-title $_status')));
    final details = tester.getRect(find.byKey(const Key('held-save-open $_status')));
    expect(title.height, lessThan(30), reason: 'the title on one line');
    expect(details.top, greaterThan(title.bottom), reason: 'the buttons under the title');
    expect(card.right, lessThanOrEqualTo(390));
  });

  // Harm: Resend all or Drop all reaches a line whose folded saves are not known, leaving saves held unseen.
  testWidgets('Resend all and Drop all leave out a line whose saves cannot be read', (tester) async {
    final held = _held();
    final shown = await _shown(
      tester,
      held: [
        {...held[0], 'saves': <dynamic>[]},
        held[1],
      ],
    );

    expect(find.text('Resend all (1)'), findsOneWidget);
    await tester.tap(find.byKey(const Key('held-saves-resend-all demo')));
    await settle(tester);
    expect(shown.source.resent, [_size]);
    expect(find.byKey(const Key('held-save $_status')), findsOneWidget);
    expect(find.byKey(const Key('held-saves-drop-all demo')), findsNothing, reason: 'nothing left Drop all reaches');
  });

  // Harm: the person agrees to drop "2 saves" and drops more, since a folded line holds several.
  testWidgets('Drop all names the saves a folded line holds, and drops them all', (tester) async {
    final shown = await _shown(tester);
    await tester.tap(find.byKey(const Key('held-saves-drop-all demo')));
    await settle(tester);

    expect(find.text('Drop all 5 held saves?'), findsOneWidget, reason: 'one, three folded, and one');
    await tester.tap(find.byKey(const Key('held-saves-drop-all-confirm demo')));
    await settle(tester);
    expect(shown.source.dropped, [_status, _size, _halted]);
  });

  // Harm: a link of another scheme opens a file or a program instead of GitHub's page.
  testWidgets('an issue whose link is no web page shows without a link', (tester) async {
    final board = _linkedBoard();
    final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
    final shown = await _shown(
      tester,
      board: {
        ...board,
        'stories': [
          {
            ...stories.first,
            'issues': [
              for (final issue in (stories.first['issues'] as List).cast<Map<String, dynamic>>())
                {...issue, 'link': 'file:///etc/passwd'},
            ],
          },
          ...stories.skip(1),
        ],
      },
    );

    await tester.tap(find.byKey(const Key('held-save-issue $_status')));
    await settle(tester);
    expect(shown.opened, isEmpty);
    expect(
      find.ancestor(of: find.byKey(const Key('held-save-issue $_status')), matching: find.byType(InkWell)),
      findsNothing,
    );
  });

  // Harm: a screen reader names the issue link but cannot open it, or cannot tell whether the details are open.
  testWidgets('the issue link and Details tell a screen reader what they do', (tester) async {
    final semantics = tester.ensureSemantics();
    final shown = await _shown(tester);

    expect(find.bySemanticsLabel('Open I2 on GitHub'), findsOneWidget);
    tester.semantics.tap(find.semantics.byLabel('Open I2 on GitHub'));
    await settle(tester);
    expect(shown.opened, [Uri.parse('https://github.com/o/repo/issues/2')]);
    expect(
      tester.getSemantics(find.bySemanticsLabel(RegExp(r'^Details of I1'))),
      isSemantics(isExpanded: false, hasExpandedState: true),
    );
    semantics.dispose();
  });

  // Harm: a save of Status and Size names only the move, and the person drops a size they wanted.
  testWidgets('where a save applies names every field it sets', (tester) async {
    final held = _held();
    await _shown(
      tester,
      held: [
        {
          ...held[0],
          'changes': [
            {'target': 'I1', 'field': 'state', 'saved': 'Review'},
            {'target': 'I1', 'field': 'size', 'saved': 'M'},
          ],
        },
      ],
    );
    await tester.tap(find.byKey(const Key('held-save-open $_status')));
    await settle(tester);

    expect(_text(tester, 'held-save-title $_status'), 'Status → Review and 1 more');
    expect(_text(tester, 'held-save-where $_status'), 'Moves I1 on the board of project demo and sets its size.');
  });

  // Harm: the window's kept row shows held saves another way than the live section, or offers what reaches no planner.
  testWidgets('kept held saves show the same cards and blocks, without Resend or Drop', (tester) async {
    tester.view.physicalSize = _desktop;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: PlanningKeptHeldSavesView(
              saves: [for (final held in _held()) PlanHeldSave.from(held)],
              project: 'demo',
            ),
          ),
        ),
      ),
    );
    await settle(tester);

    expect(_text(tester, 'held-save-title $_size'), 'Size → M');
    expect(_text(tester, 'held-save-chip $_size tries'), '3 tries, same change');
    expect(find.byKey(const Key('held-saves-resend-all demo')), findsNothing);
    expect(find.byKey(const Key('held-save-resend $_status')), findsNothing);
    expect(find.byKey(const Key('held-save-drop $_status')), findsNothing);
    await tester.tap(find.byKey(const Key('held-save-open $_status')));
    await settle(tester);
    expect(_text(tester, 'held-save-block $_status do'), 'What you can do');
    expect(find.byKey(const Key('held-save-copy $_status')), findsOneWidget);
  });
}
