import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'work_reply.dart';

final _repositoryName = RegExp(r'^[a-z0-9][a-z0-9._-]{0,99}$');
// A pull request's address in the grammar the planner's `java.net.URI` reads a host from: https, an IPv4 address or a
// host name of letter, digit and inner `-` labels whose last starts with a letter when there are several, an optional
// port, and a path of the URI's own characters and `%` escapes; no user info, query or fragment.
final _pullRequestAddress = RegExp(
  r'^https://(?:(?<ipv4>[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)'
  r'|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.?'
  r'|(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.?)'
  r'(?::(?<port>[0-9]*))?'
  r"(?:/(?:[A-Za-z0-9\-_.!~*'();:@&=+$,/]|%[0-9A-Fa-f]{2})*)?$",
);
// An issue id in the Git store's form or a tracker's reference within the project, as the description gives them.
final _trackedIssue = RegExp(r'^(?![^#]*\.\.)[A-Za-z0-9][A-Za-z0-9._-]*/[a-z0-9][a-z0-9._-]{0,99}#[1-9][0-9]{0,8}$');
final _issueName = RegExp(
  r'^([A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?|(?![^#]*\.\.)[a-z0-9][a-z0-9._-]{0,99}#[1-9][0-9]{0,8})$',
);
const _modes = {'SHELL', 'AGENT', 'UNATTENDED'};
const _stageResults = {'analysis.json', 'estimate.json', 'readiness.json'};

Never _invalid(String reason) => throw FormatException(reason);

String _text(Map<String, dynamic> map, String key, {bool blank = false, int? most}) {
  final value = map[key];
  if (value is! String || !blank && value.trim().isEmpty || most != null && value.runes.length > most) {
    _invalid('The work answer has an invalid $key.');
  }
  return value;
}

List<String> _strings(Object? value, {RegExp? pattern}) {
  if (value is! List || value.any((item) => item is! String || pattern != null && !pattern.hasMatch(item))) {
    _invalid('The work answer has an invalid list of names.');
  }
  final names = value.cast<String>();
  if (names.toSet().length != names.length) _invalid('The work answer repeats a name.');
  return List.unmodifiable(names);
}

List<Map<String, dynamic>> _records(Object? value) {
  if (value is! List || value.any((item) => item is! Map<String, dynamic>)) {
    _invalid('The work answer has an invalid list of records.');
  }
  return value.cast<Map<String, dynamic>>();
}

// An effort reaches the window's Start as a name: one line, no control or separator character, as the planner checks.
final _unsound = RegExp(r'[\u0000-\u001F\u007F-\u009F\u2028\u2029]');

// A role's name is shown as given, so it holds no format character either: none that turns the text's direction around
// it, joins, hides or marks it, such as U+202E, U+200B, U+2066 or U+FEFF.
final _format = RegExp(r'\p{Cf}', unicode: true);

/// The effort a record names: absent or null for none, else a sound name.
String? _effort(Map<String, dynamic> map) {
  final value = map['effort'];
  if (value == null) return null;
  if (value is! String || value.trim().isEmpty || value.runes.length > 128 || _unsound.hasMatch(value)) {
    _invalid('The work answer has an invalid effort.');
  }
  return value;
}

/// Where a choice came from: the planner's words, absent or a string.
String? _chosenBy(Map<String, dynamic> map) {
  final value = map['chosenBy'];
  if (value == null) return null;
  if (value is! String || value.runes.length > 4000) _invalid('The work answer has an invalid chosenBy.');
  return value;
}

void _rounds(Object? value) {
  if (value is! int || value < 1 || value > 10) _invalid('Initial reading rounds must be between 1 and 10.');
}

