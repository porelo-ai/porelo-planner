import 'dart:convert';

import 'refusal.dart';

/// Validates a complete save on a copy, publishing nothing on a refusal.
Map<String, dynamic> applyPlanningMove(Map<String, dynamic> source, Map<String, dynamic> parameters) {
  Never refuse(String name, Map<String, dynamic> fields) => throw mockRefusal('org.fuin.porelo.Tasks1.$name', fields);
  if (parameters['revision'] != source['revision']) refuse('StaleRevision', {'revision': source['revision']});
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
  final batch = parameters['moves'];
  if (batch is! List) {
    refuse('MoveNotAllowed', {'item': '', 'from': '', 'to': '', 'reason': 'Moves must be a list.'});
  }
  final seen = <String>{};
  final moved = <String>[];
  // Every move is resolved on the shown board; story moves are written before explicit issue moves.
  final storyWrites = <void Function()>[];
  final issueWrites = <void Function()>[];
  final storyChecks = <void Function()>[];
  final storyMisfits = <String, List<String>>{};
  for (final value in batch) {
    final move = value is Map<String, dynamic> ? value : <String, dynamic>{};
    final isStory = move['story'] is String;
    final id = (isStory ? move['story'] : move['issue']) as String? ?? '';
    final to = move['to'] as String? ?? '';
    void invalid(String reason) => refuse('MoveNotAllowed', {'item': id, 'from': '', 'to': to, 'reason': reason});
    if (id.isEmpty ||
        (move['story'] != null) == (move['issue'] != null) ||
        !seen.add('${isStory ? 'story' : 'issue'} $id')) {
      invalid('Each move must name exactly one item, once.');
    }
    final story = stories.where((story) => story['id'] == id).firstOrNull;
    final issue = stories
        .expand((story) => (story['issues'] as List? ?? []).cast<Map<String, dynamic>>())
        .where((issue) => issue['id'] == id)
        .firstOrNull;
    if (isStory && story == null) refuse('NoSuchStory', {'story': id});
    if (!isStory && issue == null) refuse('NoSuchIssue', {'issue': id});
    final from = (isStory ? story! : issue!)['state'];
    final target = states.where((state) => state['name'] == to).firstOrNull;
    final byHand = !isStory && issue!['worker'] == 'MANUAL';
    if (target?['kind'] == 'WORKING' && !byHand) refuse('EnteredOnlyByWork', {'item': id, 'to': to});
    if (target == null || !{'WAITING', 'CLOSED', if (byHand) 'WORKING'}.contains(target['kind'])) {
      refuse('StateNotAllowed', {
        'item': id,
        'from': from,
        'to': to,
        'reason': 'The destination must be a configured waiting or closed state.',
      });
    }
    if (isStory) {
      final issues = (story!['issues'] as List? ?? []).cast<Map<String, dynamic>>();
      if (issues.any((item) => !states.any((state) => state['name'] == item['state']))) {
        invalid('An issue of the story stands in a state outside the workflow.');
      }
      final least = _leastAdvanced(states, issues);
      storyWrites.add(() {
        for (final item in least) {
          item['state'] = to;
          moved.add(item['id'] as String);
        }
        if (issues.isEmpty) story['state'] = to;
      });
      storyChecks.add(() {
        if (issues.isEmpty) return;
        final derived = _leastAdvanced(states, issues).firstOrNull?['state'];
        if (derived == to) {
          // A story moved where its issues follow states no state of its own, as the planner unstates it.
          story.remove('stateGiven');
          return;
        }
        final misfits = [
          for (final item in issues)
            if (item['state'] != to) item['id'] as String,
        ];
        if (parameters['confirm'] != true) {
          refuse('StoryStateMismatch', {'story': id, 'to': to, 'issues': misfits});
        }
        // Confirmed, the story stands where it was moved although its issues do not fit, as the planner writes it.
        story['state'] = to;
        story['stateGiven'] = true;
        storyMisfits[id] = misfits;
      });
    } else {
      issueWrites.add(() {
        issue!['state'] = to;
        moved.add(id);
      });
    }
  }
  for (final write in [...storyWrites, ...issueWrites, ...storyChecks]) {
    write();
  }
  settleStoryStates(board);
  board['revision'] = '${source['revision']}:moved';
  board['moved'] = moved;
  board['storyMisfits'] = storyMisfits;
  return board;
}

