import 'dart:convert';

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
      if (!['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK'].contains(choice) ||
          choice == 'REWORK' && (parameters['comment'] as String? ?? '').trim().isEmpty) {
        refuse('WorkNotAllowed', {
          'from': issue['state'],
          'to': '',
          'reason': 'Choose a reading decision; Rework needs a comment.',
        });
      }
      issue['readingStopped'] = false;
      issue['needsYou'] = false;
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
      final work = story['work'] is Map ? story['work'] as Map<String, dynamic> : emptyWork();
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
            'reason': item['readingStopped'] == true ? 'ROUND_LIMIT' : 'NONE',
          },
      ];
      story['work'] = work;
      publish();
      return {'issue': issue, 'work': work, 'revision': board['revision']};
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
      final issues = (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>();
      final repositories = issues.map((issue) => issue['repository'] as String).toSet();
      if (repositories.isEmpty) repositories.add('porelo-project');
      return {
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
                  for (final kind in ['BUILDER', 'READER'])
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
        work['atWork'] = false;
        for (final task in (work['tasks'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
          task['atWork'] = false;
        }
        for (final item in (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
          item['atWork'] = false;
        }
        for (final item in (work['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
          item['atWork'] = false;
        }
        publish();
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
        if (!['KEEP', 'CHANGE', 'DROP'].contains(sub['choice']) || sub['kind'] == 'OTHER' && sub['choice'] != 'DROP') {
          invalid('Unknown helpers cannot start.');
        }
        if (sub['choice'] != 'KEEP' && (parameters['comment'] as String? ?? '').trim().isEmpty) {
          invalid('A changed or dropped participant needs a comment.');
        }
      }
    }
    final rounds = parameters['readingRounds'] ?? proposal['readingRounds'];
    if (rounds is! int || rounds < 1) invalid('Reading rounds must be positive.');
    final overrides = parameters['issueReadingRounds'] as List? ?? proposal['issueReadingRounds'] as List;
    for (final value in overrides.cast<Map<String, dynamic>>()) {
      if (value['rounds'] is! int || (value['rounds'] as int) < 1) invalid('Reading rounds must be positive.');
    }
    final work = <String, dynamic>{
      'repositories': repos,
      'comment': parameters['comment'] ?? '',
      'readingRounds': rounds,
      'issueReadingRounds': overrides,
      'coordinator': repos.length > 1 ? 'coordinator' : null,
      'since': 'mock-start',
      'atWork': true,
      'tasks': [
        for (final repo in repos)
          {
            'task': repos.length == 1
                ? (story['id'] as String).toLowerCase()
                : '${(story['id'] as String).toLowerCase()}-${repo['repository']}',
            'containerId': 'mock-${story['id']}-${repo['repository']}',
            'repository': repo['repository'],
            'issues': repo['issues'],
            'state': parameters['to'],
            'done': 'Review',
            'mode': parameters['mode'],
            'agent': (repo['main'] as Map)['agent'],
            'model': (repo['main'] as Map)['model'],
            'role': 'MAIN',
            'since': 'mock-start',
            'atWork': true,
            'ended': null,
          },
        for (final repo in repos)
          for (final sub in (repo['subAgents'] as List).cast<Map<String, dynamic>>())
            if (sub['choice'] != 'DROP' &&
                sub['kind'] != 'READER' &&
                sub['kind'] != 'OTHER' &&
                sub['workingState'] == parameters['to'])
              {
                'task': '${(story['id'] as String).toLowerCase()}-${repo['repository']}-${sub['issue']}-${sub['name']}',
                'containerId': 'mock-${sub['id']}',
                'repository': repo['repository'],
                'issues': [sub['issue']],
                'state': parameters['to'],
                'done': 'Review',
                'mode': parameters['mode'],
                'agent': sub['agent'],
                'model': sub['model'],
                'role': sub['kind'],
                'since': 'mock-start',
                'atWork': true,
                'ended': null,
              },
      ],
      'issues': <Map<String, dynamic>>[],
    };
    final before = story['state'];
    story['state'] = parameters['to'];
    for (final item in (story['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
      if (item['state'] == before) {
        item['state'] = parameters['to'];
        item['atWork'] = true;
      }
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
    publish();
    return {'work': work, 'revision': board['revision']};
  }
}