void _repository(Map<String, dynamic> repo) {
  if (!_repositoryName.hasMatch(_text(repo, 'repository'))) _invalid('The repository name is invalid.');
  final issues = _strings(repo['issues'], pattern: _issueName);
  final main = repo['main'];
  if (main is! Map<String, dynamic>) _invalid('The main worker is invalid.');
  final role = PlanningTaskRole.fromJson(main['role']);
  if (role.readsWork && _records(repo['subAgents']).isNotEmpty) _invalid('A reading has no helpers.');
  for (final key in ['name', 'agent', 'model', 'brief']) {
    _text(main, key, blank: key == 'name' || key == 'brief', most: key == 'brief' ? 4000 : null);
  }
  _effort(main);
  _chosenBy(main);
  final seen = <String>{};
  for (final sub in _records(repo['subAgents'])) {
    if (!seen.add(_text(sub, 'id')) || !issues.contains(_text(sub, 'issue'))) {
      _invalid('The helper identity is invalid.');
    }
    for (final key in ['name', 'kind', 'agent', 'model', 'workingState', 'brief']) {
      _text(sub, key, blank: true, most: key == 'brief' ? 4000 : null);
    }
    if (!['KEEP', 'CHANGE', 'DROP'].contains(sub['choice'])) _invalid('The helper choice is invalid.');
    _effort(sub);
    _chosenBy(sub);
    if (sub['kind'] == planningHelper) {
      _text(sub, 'agent');
      _text(sub, 'model');
    }
  }
}

/// A complete, checked order; accepting it starts no task and records no running work.
class StoryWorkOrder {
  StoryWorkOrder._(this.revision, this.tasks);

  /// Refuses the entire answer unless its scope and workers exactly match the chosen proposal.
  factory StoryWorkOrder.from(
    Map<String, dynamic> map, {
    required StoryWorkRequest request,
    required StoryWorkProposal proposal,
  }) {
    final revision = _text(map, 'revision');
    if (map.containsKey('type') || map.containsKey('error')) _invalid('The order is a refusal, not tasks.');
    // The order's revision is the board's when the planner took it, which a change to an item the decision did not
    // see may have moved past the proposal's; the versions seen bind the decision.
    if (!proposal.modes.contains(request.mode)) _invalid('The order does not match the requested mode.');
    final selected = request.toMap()['repositories'];
    final repositories = selected == null
        ? [for (final repo in proposal.repositories) repo.toMap()]
        : _records(selected);
    final expected = <_Expected>[];
    final names = <String>{};
    for (final repo in repositories) {
      _repository(repo);
      final name = repo['repository'] as String;
      if (!names.add(name)) _invalid('The request repeats a repository.');
      final original = proposal.repositories.where((item) => item.repository == name).firstOrNull;
      if (original == null || !listEquals(original.issues, _strings(repo['issues']))) {
        _invalid('The request changes the proposed issues.');
      }
      final main = repo['main'] as Map<String, dynamic>;
      if (main['name'] != original.main.name || main['brief'] != original.main.brief) {
        _invalid('The request changes the main identity or brief.');
      }
      final helpers = _records(repo['subAgents']);
      if (helpers.length != original.subAgents.length) _invalid('The request omits a proposed helper.');
      // The role offered is the proposal's, and one sent back is that role in every member.
      final role = original.main.role;
      if (role == null || PlanningTaskRole.fromJson(main['role']) != role) {
        _invalid('The request changes the offered role.');
      }
      if (role.readsWork) {
        // A reading by hand is one task per issue, each reading a build only the planner knows.
        for (final issue in _strings(repo['issues'])) {
          expected.add(
            _Expected(
              name,
              [issue],
              role,
              main['agent'] as String,
              main['model'] as String,
              request.mode,
              _effort(main),
            ),
          );
        }
        continue;
      }
      expected.add(
        _Expected(
          name,
          _strings(repo['issues']),
          role,
          main['agent'] as String,
          main['model'] as String,
          request.mode,
          _effort(main),
        ),
      );
      for (final sub in helpers) {
        final source = original.subAgents.where((item) => item.id == sub['id']).firstOrNull;
        if (source == null ||
            ['issue', 'name', 'kind', 'workingState', 'brief'].any((key) => source.toMap()[key] != sub[key])) {
          _invalid('The request changes a helper identity or brief.');
        }
        if (sub['choice'] == 'KEEP' && (sub['agent'] != source.agent || sub['model'] != source.model)) {
          _invalid('A kept helper changes its proposed agent or model.');
        }
        if (sub['choice'] == 'KEEP' && _effort(sub) != null && _effort(sub) != source.effort) {
          _invalid('A kept helper changes its proposed effort.');
        }
        if (sub['choice'] == 'DROP') continue;
        if (sub['kind'] != planningHelper) _invalid('Only a builder may be kept or changed at start.');
        // A helper has no role of its own: its task is started with a role that pushes work.
        expected.add(
          _Expected(
            name,
            [sub['issue'] as String],
            null,
            sub['agent'] as String,
            sub['model'] as String,
            request.mode,
            _effort(sub),
          ),
        );
      }
    }
    if (names.length != proposal.repositories.length) _invalid('The request omits a proposed repository.');
    final tasks = [for (final task in _records(map['tasks'])) PlanningTaskOrder._read(task)];
    final offers = <String>{};
    for (final task in tasks) {
      if (!offers.add(task.offer!)) _invalid('The order repeats an offer.');
      final index = expected.indexWhere((each) => each.matches(task));
      if (index < 0) _invalid('The order changes a requested task.');
      expected.removeAt(index);
    }
    if (expected.isNotEmpty) _invalid('The order omits a requested task.');
    if (tasks.where((task) => task.opensTerminal).length > 1) _invalid('The order opens more than one terminal.');
    return StoryWorkOrder._(revision, List.unmodifiable(tasks));
  }

