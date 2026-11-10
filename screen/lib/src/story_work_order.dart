import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'models.dart';

final _repositoryName = RegExp(r'^[a-z0-9][a-z0-9._-]{0,99}$');
final _issueName = RegExp(r'^[A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?$');
const _modes = {'SHELL', 'AGENT', 'UNATTENDED'};

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

void _rounds(Object? value) {
  if (value is! int || value < 1 || value > 10) _invalid('Initial reading rounds must be between 1 and 10.');
}

void _repository(Map<String, dynamic> repo) {
  if (!_repositoryName.hasMatch(_text(repo, 'repository'))) _invalid('The repository name is invalid.');
  final issues = _strings(repo['issues'], pattern: _issueName);
  final main = repo['main'];
  if (main is! Map<String, dynamic> || main['role'] != 'MAIN') _invalid('The main worker is invalid.');
  for (final key in ['name', 'agent', 'model', 'brief']) {
    _text(main, key, blank: key == 'name' || key == 'brief', most: key == 'brief' ? 4000 : null);
  }
  final seen = <String>{};
  for (final sub in _records(repo['subAgents'])) {
    if (!seen.add(_text(sub, 'id')) || !issues.contains(_text(sub, 'issue'))) {
      _invalid('The helper identity is invalid.');
    }
    for (final key in ['name', 'kind', 'agent', 'model', 'workingState', 'brief']) {
      _text(sub, key, blank: true, most: key == 'brief' ? 4000 : null);
    }
    if (!['KEEP', 'CHANGE', 'DROP'].contains(sub['choice'])) _invalid('The helper choice is invalid.');
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
    if (revision != request.revision || revision != proposal.revision || !proposal.modes.contains(request.mode)) {
      _invalid('The order does not match the requested revision or mode.');
    }
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
          ),
        );
      }
    }
    if (names.length != proposal.repositories.length) _invalid('The request omits a proposed repository.');
    final tasks = [for (final task in _records(map['tasks'])) PlanningTaskOrder._read(task)];
    final offers = <String>{};
    for (final task in tasks) {
      if (task.offer != null && !offers.add(task.offer!)) _invalid('The order repeats an offer.');
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
    return StoryWorkProposal.from(jsonDecode(jsonEncode(map)) as Map<String, dynamic>);
  }

  /// The unchanged store revision the choices were made against.
  final String revision;

  /// All checked Start arguments, in the planner's order.
  final List<PlanningTaskOrder> tasks;
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
  );

  factory PlanningTaskOrder._read(Map<String, dynamic> map) {
    final repository = _text(map, 'repository');
    if (!_repositoryName.hasMatch(repository)) _invalid('The task repository is invalid.');
    final issues = _strings(map['issues'], pattern: _issueName);
    final role = _text(map, 'role');
    final mode = _text(map, 'mode');
    if (!_modes.contains(mode)) _invalid('The task mode is invalid.');
    final prompt = _text(map, 'prompt', blank: true);
    if (utf8.encode(prompt).length > 65536) _invalid('The task prompt exceeds 64 KiB.');
    final offer = map.containsKey('offer') ? _text(map, 'offer', most: 200) : null;
    return PlanningTaskOrder._(offer, repository, issues, role, _text(map, 'agent'), _text(map, 'model'), mode, prompt);
  }

  /// The offer identity, when the planner supplies one.
  final String? offer;

  /// The repository whose work the task owns.
  final String repository;

  /// The exact ordered issue scope chosen for this task.
  final List<String> issues;

  /// MAIN or BUILDER for an initial order.
  final String role;

  /// The chosen agent identifier.
  final String agent;

  /// The chosen model identifier.
  final String model;

  /// The chosen execution mode.
  final String mode;

  /// The complete prompt as served, preserved without trimming or rewriting.
  final String prompt;

  bool _matches(PlanningTaskOrder other) =>
      repository == other.repository &&
      listEquals(issues, other.issues) &&
      role == other.role &&
      agent == other.agent &&
      model == other.model &&
      mode == other.mode;
}
