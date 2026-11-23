import 'dart:convert';

import '../client.dart' show planningTopicLabelOf, planningTopicLabels;
import '../models.dart';
import 'planning_data.dart';
import 'refusal.dart';
import 'story_state.dart';

/// Validates a complete save on a copy, publishing nothing on a refusal.
Map<String, dynamic> applyPlanningMove(Map<String, dynamic> source, Map<String, dynamic> parameters) {
  Never refuse(String name, Map<String, dynamic> fields) => throw mockRefusal('org.fuin.porelo.Tasks1.$name', fields);
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
  _shareIssues(board);
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
    final issue = [
      ...stories.expand((story) => (story['issues'] as List? ?? []).cast<Map<String, dynamic>>()),
      ...(board['issues'] as List? ?? const []).cast<Map<String, dynamic>>(),
    ].where((issue) => issue['id'] == id).firstOrNull;
    if (isStory && story == null) refuse('NoSuchStory', {'story': id});
    if (!isStory && issue == null) refuse('NoSuchIssue', {'issue': id});
    final from = (isStory ? story! : issue!)['state'];
    final target = states.where((state) => state['name'] == to).firstOrNull;
    // An agent's issue no run works enters work by hand as work started elsewhere, as the planner allows it.
    final byHand = !isStory && (issue!['worker'] == 'MANUAL' || !_runHolds(stories, issue));
    if (target?['kind'] == 'WORKING' && !byHand) refuse('EnteredOnlyByWork', {'item': id, 'to': to});
    if (target == null || !{'WAITING', 'CLOSED', if (byHand) 'WORKING'}.contains(target['kind'])) {
      refuse('StateNotAllowed', {
        'item': id,
        'from': from,
        'to': to,
        'reason': 'The destination must be a configured waiting or closed state.',
      });
    }
    if (_reasonRefused(move, target['kind'] == 'CLOSED', isStory, from == to) case final reason?) {
      refuse('MoveNotAllowed', {'item': id, 'from': from, 'to': to, 'reason': reason});
    }
    if (isStory) {
      bool closed(Object? name) => states.any((state) => state['name'] == name && state['kind'] == 'CLOSED');
      // An open story follows its issues, as the planner keeps it: it is moved only to close it, or to reopen it.
      if (!closed(from) && !closed(to)) {
        invalid('An open story follows its issues; it is moved only to close it, or to reopen it once closed.');
      }
      final issues = (story!['issues'] as List? ?? []).cast<Map<String, dynamic>>();
      if (issues.any((item) => !states.any((state) => state['name'] == item['state']))) {
        invalid('An issue of the story stands in a state outside the workflow.');
      }
      // A story moved to a completed state is judged on the shown board, before any other move of the batch, and
      // finishes its issues in the last done state with it; rejected, it closes every open one; reopened, it carries
      // none, as the planner does.
      final completes = target['kind'] == 'CLOSED' && to != _rejected;
      final done = _lastDone(states, board['work']);
      void checkOpen() {
        final open = [
          for (final item in issues)
            if (item['state'] != done &&
                states.where((state) => state['name'] == item['state']).firstOrNull?['kind'] != 'CLOSED')
              '${item['id']}:${item['state']}',
        ];
        if (open.isNotEmpty) {
          final reason = done == null
              ? 'Its issues ${open.join(', ')} are not closed.'
              : 'Its issues ${open.join(', ')} stand neither in $done nor closed.';
          refuse('MoveNotAllowed', {'item': id, 'from': from, 'to': to, 'reason': reason});
        }
      }

      if (completes) checkOpen();
      final least = completes
          ? [
              for (final item in issues)
                if (done != null && item['state'] == done) item,
            ]
          : closed(to)
          ? [
              for (final item in issues)
                if (!closed(item['state'])) item,
            ]
          : const <Map<String, dynamic>>[];
      storyWrites.add(() {
        for (final item in least) {
          item['state'] = to;
          if (!moved.contains(item['id'])) moved.add(item['id'] as String);
        }
        story['state'] = to;
        story['closedAt'] = null;
      });
      storyChecks.add(() {
        if (issues.isEmpty) return;
        // Checked again after the batch, so an issue's own move that reopens it keeps the story open.
        if (completes) checkOpen();
        // Work under an issue the batch leaves rejected is never finished as accepted, as the planner refuses it.
        for (final item in completes ? least : const <Map<String, dynamic>>[]) {
          final parent = item['parent'];
          final parentState = issues.where((each) => each['id'] == parent).firstOrNull?['state'];
          if (item['state'] == to && parent is String && parentState == _rejected) {
            refuse('MoveNotAllowed', {
              'item': id,
              'from': from,
              'to': to,
              'reason': 'Its issue ${item['id']} would be finished under $parent, which is $_rejected.',
            });
          }
        }
      });
    } else {
      issueWrites.add(() {
        issue!['state'] = to;
        if (!moved.contains(id)) moved.add(id);
      });
    }
  }
  for (final write in [...storyWrites, ...issueWrites, ...storyChecks]) {
    write();
  }
  settleStoryStates(board);
  board['revision'] = '${source['revision']}:moved';
  // The fake does not judge the stories' rules, so a change shows none of the breaches it named.
  board.remove('rules');
  board['moved'] = moved;
  return board;
}