  /// Reads the served proposal without dropping malformed repositories, workers or limits.
  static StoryWorkProposal readProposal(Map<String, dynamic> map) {
    if (map.containsKey('type') || map.containsKey('error')) _invalid('The proposal is a refusal.');
    _text(map, 'revision');
    final modes = _strings(map['modes']);
    if (modes.isEmpty || !modes.every(_modes.contains)) _invalid('The proposal has an invalid mode.');
    if (!['START', 'TELL'].contains(_text(map, 'coordinator'))) _invalid('The coordinator choice is invalid.');
    _text(map, 'comment', blank: true, most: 4000);
    _rounds(map['readingRounds']);
    final repositories = _records(map['repositories']);
    if (repositories.isEmpty) _invalid('The proposal has no repository.');
    final names = <String>{};
    final issues = <String>{};
    for (final repo in repositories) {
      _repository(repo);
      if (!names.add(repo['repository'] as String)) _invalid('The proposal repeats a repository.');
      for (final issue in _strings(repo['issues'])) {
        if (!issues.add(issue)) _invalid('The proposal repeats an issue.');
      }
    }
    final limits = <String>{};
    for (final limit in _records(map['issueReadingRounds'])) {
      final issue = _text(limit, 'issue');
      if (!issues.contains(issue) || !limits.add(issue)) _invalid('The proposal has an invalid issue limit.');
      _rounds(limit['rounds']);
    }
    // An older planner names no issue a person works; one named beside a task would be worked twice.
    if (map.containsKey('manual') && _strings(map['manual'], pattern: _issueName).any(issues.contains)) {
      _invalid('The proposal gives an issue a person works to a task.');
    }
    return StoryWorkProposal.from(jsonDecode(jsonEncode(map)) as Map<String, dynamic>);
  }

  /// The board's revision when the planner took the order.
  final String revision;

  /// All checked Start arguments, in the planner's order.
  final List<PlanningTaskOrder> tasks;
}

/// One file a task is given to read: an issue's text by its id.
class PlanningGivenFile {
  /// A given file of [name], `<issue>.md` with a tracker's `#` as `-`, holding [text].
  const PlanningGivenFile(this.name, this.text);

  /// The plain file name.
  final String name;

  /// The issue's text.
  final String text;
}

/// Whether a pull request's address is one the planner takes: at most 2048 characters in [_pullRequestAddress], each
/// part of an IPv4 address at most 255 and a port within Java's `int`. An IPv6 address, which the planner takes, is
/// refused here.
bool _pullRequestTaken(String address) {
  if (address.length > 2048) return false;
  final match = _pullRequestAddress.firstMatch(address);
  if (match == null) return false;
  final ipv4 = match.namedGroup('ipv4');
  final port = match.namedGroup('port');
  return (ipv4 == null || ipv4.split('.').every((part) => (int.tryParse(part) ?? 256) <= 255)) &&
      (port == null || port.isEmpty || (int.tryParse(port) ?? 0x80000000) <= 0x7fffffff);
}

/// The plain name of an issue's given file without `.md`: its id, a tracker's `#` as `-`, so no mounted name holds one.
String _givenName(String issue) => issue.replaceAll('#', '-');

/// Where a run works, as Start is handed it: exactly one of a push at the gate, a pull request by the branch it comes
/// from, or the task's own pull request.
@immutable
class PlanningRunPlace {
  const PlanningRunPlace._({this.gatePush, this.gateRepository, this.pullRequest, this.ownPullRequest = false});

