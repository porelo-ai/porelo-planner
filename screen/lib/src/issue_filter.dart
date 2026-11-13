import 'models.dart';

/// The orders a story's issue list can be sorted in, as the menu names them.
enum PlanningIssueSort {
  /// By the identifier's trailing number, as the board orders stories.
  identifier('Identifier'),

  /// By title, ignoring case.
  title('Title'),

  /// By repository, ignoring case.
  repository('Repository'),

  /// By size, XS to XL.
  size('Size'),

  /// By state, in the board's reported state order.
  state('State');

  const PlanningIssueSort(this.label);

  /// The name shown in the sort menu.
  final String label;
}

/// One story's issue list choices - the search, the sort and its direction - and the issues they show.
class PlanningIssueFilter {
  /// The text searched for; empty hides no issue.
  String search = '';

  /// Whether the search field stands open.
  bool searchOpen = false;

  /// The order of the shown issues.
  PlanningIssueSort sort = PlanningIssueSort.identifier;

  /// Whether the order runs up rather than down.
  bool ascending = true;

  /// Picks [chosen]: another sort runs ascending, the current one again flips its direction.
  void choose(PlanningIssueSort chosen) {
    ascending = chosen == sort ? !ascending : true;
    sort = chosen;
  }

  /// Closes the search, which then hides no issue.
  void closeSearch() {
    search = '';
    searchOpen = false;
  }

  static const _sizes = ['XS', 'S', 'M', 'L', 'XL'];
  static final _trailingNumber = RegExp(r'\d+$');

  /// The issues of [issues] the search lets through, in the chosen order; an unreported or unknown value stands after
  /// the reported ones either way, issues of an equal key keep their given order, and [issues] itself is never
  /// changed. [states] is the board's state order.
  List<PlanIssue> shown(List<PlanIssue> issues, List<String> states) {
    final query = searchOpen ? search.trim().toLowerCase() : '';
    final found = [
      for (final (index, issue) in issues.indexed)
        if (query.isEmpty || _words(issue).contains(query)) (index, issue),
    ];
    found.sort((a, b) {
      final tier = _tier(a.$2, states).compareTo(_tier(b.$2, states));
      if (tier != 0) return tier;
      final order = _compare(a.$2, b.$2, states);
      return order != 0 ? (ascending ? order : -order) : a.$1.compareTo(b.$1);
    });
    return [for (final (_, issue) in found) issue];
  }

  static String _words(PlanIssue issue) =>
      [issue.id, issue.title, issue.repository, issue.state, issue.size].join(' ').toLowerCase();

  /// 0 for a reported value the sort knows, 1 for one it does not know, 2 for none reported.
  int _tier(PlanIssue issue, List<String> states) {
    final (value, known) = switch (sort) {
      PlanningIssueSort.identifier => (issue.id, _trailingNumber.hasMatch(issue.id)),
      PlanningIssueSort.title => (issue.title, true),
      PlanningIssueSort.repository => (issue.repository, true),
      PlanningIssueSort.size => (issue.size, _sizes.contains(issue.size)),
      PlanningIssueSort.state => (issue.state, states.contains(issue.state)),
    };
    return value.isEmpty
        ? 2
        : known
        ? 0
        : 1;
  }

  int _compare(PlanIssue a, PlanIssue b, List<String> states) => switch (sort) {
    PlanningIssueSort.identifier => _byIdentifier(a.id, b.id),
    PlanningIssueSort.title => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
    PlanningIssueSort.repository => a.repository.toLowerCase().compareTo(b.repository.toLowerCase()),
    PlanningIssueSort.size => _byRank(_sizes, a.size, b.size),
    PlanningIssueSort.state => _byRank(states, a.state, b.state),
  };

  /// By trailing number, an identifier without one after those with one, then by its text.
  static int _byIdentifier(String a, String b) {
    final numberA = int.tryParse(_trailingNumber.firstMatch(a)?.group(0) ?? '');
    final numberB = int.tryParse(_trailingNumber.firstMatch(b)?.group(0) ?? '');
    if (numberA != null && numberB != null && numberA != numberB) return numberA.compareTo(numberB);
    if ((numberA == null) != (numberB == null)) return numberA == null ? 1 : -1;
    return a.compareTo(b);
  }

  /// By place in [order], a value outside it after those in it, then by its text.
  static int _byRank(List<String> order, String a, String b) {
    int rank(String value) => order.contains(value) ? order.indexOf(value) : order.length;
    final byRank = rank(a).compareTo(rank(b));
    return byRank != 0 ? byRank : a.compareTo(b);
  }
}
