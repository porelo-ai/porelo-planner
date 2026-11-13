import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

PlanIssue _issue(String id, {String title = '', String repository = '', String state = '', String size = ''}) =>
    PlanIssue.from({'id': id, 'title': title, 'repository': repository, 'state': state, 'size': size});

const _states = ['Backlog', 'Implementing', 'Review', 'Finished'];

final _issues = [
  _issue('item-10', title: 'Fold the description', repository: 'planner', state: 'Review', size: 'M'),
  _issue('item-2', title: 'search the list', repository: 'window', state: 'Backlog', size: 'XL'),
  _issue('item-33', title: 'Answer', repository: 'Core', state: 'Implementing', size: 'XS'),
  _issue('item-4'),
];

List<String> _ids(Iterable<PlanIssue> issues) => [for (final issue in issues) issue.id];

List<String> _shown(PlanningIssueFilter filter) => _ids(filter.shown(_issues, _states));

PlanningIssueFilter _searching(String text) => PlanningIssueFilter()
  ..searchOpen = true
  ..search = text;

void main() {
  // Harm: an issue that matches is hidden, so the person misses work that waits on them.
  group('the search', () {
    test('finds an issue by each of its words, ignoring case and the text\'s outer blanks', () {
      expect(_shown(_searching('ITEM-33')), ['item-33'], reason: 'by identifier');
      expect(_shown(_searching('  FOLD ')), ['item-10'], reason: 'by title, trimmed and ignoring case');
      expect(_shown(_searching('window')), ['item-2'], reason: 'by repository');
      expect(_shown(_searching('implementing')), ['item-33'], reason: 'by state');
      expect(_shown(_searching('xl')), ['item-2'], reason: 'by size');
    });

    test('an issue whose fields are not reported is still found by its identifier', () {
      expect(_shown(_searching('item-4')), ['item-4'], reason: 'nothing but the identifier was reported');
    });

    test('an empty or closed search hides no issue', () {
      expect(_shown(_searching('   ')), [
        'item-2',
        'item-4',
        'item-10',
        'item-33',
      ], reason: 'blanks alone search for nothing');
      final closed = PlanningIssueFilter()..search = 'nothing like it';
      expect(_shown(closed), ['item-2', 'item-4', 'item-10', 'item-33'], reason: 'a closed search hides no row');
      final shut = _searching('nothing like it')..closeSearch();
      expect(shut.search, isEmpty, reason: 'closing clears the text');
      expect(_shown(shut), hasLength(4), reason: 'and hides no row');
    });

    test('a search that matches none shows none and leaves the given list as it was', () {
      final given = List.of(_issues);
      expect(_shown(_searching('no such issue')), isEmpty, reason: 'nothing matches');
      _shown(PlanningIssueFilter()..choose(PlanningIssueSort.title));
      expect(_ids(_issues), _ids(given), reason: 'the answer\'s own list is never reordered');
    });
  });

  // Harm: the list in a wrong order puts the issue a person looks for out of sight among the others.
  group('the sort', () {
    PlanningIssueFilter sortedBy(PlanningIssueSort sort) => PlanningIssueFilter()..choose(sort);

    test('runs up by identifier\'s number at first', () {
      final filter = PlanningIssueFilter();
      expect(filter.sort, PlanningIssueSort.identifier, reason: 'the first sort');
      expect(filter.ascending, isTrue, reason: 'running up');
      expect(_shown(filter), ['item-2', 'item-4', 'item-10', 'item-33'], reason: 'by number, not text');
    });

    test('orders by each key, an unreported value after the reported ones', () {
      expect(_shown(sortedBy(PlanningIssueSort.title)), [
        'item-33',
        'item-10',
        'item-2',
        'item-4',
      ], reason: 'ignoring case');
      expect(_shown(sortedBy(PlanningIssueSort.repository)), [
        'item-33',
        'item-10',
        'item-2',
        'item-4',
      ], reason: 'ignoring case');
      expect(_shown(sortedBy(PlanningIssueSort.size)), ['item-33', 'item-10', 'item-2', 'item-4'], reason: 'XS to XL');
      expect(_shown(sortedBy(PlanningIssueSort.state)), [
        'item-2',
        'item-33',
        'item-10',
        'item-4',
      ], reason: 'the board\'s order');
    });

    test('choosing the current sort again flips it, and another sort runs up again', () {
      final filter = PlanningIssueFilter()..choose(PlanningIssueSort.identifier);
      expect(filter.ascending, isFalse, reason: 'the current sort chosen again');
      expect(_shown(filter), ['item-33', 'item-10', 'item-4', 'item-2'], reason: 'running down');
      filter.choose(PlanningIssueSort.size);
      expect((filter.sort, filter.ascending), (PlanningIssueSort.size, true), reason: 'another sort runs up');
      filter.choose(PlanningIssueSort.size);
      expect(_shown(filter), ['item-2', 'item-10', 'item-33', 'item-4'], reason: 'sizes running down');
    });

    test('puts an unreported or unknown value after the reported ones either way', () {
      final issues = [
        _issue('item-1', title: 'b', size: 'S', state: 'Review'),
        _issue('item-2'),
        _issue('item-3', title: 'a', size: 'XXL', state: 'Elsewhere'),
        _issue('item-4', title: 'c', size: 'XL', state: 'Backlog'),
      ];
      for (final (sort, up, down) in [
        (PlanningIssueSort.title, ['item-3', 'item-1', 'item-4', 'item-2'], ['item-4', 'item-1', 'item-3', 'item-2']),
        (PlanningIssueSort.size, ['item-1', 'item-4', 'item-3', 'item-2'], ['item-4', 'item-1', 'item-3', 'item-2']),
        (PlanningIssueSort.state, ['item-4', 'item-1', 'item-3', 'item-2'], ['item-1', 'item-4', 'item-3', 'item-2']),
      ]) {
        final filter = PlanningIssueFilter()..choose(sort);
        expect(_ids(filter.shown(issues, _states)), up, reason: '$sort running up');
        filter.choose(sort);
        expect(_ids(filter.shown(issues, _states)), down, reason: '$sort running down');
      }
    });

    test('keeps the given order of issues with an equal key, either way', () {
      final equal = [
        for (final id in ['item-9', 'item-1', 'item-5']) _issue(id, size: 'S', state: 'Review'),
      ];
      for (final sort in [PlanningIssueSort.size, PlanningIssueSort.state]) {
        final filter = PlanningIssueFilter()..choose(sort);
        expect(_ids(filter.shown(equal, _states)), ['item-9', 'item-1', 'item-5'], reason: '$sort running up');
        filter.choose(sort);
        expect(_ids(filter.shown(equal, _states)), ['item-9', 'item-1', 'item-5'], reason: '$sort running down');
      }
    });
  });
}