  /// Reads the planner's `place` object, refusing one naming not exactly one place.
  factory PlanningRunPlace.fromJson(Object? value) {
    if (value is! Map<String, dynamic> || value.length != 1) _invalid('The task place names not one place.');
    if (value['gatePush'] case final Map<String, dynamic> gate) {
      if (gate.keys.any((key) => key != 'name' && key != 'repository')) _invalid('The gate push is invalid.');
      final name = gate['name'];
      if (name is! String || !validTaskLabel(name)) _invalid('The gate push names no task.');
      final repository = gate['repository'];
      if (repository != null && (repository is! String || !_repositoryName.hasMatch(repository))) {
        _invalid('The gate push names an invalid repository.');
      }
      return PlanningRunPlace._(gatePush: name, gateRepository: repository as String?);
    }
    if (value['pullRequest'] case final String address when _pullRequestTaken(address)) {
      return PlanningRunPlace._(pullRequest: address);
    }
    if (value['ownPullRequest'] == true) return const PlanningRunPlace._(ownPullRequest: true);
    _invalid('The task place is invalid.');
  }

  /// The task whose push waits at the gate, by its name within the project; null for another place.
  final String? gatePush;

  /// The push's repository; null for the task's own.
  final String? gateRepository;

  /// A pull request by its https address; null for another place.
  final String? pullRequest;

  /// Whether the place is the task's own pull request.
  final bool ownPullRequest;

  /// The planner's `place` object, as Start takes it.
  Map<String, dynamic> toJson() => {
    if (gatePush != null) 'gatePush': {'name': gatePush, if (gateRepository != null) 'repository': gateRepository},
    if (pullRequest != null) 'pullRequest': pullRequest,
    if (ownPullRequest) 'ownPullRequest': true,
  };

  @override
  bool operator ==(Object other) =>
      other is PlanningRunPlace &&
      other.gatePush == gatePush &&
      other.gateRepository == gateRepository &&
      other.pullRequest == pullRequest &&
      other.ownPullRequest == ownPullRequest;

  @override
  int get hashCode => Object.hash(gatePush, gateRepository, pullRequest, ownPullRequest);
}

/// Porelo's fixed words for what a task may do: the actions at its place, the rights of its own endpoint and the one
/// judgment, `reject`.
const planningRights = ['push', 'reply', 'openThreads', 'resolveThreads', 'start', 'stop', 'done', 'report', 'reject'];

/// The one right that is a judgment of the work rather than an act.
const planningJudgment = 'reject';

/// How a task of a role ends, from the planner's fixed list.
enum PlanningEndKind {
  /// It pushes work.
  pushesWork('PUSHES_WORK'),

  /// It reports findings.
  reportsFindings('REPORTS_FINDINGS'),

  /// It gives points at a place.
  givesPoints('GIVES_POINTS'),

  /// It hands back a result.
  handsBackResult('HANDS_BACK_RESULT');

  const PlanningEndKind(this.wire);

  /// The planner's word for it.
  final String wire;
}

const _workspaces = {'own', 'foreign', 'readOnly', 'allRepositories', 'none'};
const _writeAs = {'author', 'review'};

/// A role of the project's tasks, as its work rules define it: its name, how its task ends and the parts of a start.
/// The screen knows no role by its name; what it decides, it decides by the end kind and the rights.
@immutable
class PlanningTaskRole {
  /// A role named [name].
  const PlanningTaskRole({
    required this.name,
    required this.endKind,
    required this.workspace,
    required this.readLanded,
    required this.projectServices,
    required this.writeAs,
    required this.rights,
  });

  /// Reads the planner's role, refusing anything but its exact shape.
  factory PlanningTaskRole.fromJson(Object? value) {
    const keys = {'name', 'endKind', 'workspace', 'readLanded', 'projectServices', 'writeAs', 'rights'};
    if (value is! Map<String, dynamic> || value.length != keys.length || !value.keys.every(keys.contains)) {
      _invalid('The task role is not a role.');
    }
    final name = value['name'];
    // Shown as given, so one line of at most 64 code points that reorders nothing around it.
    if (name is! String ||
        name.isEmpty ||
        name.runes.length > 64 ||
        _unsound.hasMatch(name) ||
        _format.hasMatch(name)) {
      _invalid('The task role has an invalid name.');
    }
    final endKind = PlanningEndKind.values.where((kind) => kind.wire == value['endKind']).firstOrNull;
    if (endKind == null) _invalid('The task role has an invalid end kind.');
    final workspace = value['workspace'];
    if (workspace is! String || !_workspaces.contains(workspace)) _invalid('The task role has an invalid workspace.');
    final writeAs = value['writeAs'];
    if (writeAs is! String || !_writeAs.contains(writeAs)) _invalid('The task role writes as no known account.');
    if (value['readLanded'] is! bool || value['projectServices'] is! bool) _invalid('The task role is not a role.');
    // A task at another task's work reads it with that task's checkout and never runs the project's services there.
    if (workspace == 'foreign' && value['projectServices'] == true) {
      _invalid("A role at another task's work has no project services.");
    }
    return PlanningTaskRole(
      name: name,
      endKind: endKind,
      workspace: workspace,
      readLanded: value['readLanded'] as bool,
      projectServices: value['projectServices'] as bool,
      writeAs: writeAs,
      rights: _rights(value['rights']),
    );
  }