/// Answers what a batch would do as the planner's preview does, writing nothing: every issue it carries with its
/// repository and states, and the warnings the stand-in can tell from its board - issues a story's move carries
/// along from a working state or another repository than the project's own, a move forward past waiting states, and a
/// story that would not fit its issues. A refusal is the save's own.
Map<String, dynamic> previewPlanningMove(Map<String, dynamic> source, Map<String, dynamic> parameters) {
  // Previewed as confirmed, so a story that would not fit its issues is warned of, as the planner's preview does.
  final after = applyPlanningMove(source, {...parameters, 'confirm': true});
  final states = (source['states'] as List).cast<Map<String, dynamic>>();
  int place(Object? state) => states.indexWhere((each) => each['name'] == state);
  String kind(Object? state) => states.where((each) => each['name'] == state).firstOrNull?['kind'] as String? ?? '';
  final stories = (source['stories'] as List).cast<Map<String, dynamic>>();
  final issues = {
    for (final story in stories)
      for (final issue in (story['issues'] as List? ?? []).cast<Map<String, dynamic>>()) issue['id'] as String: issue,
  };
  final issuesAfter = {
    for (final story in (after['stories'] as List).cast<Map<String, dynamic>>())
      for (final issue in (story['issues'] as List? ?? []).cast<Map<String, dynamic>>()) issue['id'] as String: issue,
  };
  final repositories = (source['repositories'] as List? ?? const []).cast<String>();
  final own = repositories.isEmpty ? null : repositories.first;
  Map<String, dynamic> moved(String id) => {
    'id': id,
    'repository': issues[id]?['repository'] ?? '',
    'from': issues[id]?['state'] ?? '',
    'to': issuesAfter[id]?['state'] ?? '',
  };
  final batch = (parameters['moves'] as List).cast<Map<String, dynamic>>();
  final ownMoves = {for (final move in batch) ?move['issue'] as String?};
  final warnings = <Map<String, dynamic>>[];
  Map<String, dynamic> warning(String kind, String item, String from, String to, {List<Object?> issues = const []}) => {
    'kind': kind,
    'item': item,
    'from': from,
    'to': to,
    'run': null,
    'tasks': <String>[],
    'offers': <String>[],
    'issues': issues,
    'skipped': <String>[],
  };
  for (final move in batch) {
    final to = move['to'] as String;
    final story = stories.where((story) => story['id'] == move['story']).firstOrNull;
    final id = (move['story'] ?? move['issue']) as String;
    final from = (story ?? issues[id])?['state'] as String? ?? '';
    if (story != null) {
      final along = [
        for (final issue in (story['issues'] as List? ?? []).cast<Map<String, dynamic>>())
          if (!ownMoves.contains(issue['id']) &&
              (after['moved'] as List).contains(issue['id']) &&
              (issue['repository'] != own || kind(issue['state']) == 'WORKING'))
            moved(issue['id'] as String),
      ];
      if (along.isNotEmpty) warnings.add(warning('ISSUES_ALONG', id, from, to, issues: along));
    }
    final start = place(from), end = place(to);
    if (start >= 0 && end > start + 1 && kind(to) != 'CLOSED') {
      final skipped = [
        for (final state in states.sublist(start + 1, end))
          if (state['kind'] == 'WAITING') state['name'] as String,
      ];
      if (skipped.isNotEmpty) warnings.add({...warning('SKIPS_STATES', id, from, to), 'skipped': skipped});
    }
  }
  final misfits = (after['storyMisfits'] as Map).cast<String, List<String>>();
  for (final MapEntry(key: story, value: ids) in misfits.entries) {
    final move = batch.firstWhere((move) => move['story'] == story);
    final from = stories.firstWhere((each) => each['id'] == story)['state'] as String? ?? '';
    warnings.add(
      warning('STORY_MISMATCH', story, from, move['to'] as String, issues: [for (final id in ids) moved(id)]),
    );
  }
  final revision = source['revision'] as String;
  return {
    'revision': revision,
    'digest': _digest(jsonEncode([revision, batch, warnings])),
    'issues': [for (final id in (after['moved'] as List).cast<String>()) moved(id)],
    'stories': [
      for (final move in batch)
        if (stories.where((story) => story['id'] == move['story']).firstOrNull case final story?)
          {'id': story['id'], 'from': story['state'], 'to': move['to']},
    ],
    'warnings': warnings,
  };
}

