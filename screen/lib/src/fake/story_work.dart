import 'dart:convert';

import '../models.dart' show planReasonNeedsAttention;
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
  int _run = 0;

  /// A run's id as the planner draws one when a run starts.
  String nextRun() => '00000000-0000-4000-9000-${(++_run).toRadixString(16).padLeft(12, "0")}';
  final _orders = <(String, String), Map<String, dynamic>>{};

  /// The offers handed out, by id: where each belongs, which issues it works, how it stands, and for a rework its run
  /// role and the place it works at.
  final _offers =
      <String, ({String project, String story, List<String> issues, String status, String? runRole, Object? place})>{};

  /// The place each issue's work waits at, by project and issue, as the planner keeps it.
  final _places = <(String, String), Object>{};

  /// Hands out an author's rework of [issues] at [place], as a read of a kept place offers one; the issues' kept place
  /// is [kept], the same place unless a test names another.
  String offerRework(String project, String story, List<String> issues, Object place, {Object? kept}) {
    for (final issue in issues) {
      _places[(project, issue)] = kept ?? place;
    }
    final offer = nextOffer();
    _offers[offer] = (
      project: project,
      story: story,
      issues: issues,
      status: 'OFFERED',
      runRole: 'AUTHOR',
      place: place,
    );
    return offer;
  }

  /// Whether an offer is its author's rework at the place each of its issues keeps, as the planner's atKeptPlace reads.
  bool _atKeptPlace(
    String project,
    ({String project, String story, List<String> issues, String status, String? runRole, Object? place}) offer,
  ) =>
      offer.runRole == 'AUTHOR' &&
      offer.place != null &&
      offer.issues.isNotEmpty &&
      offer.issues.every((issue) => jsonEncode(_places[(project, issue)]) == jsonEncode(offer.place));

  /// The reason an issue of [owner] asks for now: the run's word for it, else none.
  static String _reasonOf(Map<String, dynamic> owner, String issue) {
    final work = owner['work'];
    final kept = work is Map
        ? (work['issues'] as List? ?? const []).cast<Map<dynamic, dynamic>>().where((item) => item['issue'] == issue)
        : const <Map<dynamic, dynamic>>[];
    return kept.firstOrNull?['reason'] as String? ?? 'NONE';
  }

  /// The refusal an issue is served while it asks because of one, as the planner serves it.
  Map<String, dynamic>? _standingRefusal(String issue, String reason, bool needsYou) {
    if (!needsYou || (reason != 'START_REFUSED' && reason != 'PLACE_CHANGED')) return null;
    return _refusals.entries
        .where((each) => _offers[each.key]?.issues.contains(issue) ?? false)
        .map((each) => each.value)
        .firstOrNull;
  }

  /// The refusals standing on offers Porelo refused to start, by offer id.
  final _refusals = <String, Map<String, dynamic>>{};

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
    if (method == 'TaskEvent' && parameters['event'] == 'REFUSED') {
      // As the planner does: the refusal stands on the offer's issues, which ask the person, the offer stays offered,
      // a resend is answered with what stands and an offer Porelo started is a conflict. An offer whose issues left its
      // story, or whose issues all ask the person already, keeps nothing of it; an issue that asks already keeps its
      // reason; a rework at its kept place refused as NotTheTasksPush or RoleNotAllowed says its place changed.
      final offer = _offers[parameters['offer']];
      if (offer == null || offer.project != project) refuse('TaskNotAnswered', {'task': parameters['task']});
      if (offer.status == 'STARTED') refuse('TaskEventConflict', {'task': parameters['task']});
      // A story that left the board owns nothing, as the planner's NextStep.owns answers.
      final owner = stories.where((item) => item['id'] == offer.story).firstOrNull;
      final work = <String, dynamic>{
        'tasks': <Map<String, dynamic>>[],
        'coordinator': null,
        'since': '',
        'atWork': false,
        'repositories': <Map<String, dynamic>>[],
        'comment': '',
        'readingRounds': 2,
        'issueReadingRounds': <Map<String, dynamic>>[],
        'issues': <Map<String, dynamic>>[],
        if (owner?['work'] is Map) ...owner!['work'] as Map<String, dynamic>,
      };
      if (owner == null) return {'revision': board['revision'], 'work': work, 'tasks': <dynamic>[]};
      final items = (owner['issues'] as List).cast<Map<String, dynamic>>();
      final owned = offer.issues.every((id) => items.any((item) => item['id'] == id));
      final asking =
          offer.issues.isNotEmpty &&
          offer.issues.every((id) => items.any((item) => item['id'] == id && item['needsYou'] == true));
      if (offer.status == 'OFFERED' && !_refusals.containsKey(parameters['offer']) && owned && !asking) {
        final refusal = parameters['refusal'] as Map<String, dynamic>;
        _refusals[parameters['offer'] as String] = refusal;
        // The refused offer is of the run its order made, which a later Start of it goes on.
        if (_orders[(project, offer.story)]?['run'] case final run?) work['run'] = run;
        final place =
            const {'NotTheTasksPush', 'RoleNotAllowed'}.contains(refusal['name']) && _atKeptPlace(project, offer);
        final reasons = {for (final item in items) item['id']: _reasonOf(owner, item['id'] as String)};
        for (final item in items) {
          if (offer.issues.contains(item['id']) && item['needsYou'] != true) {
            item['needsYou'] = true;
            item['attention'] = place ? 'PLACE_CHANGED' : 'START_REFUSED';
            reasons[item['id']] = item['attention'] as String;
          }
        }
        work['issues'] = [
          for (final item in items)
            {
              'issue': item['id'],
              'rounds': item['rounds'] ?? 0,
              'roundsMost': item['roundsMost'] ?? 2,
              'atWork': item['atWork'] == true,
              'needsYou': item['needsYou'] == true,
              'readingStopped': item['readingStopped'] == true,
              'findings': item['findings'] ?? <Map<String, dynamic>>[],
              'reason': reasons[item['id']],
              'startRefusal': ?_standingRefusal(item['id'] as String, reasons[item['id']]!, item['needsYou'] == true),
            },
        ];
        owner['work'] = work;
        boardFor(project)['stories'] = board['stories'];
        if (!deferEcho) changed(project, board);
      }
      return {'revision': board['revision'], 'work': work, 'tasks': <dynamic>[]};
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
        if (parameters['event'] == 'ENDED') {
          task['ended'] = parameters['ended'];
          // As the planner does: a run that did not finish raises its issues, with FAILED as the reason.
          if ((parameters['ended'] as Map?)?['finished'] == false) {
            for (final item in (work['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
              if ((task['issues'] as List).contains(item['issue'])) {
                item['needsYou'] = true;
                item['reason'] = 'FAILED';
              }
            }
          }
        }
        if (parameters['event'] == 'LANDED' && task['ended'] is Map) {
          (task['ended'] as Map)['landed'] = parameters['landed'];
        }
      }
      work['atWork'] = (work['tasks'] as List).any((task) => (task as Map)['atWork'] == true);
      if (!deferEcho) changed(project, board);
      return {'revision': board['revision'], 'work': work, 'tasks': <dynamic>[]};
    }
    if (story == null) refuse('NoSuchStory', {'story': parameters['story']});
    // The versions the decision saw of the items it changes, checked as the planner checks them.
    void checkSeen(Iterable<String> targets) => mockCheckSeen(board, {
      for (final MapEntry(:key, :value) in ((parameters['seen'] as Map?) ?? const {}).entries)
        if (key is String && value is String) key: value,
    }, targets);

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
      checkSeen([parameters['issue'] as String]);
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
      // The planner stops a reading whose findings could not be read, or whose reader failed or was blocked, and keeps
      // that reason.
      final unreadable = {
        for (final item in ((story['work'] as Map?)?['issues'] as List? ?? const []).cast<Map<dynamic, dynamic>>())
          if (item['readingStopped'] == true &&
              const {'INVALID_FINDINGS', 'FAILED', 'BLOCKED'}.contains(item['reason']))
            item['issue']: item['reason'],
      };
      if (choice == 'REWORK' && unreadable.containsKey(issue['id'])) {
        refuse('ReworkNeedsFindings', {'issue': issue['id']});
      }
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
                : unreadable.containsKey(item['id'])
                ? unreadable[item['id']]
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
      final chosen = parameters['issues'] as List?;
      final issues = all
          .where((issue) => issue['worker'] != 'MANUAL' && (chosen == null || chosen.contains(issue['id'])))
          .toList();
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
                'effort': 'high',
                'chosenBy': '${parameters['to']}, risk ordinary',
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
      if (parameters['run'] case final String run? when work is! Map || work['run'] != run) {
        refuse('RunChanged', {'story': story['id'], 'reason': 'The run named is not the one standing.'});
      }
      if (work is Map<String, dynamic>) {
        for (final item in (work['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>()) {
          item['reason'] = 'STOPPED';
        }
        if (!deferEcho) changed(project, board);
      }
      return {'work': work};
    }
    if (method != 'StartStoryWork') refuse('NotYetBuilt', {'method': method});
    checkSeen([story['id'] as String]);
    final standing = story['work'] is Map ? (story['work'] as Map)['run'] : null;
    if (parameters['replaces'] case final String replaces? when replaces != standing) {
      refuse('RunChanged', {'story': story['id'], 'reason': 'The run a Start replaces is not the one standing.'});
    }
    if (parameters['passOver'] case final Map<String, dynamic> passOver?) {
      final reasons = {
        for (final item in ((story['work'] as Map?)?['issues'] as List? ?? <dynamic>[]).cast<Map<String, dynamic>>())
          item['issue']: item['reason'],
      };
      if (passOver['run'] != standing ||
          reasons[passOver['issue']] != passOver['reason'] ||
          reasons.entries.any(
            (each) =>
                each.key != passOver['issue'] &&
                planReasonNeedsAttention('${each.value}') &&
                each.value != passOver['reason'],
          )) {
        refuse('RunChanged', {'story': story['id'], 'reason': 'The reason passed over changed.'});
      }
    }
    if (story['work'] is Map && (story['work'] as Map)['atWork'] == true) {
      refuse('WorkRunning', {
        'story': story['id'],
        'tasks': [for (final task in (story['work'] as Map)['tasks'] as List) (task as Map)['task']],
      });
    }
    final proposal = call('ProposeStoryWork', {
      'project': project,
      'story': story['id'],
      'to': parameters['to'],
      'issues': ?parameters['issues'],
    });
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
    // A new order replaces the story's run: the refusals of its offers no longer stand, and an issue asks no more for
    // them, as the planner serves a refusal only on an offered offer of the current run.
    final replaced = [
      for (final MapEntry(key: id, value: offer) in _offers.entries)
        if (offer.project == project && offer.story == story['id'] && _refusals.containsKey(id)) id,
    ];
    if (replaced.isNotEmpty) {
      _refusals.removeWhere((id, _) => replaced.contains(id));
      final stored = (boardFor(project)['stories'] as List).cast<Map<String, dynamic>>().singleWhere(
        (each) => each['id'] == story['id'],
      );
      for (final item in (stored['issues'] as List? ?? const []).cast<Map<String, dynamic>>()) {
        if (const {'START_REFUSED', 'PLACE_CHANGED'}.contains(item['attention'])) {
          item['needsYou'] = false;
          item['attention'] = 'NONE';
        }
      }
      if (stored['work'] case final Map<String, dynamic> standing) {
        for (final item in (standing['issues'] as List? ?? const []).cast<Map<String, dynamic>>()) {
          if (const {'START_REFUSED', 'PLACE_CHANGED'}.contains(item['reason'])) {
            item['needsYou'] = false;
            item['reason'] = 'NONE';
            item.remove('startRefusal');
          }
        }
      }
    }
    _orders[(project, story['id'] as String)] = {
      ...parameters,
      'run': nextRun(),
      'repositories': repos,
      'readingRounds': rounds,
      'issueReadingRounds': overrides,
    };
    final tasks = [
      for (final repo in repos) ...[
        {
          'offer': nextOffer(),
          'repository': repo['repository'],
          'issues': repo['issues'],
          'role': 'MAIN',
          'agent': (repo['main'] as Map)['agent'],
          'model': (repo['main'] as Map)['model'],
          'effort': ?(repo['main'] as Map)['effort'],
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
              'effort': ?sub['effort'],
              'mode': parameters['mode'],
              'prompt': sub['brief'],
            },
      ],
    ];
    for (final task in tasks) {
      _offers[task['offer'] as String] = (
        project: project,
        story: story['id'] as String,
        issues: (task['issues'] as List).cast<String>(),
        status: 'OFFERED',
        runRole: null,
        place: null,
      );
    }
    return {'revision': board['revision'], 'tasks': tasks};
  }

  /// Runtime activity is published only for the individual task the window actually started.
  Map<String, dynamic> startTask(String project, String id, PlanningTaskOrder task) {
    final board = jsonDecode(jsonEncode(boardFor(project))) as Map<String, dynamic>;
    final story = (board['stories'] as List).cast<Map<String, dynamic>>().singleWhere((story) => story['id'] == id);
    final parameters = _orders[(project, id)]!;
    // Within the same run each issue keeps the reason it asks for; a new run starts with none.
    final sameRun = story['work'] is Map && (story['work'] as Map)['run'] == parameters['run'];
    final reasons = {
      for (final item in (story['issues'] as List).cast<Map<String, dynamic>>())
        item['id']: sameRun ? _reasonOf(story, item['id'] as String) : 'NONE',
    };
    final refusalCleared = <String>{};
    // A Start that went through after Porelo refused one clears the refusal, as the planner does.
    if (task.offer case final offer?) {
      final earlier = _offers[offer];
      if (earlier != null) {
        _offers[offer] = (
          project: earlier.project,
          story: earlier.story,
          issues: earlier.issues,
          status: 'STARTED',
          runRole: earlier.runRole,
          place: earlier.place,
        );
      }
      if (_refusals.remove(offer) != null) {
        // Only the refusal's own reasons are cleared; an issue that asks for another keeps it.
        for (final item in (story['issues'] as List).cast<Map<String, dynamic>>()) {
          if (task.issues.contains(item['id']) &&
              const {'START_REFUSED', 'PLACE_CHANGED'}.contains(_reasonOf(story, item['id'] as String))) {
            item['needsYou'] = false;
            item['attention'] = 'NONE';
            refusalCleared.add(item['id'] as String);
          }
        }
      }
    }
    final rounds = parameters['readingRounds'] as int;
    final overrides = (parameters['issueReadingRounds'] as List).cast<Map<String, dynamic>>();
    final name =
        '${id.toLowerCase()}-${task.repository}-${task.role.toLowerCase()}-${task.issues.join('-').toLowerCase()}';
    final container = 'mock-$name';
    final work = <String, dynamic>{
      'run': parameters['run'],
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
    // A Start of a new order stands in place of the run before it, as the planner's does.
    work['run'] = parameters['run'];
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
        'held': 0,
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
          'reason': refusalCleared.contains(item['id']) ? 'NONE' : reasons[item['id']],
          'startRefusal': ?_standingRefusal(
            item['id'] as String,
            refusalCleared.contains(item['id']) ? 'NONE' : reasons[item['id']]!,
            item['needsYou'] == true,
          ),
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