  /// Reads a role where one that cannot be read is shown as not reported; null then.
  static PlanningTaskRole? read(Object? value) {
    try {
      return PlanningTaskRole.fromJson(value);
    } on FormatException {
      return null;
    }
  }

  /// The role's name in the project's work rules.
  final String name;

  /// How a task of the role ends.
  final PlanningEndKind endKind;

  /// The kind of its workspace in Porelo's words: `own`, `foreign`, `readOnly`, `allRepositories` or `none`.
  final String workspace;

  /// Whether a task of the role also reads the forwarded work of the tasks whose build it reads.
  final bool readLanded;

  /// Whether a task of the role has the project's services.
  final bool projectServices;

  /// The account its released words are written as at a pull request: `author` or `review`.
  final String writeAs;

  /// Its actions and judgments, in Porelo's words.
  final List<String> rights;

  /// Whether its task pushes work, as a building task does.
  bool get pushesWork => endKind == PlanningEndKind.pushesWork;

  /// Whether its task hands back a result file instead of pushing.
  bool get handsBackResult => endKind == PlanningEndKind.handsBackResult;

  /// Whether its task reads work and ends by what it found or the points it gave, delivering only its answer.
  bool get readsWork => endKind == PlanningEndKind.reportsFindings || endKind == PlanningEndKind.givesPoints;

  /// The planner's role object.
  Map<String, dynamic> toJson() => {
    'name': name,
    'endKind': endKind.wire,
    'workspace': workspace,
    'readLanded': readLanded,
    'projectServices': projectServices,
    'writeAs': writeAs,
    'rights': rights,
  };

  @override
  bool operator ==(Object other) =>
      other is PlanningTaskRole &&
      other.name == name &&
      other.endKind == endKind &&
      other.workspace == workspace &&
      other.readLanded == readLanded &&
      other.projectServices == projectServices &&
      other.writeAs == writeAs &&
      listEquals(other.rights, rights);

  @override
  int get hashCode =>
      Object.hash(name, endKind, workspace, readLanded, projectServices, writeAs, Object.hashAll(rights));
}

/// Reads a list of Porelo's rights, each once, refusing a word Porelo does not know.
List<String> _rights(Object? value) {
  final rights = _strings(value);
  if (!rights.every(planningRights.contains)) _invalid('The work answer names a right Porelo does not know.');
  return rights;
}

/// The tracker's issue a planned stage's task works and the stage's phase, as Start is handed them.
class PlanningWork {
  /// The issue [issue], `<owner>/<repository>#<number>`, worked in [phase].
  const PlanningWork(this.issue, this.phase);

  /// Reads the planner's `work`, refusing anything but its exact shape.
  factory PlanningWork.fromJson(Object? value) {
    if (value is! Map<String, dynamic> || value.length != 2) _invalid('The task work is not an issue and a phase.');
    final issue = _text(value, 'issue', most: 200);
    // One line without white space, so Start names exactly one tracker issue.
    if (!_trackedIssue.hasMatch(issue)) _invalid('The task work names no tracker issue.');
    final phase = _text(value, 'phase', most: 64);
    if (_unsound.hasMatch(phase)) _invalid('The task work has an invalid phase.');
    return PlanningWork(issue, phase);
  }

  /// The tracker's issue with its owner.
  final String issue;

  /// The name of the stage's phase.
  final String phase;

  /// The planner's `work` object.
  Map<String, dynamic> toJson() => {'issue': issue, 'phase': phase};

  @override
  bool operator ==(Object other) => other is PlanningWork && other.issue == issue && other.phase == phase;

