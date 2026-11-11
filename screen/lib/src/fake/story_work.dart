import 'dart:convert';

import '../story_work_order.dart';
import 'planning_moves.dart';
import 'refusal.dart';

/// The same daemon decisions behind the socket mock and widget FakeBackend.
class MockStoryWork {
  /// Uses the same store and publication path as the planning board.
  MockStoryWork(this.boardFor, this.changed);

  /// Reads the current board for a project.
  final Map<String, dynamic> Function(String) boardFor;

  /// Publishes the resulting board after a successful action.
  final void Function(String, Map<String, dynamic>) changed;

  /// Received methods and their exact parameters.
  final List<(String, Map<String, dynamic>)> calls = [];

  /// Scenario proposals indexed by story.
  final Map<String, Map<String, dynamic>> proposals = {};

  /// Scenario findings indexed by issue.
  final Map<String, List<Map<String, dynamic>>> findings = {};

  /// Scenario work records indexed by story.
  final Map<String, Map<String, dynamic>> statuses = {};

  /// An explicit scenario refusal.
  String? refusal;

  /// Whether a scenario holds back the watch publication.
  bool deferEcho = false;
  int _revision = 0;
  int _offer = 0;
  String nextOffer() => '00000000-0000-4000-8000-${(++_offer).toRadixString(16).padLeft(12, "0")}';
  final _orders = <(String, String), Map<String, dynamic>>{};