/// Makes an issue two stories share, or one in no story that a story lists too, one issue on a decoded [board], as the
/// planner holds it: every story's list points at the first copy of each issue, so a write to it is seen everywhere.
void _shareIssues(Map<String, dynamic> board) {
  final one = <Object?, Map<String, dynamic>>{};
  for (final issue in (board['issues'] as List? ?? const []).whereType<Map<String, dynamic>>()) {
    one.putIfAbsent(issue['id'], () => issue);
  }
  for (final story in (board['stories'] as List).whereType<Map<String, dynamic>>()) {
    final issues = story['issues'];
    if (issues is! List) continue;
    for (var at = 0; at < issues.length; at++) {
      if (issues[at] case final Map<String, dynamic> issue) issues[at] = one.putIfAbsent(issue['id'], () => issue);
    }
  }
}

/// The closed state that is not completed work.
const _rejected = 'Rejected';

/// The state the last work of the board's rules leads to, where finished work waits for the person's test; null when
/// the board has no work rules.
/// The planner's words refusing a move's reason, or null when it has none or may keep it: at most 280 code points
/// after trimming, none that hides or turns text, and only with a move that closes its item; a blank one is none.
String? _reasonRefused(Map<String, dynamic> move, bool closes, bool isStory, bool unchanged) {
  final reason = (move['reason'] as String? ?? '').trim();
  if (reason.isEmpty) return null;
  if (planningReasonRefusal(reason) case final refused?) return refused;
  if (!closes) return 'A reason is taken only with a move into a closed state.';
  if (!isStory && unchanged) return 'It is in that state already; a reason goes only with the move that closes it.';
  return null;
}

String? _lastDone(List<Map<String, dynamic>> states, Object? work) {
  int place(Object? state) => states.indexWhere((each) => each['name'] == state);
  String? last;
  for (final rule in (work is List ? work : const <Object?>[]).whereType<Map<String, dynamic>>()) {
    final done = rule['done'];
    if (done is String && (last == null || place(done) > place(last))) last = done;
  }
  return last;
}