  @override
  int get hashCode => Object.hash(issue, phase);
}

/// The planner's exact arguments for one future Start, with no runtime identity inferred.
class PlanningTaskOrder {
  PlanningTaskOrder._(
    this.offer,
    this.repository,
    this.issues,
    this.role,
    this.agent,
    this.model,
    this.mode,
    this.prompt,
    this.result, {
    this.discussion = false,
    this.opensTerminal = false,
    this.effort,
    this.chosenBy,
    this.given = const [],
    this.work,
    this.place,
    this.readOnly = false,
    this.allRepositories = false,
    this.noGate = false,
    this.rights = const [],
    this.readLanded = const [],
    this.projectServices = false,
    this.writeAs,
    this.task,
    this.ownTask = false,
  });

  /// Reads one served offer without inventing missing Start arguments.
  factory PlanningTaskOrder.from(Map<String, dynamic> map) => PlanningTaskOrder._read(map);

  // Every member the order may hold: one for a part of Start the planner never hands on, such as credentials, would
  // reach Porelo unread.
  static const _members = {
    'offer',
    'repository',
    'issues',
    'kind',
    'role',
    'task',
    'ownTask',
    'opensTerminal',
    'agent',
    'model',
    'effort',
    'chosenBy',
    'mode',
    'prompt',
    'result',
    'given',
    'readOnly',
    'allRepositories',
    'noGate',
    'rights',
    'readLanded',
    'projectServices',
    'writeAs',
    'work',
    'place',
  };

