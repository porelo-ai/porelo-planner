import 'package:markdown/markdown.dart' as md;

import '../models.dart';
import '../planning_board.dart';
import 'open_work_figures.dart';

/// One story of Open work: its figures when they are known, and the open states it holds now.
class PlanningOpenWorkRow {
  PlanningOpenWorkRow._(this.story, this.open, this.now);

  /// The story.
  final PlanStory story;

  /// The story's figures, or null when they are not known now, so nothing reads as 0.
  final PlanOpenWork? open;

  /// Each open kind of state with its issues, in the design's order; finished and rejected issues are not open.
  final List<(PlanningStateKind, int)> now;

  /// The minutes left to build, or null when they are not known.
  double? get minutes => open?.minutes;
}

/// The stories of Open work, and how many were left out because nothing of them is left to build.
class PlanningOpenWorkList {
  PlanningOpenWorkList._(this.rows, this.finished);

  /// The rows, the most minutes left first, those whose time left is not known last.
  final List<PlanningOpenWorkRow> rows;

  /// The stories left out since the planner says nothing of them is left to build.
  final int finished;
}

/// The order the kinds of state are shown in, as the board's cards show them.
const _order = [
  PlanningStateKind.implementing,
  PlanningStateKind.blocked,
  PlanningStateKind.review,
  PlanningStateKind.backlog,
  PlanningStateKind.estimated,
  PlanningStateKind.analysis,
  PlanningStateKind.idea,
  PlanningStateKind.other,
];

/// The stories by what is left of them, from each story's own figures, with [states] the workflow's and [review] the
/// states that wait for review; while [known] is false every figure reads unknown and no story is left out.
///
/// A story the planner says has nothing left to build is left out, since Open work lists what is left; a story whose
/// figures are not known stays, last, because it may still hold work.
PlanningOpenWorkList planningOpenWork(
  List<PlanStory> stories,
  List<PlanState> states,
  Set<String> review, {
  required bool known,
}) {
  final byName = {for (final state in states) state.name: state};
  final rows = <PlanningOpenWorkRow>[];
  var finished = 0;
  for (final story in stories) {
    final open = known ? story.open : null;
    // A story whose issues wait only for the person's review stays, since the person acts on it.
    if (open != null && open.left == 0 && open.review == 0) {
      finished++;
      continue;
    }
    final counts = <PlanningStateKind, int>{};
    if (open != null) {
      for (final MapEntry(key: name, value: issues) in open.states.entries) {
        if (issues <= 0) continue;
        final state = byName[name];
        final kind = state == null ? PlanningStateKind.other : planningStateKind(state, review);
        counts[kind] = (counts[kind] ?? 0) + issues;
      }
      if (open.blocked > 0) counts[PlanningStateKind.blocked] = (counts[PlanningStateKind.blocked] ?? 0) + open.blocked;
    }
    rows.add(
      PlanningOpenWorkRow._(story, open, [
        for (final kind in _order)
          if (counts[kind] case final issues?) (kind, issues),
      ]),
    );
  }
  final order = {for (final (index, row) in rows.indexed) row: index};
  rows.sort((a, b) {
    final (left, right) = (a.minutes, b.minutes);
    if (left != null && right != null && left != right) return right.compareTo(left);
    if ((left == null) != (right == null)) return left == null ? 1 : -1;
    final issues = (b.open?.left ?? 0).compareTo(a.open?.left ?? 0);
    if (issues != 0) return issues;
    final rank = PlanningBoard.rank(a.story.priority).compareTo(PlanningBoard.rank(b.story.priority));
    return rank != 0 ? rank : order[a]!.compareTo(order[b]!);
  });
  return PlanningOpenWorkList._(rows, finished);
}

/// The longest first paragraph read; a longer one is cut, so a huge text cannot hold the list.
const _longest = 2000;

final _blank = RegExp(r'^[ \t]*$');
final _heading = RegExp(r'^ {0,3}#{1,6}([ \t]|$)');
final _rule = RegExp(r'^ {0,3}([-*_])([ \t]*\1){2,}[ \t]*$');
final _underline = RegExp(r'^ {0,3}(=+|-+)[ \t]*$');
final _fence = RegExp(r'^ {0,3}(```|~~~)');
final _marker = RegExp(r'^[ \t]*(?:(?:>|[*+-][ \t]|\d{1,9}[.)][ \t])[ \t]*)*');

/// A run of emphasis signs the inline parser follows by recursion; such a paragraph is shown with its signs.
final _signs = RegExp(r'[*_~`\[\]]{64}');

/// The first paragraph of a story's [text] in the store's [format], as one line of plain words: headings, rules and
/// fenced code before it are passed over, and for MARKDOWN its markup is stripped; empty when the text holds none.
String planningFirstParagraph(String text, String format) {
  final lines = text.replaceAll('\r\n', '\n').split('\n');
  final markdown = format == 'MARKDOWN';
  final paragraph = <String>[];
  var fenced = false;
  for (final line in lines) {
    if (markdown && _fence.hasMatch(line)) {
      if (paragraph.isNotEmpty) break;
      fenced = !fenced;
      continue;
    }
    if (fenced) continue;
    if (_blank.hasMatch(line)) {
      if (paragraph.isNotEmpty) break;
      continue;
    }
    // Before a rule, since a heading underlined with dashes reads as one.
    if (markdown && paragraph.length == 1 && _underline.hasMatch(line)) {
      // The line before was a heading underlined, not a paragraph.
      paragraph.clear();
      continue;
    }
    if (markdown && (_heading.hasMatch(line) || _rule.hasMatch(line))) {
      if (paragraph.isNotEmpty) break;
      continue;
    }
    paragraph.add(markdown && paragraph.isEmpty ? line.replaceFirst(_marker, '') : line);
  }
  var joined = paragraph.join('\n');
  if (joined.length > _longest) {
    final high = joined.codeUnitAt(_longest - 1);
    joined = joined.substring(0, high >= 0xD800 && high <= 0xDBFF ? _longest - 1 : _longest);
  }
  final words = markdown && !_signs.hasMatch(joined) ? _plain(joined) : joined;
  return planningShown(words.replaceAll(RegExp(r'\s+'), ' ').trim(), multiline: false);
}

/// The inline elements, whose words run on into the text around them.
const _inline = {'em', 'strong', 'code', 'a', 'del', 'span'};

/// The words of one Markdown paragraph without its markup; HTML stays the text it is, an image reads as its
/// alternative text.
String _plain(String paragraph) {
  final document = md.Document(
    extensionSet: md.ExtensionSet(const [md.FencedCodeBlockSyntax()], const []),
    encodeHtml: false,
  );
  final words = StringBuffer();
  void add(md.Node node) {
    switch (node) {
      case md.Element(tag: 'img'):
        words.write(node.attributes['alt'] ?? '');
      case md.Element(tag: 'br'):
        words.write(' ');
      case md.Element(:final tag, :final children):
        children?.forEach(add);
        // Inline markup stands inside a word; a block ends one.
        if (!_inline.contains(tag)) words.write(' ');
      case md.Text(:final text):
        words.write(text);
      default:
        words.write(node.textContent);
    }
  }

  document.parseLines(paragraph.split('\n')).forEach(add);
  return words.toString();
}
