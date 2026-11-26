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
  if (main is! Map<String, dynamic> || !const {'MAIN', 'READER'}.contains(main['role'])) {
    _invalid('The main worker is invalid.');
  }
  if (main['role'] == 'READER' && _records(repo['subAgents']).isNotEmpty) _invalid('A reading has no helpers.');
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
    if (sub['kind'] == 'BUILDER') {
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
    final expected = <PlanningTaskOrder>[];
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
      if (original.main.role == 'READER') {
        // A reading by hand is one reader per issue, each reading a build only the planner knows.
        for (final issue in _strings(repo['issues'])) {
          expected.add(
            PlanningTaskOrder._(
              null,
              name,
              [issue],
              'READER',
              main['agent'] as String,
              main['model'] as String,
              request.mode,
              '',
              null,
              const [],
              effort: _effort(main),
            ),
          );
        }
        continue;
      }
      expected.add(
        PlanningTaskOrder._(
          null,
          name,
          _strings(repo['issues']),
          'MAIN',
          main['agent'] as String,
          main['model'] as String,
          request.mode,
          '',
          null,
          const [],
          effort: _effort(main),
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
        if (sub['kind'] != 'BUILDER') _invalid('Only a builder may be kept or changed at start.');
        expected.add(
          PlanningTaskOrder._(
            null,
            name,
            [sub['issue'] as String],
            'BUILDER',
            sub['agent'] as String,
            sub['model'] as String,
            request.mode,
            '',
            null,
            const [],
            effort: _effort(sub),
          ),
        );
      }
    }
    if (names.length != proposal.repositories.length) _invalid('The request omits a proposed repository.');
    final tasks = [for (final task in _records(map['tasks'])) PlanningTaskOrder._read(task)];
    final offers = <String>{};
    for (final task in tasks) {
      if (!offers.add(task.offer!)) _invalid('The order repeats an offer.');
      final index = expected.indexWhere(task._matches);
      if (index < 0) _invalid('The order changes a requested task.');
      expected.removeAt(index);
    }
    if (expected.isNotEmpty) _invalid('The order omits a requested task.');
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
    this.result,
    this.reads, {
    this.effort,
    this.chosenBy,
    this.given = const [],
    this.work,
    this.runRole,
    this.place,
  });

  /// Reads one served offer without inventing missing Start arguments.
  factory PlanningTaskOrder.from(Map<String, dynamic> map) => PlanningTaskOrder._read(map);

  factory PlanningTaskOrder._read(Map<String, dynamic> map) {
    final role = _text(map, 'role');
    if (!{'MAIN', 'BUILDER', 'READER', 'DISCUSSION'}.contains(role)) _invalid('The task role is invalid.');
    final discussion = role == 'DISCUSSION';
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
        if (role == 'READER') _invalid('A reader hands back no result.');
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
    final reads = map.containsKey('reads') ? _strings(map['reads']) : const <String>[];
    // A reader started naming no build reads nothing; any other task given another's work could change it.
    if (reads.any((task) => !validTaskLabel(task))) _invalid('A task the reader reads has an invalid name.');
    if (role == 'READER' ? reads.isEmpty : reads.isNotEmpty) _invalid('Only a reader names the tasks it reads.');
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
      List.unmodifiable(reads),
      effort: _effort(map),
      chosenBy: _chosenBy(map),
      given: given,
      work: work,
      runRole: _runRole(map),
      place: map.containsKey('place') ? PlanningRunPlace.fromJson(map['place']) : null,
    );
  }

  /// Reads the run's role Start is handed, absent for an author at its own push.
  static String? _runRole(Map<String, dynamic> map) {
    if (!map.containsKey('runRole')) return null;
    final role = map['runRole'];
    if (role != 'AUTHOR' && role != 'REVIEWER') _invalid('The task run role is invalid.');
    return role as String;
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

  /// MAIN or BUILDER for an initial order, READER or BUILDER for a follow-up, DISCUSSION for an idea's discussion.
  final String role;

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

  /// The Porelo task names whose work a reader reads, passed to Start; empty for every other role.
  final List<String> reads;

  /// The tracker's issue and phase Start is handed; null for a project no tracker keeps and for a discussion.
  final PlanningWork? work;

  /// The run's role Start is handed as its `role`, `AUTHOR` or `REVIEWER`; null for an author at its own push.
  final String? runRole;

  /// Where the run works, handed to Start as its `place`; null for the task's own push. An author resumes at its gate
  /// push, which names the task it resumes, the name Start is given, or at its task's own pull request.
  final PlanningRunPlace? place;

  bool _matches(PlanningTaskOrder other) =>
      repository == other.repository &&
      listEquals(issues, other.issues) &&
      role == other.role &&
      agent == other.agent &&
      model == other.model &&
      // The effort the person confirmed, none included, is the one the window may start.
      effort == other.effort &&
      mode == other.mode &&
      // An expected reader names no build; the planner's names one, which reading it refuses otherwise.
      (role == 'READER' && other.reads.isEmpty || listEquals(reads, other.reads));
}