  factory PlanningTaskOrder._read(Map<String, dynamic> map) {
    if (map.keys.any((key) => !_members.contains(key))) _invalid('The task names a member Start is not handed.');
    final kind = map['kind'];
    if (kind != 'work' && kind != 'discussion') _invalid('The task kind is invalid.');
    final discussion = kind == 'discussion';
    if (discussion == map.containsKey('role')) _invalid('Work names its role and a discussion none.');
    final role = discussion ? null : PlanningTaskRole.fromJson(map['role']);
    final issues = _strings(map['issues'], pattern: _issueName);
    final mode = _text(map, 'mode');
    if (!_modes.contains(mode)) _invalid('The task mode is invalid.');
    String? repository;
    String? result;
    if (discussion) {
      // A discussion reads every repository of the project; one named here would narrow or redirect its Start.
      if (map.containsKey('repository')) _invalid('A discussion names no repository.');
      if (mode != 'AGENT' || issues.isNotEmpty) _invalid('A discussion is an agent task over no issue.');
      // A discussion reports its idea back through Porelo's Report; a file it handed back would be read by nobody.
      if (map.containsKey('result')) _invalid('A discussion hands back no result file.');
    } else {
      repository = _text(map, 'repository');
      if (!_repositoryName.hasMatch(repository)) _invalid('The task repository is invalid.');
      if (map.containsKey('result')) {
        // A stage that hands back its result writes one of the planner's three files, never an idea.
        if (!role!.handsBackResult) _invalid('Only a role that hands back a result names one.');
        result = _text(map, 'result');
        if (!_stageResults.contains(result)) {
          _invalid('A stage hands back analysis.json, estimate.json or readiness.json.');
        }
      }
    }
    final given = map.containsKey('given') ? _given(map['given'], issues) : const <PlanningGivenFile>[];
    if (given.isNotEmpty && (discussion || result == null)) _invalid('Only a stage with a result is given files.');
    final prompt = _text(map, 'prompt', blank: true);
    if (utf8.encode(prompt).length > 65536) _invalid('The task prompt exceeds 64 KiB.');
    final readLanded = _strings(map['readLanded']);
    if (readLanded.any((task) => !validTaskLabel(task))) _invalid('A task whose work it reads has an invalid name.');
    // Another task's branches are given read only, and only to a role that reads them besides; a task at another
    // task's work reads that work through its place.
    if (readLanded.isNotEmpty && role?.readLanded != true) _invalid('Only a role that reads landed work names it.');
    // Only a planned stage names a tracker's issue; a discussion works over no issue.
    if (map.containsKey('work') && discussion) _invalid('A discussion names no tracker issue.');
    final work = map.containsKey('work') ? PlanningWork.fromJson(map['work']) : null;
    // Start names the tracker's issue the planner ordered, never one beyond the task's own issues.
    if (work != null && !issues.contains(work.issue.substring(work.issue.indexOf('/') + 1))) {
      _invalid('The task work names an issue the task does not work.');
    }
    final offer = _text(map, 'offer');
    if (!RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$').hasMatch(offer)) {
      _invalid('The task offer is not a UUID.');
    }
    final place = map.containsKey('place') ? PlanningRunPlace.fromJson(map['place']) : null;
    String? writeAs;
    if (map.containsKey('writeAs')) {
      writeAs = map['writeAs'] is String && _writeAs.contains(map['writeAs']) ? map['writeAs'] as String : null;
      if (writeAs == null) _invalid('The task writes as no known account.');
      // Start refuses an account to write as anywhere but at a pull request.
      if (place?.pullRequest == null && place?.ownPullRequest != true) {
        _invalid('Only a task at a pull request writes as an account.');
      }
    }
    String? task;
    if (map.containsKey('task')) {
      task = map['task'] is String && validTaskLabel(map['task'] as String) ? map['task'] as String : null;
      if (task == null) _invalid('The task to run again has an invalid name.');
      // Only a task's own work is run again; at another task's work Start would hand it that task's workspace.
      if (discussion || role!.workspace == 'foreign') _invalid('Only work at its own place runs a task again.');
      // A task is run again only where it left its work: its gate push, which names it, or its own pull request.
      if (place == null || place.pullRequest != null) _invalid('A task is run again only at its own place.');
      if (place.gatePush != null && place.gatePush != task) {
        _invalid('A task run again at a gate push is the task whose push waits there.');
      }
    }
    // An author sent back to its own work resumes its task; a new task in its place would leave the work behind.
    if (role?.workspace == 'own' && (place?.gatePush != null || place?.ownPullRequest == true) && task == null) {
      _invalid('Work sent back to its own place names the task it runs again.');
    }
    final ownTask = _onlyTrue(map, 'ownTask');
    // A name of the window's own choosing and the name of a task run again exclude each other.
    if (ownTask && task != null) _invalid('A task run again is asked under its own name.');
    // A task at another task's work reads it at that task's place, under a name of its own, never its own work under
    // that task's name.
    if (role?.workspace == 'foreign' && (place == null || !ownTask)) {
      _invalid("A task at another task's work names its place and a task name of its own.");
    }
    if (map['projectServices'] is! bool) _invalid('The task names no project services.');
    final projectServices = map['projectServices'] as bool;
    if (role != null && projectServices != role.projectServices) {
      _invalid('The task has the project services other than its role.');
    }
    final readOnly = _onlyTrue(map, 'readOnly');
    final allRepositories = _onlyTrue(map, 'allRepositories');
    final noGate = _onlyTrue(map, 'noGate');
    // Start's workspace members are the role's workspace in Start's words; a discussion reads every repository.
    final workspace = discussion ? 'allRepositories' : role!.workspace;
    if (readOnly != (workspace == 'readOnly' || workspace == 'allRepositories') ||
        allRepositories != (workspace == 'allRepositories') ||
        noGate != (workspace == 'none')) {
      _invalid("The task's workspace members are not its role's workspace.");
    }
    final rights = _rights(map['rights']);
    // The person reads the role's rights; Porelo would be handed others.
    if (role != null && !setEquals(rights.toSet(), role.rights.toSet())) {
      _invalid('The task holds other rights than its role.');
    }
    return PlanningTaskOrder._(
      offer,
      repository,
      issues,
      role,
      _text(map, 'agent', most: 128),
      _text(map, 'model', most: 128),
      mode,
      prompt,
      result,
      discussion: discussion,
      opensTerminal: _onlyTrue(map, 'opensTerminal'),
      effort: _effort(map),
      chosenBy: _chosenBy(map),
      given: given,
      work: work,
      place: place,
      readOnly: readOnly,
      allRepositories: allRepositories,
      noGate: noGate,
      rights: rights,
      readLanded: readLanded,
      projectServices: projectServices,
      writeAs: writeAs,
      task: task,
      ownTask: ownTask,
    );
  }

  /// A member present only as true: absent is false, and any other value refuses the task.
  static bool _onlyTrue(Map<String, dynamic> map, String key) {
    if (!map.containsKey(key)) return false;
    if (map[key] != true) _invalid('The task names $key other than as true.');
    return true;
  }