/// Answers what a batch would do as the planner's preview does, writing nothing: every issue it carries with its
/// repository and states, and the warnings the stand-in can tell from its board - issues a story's move carries
/// along from a working state or another repository than the project's own, or finishes with it, a move forward past
/// waiting states. A refusal is the save's own.
Map<String, dynamic> previewPlanningMove(Map<String, dynamic> source, Map<String, dynamic> parameters) {
  final after = applyPlanningMove(source, {...parameters, 'confirm': true});
  final states = (source['states'] as List).cast<Map<String, dynamic>>();
  int place(Object? state) => states.indexWhere((each) => each['name'] == state);
  String kind(Object? state) => states.where((each) => each['name'] == state).firstOrNull?['kind'] as String? ?? '';
  final stories = (source['stories'] as List).cast<Map<String, dynamic>>();
  final issues = {
    for (final issue in (source['issues'] as List? ?? const []).cast<Map<String, dynamic>>())
      issue['id'] as String: issue,
    for (final story in stories)
      for (final issue in (story['issues'] as List? ?? []).cast<Map<String, dynamic>>()) issue['id'] as String: issue,
  };
  final issuesAfter = {
    for (final issue in (after['issues'] as List? ?? const []).cast<Map<String, dynamic>>())
      issue['id'] as String: issue,
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
      // Each issue a story's finish takes along is settled and its file deleted, so all are named.
      final finishes = kind(to) == 'CLOSED' && to != _rejected;
      final along = [
        for (final issue in (story['issues'] as List? ?? []).cast<Map<String, dynamic>>())
          if (!ownMoves.contains(issue['id']) &&
              (after['moved'] as List).contains(issue['id']) &&
              (finishes || issue['repository'] != own || kind(issue['state']) == 'WORKING'))
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
  // The items the batch changes: those it moves and every issue it carries, each with the version it stands at.
  final versions = mockItemVersions(source);
  final targets = {
    for (final id in {
      for (final move in batch) (move['story'] ?? move['issue']) as String,
      ...(after['moved'] as List).cast<String>(),
    })
      id: ?versions[id],
  };
  return {
    'revision': source['revision'] as String,
    'targets': [
      for (final target in targets.entries) {'id': target.key, 'version': target.value},
    ],
    // The reason decides no item, so the planner's digest leaves it out and a reason typed after the preview saves.
    'digest': mockDigest(
      jsonEncode([
        targets,
        [for (final move in batch) Map.of(move)..remove('reason')],
        warnings,
      ]),
    ),
    'issues': [for (final id in (after['moved'] as List).cast<String>()) moved(id)],
    'stories': [
      for (final move in batch)
        if (stories.where((story) => story['id'] == move['story']).firstOrNull case final story?)
          {'id': story['id'], 'from': story['state'], 'to': move['to']},
    ],
    'warnings': warnings,
  };
}

/// Checks the versions [seen] of a write's [targets] on [board] as the planner does: a target without one is refused as
/// `BasisIncomplete`, and one at another version, or no longer on the board, as `ItemChanged` naming it.
void mockCheckSeen(Map<String, dynamic> board, Map<String, String> seen, Iterable<String> targets) {
  final missing = [
    for (final id in targets.toSet())
      if (!seen.containsKey(id)) id,
  ]..sort();
  if (missing.isNotEmpty) throw mockRefusal('BasisIncomplete', {'missing': missing});
  final versions = mockItemVersions(board);
  final changed = [
    for (final id in targets.toSet())
      if (versions[id] != seen[id]) id,
  ]..sort();
  if (changed.isNotEmpty) {
    throw mockRefusal('ItemChanged', {
      'changed': changed,
      'items': [
        for (final id in changed) {'id': id, 'gone': !versions.containsKey(id)},
      ],
    });
  }
}

/// Sets who works one issue on a copy, as the planner does, publishing nothing on a refusal.
Map<String, dynamic> applyPlanningWorker(
  Map<String, dynamic> source,
  String id,
  String worker,
  Map<String, String> seen,
) {
  Never refuse(String name, Map<String, dynamic> fields) => throw mockRefusal('org.fuin.porelo.Tasks1.$name', fields);
  mockCheckSeen(source, seen, [id]);
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  final issues = (board['stories'] as List)
      .cast<Map<String, dynamic>>()
      .expand((story) => (story['issues'] as List? ?? []).cast<Map<String, dynamic>>())
      .toList();
  issues.addAll((board['issues'] as List? ?? const []).cast<Map<String, dynamic>>());
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
  // The fake does not judge the stories' rules, so a change shows none of the breaches it named.
  board.remove('rules');
  return board;
}

/// Sets the topic labels of one item, a story when [story] holds, on a copy as the planner does: a name outside the
/// topic labels, or one given twice, is refused as `LabelNotAllowed`, a label of the person's own stays, and labels
/// the item carries already save nothing, at the same revision.
Map<String, dynamic> applyPlanningLabels(
  Map<String, dynamic> source,
  String id,
  List<String> labels,
  Map<String, String> seen, {
  required bool story,
}) {
  final asked = <String>{};
  for (final label in labels) {
    if (!planningTopicLabels.contains(label) || !asked.add(label)) {
      throw mockRefusal('LabelNotAllowed', {'item': id, 'label': label});
    }
  }
  mockCheckSeen(source, seen, [id]);
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final issues = [
    ...(board['stories'] as List).cast<Map<String, dynamic>>().expand(
      (each) => (each['issues'] as List? ?? []).cast<Map<String, dynamic>>(),
    ),
    ...(board['issues'] as List? ?? const []).cast<Map<String, dynamic>>(),
  ];
  // The item itself, never a copy, so the change stands on the board.
  final item = story ? mockPlanningStory(board, id) : issues.where((each) => each['id'] == id).firstOrNull;
  if (item == null) throw mockRefusal(story ? 'NoSuchStory' : 'NoSuchIssue', {story ? 'story' : 'issue': id});
  final carried = (item['labels'] as List? ?? const []).cast<String>();
  // As the planner does: a label in another case is the topic label, and the topic labels carried save nothing.
  final topics = {for (final label in carried) ?planningTopicLabelOf(label)};
  if (topics.length == asked.length && topics.containsAll(asked)) return source;
  final kept = [
    for (final label in carried)
      if (planningTopicLabelOf(label) == null) label,
  ];
  final after = [
    ...kept,
    for (final label in planningTopicLabels)
      if (asked.contains(label)) label,
  ];
  item['labels'] = after;
  board['revision'] = '${source['revision']}:labels';
  return board;
}

/// Puts every open story with issues where the planner's one rule ([PlanningStoryRule]) puts it, naming the issues
/// that stand before it in one warning: only a person's move closes a story, as the planner keeps it. A closed story
/// stays closed; a workflow the rule cannot read derives no state, as the planner then derives none.
void settleStoryStates(Map<String, dynamic> board) {
  final states = (board['states'] as List).cast<Map<String, dynamic>>();
  final rule = PlanningStoryRule.of(states);
  if (rule == null) return;
  bool closed(Object? name) => states.any((state) => state['name'] == name && state['kind'] == 'CLOSED');
  for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {
    if (closed(story['state'])) continue;
    final issues = (story['issues'] as List? ?? []).cast<Map<String, dynamic>>();
    final issueStates = [for (final issue in issues) issue['state'] as String];
    final derived = rule.state(issueStates);
    if (derived == null) continue;
    story['state'] = derived;
    final behind = rule.behind(derived, [for (final issue in issues) issue['id'] as String], issueStates);
    final warnings = [
      for (final warning in (story['warnings'] as List? ?? const []).cast<Map<String, dynamic>>())
        if (warning['kind'] != PlanningStoryRule.behindKind) warning,
      if (behind.isNotEmpty) PlanningStoryRule.warning(behind, derived),
    ];
    if (warnings.isEmpty) {
      story.remove('warnings');
    } else {
      story['warnings'] = warnings;
    }
  }
}

/// Whether a run works [issue], at work on it or recorded for a story holding it or its parent, as the planner's work
/// record holds runs per story.
bool _runHolds(List<Map<String, dynamic>> stories, Map<String, dynamic> issue) =>
    issue['atWork'] == true ||
    stories.any(
      (story) =>
          _working(story['work']) &&
          (story['issues'] as List? ?? const []).whereType<Map<dynamic, dynamic>>().any(
            (each) => each['id'] == issue['id'] || issue['parent'] is String && each['id'] == issue['parent'],
          ),
    );

/// Adds an issue the board offers as made outside the project, on a copy: it leaves the offer and stands in no story
/// in the first state, as the planner adds it.
Map<String, dynamic> applyPlanningAddUnlisted(Map<String, dynamic> source, String issue, Map<String, String> seen) {
  mockCheckSeen(source, seen, [issue]);
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final offer = (board['unlisted'] as List? ?? const []).cast<Map<String, dynamic>>();
  final offered = offer.where((each) => each['id'] == issue).firstOrNull;
  if (offered == null) throw mockRefusal('NotUnlisted', {'issue': issue});
  final first = ((board['states'] as List).cast<Map<String, dynamic>>().first)['name'];
  board['unlisted'] = [
    for (final each in offer)
      if (each['id'] != issue) each,
  ];
  if (offered['kind'] == 'story') {
    // A story gets no state of its own: it follows its issues, of which it has none yet.
    (board['stories'] as List).add({
      'id': issue,
      'title': offered['title'],
      'state': first,
      'issues': <Map<String, dynamic>>[],
    });
  } else {
    board['issues'] = [
      ...(board['issues'] as List? ?? const []),
      {
        'id': issue,
        'repository': offered['repository'],
        'title': offered['title'],
        'state': first,
        'worker': 'MANUAL',
        'blockedBy': <String>[],
      },
    ];
  }
  board['revision'] = '${source['revision']}:listed';
  board.remove('rules');
  return board;
}

/// Checks a whole story list on a copy so a refusal cannot remove any of its issues.
Map<String, dynamic> applyPlanningStoryIssues(
  Map<String, dynamic> source,
  String story,
  List<String> ids,
  Map<String, String> seen,
) {
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
  final target = stories.where((each) => each['id'] == story).firstOrNull;
  // The story and every issue the list hangs in or takes out are the items it changes.
  final before = {
    for (final issue in (target?['issues'] as List? ?? const []).whereType<Map<String, dynamic>>()) issue['id'],
  };
  mockCheckSeen(source, seen, {
    story,
    for (final id in ids)
      if (!before.contains(id)) id,
    for (final id in before.whereType<String>())
      if (!ids.contains(id)) id,
  });
  if (target == null) throw mockRefusal('NoSuchStory', {'story': story});
  void refuse(String why) => throw mockRefusal('EditNotAllowed', {'item': story, 'field': 'issues', 'reason': why});
  if (target['issues'] is! List || ids.length > 100 || ids.toSet().length != ids.length) {
    refuse('The complete story list must name each issue once, with at most 100 issues.');
  }
  final previous = (target['issues'] as List).cast<Map<String, dynamic>>();
  if (previous.length == ids.length && ids.indexed.every((entry) => previous[entry.$1]['id'] == entry.$2)) {
    return source;
  }
  final all = {
    for (final issue in (board['issues'] as List? ?? const []).cast<Map<String, dynamic>>())
      issue['id'] as String: issue,
    for (final each in stories)
      for (final issue in (each['issues'] as List? ?? const []).cast<Map<String, dynamic>>())
        issue['id'] as String: issue,
  };
  final elsewhere = {
    for (final each in stories.where((each) => each['id'] != story))
      for (final issue in (each['issues'] as List? ?? const []).cast<Map<String, dynamic>>()) issue['id'],
  };
  for (final id in ids) {
    if (previous.any((issue) => issue['id'] == id)) continue;
    final issue = all[id];
    if (issue == null) throw mockRefusal('NoSuchIssue', {'issue': id});
    if (elsewhere.contains(id) || issue['parent'] != null) {
      refuse('An issue belongs to one story and cannot be a sub-issue.');
    }
  }
  for (final issue in previous) {
    if (!ids.contains(issue['id']) && issue['atWork'] == true) throw mockRefusal('WorkRunning', {'story': story});
  }
  target['issues'] = [for (final id in ids) all[id]!];
  board['issues'] = [
    for (final entry in all.entries)
      if (!ids.contains(entry.key) && !elsewhere.contains(entry.key)) entry.value,
  ];
  settleStoryStates(board);
  board['revision'] = '${source['revision']}:issues';
  // The fake does not judge the stories' rules, so a change shows none of the breaches it named.
  board.remove('rules');
  return board;
}

/// Whether a story's run has a task at work or not ended, as the planner holds a run that works now.
bool _working(Object? work) =>
    work is Map &&
    (work['atWork'] != false ||
        (work['tasks'] as List? ?? const []).whereType<Map<dynamic, dynamic>>().any((task) => task['ended'] == null));

/// The topic labels every repository of a project carries, each with its color and description, in their order.
const planningTopicLabelStyles = <String, ({String color, String description})>{
  'documentation': (color: '0075ca', description: 'Guides, references and decisions'),
  'security': (color: 'b60205', description: 'A guard, a secret or a trust boundary'),
  'tests': (color: 'fbca04', description: 'Tests and what runs them'),
  'build': (color: '5319e7', description: 'Building, packaging, releasing and CI'),
  'dependencies': (color: '0366d6', description: 'A dependency moved or pinned'),
  'performance': (color: 'd4c5f9', description: 'Time, memory or size'),
  'usability': (color: 'c2e0c6', description: 'What a person sees, reads or types'),
};

/// Previews a correction of one warning on [source] as the planner does, writing nothing: a story's Priority and a
/// repository's Labels are the kinds it answers here, since a Move is previewed as any move, and every other kind is
/// not offered.
Map<String, dynamic> previewPlanningCorrection(Map<String, dynamic> source, Map<String, dynamic> correction) {
  if (correction['repository'] != null) return _previewLabels(source, correction);
  final id = (correction['story'] ?? correction['issue']) as String;
  final warningKind = correction['warning'] as String;
  final choice = correction['choice'] as String;
  Never refused(String reason) =>
      throw mockRefusal('CorrectionRefused', {'item': id, 'warning': warningKind, 'reason': reason});
  final items = correction['story'] != null
      ? (source['stories'] as List).cast<Map<String, dynamic>>()
      : [
          for (final story in (source['stories'] as List).cast<Map<String, dynamic>>())
            ...(story['issues'] as List? ?? const []).cast<Map<String, dynamic>>(),
          ...(source['issues'] as List? ?? const []).cast<Map<String, dynamic>>(),
        ];
  final found = items.where((item) => item['id'] == id).toList();
  if (found.isEmpty) {
    throw correction['story'] != null
        ? mockRefusal('NoSuchStory', {'story': id})
        : mockRefusal('NoSuchIssue', {'issue': id});
  }
  if (found.length > 1) refused('Its id names two items.');
  final item = found.single;
  final warnings = (item['warnings'] as List? ?? const []).cast<Map<String, dynamic>>();
  final warning = warnings.where((each) => each['kind'] == warningKind).firstOrNull;
  if (warning == null) refused('The warning no longer stands on it.');
  final holding = [
    for (final other in warnings)
      if (!identical(other, warning) && other['holds'] == true) other['text'] as String,
  ];
  if (holding.isNotEmpty) refused(holding.join(' '));
  final offered = warning['correction'];
  if (offered is! Map<String, dynamic>) throw mockRefusal('NotOffered', {'project': 'mock'});
  if (offered['kind'] == 'Move') refused('It is set right by a move of it, previewed and saved as any move.');
  if (offered['kind'] != 'Priority' || correction['story'] == null) {
    throw mockRefusal('NotOffered', {'project': 'mock'});
  }
  if (!(offered['choices'] as List).contains(choice)) refused('$choice is none of the choices it offers.');
  final targets = {id: ?mockItemVersions(source)[id]};
  return {
    'revision': source['revision'] as String,
    'digest': mockDigest(jsonEncode([targets, correction])),
    'targets': [
      for (final target in targets.entries) {'id': target.key, 'version': target.value},
    ],
    'story': id,
    'issue': null,
    'repository': null,
    'warning': warningKind,
    // The planner tells no priority before: the warning says it has none, or one it does not know.
    'from': '',
    'to': choice,
    'calls': <Object>[],
  };
}

/// Previews a repository's Labels as the planner does: each default deleted, each missing topic label created, each
/// differing one updated, in that order; its own labels are never named, and it has no targets.
Map<String, dynamic> _previewLabels(Map<String, dynamic> source, Map<String, dynamic> correction) {
  final repository = correction['repository'] as String;
  final warningKind = correction['warning'] as String;
  Never refused(String reason) =>
      throw mockRefusal('CorrectionRefused', {'item': repository, 'warning': warningKind, 'reason': reason});
  final warning = (source['repositoryWarnings'] as List? ?? const [])
      .cast<Map<String, dynamic>>()
      .where((each) => each['repository'] == repository && each['kind'] == warningKind)
      .firstOrNull;
  if (warning == null) refused('The warning no longer stands on it.');
  final offered = warning['correction'];
  if (offered is! Map<String, dynamic> || offered['kind'] != 'Labels') {
    throw mockRefusal('NotOffered', {'project': 'mock'});
  }
  if (correction['choice'] != null) refused('The choice is none of those the correction offers.');
  Map<String, dynamic> written(String action, Object? label) => {
    'action': action,
    'label': label,
    'color': planningTopicLabelStyles[label]!.color,
    'description': planningTopicLabelStyles[label]!.description,
  };
  final calls = [
    for (final label in warning['defaults'] as List)
      {'action': 'Delete', 'label': label, 'color': null, 'description': null},
    for (final label in warning['missing'] as List) written('Create', label),
    for (final label in warning['differing'] as List) written('Update', label),
  ];
  return {
    'revision': source['revision'] as String,
    'digest': mockDigest(jsonEncode([repository, warningKind, calls])),
    'targets': <Object>[],
    'story': null,
    'issue': null,
    'repository': repository,
    'warning': warningKind,
    'from': '',
    'to': '',
    'calls': calls,
  };
}

/// Saves a previewed correction on a copy of [source] as the planner does: the versions [seen] and the [digest] are
/// checked first, and only the warning corrected is lifted, at a new revision; nothing is written on a refusal.
Map<String, dynamic> applyPlanningCorrection(
  Map<String, dynamic> source,
  Map<String, dynamic> correction,
  Map<String, String> seen,
  String digest,
) {
  final preview = previewPlanningCorrection(source, correction);
  mockCheckSeen(source, seen, [
    for (final target in (preview['targets'] as List).cast<Map<String, dynamic>>()) target['id'] as String,
  ]);
  if (preview['digest'] != digest) throw mockRefusal('PreviewChanged');
  final board = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
  if (correction['repository'] case final String repository) {
    board['repositoryWarnings'] = [
      for (final warning in (board['repositoryWarnings'] as List).cast<Map<String, dynamic>>())
        if (warning['repository'] != repository || warning['kind'] != correction['warning']) warning,
    ];
  }
  for (final story in (board['stories'] as List).cast<Map<String, dynamic>>()) {
    if (story['id'] != correction['story']) continue;
    story['priority'] = correction['choice'];
    story['warnings'] = [
      for (final warning in (story['warnings'] as List? ?? const []).cast<Map<String, dynamic>>())
        if (warning['kind'] != correction['warning']) warning,
    ];
  }
  board['revision'] = '${source['revision']}:corrected';
  return board;
}
