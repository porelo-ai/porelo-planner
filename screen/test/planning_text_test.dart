import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

Future<List<Uri>> _show(WidgetTester tester, String text, {String format = 'MARKDOWN', Object? refusal}) async {
  final opened = <Uri>[];
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: PlanningText(
            text: text,
            format: format,
            openLink: (address) async {
              opened.add(address);
              if (refusal != null) throw refusal;
            },
          ),
        ),
      ),
    ),
  );
  return opened;
}

/// Every piece of text on screen, as the person reads it.
String _shown(WidgetTester tester) =>
    [for (final text in tester.widgetList<RichText>(find.byType(RichText))) text.text.toPlainText()].join('\n');

void main() {
  // Harm: the screen opens what the window would not - another scheme, a plain address, or one carrying a login.
  testWidgets('only an https link with a host and no user part is followed', (tester) async {
    final opened = await _show(
      tester,
      '[plain](http://example.org) [file](file:///etc/passwd) [login](https://user:token@example.org/p) '
      '[script](javascript:alert(1)) [ok](https://example.org/spec)',
    );
    expect(find.byType(InkWell), findsOneWidget, reason: 'one link offered to tap');
    await tester.tap(find.byType(InkWell));
    await tester.pump();
    expect(opened, [Uri.parse('https://example.org/spec')], reason: 'only the https link was followed');
    for (final href in [
      'http://example.org',
      'file:///etc/passwd',
      'https://user:token@example.org/p',
      'javascript:alert(1)',
      'https:///nohost',
      'relative/page.md',
      'https://example.org/\u202eexe',
      'https://example.org/%E2%80%AEexe',
      'https://example.org/%1Bexe',
    ]) {
      expect(planningLinkAddress(href), isNull, reason: '$href is not followed');
    }
  });

  // Harm: a direction override hidden in a link's address, which the parser encodes, makes it open another place.
  testWidgets('a link whose address carries a direction character is not offered', (tester) async {
    await _show(tester, '[raw](https://example.org/\u202eexe) [ref](https://example.org/&#x202e;exe)');
    expect(find.byType(InkWell), findsNothing, reason: 'neither link is offered');
  });

  // Harm: a deeply nested or very long text overflows the stack or holds the window, and the story cannot be read.
  testWidgets('deep nesting and very many blocks are shown plain', (tester) async {
    for (final text in ['${'>' * 20000}a', '${'*' * 20000}a${'*' * 20000}', '- a\n' * 50000]) {
      await _show(tester, text);
      expect(tester.takeException(), isNull, reason: 'no overflow for ${text.length} characters');
      expect(find.byType(SelectableText), findsOneWidget, reason: 'shown plain');
    }
  });

  // Harm: a link the host refuses to open ends in an exception nobody catches.
  testWidgets('a link the host cannot open fails quietly', (tester) async {
    await _show(tester, '[ok](https://example.org/spec)', refusal: StateError('no browser'));
    await tester.tap(find.text('ok', findRichText: true).first);
    await tester.pump();
    expect(tester.takeException(), isNull, reason: 'the refusal is caught');
  });

  // Harm: a picture in a description tells its server who reads the text.
  testWidgets('an image is never fetched and its alternative text stands for it', (tester) async {
    await _show(tester, 'See ![a diagram](https://example.org/p.png) here.');
    expect(find.byType(Image), findsNothing, reason: 'no image widget, so no fetch');
    expect(_shown(tester), contains('[image: a diagram]'), reason: 'the alternative text stands for it');
  });

  // Harm: a direction override reorders what the person reads, raw or written as a character reference.
  testWidgets('control and direction characters never reach the screen', (tester) async {
    await _show(
      tester,
      '# Head\u202e\n\nSee &#x202e;txt.exe&#x202c; and &rlm; and `code\u202e` and ![a\u202eb](https://example.org/x.png)'
      ' and [x\u202ey](https://example.org/z)\n\n```\nblock\u202e\x1b\n```\n',
    );
    final shown = _shown(tester);
    for (final character in ['\u202e', '\u202c', '\u200f', '\x1b']) {
      expect(shown.contains(character), isFalse, reason: 'U+${character.codeUnitAt(0).toRadixString(16)} is shown');
    }
    expect(shown, contains('txt.exe'), reason: 'the text around it stays');
  });

  // Harm: a text of another store's format is lost or garbled by rendering it as Markdown.
  testWidgets('a format the screen does not know shows the text as it is', (tester) async {
    await _show(tester, 'h1. Jira *bold* [link|https://example.org]', format: 'JIRA_WIKI');
    expect(
      find.text('h1. Jira *bold* [link|https://example.org]'),
      findsOneWidget,
      reason: 'the text unchanged, plain',
    );
  });

  // Harm: a huge or deeply nested text holds the window while it is laid out.
  testWidgets('a text past the bounds is shown plain or flattened', (tester) async {
    await _show(tester, '# Big\n${'word ' * (planningMarkdownMost ~/ 5)}');
    expect(find.byType(SelectableText), findsOneWidget, reason: 'past the size bound, shown plain');
    await _show(tester, '${'> ' * 500}deep');
    expect(
      tester.widget<SelectableText>(find.byType(SelectableText)).data,
      contains('deep'),
      reason: 'the deep text is still shown, plain',
    );
  });

  // Harm: a long rule under a heading or a long dash line makes an ordinary description lose its rendering.
  testWidgets('an ordinary text with long rules is still rendered', (tester) async {
    await _show(tester, 'Title\n${'-' * 40}\n\nText **here**.\n\n${'=' * 40}\n');
    expect(find.byType(SelectableText), findsNothing, reason: 'rendered, not shown plain');
  });

  // Harm: the Git store's Markdown shows its signs instead of what its author wrote.
  testWidgets('Markdown is rendered: headings, emphasis, lists and HTML as text', (tester) async {
    await _show(tester, '## Why\n\nIt **holds**.\n\n- one\n- two\n\n<b>raw</b>');
    final shown = _shown(tester);
    expect(shown, contains('Why'));
    expect(shown, isNot(contains('##')), reason: 'the heading sign is gone');
    expect(shown, isNot(contains('**')), reason: 'the emphasis sign is gone');
    expect(shown, contains('<b>raw</b>'), reason: 'HTML is the text it is');
  });
}
