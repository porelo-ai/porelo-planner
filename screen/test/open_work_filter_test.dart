import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/ui/open_work_rows.dart';

const _workflow = [
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
];

Map<String, dynamic> _open({int left = 0, int review = 0, Object? minutes}) => {
  'states': [
    {'name': 'Backlog', 'issues': left},
    {'name': 'Finished', 'issues': 0},
  ],
  'blocked': 0,
  'sizes': <Object>[],
  'unsized': 0,
  'left': left,
  'minutes': minutes,
  'unestimated': 0,
  'review': review,
};

Map<String, dynamic> _story(String id, String title, Map<String, dynamic>? open, {String? priority}) => {
  'id': id,
  'title': title,
  'state': 'Backlog',
  'priority': ?priority,
  'description': 'What story $id holds.',
  'issues': <Object>[],
  'open': ?open,
};

/// Stories whose orders by each sort differ, with an unknown time, unknown figures and an unknown priority among them.
final _rows = planningOpenWork(
  PlanningSnapshot.from({
    'revision': 'r1',
    'states': _workflow,
    'phases': [
      {
        'name': 'All',
        'states': ['Backlog', 'Finished'],
      },
    ],
    'work': <Object>[],
    'faults': <Object>[],
    'repositories': ['repo'],
    'stories': [
      _story('porelo-store#12', 'Answer the window', _open(left: 4, minutes: 200), priority: 'Medium'),
      _story('ST3', 'build the list', _open(left: 1, minutes: 20), priority: 'High'),
      _story('porelo-store#40', 'Count what is left', _open(left: 2), priority: 'Highest'),
      _story('ST7', 'drop the figures', null, priority: 'Low'),
      _story('ST1', 'Escape the field', _open(left: 3, minutes: 90)),
    ],
    'open': _open(left: 10, minutes: 310),
  }).stories,
  _workflow.map(PlanState.from).toList(),
  const {},
  known: true,
).rows;

List<String> _shown(PlanningOpenWorkFilter filter) => [for (final row in filter.shown(_rows)) row.story.id];

PlanningOpenWorkFilter _sorted(PlanningOpenWorkSort sort, {bool flip = false}) {
  final filter = PlanningOpenWorkFilter()..choose(sort);
  if (flip) filter.choose(sort);
  return filter;
}

PlanningOpenWorkFilter _searching(String text) => PlanningOpenWorkFilter()
  ..searchOpen = true
  ..search = text;

void main() {
  // Harm: a sort that drops or reorders the person's default, so Open work no longer reads the most left first.
  test('it starts by what is left, the most first, the given order unchanged', () {
    final filter = PlanningOpenWorkFilter();
    expect(filter.sort, PlanningOpenWorkSort.left);
    expect(filter.ascending, isFalse, reason: 'the most first, as the page says');
    expect(_shown(filter), [
      'porelo-store#12',
      'ST1',
      'ST3',
      'porelo-store#40',
      'ST7',
    ], reason: 'the order Open work gives');
  });

  // Harm: a value nobody knows sorted as the least or the most, so the person plans by a figure that is not there.
  group('a value not known stands last either way', () {
    test('by what is left, up and down', () {
      final down = PlanningOpenWorkFilter();
      final up = PlanningOpenWorkFilter()..choose(PlanningOpenWorkSort.left);
      expect(up.ascending, isTrue, reason: 'the current sort again flips its direction');
      expect(_shown(up), [
        'ST3',
        'ST1',
        'porelo-store#12',
        'porelo-store#40',
        'ST7',
      ], reason: 'unknown time and figures after the known');
      expect(_shown(down).sublist(3), ['porelo-store#40', 'ST7'], reason: 'the same unknowns last going down');
    });

    test('by open issues, up and down', () {
      expect(_shown(_sorted(PlanningOpenWorkSort.open)), ['ST3', 'porelo-store#40', 'ST1', 'porelo-store#12', 'ST7']);
      expect(_shown(_sorted(PlanningOpenWorkSort.open, flip: true)), [
        'porelo-store#12',
        'ST1',
        'porelo-store#40',
        'ST3',
        'ST7',
      ]);
    });

    test('by priority, Highest first going up, the story without one last', () {
      expect(_shown(_sorted(PlanningOpenWorkSort.priority)), [
        'porelo-store#40',
        'ST3',
        'porelo-store#12',
        'ST7',
        'ST1',
      ]);
      expect(_shown(_sorted(PlanningOpenWorkSort.priority, flip: true)), [
        'ST7',
        'porelo-store#12',
        'ST3',
        'porelo-store#40',
        'ST1',
      ]);
    });
  });

  // Harm: stories ordered by their text, so porelo-store#12 stands before ST3, or a title's case deciding its place.
  test('by story the identifier\'s number decides, by title the words ignoring case', () {
    expect(_shown(_sorted(PlanningOpenWorkSort.story)), ['ST1', 'ST3', 'ST7', 'porelo-store#12', 'porelo-store#40']);
    expect(_shown(_sorted(PlanningOpenWorkSort.title)), ['porelo-store#12', 'ST3', 'porelo-store#40', 'ST7', 'ST1']);
    expect(_shown(_sorted(PlanningOpenWorkSort.title, flip: true)), [
      'ST1',
      'ST7',
      'porelo-store#40',
      'ST3',
      'porelo-store#12',
    ]);
  });

  // Harm: a story that matches is hidden, so the person misses open work.
  group('the filter', () {
    test('finds a story by its identifier, title or priority, ignoring case and outer blanks', () {
      expect(_shown(_searching('STORE#40')), ['porelo-store#40'], reason: 'by identifier');
      expect(_shown(_searching('  BUILD THE ')), ['ST3'], reason: 'by title, trimmed and ignoring case');
      expect(_shown(_searching('highest')), ['porelo-store#40'], reason: 'by priority');
    });

    test('an empty or closed filter hides no story, and the sort stays', () {
      expect(_shown(_searching('   ')), hasLength(5), reason: 'blanks hide nothing');
      final closed = _sorted(PlanningOpenWorkSort.story)
        ..search = 'store#40'
        ..searchOpen = false;
      expect(_shown(closed), [
        'ST1',
        'ST3',
        'ST7',
        'porelo-store#12',
        'porelo-store#40',
      ], reason: 'a closed filter hides nothing');
      expect(closed.filtering, isFalse);
      final open = _sorted(PlanningOpenWorkSort.story)
        ..searchOpen = true
        ..search = 'st';
      expect(_shown(open), [
        'ST1',
        'ST3',
        'ST7',
        'porelo-store#12',
        'porelo-store#40',
      ], reason: 'the matches in the chosen order');
    });

    test('closing it forgets its text', () {
      final filter = _searching('STORE#40')..closeSearch();
      expect(filter.search, isEmpty);
      expect(filter.searchOpen, isFalse);
    });
  });
}