/// A stand-in's digest: 64 hexadecimal characters that differ with what the preview showed.
String _digest(String text) {
  var hash = 0xcbf29ce484222325;
  final out = StringBuffer();
  for (var round = 0; out.length < 64; round++) {
    for (final unit in '$round$text'.codeUnits) {
      hash = ((hash ^ unit) * 0x100000001b3) & 0xFFFFFFFFFFFF;
    }
    out.write(hash.toRadixString(16).padLeft(12, '0'));
  }
  return out.toString().substring(0, 64);
}

/// Sets who works one issue on a copy, as the planner does, publishing nothing on a refusal.
Map<String, dynamic> applyPlanningWorker(Map<String, dynamic> source, String id, String worker, String revision) {
  Never refuse(String name, Map<String, dynamic> fields) => throw mockRefusal('org.fuin.porelo.Tasks1.$name', fields);
  if (revision != source['revision']) refuse('StaleRevision', {'revision': source['revision']});
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  final issues = (board['stories'] as List)
      .cast<Map<String, dynamic>>()
      .expand((story) => (story['issues'] as List? ?? []).cast<Map<String, dynamic>>())
      .toList();
  final issue = issues.where((issue) => issue['id'] == id).firstOrNull;
  if (issue == null) refuse('NoSuchIssue', {'issue': id});
  void notAllowed(String reason) => refuse('MoveNotAllowed', {'item': id, 'from': '', 'to': '', 'reason': reason});
  if (issue['parent'] is String) notAllowed('$id is a sub-issue and is worked as its parent ${issue['parent']} is.');
  if (!{'AGENT', 'MANUAL'}.contains(worker)) notAllowed('A worker is AGENT or MANUAL.');
  // A worker the issue has already is saved as nothing, at the same revision.
  if (issue['worker'] == worker) return board;
  if (worker == 'AGENT') {
    for (final each in issues.where((each) => each['id'] == id || each['parent'] == id)) {
      if (states.any((state) => state['name'] == each['state'] && state['kind'] == 'WORKING')) {
        notAllowed('${each['id']} stands in ${each['state']}, which only its work enters.');
      }
    }
  }
  for (final each in issues.where((each) => each['id'] == id || each['parent'] == id)) {
    each['worker'] = worker;
  }
  board['revision'] = '${source['revision']}:worker';
  return board;
}

/// Puts every story with issues in the earliest open state of its issues, or the earliest closed one when all are;
/// a story whose state was given keeps it, with each issue standing elsewhere as a misfit when its issues give another.
void settleStoryStates(Map<String, dynamic> board) {
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {
    final issues = (story['issues'] as List? ?? []).cast<Map<String, dynamic>>();
    final least = _leastAdvanced(states, issues);
    if (story['stateGiven'] == true) {
      story['misfits'] = least.isEmpty || least.first['state'] == story['state']
          ? <String>[]
          : [
              for (final issue in issues)
                if (issue['state'] != story['state']) issue['id'] as String,
            ];
    } else {
      if (least.isNotEmpty) story['state'] = least.first['state'];
      if (story.containsKey('misfits')) story['misfits'] = <String>[];
    }
  }
}

/// The issues in the earliest open state in workflow order, or in the earliest closed state when all are closed.
List<Map<String, dynamic>> _leastAdvanced(List<Map<String, dynamic>> states, List<Map<String, dynamic>> issues) {
  int place(Map<String, dynamic> issue) => states.indexWhere((state) => state['name'] == issue['state']);
  final placed = issues.where((issue) => place(issue) >= 0).toList();
  final open = placed.where((issue) => states[place(issue)]['kind'] != 'CLOSED').toList();
  final candidates = open.isEmpty ? placed : open;
  if (candidates.isEmpty) return const [];
  final earliest = candidates.map(place).reduce((a, b) => a < b ? a : b);
  return [
    for (final issue in candidates)
      if (place(issue) == earliest) issue,
  ];
}
