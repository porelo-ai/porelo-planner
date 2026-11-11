import 'models.dart';
import 'story_work_order.dart';

final _taskLabel = RegExp(r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$');

/// Runtime task and container labels are safe to pass to the window's task operations.
bool validTaskLabel(String value) => _taskLabel.hasMatch(value);

Never _bad(String field) => throw FormatException('The work answer has an invalid $field.');

/// Checks the fields the screen uses before treating a run as authority to act.
PlanWork readWork(Object? value) {
  if (value is! Map<String, dynamic> || value['atWork'] is! bool) _bad('work');
  if (!value.containsKey('coordinator') ||
      value['coordinator'] != null && value['coordinator'] is! String ||
      value['since'] is! String ||
      value['comment'] is! String ||
      value['readingRounds'] is! int) {
    _bad('work metadata');
  }
  for (final field in ['tasks', 'repositories', 'issues', 'issueReadingRounds']) {
    if (value[field] is! List || (value[field] as List).any((entry) => entry is! Map<String, dynamic>)) _bad(field);
  }
  for (final task in (value['tasks'] as List).cast<Map<String, dynamic>>()) {
    if (task['atWork'] is! bool || !task.containsKey('machine') || !task.containsKey('ended')) _bad('task');
    for (final field in [
      'task',
      'containerId',
      'repository',
      'state',
      'done',
      'mode',
      'agent',
      'model',
      'role',
      'since',
    ]) {
      if (task[field] is! String) _bad('task $field');
    }
    if (task['atWork'] == true &&
        (!validTaskLabel(task['task'] as String) || !validTaskLabel(task['containerId'] as String))) {
      _bad('runtime task identity');
    }
    if (task['issues'] is! List || (task['issues'] as List).any((issue) => issue is! String)) _bad('task issues');
    if (task['machine'] != null && task['machine'] is! String) _bad('machine');
    if (task['ended'] != null && task['ended'] is! Map<String, dynamic>) _bad('ended');
    if (task['ended'] case final Map<String, dynamic> ended) {
      if (ended['at'] is! String ||
          ended['finished'] is! bool ||
          ended['why'] is! String ||
          ended['answer'] is! String ||
          ended['unpushed'] is! int) {
        _bad('ended');
      }
    }
  }
  for (final repo in (value['repositories'] as List).cast<Map<String, dynamic>>()) {
    if (repo['repository'] is! String ||
        repo['issues'] is! List ||
        (repo['issues'] as List).any((issue) => issue is! String)) {
      _bad('repository');
    }
  }
  for (final issue in (value['issues'] as List).cast<Map<String, dynamic>>()) {
    if (issue['issue'] is! String ||
        issue['reason'] is! String ||
        !validRound(issue['rounds']) ||
        !validRound(issue['roundsMost'], minimum: 1)) {
      _bad('issue');
    }
    for (final field in ['atWork', 'needsYou', 'readingStopped']) {
      if (issue[field] is! bool) _bad(field);
    }
  }
  return PlanWork.from(value);
}

/// Reads every offer before any one is handed to Start.
List<PlanningTaskOrder> readFollowUps(Map<String, dynamic> map, PlanWork work) {
  final value = map['tasks'];
  if (map['revision'] is! String ||
      (map['revision'] as String).trim().isEmpty ||
      value is! List ||
      value.any((entry) => entry is! Map<String, dynamic>)) {
    _bad('follow-ups');
  }
  final tasks = [for (final task in value.cast<Map<String, dynamic>>()) PlanningTaskOrder.from(task)];
  final offers = <String>{};
  for (final task in tasks) {
    if (!offers.add(task.offer!)) _bad('repeated offer');
    if (!{'READER', 'BUILDER'}.contains(task.role) ||
        task.issues.length != 1 ||
        !{'AGENT', 'UNATTENDED'}.contains(task.mode) ||
        work.tasks.isEmpty ||
        work.tasks.any((recorded) => recorded.mode != task.mode)) {
      _bad('follow-up role, issues or mode');
    }
    final repo = work.repositories.where((repo) => repo.repository == task.repository).firstOrNull;
    if (repo == null || !task.issues.every(repo.issues.contains)) _bad('follow-up scope');
  }
  return List.unmodifiable(tasks);
}

/// Unknown or malformed findings never authorize a reading decision.
bool reportedFindings(Object? value) =>
    value is List &&
    value.every(
      (entry) =>
          entry is Map<String, dynamic> &&
          entry['number'] is int &&
          {'BLOCKING', 'NOTE'}.contains(entry['severity']) &&
          entry['text'] is String &&
          (entry['text'] as String).trim().isNotEmpty &&
          (!entry.containsKey('file') || entry['file'] is String) &&
          (!entry.containsKey('line') || entry['line'] is int),
    );

/// Runtime reading allowances can grow beyond the initial limit of ten.
bool validRound(Object? value, {int minimum = 0}) => value is int && value >= minimum && value <= 2147483647;
