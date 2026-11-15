import 'dart:async';

import 'package:flutter/material.dart';
import 'package:markdown/markdown.dart' as md;

import '../models.dart';
import 'actions.dart';
import 'planning_detail_widgets.dart';
import 'style.dart';

/// The longest text rendered as Markdown; a longer one is shown plain, so a huge file cannot hold the window.
const planningMarkdownMost = 200000;

/// The deepest nesting of blocks rendered; below it, a block's text is shown plain.
const planningMarkdownDepth = 16;

/// The most lines rendered as Markdown; a text with more is shown plain, so its blocks cannot hold the window.
const planningMarkdownLines = 2000;

/// A line nested deeper than the screen renders, or a run of emphasis signs, either of which the parser and the
/// rendering follow by recursion; such a text is shown plain before it is parsed.
final _tooDeep = RegExp(
  r'(^|\n)[ \t]*(?:(?:>|[*+-][ \t]|\d{1,9}[.)][ \t])[ \t]*){'
  '$planningMarkdownDepth'
  r'}'
  r'|(^|\n)[ \t]{'
  '${4 * planningMarkdownDepth}'
  r'}'
  r'|[*_~`\[\]]{64}',
);

/// A store's text in the format the store names: MARKDOWN rendered, any other or none shown plain as it is.
///
/// Rendered Markdown keeps the screen's rules: a link opens only through [openLink] and only for an `https` address
/// with a host and no user part; an image is never fetched, its alternative text stands for it; HTML is shown as the
/// text it is; and every piece of text is cleaned of control and direction characters after parsing.
class PlanningText extends StatelessWidget {
  /// Takes the text, its format and how the window opens a web address.
  const PlanningText({required this.text, required this.format, required this.openLink, super.key});

  /// The store's text.
  final String text;

  /// The format the store named, such as MARKDOWN; any other is shown plain.
  final String format;

  /// Opens an address in the person's browser.
  final Future<void> Function(Uri) openLink;

  @override
  Widget build(BuildContext context) {
    if (format != 'MARKDOWN' ||
        text.trim().isEmpty ||
        text.length > planningMarkdownMost ||
        '\n'.allMatches(text).length >= planningMarkdownLines ||
        _tooDeep.hasMatch(text)) {
      return SelectableText(planningValue(text));
    }
    final document = md.Document(
      // Fenced code is read; inline HTML is not, so it stays the text it is.
      extensionSet: md.ExtensionSet(const [md.FencedCodeBlockSyntax()], const []),
      encodeHtml: false,
    );
    final nodes = document.parseLines(text.replaceAll('\r\n', '\n').split('\n'));
    return SelectionArea(
      child: _MarkdownBlocks(nodes: nodes, openLink: openLink, depth: 0),
    );
  }
}

class _MarkdownBlocks extends StatelessWidget {
  const _MarkdownBlocks({required this.nodes, required this.openLink, required this.depth});

  final List<md.Node> nodes;
  final Future<void> Function(Uri) openLink;
  final int depth;

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    final inline = <md.Node>[];
    void flush() {
      if (inline.isEmpty) return;
      children.add(_paragraph(context, [...inline], Theme.of(context).textTheme.bodyMedium));
      inline.clear();
    }

