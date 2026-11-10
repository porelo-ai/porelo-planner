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
    if (target?['kind'] == 'WORKING') refuse('EnteredOnlyByWork', {'item': id, 'to': to});
    if (target == null || !{'WAITING', 'CLOSED'}.contains(target['kind'])) {
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
    } else {
      issueWrites.add(() {
        issue!['state'] = to;
        moved.add(id);
      });
    }
  }
  for (final write in [...storyWrites, ...issueWrites]) {
    write();
  }
  settleStoryStates(board);
  board['revision'] = '${source['revision']}:moved';
  board['moved'] = moved;
  return board;
}

/// Puts every story with issues in the earliest open state of its issues, or the earliest closed one when all are.
void settleStoryStates(Map<String, dynamic> board) {
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {
    final least = _leastAdvanced(states, (story['issues'] as List? ?? []).cast<Map<String, dynamic>>());
    if (least.isNotEmpty) story['state'] = least.first['state'];
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
