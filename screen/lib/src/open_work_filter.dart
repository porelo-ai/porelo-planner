import 'issue_filter.dart';
import 'planning_board.dart';
import 'ui/open_work_rows.dart';

/// The orders Open work can be sorted in, as the menu names them.
enum PlanningOpenWorkSort {
  /// By the minutes left to build; it starts with the most first.
  left('Left'),

  /// By the issues left to build.
  open('Open issues'),

  /// By priority, Highest to Lowest.
  priority('Priority'),

  /// By the story's identifier's trailing number, as the board orders stories.
  story('Story'),

  /// By title, ignoring case.
  title('Title');

  const PlanningOpenWorkSort(this.label);

  /// The name shown in the sort menu.
  final String label;
}

/// Open work's choices - the filter, the sort and its direction - and the rows they show, as the window's own lists
/// offer them.
class PlanningOpenWorkFilter {
  /// The text filtered by; empty hides no story.
  String search = '';

  /// Whether the filter field stands open.
  bool searchOpen = false;

  /// The order of the shown rows; it starts by what is left, the most first.
  PlanningOpenWorkSort sort = PlanningOpenWorkSort.left;

  /// Whether the order runs up rather than down.
  bool ascending = false;

  /// Picks [chosen]: another sort runs ascending, the current one again flips its direction.
  void choose(PlanningOpenWorkSort chosen) {
    ascending = chosen == sort ? !ascending : true;
    sort = chosen;
  }

  /// Closes the filter, which then hides no story.
  void closeSearch() {
    search = '';
    searchOpen = false;
  }

  /// Whether the filter hides stories now.
  bool get filtering => searchOpen && search.trim().isNotEmpty;

  /// The rows of [rows] the filter lets through, in the chosen order; a value not known stands after the known ones
  /// either way, rows of an equal key keep their given order, and [rows] itself is never changed.
  ///
  /// The filter matches a story's identifier, title and priority, the words every row shows at once; what a story
  /// holds is read for only some rows, so a filter by it would hide the others.
  List<PlanningOpenWorkRow> shown(List<PlanningOpenWorkRow> rows) {
    final query = searchOpen ? search.trim().toLowerCase() : '';
    final found = [
      for (final (index, row) in rows.indexed)
        if (query.isEmpty || _words(row).contains(query)) (index, row),
    ];
    found.sort((a, b) {
      final (knownA, knownB) = (_known(a.$2), _known(b.$2));
      if (knownA != knownB) return knownA ? -1 : 1;
      final order = knownA ? _compare(a.$2, b.$2) : 0;
      return order != 0 ? (ascending ? order : -order) : a.$1.compareTo(b.$1);
    });
    return [for (final (_, row) in found) row];
  }

  static String _words(PlanningOpenWorkRow row) =>
      [row.story.id, row.story.title, row.story.priority ?? ''].join(' ').toLowerCase();

  /// Whether the row has the value the sort orders by.
  bool _known(PlanningOpenWorkRow row) => switch (sort) {
    PlanningOpenWorkSort.left => row.minutes != null,
    PlanningOpenWorkSort.open => row.open != null,
    PlanningOpenWorkSort.priority => PlanningBoard.rank(row.story.priority) < PlanningBoard.rank(null),
    PlanningOpenWorkSort.story || PlanningOpenWorkSort.title => true,
  };

  int _compare(PlanningOpenWorkRow a, PlanningOpenWorkRow b) => switch (sort) {
    PlanningOpenWorkSort.left => a.minutes!.compareTo(b.minutes!),
    PlanningOpenWorkSort.open => a.open!.left.compareTo(b.open!.left),
    // Highest first when the order runs up, as the board ranks priorities.
    PlanningOpenWorkSort.priority => PlanningBoard.rank(
      a.story.priority,
    ).compareTo(PlanningBoard.rank(b.story.priority)),
    PlanningOpenWorkSort.story => PlanningIssueFilter.byIdentifier(a.story.id, b.story.id),
    PlanningOpenWorkSort.title => a.story.title.toLowerCase().compareTo(b.story.title.toLowerCase()),
  };
}