    for (final node in nodes) {
      if (node is md.Element && _blocks.contains(node.tag)) {
        flush();
        children.add(_block(context, node));
      } else {
        inline.add(node);
      }
    }
    flush();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: Space.small, children: children);
  }

  static const _blocks = {'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'ul', 'ol', 'li', 'blockquote', 'pre', 'hr'};

  Widget _block(BuildContext context, md.Element element) {
    final theme = Theme.of(context);
    if (depth >= planningMarkdownDepth) return Text(planningShown(element.textContent));
    final nested = depth + 1;
    switch (element.tag) {
      case 'h1' || 'h2':
        return _paragraph(context, element.children ?? const [], theme.textTheme.titleLarge);
      case 'h3' || 'h4' || 'h5' || 'h6':
        return _paragraph(context, element.children ?? const [], theme.textTheme.titleMedium);
      case 'hr':
        return const Divider();
      case 'pre':
        return Container(
          padding: const EdgeInsets.all(Space.small),
          color: theme.colorScheme.surfaceContainerHighest,
          child: Text(
            planningShown(element.textContent),
            style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
          ),
        );
      case 'blockquote':
        return Container(
          padding: const EdgeInsets.only(left: Space.normal),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: theme.colorScheme.outlineVariant, width: 3)),
          ),
          child: _MarkdownBlocks(nodes: element.children ?? const [], openLink: openLink, depth: nested),
        );
      case 'ul' || 'ol':
        final ordered = element.tag == 'ol';
        final first = int.tryParse(element.attributes['start'] ?? '') ?? 1;
        final items = (element.children ?? const []).whereType<md.Element>().toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: Space.tight,
          children: [
            for (final (index, item) in items.indexed)
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: Space.normal * 2, child: Text(ordered ? '${first + index}.' : '•')),
                  Expanded(
                    child: _MarkdownBlocks(nodes: item.children ?? const [], openLink: openLink, depth: nested),
                  ),
                ],
              ),
          ],
        );
      case 'li':
        return _MarkdownBlocks(nodes: element.children ?? const [], openLink: openLink, depth: nested);
      default:
        return _paragraph(context, element.children ?? const [], theme.textTheme.bodyMedium);
    }
  }

  Widget _paragraph(BuildContext context, List<md.Node> nodes, TextStyle? style) =>
      Text.rich(TextSpan(style: style, children: _inline(context, nodes, style ?? const TextStyle())));

  List<InlineSpan> _inline(BuildContext context, List<md.Node> nodes, TextStyle style) => [
    for (final node in nodes) ..._span(context, node, style),
  ];

  List<InlineSpan> _span(BuildContext context, md.Node node, TextStyle style) {
    // A single line break inside a paragraph is a space in Markdown; a hard break comes as its own element.
    if (node is md.Text) return [TextSpan(text: planningShown(node.text.replaceAll('\n', ' ')))];
    if (node is! md.Element) return [TextSpan(text: planningShown(node.textContent))];
    final children = node.children ?? const <md.Node>[];
    switch (node.tag) {
      case 'em':
        return [
          TextSpan(
            style: const TextStyle(fontStyle: FontStyle.italic),
            children: _inline(context, children, style),
          ),
        ];
      case 'strong':
        return [
          TextSpan(
            style: const TextStyle(fontWeight: FontWeight.bold),
            children: _inline(context, children, style),
          ),
        ];
      case 'code':
        return [
          TextSpan(
            text: planningShown(node.textContent),
            style: const TextStyle(fontFamily: 'monospace'),
          ),
        ];
      case 'br':
        return const [TextSpan(text: '\n')];
      case 'img':
        // Never fetched: a picture would tell its server who reads the text.
        final alt = planningShown(node.attributes['alt'] ?? '', multiline: false);
        return [TextSpan(text: alt.isEmpty ? '[image]' : '[image: $alt]')];
      case 'a':
        return [_link(context, node, style)];
      default:
        return _inline(context, children, style);
    }
  }

  InlineSpan _link(BuildContext context, md.Element node, TextStyle style) {
    final label = planningShown(node.textContent, multiline: false);
    final address = planningLinkAddress(node.attributes['href'] ?? '');
    if (address == null) return TextSpan(text: label);
    final action = planningWebAction(label, address.toString(), openLink);
    return WidgetSpan(
      alignment: PlaceholderAlignment.baseline,
      baseline: TextBaseline.alphabetic,
      child: Tooltip(
        message: address.toString(),
        child: InkWell(
          onTap: () => unawaited(Future.sync(action.run)),
          child: Text(
            label,
            style: style.copyWith(color: Theme.of(context).colorScheme.primary, decoration: TextDecoration.underline),
          ),
        ),
      ),
    );
  }
}

/// The address a Markdown link may open: `https` with a host, no user part, and nothing the screen would not show;
/// null for any other, which then stands as its label alone.
Uri? planningLinkAddress(String href) {
  final trimmed = href.trim();
  // The parser percent-encodes a destination, so a hidden character is looked for in its decoded form too.
  final String decoded;
  try {
    decoded = Uri.decodeFull(trimmed);
  } on ArgumentError {
    return null;
  }
  for (final form in [trimmed, decoded]) {
    if (form.isEmpty || planningShown(form, multiline: false) != form || form.contains(RegExp(r'\s'))) return null;
  }
  final address = Uri.tryParse(trimmed);
  if (address == null || !address.isScheme('https') || address.host.isEmpty || address.userInfo.isNotEmpty) {
    return null;
  }
  return address;
}