  /// Reads the given files: each owned issue's text once, by its id, within what one task is given.
  static List<PlanningGivenFile> _given(Object? value, List<String> issues) {
    final files = <PlanningGivenFile>[];
    final names = <String>{};
    var all = 0;
    for (final item in _records(value)) {
      final name = _text(item, 'name');
      final text = _text(item, 'text', blank: true);
      if (!issues.any((issue) => name == '${_givenName(issue)}.md') || !names.add(name)) {
        _invalid('A given file is not one of the task\'s issues, once.');
      }
      final bytes = utf8.encode(text).length;
      if (bytes > 65536) _invalid('A given file exceeds 64 KiB.');
      all += bytes;
      files.add(PlanningGivenFile(name, text));
    }
    if (files.length > 16 || all > 1048576) _invalid('The given files exceed 16 files or 1 MiB.');
    return List.unmodifiable(files);
  }

  /// The effort Start is handed, null for none.
  final String? effort;

  /// Where the rule's choice of agent, model and effort came from, as the planner says it; null when unsaid.
  final String? chosenBy;

  /// The required offer identity in a checked order.
  final String? offer;

  /// The repository whose work the task owns; null for a discussion, which reads every repository of the project.
  final String? repository;

  /// The exact ordered issue scope chosen for this task.
  final List<String> issues;

  /// The role bound to the offer, for the person to read; null for a discussion, which has none. Start is handed no
  /// role, only the members below.
  final PlanningTaskRole? role;

  /// Whether the task discusses an idea with the person, the order's kind `discussion`, rather than work a rule
  /// offers.
  final bool discussion;

  /// The order's kind, `work` or `discussion`.
  String get kind => discussion ? 'discussion' : 'work';

  /// Whether the window opens its terminal on this task, as the planner marked at most one of an answer.
  final bool opensTerminal;

  /// Start's `readOnly`, for a task that only reads.
  final bool readOnly;

  /// Start's `allRepositories`, for a task that reads every repository of the project.
  final bool allRepositories;

  /// Start's `noGate`, for a task without a workspace.
  final bool noGate;

  /// Start's `rights`, always named: the role's actions and judgments in one list, empty for none.
  final List<String> rights;

  /// Start's `readLanded`: the Porelo task names whose forwarded work the task also reads, read only.
  final List<String> readLanded;

  /// Start's `projectServices`: whether the task has the project's services.
  final bool projectServices;

  /// Start's `writeAs`, `author` or `review`, the account its released words are written as at a pull request;
  /// null elsewhere.
  final String? writeAs;

  /// Start's `task`: the existing Porelo task an author sent back to its own work runs again; null for a new task.
  final String? task;

  /// Start's `ownTask`: Start is asked under a task name of the window's own choosing, for a task at another task's
  /// work and for the building work of an issue its run's start held back.
  final bool ownTask;

  /// The chosen agent identifier.
  final String agent;

  /// The chosen model identifier.
  final String model;

  /// The chosen execution mode.
  final String mode;

  /// The complete prompt as served, preserved without trimming or rewriting.
  final String prompt;

  /// The plain file name a stage that hands back its result writes in its result directory; null for work that
  /// pushes and for a discussion, which reports its idea back.
  final String? result;

  /// The issues' texts a stage's task reads, read only, as the planner holds them now; empty for work that pushes.
  final List<PlanningGivenFile> given;

  /// The tracker's issue and phase Start is handed; null for a project no tracker keeps and for a discussion.
  final PlanningWork? work;

  /// Where the run works, handed to Start as its `place`; null for a new task's own push. A task sent back to its own
  /// work resumes at its gate push, which names the task it resumes, the name Start is given, or at its task's own
  /// pull request; a task at another task's work always names the push or pull request it reads.
  final PlanningRunPlace? place;
}

/// A task the person's choices ask the order for: its scope, agent, model, effort and mode, and either the role
/// offered, in every member, or, for a helper that builds, null, which any role that pushes work answers.
class _Expected {
  const _Expected(this.repository, this.issues, this.role, this.agent, this.model, this.mode, this.effort);

  final String repository;
  final List<String> issues;
  final PlanningTaskRole? role;
  final String agent, model, mode;
  final String? effort;

  bool matches(PlanningTaskOrder order) =>
      !order.discussion &&
      repository == order.repository &&
      listEquals(issues, order.issues) &&
      (role == null ? order.role!.pushesWork : role == order.role) &&
      agent == order.agent &&
      model == order.model &&
      // The effort the person confirmed, none included, is the one the window may start.
      effort == order.effort &&
      mode == order.mode;
}