  /// Applies one action to a copy and publishes only after validation.
  Map<String, dynamic> call(String method, Map<String, dynamic> parameters) {
    calls.add((method, jsonDecode(jsonEncode(parameters)) as Map<String, dynamic>));
    Never refuse(String name, Map<String, dynamic> fields) => throw mockRefusal('org.fuin.porelo.Tasks1.$name', fields);
    if (refusal case final detail?) {
      refuse('WorkNotAllowed', {'from': '', 'to': parameters['to'] ?? '', 'reason': detail});
    }
    final project = parameters['project'];
    if (project is! String || project.isEmpty || project.contains('/') || project.contains('\\')) {
      refuse('InvalidSettings', {'detail': 'A project name is required.'});
    }
    final board = jsonDecode(jsonEncode(boardFor(project))) as Map<String, dynamic>;
    final stories = (board['stories'] as List).cast<Map<String, dynamic>>();
    final story = stories.where((story) => story['id'] == parameters['story']).firstOrNull;
    final issue = stories
        .expand((story) => (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>())
        .where((issue) => issue['id'] == parameters['issue'])
        .firstOrNull;
    if (method == 'GetIssue') {
      if (issue == null) refuse('NoSuchIssue', {'issue': parameters['issue']});
      return {
        'issue': {...issue, 'findings': findings[issue['id']] ?? issue['findings'] ?? <dynamic>[]},
        'revision': board['revision'],
      };
    }
    if (method == 'TaskEvent') {
      final owner = stories
          .where(
            (item) =>
                item['work'] is Map &&
                ((item['work'] as Map)['tasks'] as List).any((task) => (task as Map)['task'] == parameters['task']),
          )
          .firstOrNull;
      if (owner == null) refuse('TaskEventConflict', {'task': parameters['task']});
      final work = owner['work'] as Map<String, dynamic>;
      final task = (work['tasks'] as List).cast<Map<String, dynamic>>().singleWhere(
        (task) => task['task'] == parameters['task'],
      );
      task['machine'] = parameters['machine'];
      if (parameters['event'] != 'STARTED') {
        task['atWork'] = false;
        if (parameters['event'] == 'ENDED') task['ended'] = parameters['ended'];
      }
      work['atWork'] = (work['tasks'] as List).any((task) => (task as Map)['atWork'] == true);
      if (!deferEcho) changed(project, board);
      return {'revision': board['revision'], 'work': work, 'tasks': <dynamic>[]};
    }
    if (story == null) refuse('NoSuchStory', {'story': parameters['story']});
    void checkRevision() {
      if (parameters['revision'] != board['revision']) refuse('StaleRevision', {'revision': board['revision']});
    }

    void publish() {
      settleStoryStates(board);
      board['revision'] = '${board['revision']}:work-${++_revision}';
      if (!deferEcho) changed(project, board);
    }

    Map<String, dynamic> emptyWork() => {
      'tasks': <Map<String, dynamic>>[],
      'coordinator': null,
      'since': '',
      'atWork': false,
      'repositories': <Map<String, dynamic>>[],
      'comment': '',
      'readingRounds': 2,
      'issueReadingRounds': <Map<String, dynamic>>[],
      'issues': <Map<String, dynamic>>[],
    };
    if (method == 'ResolveIssueReading') {
      checkRevision();
      if (issue == null ||
          !(story['issues'] as List).any((item) => (item as Map)['id'] == issue['id']) ||
          issue['readingStopped'] != true) {
        refuse('ReadingNotStopped', {'issue': parameters['issue']});
      }
      if (issue['rounds'] != parameters['round']) {
        refuse('ReadingRoundChanged', {'issue': issue['id'], 'rounds': issue['rounds']});
      }
      final choice = parameters['choice'];
      final settings = ['mode', 'agent', 'model'].where(parameters.containsKey).length;
      if (settings != 0 && (settings != 3 || choice != 'REWORK')) refuse('InvalidSettings', {});
      final blocking = (issue['findings'] as List? ?? const []).any(
        (finding) => finding is Map && finding['severity'] == 'BLOCKING',
      );
      if (!['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK'].contains(choice) ||
          choice == 'REWORK' && !blocking && (parameters['comment'] as String? ?? '').trim().isEmpty) {
        refuse('WorkNotAllowed', {
          'from': issue['state'],
          'to': '',
          'reason': 'Choose a reading decision; Rework needs a comment or blocking findings.',
        });
      }
      issue['readingStopped'] = false;
      issue['needsYou'] = false;
      issue['attention'] = 'NONE';
      if (choice == 'REVIEW_ANYWAY') {
        issue['state'] = 'Review';
      } else {
        final rounds = issue['rounds'] as int;
        final most = issue['roundsMost'] as int;
        issue['roundsMost'] = most > rounds ? most : rounds + 1;
        issue['atWork'] = true;
        issue['state'] = choice == 'REWORK' ? 'Reworking' : 'Reading';
        if (choice == 'ONE_MORE_ROUND') issue['rounds'] = rounds + 1;
      }
      final work = <String, dynamic>{
        ...emptyWork(),
        if (story['work'] is Map) ...story['work'] as Map<String, dynamic>,
      };
      work['issues'] = [
        for (final item in (story['issues'] as List).cast<Map<String, dynamic>>())
          {
            'issue': item['id'],
            'rounds': item['rounds'],
            'roundsMost': item['roundsMost'],
            'atWork': item['atWork'],
            'needsYou': item['needsYou'],
            'readingStopped': item['readingStopped'],
            'findings': item['findings'],
            'reason': item['readingStopped'] != true
                ? 'NONE'
                : (item['rounds'] as int? ?? 0) < (item['roundsMost'] as int? ?? 0)
                ? 'BLOCKING_FINDINGS'
                : 'ROUND_LIMIT',
          },
      ];
      story['work'] = work;
      publish();
      return {'issue': issue, 'work': work, 'revision': board['revision'], 'tasks': <dynamic>[]};
    }
    if (method == 'GetStoryWork') return statuses[story['id']] ?? {'work': story['work']};
    if (method == 'ProposeStoryWork') {
      if (proposals[story['id']] case final supplied?) return supplied;
      final target = (board['states'] as List)
          .cast<Map<String, dynamic>>()
          .where((state) => state['name'] == parameters['to'])
          .firstOrNull;
      if (target?['kind'] != 'WORKING') {
        refuse('WorkNotAllowed', {
          'from': story['state'],
          'to': parameters['to'],
          'reason': 'The target is not a working state.',
        });
      }
      final all = (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>();
      // A person works a MANUAL issue by hand, so no task is proposed for it.
      final issues = all.where((issue) => issue['worker'] != 'MANUAL').toList();
      final manual = [for (final issue in all.where((issue) => issue['worker'] == 'MANUAL')) issue['id'] as String];
      if (all.isNotEmpty && issues.isEmpty) {
        refuse('WorkNotAllowed', {
          'from': story['state'],
          'to': parameters['to'],
          'reason': 'A person works every issue of the story: ${manual.join(', ')}.',
        });
      }
      final repositories = issues.map((issue) => issue['repository'] as String).toSet();
      if (repositories.isEmpty) repositories.add('porelo-project');
      return {
        'manual': manual,
        'modes': ['UNATTENDED', 'AGENT'],
        'comment': '',
        'readingRounds': 2,
        'issueReadingRounds': <Map<String, dynamic>>[],
        'revision': board['revision'],
        'coordinator':
            stories.any(
              (other) =>
                  other['work'] is Map &&
                  (other['work'] as Map)['atWork'] == true &&
                  (other['work'] as Map)['coordinator'] != null,
            )
            ? 'TELL'
            : 'START',
        'repositories': [
          for (final repo in repositories)
            {
              'repository': repo,
              'issues': [
                for (final issue in issues)
                  if (issue['repository'] == repo) issue['id'],
              ],
              'main': {
                'name': 'main',
                'agent': 'codex',
                'model': 'proposed-model',
                'role': 'MAIN',
                'brief': 'Read the story and complete its work in $repo.',
              },
              'subAgents': [
                for (final issue in issues.where((issue) => issue['repository'] == repo))
                  for (final kind in ['BUILDER'])
                    {
                      'id': '$repo/${issue['id']}/$kind',
                      'issue': issue['id'],
                      'name': kind.toLowerCase(),
                      'agent': kind == 'READER' ? 'claude-code' : 'codex',
                      'model': kind == 'READER' ? 'reader-model' : 'proposed-model',
                      'kind': kind,
                      'workingState': kind == 'READER' ? 'Reading' : parameters['to'],
                      'brief': kind == 'READER' ? 'Read the final changes.' : 'Build the issue.',
                      'choice': 'KEEP',
                    },
              ],
            },
        ],
      };
    }
    if (method == 'StopStoryWork') {
      final work = story['work'];
      if (work is Map<String, dynamic>) {
        for (final item in (work['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
          item['reason'] = 'STOPPED';
        }
        if (!deferEcho) changed(project, board);
      }
      return {'work': work};
    }
    if (method != 'StartStoryWork') refuse('NotYetBuilt', {'method': method});
    checkRevision();
    if (story['work'] is Map && (story['work'] as Map)['atWork'] == true) {
      refuse('WorkRunning', {
        'story': story['id'],
        'tasks': [for (final task in (story['work'] as Map)['tasks'] as List) (task as Map)['task']],
      });
    }
    final proposal = call('ProposeStoryWork', {'project': project, 'story': story['id'], 'to': parameters['to']});
    void invalid(String reason) =>
        refuse('WorkNotAllowed', {'from': story['state'], 'to': parameters['to'], 'reason': reason});
    if (!(proposal['modes'] as List).contains(parameters['mode'])) invalid('The project does not allow that mode.');
    final repos = (parameters['repositories'] as List? ?? proposal['repositories'] as List)
        .cast<Map<String, dynamic>>();
    final proposed = (proposal['repositories'] as List).cast<Map<String, dynamic>>();
    if (repos.length != proposed.length || repos.map((repo) => repo['repository']).toSet().length != repos.length) {
      invalid('Choose every repository exactly once.');
    }
    for (final repo in repos) {
      final original = proposed.where((item) => item['repository'] == repo['repository']).firstOrNull;
      if (original == null) invalid('The repository was not proposed.');
      final main = repo['main'] as Map;
      if (main['role'] != 'MAIN' || (main['agent'] as String).isEmpty || (main['model'] as String).isEmpty) {
        invalid('Choose a main agent and model.');
      }
      final assignments = (repo['subAgents'] as List).cast<Map<String, dynamic>>();
      final originals = (original!['subAgents'] as List).cast<Map<String, dynamic>>();
      if (assignments.length != originals.length ||
          assignments.map((sub) => sub['id']).toSet().length != originals.length) {
        invalid('Choose every proposed helper exactly once.');
      }
      for (final sub in assignments) {
        final source = originals.where((item) => item['id'] == sub['id']).firstOrNull;
        if (source == null ||
            ['issue', 'name', 'kind', 'workingState', 'brief'].any((key) => source[key] != sub[key])) {
          invalid('A helper identity or brief was changed.');
        }
        if (!['KEEP', 'CHANGE', 'DROP'].contains(sub['choice']) ||
            sub['kind'] != 'BUILDER' && sub['choice'] != 'DROP') {
          invalid('Unknown helpers cannot start.');
        }
        if (sub['choice'] != 'KEEP' && (parameters['comment'] as String? ?? '').trim().isEmpty) {
          invalid('A changed or dropped participant needs a comment.');
        }
      }
    }
    final rounds = parameters['readingRounds'] ?? proposal['readingRounds'];
    if (rounds is! int || rounds < 1 || rounds > 10) invalid('Initial reading rounds must be between 1 and 10.');
    final overrides = parameters['issueReadingRounds'] as List? ?? proposal['issueReadingRounds'] as List;
    for (final value in overrides.cast<Map<String, dynamic>>()) {
      if (value['rounds'] is! int || (value['rounds'] as int) < 1 || (value['rounds'] as int) > 10) {
        invalid('Initial reading rounds must be between 1 and 10.');
      }
    }
    _orders[(project, story['id'] as String)] = {
      ...parameters,
      'repositories': repos,
      'readingRounds': rounds,
      'issueReadingRounds': overrides,
    };
    return {
      'revision': board['revision'],
      'tasks': [
        for (final repo in repos) ...[
          {
            'offer': nextOffer(),
            'repository': repo['repository'],
            'issues': repo['issues'],
            'role': 'MAIN',
            'agent': (repo['main'] as Map)['agent'],
            'model': (repo['main'] as Map)['model'],
            'mode': parameters['mode'],
            'prompt': (repo['main'] as Map)['brief'],
          },
          for (final sub in (repo['subAgents'] as List).cast<Map<String, dynamic>>())
            if (sub['kind'] == 'BUILDER' && sub['choice'] != 'DROP')
              {
                'offer': nextOffer(),
                'repository': repo['repository'],
                'issues': [sub['issue']],
                'role': 'BUILDER',
                'agent': sub['agent'],
                'model': sub['model'],
                'mode': parameters['mode'],
                'prompt': sub['brief'],
              },
        ],
      ],
    };
  }

  /// Runtime activity is published only for the individual task the window actually started.
  Map<String, dynamic> startTask(String project, String id, PlanningTaskOrder task) {
    final board = jsonDecode(jsonEncode(boardFor(project))) as Map<String, dynamic>;
    final story = (board['stories'] as List).cast<Map<String, dynamic>>().singleWhere((story) => story['id'] == id);
    final parameters = _orders[(project, id)]!;
    final rounds = parameters['readingRounds'] as int;
    final overrides = (parameters['issueReadingRounds'] as List).cast<Map<String, dynamic>>();
    final name =
        '${id.toLowerCase()}-${task.repository}-${task.role.toLowerCase()}-${task.issues.join('-').toLowerCase()}';
    final container = 'mock-$name';
    final work = <String, dynamic>{
      'repositories': parameters['repositories'],
      'comment': parameters['comment'] ?? '',
      'readingRounds': rounds,
      'issueReadingRounds': overrides,
      'coordinator': null,
      'since': 'mock-start',
      'atWork': true,
      'tasks': <Map<String, dynamic>>[],
      'issues': <Map<String, dynamic>>[],
      if (story['work'] is Map<String, dynamic>) ...story['work'] as Map<String, dynamic>,
    };
    work['atWork'] = true;
    work['tasks'] = [
      ...work['tasks'] as List? ?? <dynamic>[],
      {
        'task': name,
        'containerId': container,
        'machine': null,
        'repository': task.repository,
        'issues': task.issues,
        'state': parameters['to'],
        'done': 'Review',
        'mode': task.mode,
        'agent': task.agent,
        'model': task.model,
        'role': task.role,
        'since': 'mock-start',
        'atWork': true,
        'ended': null,
      },
    ];
    for (final item in (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
      if (task.issues.contains(item['id'])) item['atWork'] = true;
    }
    work['issues'] = [
      for (final item in (story['issues'] as List).cast<Map<String, dynamic>>())
        {
          'issue': item['id'],
          'rounds': item['rounds'] ?? 0,
          'roundsMost':
              overrides
                  .cast<Map<String, dynamic>>()
                  .where((entry) => entry['issue'] == item['id'])
                  .firstOrNull?['rounds'] ??
              rounds,
          'atWork': item['atWork'] == true,
          'needsYou': item['needsYou'] == true,
          'readingStopped': item['readingStopped'] == true,
          'findings': item['findings'] ?? <Map<String, dynamic>>[],
          'reason': 'NONE',
        },
    ];
    for (final item in (story['issues'] as List).cast<Map<String, dynamic>>()) {
      item['roundsMost'] =
          overrides
              .cast<Map<String, dynamic>>()
              .where((entry) => entry['issue'] == item['id'])
              .firstOrNull?['rounds'] ??
          rounds;
    }
    story['work'] = work;
    boardFor(project)['stories'] = board['stories'];
    if (!deferEcho) changed(project, board);
    return {'task': name, 'container': container, 'work': work};
  }
}
