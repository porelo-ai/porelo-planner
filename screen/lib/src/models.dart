/// The planning records as the planning service reports them; an absent field stays absent.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart' show immutable;

import 'story_work_order.dart';
import 'work_reply.dart';

String _planText(Map<String, dynamic> map, String key) => map[key] is String ? map[key] as String : '';

/// The line the planner ends a story's body with when it made the story from issues that stand: a hidden mark of the
/// request, which the screen, rendering no HTML, would show as text.
final _marker = RegExp(r'\n*^[ \t]*<!-- porelo-planner story from issues [0-9a-f]{64} -->[ \t]*$\n?', multiLine: true);

/// [text] without the planner's marker line; a comment a person wrote stays.
String _withoutMarker(String text) => text.replaceAll(_marker, '\n');

List<String> _planStrings(Object? value) =>
    value is List ? value.whereType<String>().where((text) => text.trim().isNotEmpty).toList() : const [];
bool _planMapsReported(Object? value) => value is List && value.every((entry) => entry is Map<String, dynamic>);
bool _planStringsReported(Object? value) =>
    value is List && value.every((entry) => entry is String && entry.trim().isNotEmpty);
List<T> _planList<T>(Object? value, T Function(Map<String, dynamic>) read) =>
    value is List ? value.whereType<Map<String, dynamic>>().map(read).toList() : <T>[];

/// Removes directional, control and invisible format characters only for display; wire records remain unchanged.
String planningShown(String text, {bool multiline = true}) => text.replaceAll(
  RegExp(
    multiline
        ? r'[\x00-\x08\x0b-\x1f\x7f-\x9f\u2028\u2029\u061c\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u206f\ufeff]'
        : r'[\x00-\x1f\x7f-\x9f\u061c\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u206f\ufeff\u2028\u2029]',
  ),
  '',
);

/// What the store could not read in a story or an issue it still shows: an open [kind] and one sentence saying what
/// is wrong.
@immutable
class PlanWarning {
  /// Takes a warning's parts.
  const PlanWarning(this.kind, this.text, {this.holds = false, this.correction});

  /// Reads one warning; a field that is no text reads as empty, a hold that is not true as none, and a correction the
  /// window cannot read as none offered.
  PlanWarning.from(Map<String, dynamic> map)
    : kind = _planText(map, 'kind'),
      text = _planText(map, 'text'),
      holds = map['holds'] == true,
      correction = PlanCorrection.read(map['correction']);

  /// The kind of an id that names another item too, whose own page no read by that id finds.
  static const idTwice = 'IdTwice';

  /// The kind of a text without the sections the planner reads, which the person repairs where the item is kept.
  static const bodyNotSectioned = 'BodyNotSectioned';

  /// The one change the store offers to set it right, or null when it offers none.
  final PlanCorrection? correction;

  /// The planner's name of what is wrong, such as StateOutsideWorkflow; one the window does not know is shown as any.
  final String kind;

  /// The sentence the person reads, empty when unsaid.
  final String text;

  /// Whether the planner holds work, edits and moves on the item for it, but a move out of a state the workflow does
  /// not have.
  final bool holds;

  /// Whether one of [warnings] holds its item.
  static bool holding(List<PlanWarning> warnings) => warnings.any((warning) => warning.holds);

  /// Whether one of [warnings] says the item's id names another item too.
  static bool namesTwice(List<PlanWarning> warnings) => warnings.any((warning) => warning.kind == idTwice);

  /// The warnings of an item, none when the planner named none, as an older one does.
  static List<PlanWarning> read(Object? value) => [
    for (final warning in _planList(value, PlanWarning.from))
      if (warning.kind.isNotEmpty || warning.text.isNotEmpty) warning,
  ];
}

/// The one change a store offers to set a warning right, which the person picks among its [choices] and confirms.
@immutable
class PlanCorrection {
  /// Takes a correction's parts.
  const PlanCorrection(this.kind, this.choices);

  /// Reads a warning's correction; null for none and for one the window cannot read, which it then offers as none.
  static PlanCorrection? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final kind = value['kind'];
    final choices = value['choices'];
    if (kind is! String || !_kind.hasMatch(kind) || choices is! List) return null;
    // A Labels alone offers no choice; any other kind without one could never be picked.
    if ((choices.isEmpty && kind != labels) || choices.length > mostChoices) return null;
    final read = <String>[];
    for (final choice in choices) {
      if (choice is! String || !_choice.hasMatch(choice) || read.contains(choice)) return null;
      read.add(choice);
    }
    return PlanCorrection(kind, List.unmodifiable(read));
  }

  static final _kind = RegExp(r'^[A-Z][A-Za-z]{0,63}$');

  /// A choice as the planner takes it back: a state's or a priority's name.
  static final _choice = RegExp(r'^[A-Za-z][A-Za-z0-9 _-]{0,63}$');

  /// The most choices the window reads of one correction.
  static const mostChoices = 64;

  /// An issue moved into the state picked, previewed and saved as any move of it.
  static const move = 'Move';

  /// A story's priority set to the one picked, previewed and saved as a correction.
  static const priority = 'Priority';

  /// A repository's labels brought to the topic labels, previewed and saved as a correction; it offers no choice.
  static const labels = 'Labels';

  /// An open name of the change; the window offers only those it knows.
  final String kind;

  /// The values the person picks from, in the planner's order.
  final List<String> choices;

  /// Whether the window knows how to offer it.
  bool get known => kind == move || kind == priority || kind == labels;
}

/// Whether [name] is a label's name as a forge keeps it: 1 to 50 characters, none a control, format, bidi or
/// zero-width character, a soft hyphen, a combining grapheme joiner, the Mongolian vowel separator or a tag character.
bool planningLabelName(String name) => name.isNotEmpty && name.runes.length <= 50 && !name.contains(_notInLabelName);

final _notInLabelName = RegExp(
  r'[\x00-\x1f\x7f-\x9f\xad\u034f\u061c\u180e\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u206f\ufeff\u2028\u2029\u{e0000}-\u{e007f}]',
  unicode: true,
);

/// What is wrong in one of the project's repositories itself, rather than in a story or an issue: for
/// [topicLabels], the topic labels it lacks or carries otherwise, and GitHub's defaults it still carries. It holds
/// back no item and no work.
@immutable
class PlanRepositoryWarning {
  /// Takes a warning's parts.
  const PlanRepositoryWarning(
    this.repository,
    this.kind, {
    this.missing = const [],
    this.defaults = const [],
    this.differing = const [],
    this.correction,
  });

  /// Reads one warning; null for one the window cannot read: a repository or kind that is no name, a label list
  /// that is no list of label names, names one twice or is longer than its bound.
  static PlanRepositoryWarning? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final repository = value['repository'];
    final kind = value['kind'];
    if (repository is! String || !_repository.hasMatch(repository) || repository.contains('..')) return null;
    if (kind is! String || !_kind.hasMatch(kind)) return null;
    final missing = _labels(value['missing'], mostTopicLabels);
    final defaults = _labels(value['defaults'], mostDefaultLabels);
    final differing = _labels(value['differing'], mostTopicLabels);
    if (missing == null || defaults == null || differing == null) return null;
    return PlanRepositoryWarning(
      repository,
      kind,
      missing: missing,
      defaults: defaults,
      differing: differing,
      correction: PlanCorrection.read(value['correction']),
    );
  }

  /// The warnings a whole board names; one the window cannot read is left out, and a list longer than
  /// [mostWarnings] is read as none, since it is no list the planner sends.
  static List<PlanRepositoryWarning> readAll(Object? value) {
    if (value is! List || value.length > mostWarnings) return const [];
    return List.unmodifiable([for (final each in value) ?read(each)]);
  }

  static List<String>? _labels(Object? value, int most) {
    if (value is! List || value.length > most) return null;
    final read = <String>[];
    for (final label in value) {
      if (label is! String || !planningLabelName(label) || read.contains(label)) return null;
      read.add(label);
    }
    return List.unmodifiable(read);
  }

  static final _repository = RegExp(r'^[a-z0-9][a-z0-9._-]{0,99}$');
  static final _kind = RegExp(r'^[A-Z][A-Za-z]{0,63}$');

  /// The kind of a repository whose labels differ from the topic labels.
  static const topicLabels = 'TopicLabels';

  /// The most warnings the window reads of one board.
  static const mostWarnings = 100;

  /// How many topic labels there are, the most a list of them holds.
  static const mostTopicLabels = 7;

  /// How many default labels GitHub gives a repository, the most a list of them holds.
  static const mostDefaultLabels = 8;

  /// The repository's name.
  final String repository;

  /// The planner's name of what is wrong; one the window does not know is shown with its repository alone.
  final String kind;

  /// The topic labels the repository lacks.
  final List<String> missing;

  /// GitHub's default labels the repository still carries.
  final List<String> defaults;

  /// The topic labels the repository carries with another color or description.
  final List<String> differing;

  /// The change the store offers to set it right, or null when it offers none.
  final PlanCorrection? correction;

  /// What is wrong, in the screen's words.
  String get words {
    if (kind != topicLabels) return kind;
    return [
      'Its labels differ from the topic labels',
      if (missing.isNotEmpty) 'it lacks ${missing.join(', ')}',
      if (defaults.isNotEmpty) "it still carries GitHub's ${defaults.join(', ')}",
      if (differing.isNotEmpty)
        '${differing.join(', ')} ${differing.length == 1 ? 'has' : 'have'} another color or description',
    ].join('; ');
  }
}

/// One call a repository's Labels correction makes on its forge, as its preview names it.
@immutable
class PlanLabelCall {
  /// Takes a call's parts; a [delete] has no color and no description.
  const PlanLabelCall(this.action, this.label, {this.color, this.description});

  /// Reads one call; null for one the window cannot read.
  static PlanLabelCall? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final action = value['action'];
    final label = value['label'];
    final color = value['color'];
    final description = value['description'];
    if (action is! String || !actions.contains(action) || label is! String || !planningLabelName(label)) {
      return null;
    }
    if (action == delete) return color == null && description == null ? PlanLabelCall(action, label) : null;
    if (color is! String || !_color.hasMatch(color) || description is! String || description.runes.length > 100) {
      return null;
    }
    return PlanLabelCall(action, label, color: color, description: description);
  }

  /// Reads a preview's calls; null when one of them cannot be read or there are more than [mostCalls], so a
  /// correction is never confirmed over calls the person was not shown.
  static List<PlanLabelCall>? readAll(Object? value) {
    if (value is! List || value.length > mostCalls) return null;
    final read = <PlanLabelCall>[];
    for (final each in value) {
      final call = PlanLabelCall.read(each);
      if (call == null) return null;
      read.add(call);
    }
    return List.unmodifiable(read);
  }

  static final _color = RegExp(r'^[0-9a-f]{6}$');

  /// A default label deleted.
  static const delete = 'Delete';

  /// A missing topic label created.
  static const create = 'Create';

  /// A topic label's color and description set to the topic labels'.
  static const update = 'Update';

  /// Every action the window reads.
  static const actions = {delete, create, update};

  /// The most calls one correction makes: every default deleted and every topic label created or updated.
  static const mostCalls = PlanRepositoryWarning.mostDefaultLabels + PlanRepositoryWarning.mostTopicLabels;

  /// What the call does.
  final String action;

  /// The label it names.
  final String label;

  /// The color and the description it writes, null for a [delete].
  final String? color, description;

  /// The call in the screen's words.
  String get words => switch (action) {
    delete => 'Delete the label $label, which takes it off every issue that carries it',
    create => 'Create the label $label, color $color: $description',
    _ => 'Set the label $label to color $color: $description',
  };
}

/// One state, with the machine's kind preserved even when unknown.
class PlanState {
  /// Reads the configured state and preserves unknown kinds for display.
  PlanState.from(Map<String, dynamic> map)
    : name = _planText(map, 'name'),
      kind = _planText(map, 'kind'),
      measured = map['measured'] == true;

  /// The name reported by the daemon.
  final String name;

  /// The kind reported by the daemon.
  final String kind;

  /// Whether moving an issue into it needs a stage record; the planner derives an open story's state from these.
  final bool measured;
}

/// A configured column and the names of its states.
class PlanPhase {
  /// Reads a phase in its reported order.
  PlanPhase.from(Map<String, dynamic> map) : name = _planText(map, 'name'), states = _planStrings(map['states']);

  /// The column's name.
  final String name;

  /// Its states, in order.
  final List<String> states;
}

/// Work reported for a story, without inferring whether anyone is working.
class PlanWork {
  /// Reads the complete durable work record.
  PlanWork.from(Map<String, dynamic> map)
    : run = map['run'] is String ? map['run'] as String : null,
      _tasksReported = _planMapsReported(map['tasks']),
      tasks = _planList(map['tasks'], PlanWorkTask.from),
      coordinator = map['coordinator'] is String ? map['coordinator'] as String : null,
      since = _planText(map, 'since'),
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      repositories = _planList(map['repositories'], PlanRepositoryWork.from),
      comment = _planText(map, 'comment'),
      readingRounds = map['readingRounds'] is int ? map['readingRounds'] as int : null,
      issueReadingRounds = _planList(map['issueReadingRounds'], PlanIssueReadingRounds.from),
      issues = _planList(map['issues'], PlanWorkIssue.from),
      starts = _starts(map['starts'], _planList(map['repositories'], PlanRepositoryWork.from));

  /// Reads the offers the window starts without asking, each once by its offer; one that cannot be read, or that is
  /// no author's builder or reviewer's reader of one issue the run works in its repository, is left out and never
  /// started.
  static List<PlanningTaskOrder> _starts(Object? value, List<PlanRepositoryWork> repositories) {
    if (value is! List) return const [];
    final starts = <PlanningTaskOrder>[];
    final offers = <String>{};
    for (final entry in value.whereType<Map<String, dynamic>>()) {
      try {
        final order = PlanningTaskOrder.from(entry);
        final repository = repositories.where((repo) => repo.repository == order.repository).firstOrNull;
        // Only an author sent back to a place it resumes, its gate push or its task's own pull request, as a builder
        // that pushes its work, or a reviewer of the work at its place, a reader, starts without the person, each for
        // one issue of the run's.
        final resumes = order.place?.gatePush != null || order.place?.ownPullRequest == true;
        final author = order.runRole == 'AUTHOR' && resumes && order.role == 'BUILDER';
        final reviewer = order.runRole == 'REVIEWER' && order.place != null && order.role == 'READER';
        if ((author || reviewer) &&
            order.result == null &&
            order.issues.length == 1 &&
            repository != null &&
            order.issues.every(repository.issues.contains) &&
            offers.add(order.offer!)) {
          starts.add(order);
        }
      } on FormatException {
        continue;
      }
    }
    return List.unmodifiable(starts);
  }

  /// The run's offers made without a request of the window's, which the window starts through the hook it gives for
  /// them: an author sent back to the push its work waits at, started under that push's name, or to its task's own
  /// pull request, or a reviewer of the work at its place, started under a task name of its own.
  final List<PlanningTaskOrder> starts;

  /// The run the planner drew when the work started, compared only for equality; null from a planner without it.
  final String? run;

  /// The tasks reported by the daemon.
  final List<PlanWorkTask> tasks;

  /// The coordinator reported by the daemon.
  final String? coordinator;

  /// The since reported by the daemon.
  final String since;

  /// The atWork reported by the daemon.
  final bool? atWork;

  /// The repositories reported by the daemon.
  final List<PlanRepositoryWork> repositories;

  /// The comment reported by the daemon.
  final String comment;

  /// The readingRounds reported by the daemon.
  final int? readingRounds;

  /// The issueReadingRounds reported by the daemon.
  final List<PlanIssueReadingRounds> issueReadingRounds;

  /// The issues reported by the daemon.
  final List<PlanWorkIssue> issues;

  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => tasks
      .map((task) => task.ended)
      .whereType<WorkEnded>()
      .where((end) => end.finished != true || (end.unpushed ?? 0) > 0 || end.declaredStop)
      .firstOrNull;

  /// The reported value presented to the window.
  bool get tasksReported => _tasksReported;

  /// The _tasksReported reported by the daemon.
  final bool _tasksReported;

  /// The reported value presented to the window.
  String get mode => tasks.firstOrNull?.mode ?? '';

  /// The reported value presented to the window.
  String get agent => tasks.firstOrNull?.agent ?? '';

  /// The reported value presented to the window.
  String get model => tasks.firstOrNull?.model ?? '';
}

/// One recorded runtime participant; identity is never inferred from its name.
class PlanWorkTask {
  /// Reads the fields reported by the daemon.
  PlanWorkTask.from(Map<String, dynamic> map)
    : task = _planText(map, 'task'),
      containerId = _planText(map, 'containerId'),
      machine = map['machine'] is String ? map['machine'] as String : null,
      repository = _planText(map, 'repository'),
      issues = _planStrings(map['issues']),
      state = _planText(map, 'state'),
      done = _planText(map, 'done'),
      mode = _planText(map, 'mode'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      role = _planText(map, 'role'),
      since = _planText(map, 'since'),
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      ended = map['ended'] is Map<String, dynamic> ? WorkEnded.from(map['ended'] as Map<String, dynamic>) : null,
      runRole = map['runRole'] is String ? map['runRole'] as String : null,
      place = _readPlace(map['place']),
      placeUnreadable = map['place'] != null && _readPlace(map['place']) == null,
      held = map['held'] is int && (map['held'] as int) >= 0 ? map['held'] as int : null;

  /// The place as served, or null where none is served or the one served cannot be read.
  static PlanningRunPlace? _readPlace(Object? value) {
    try {
      return value == null ? null : PlanningRunPlace.fromJson(value);
    } on FormatException {
      return null;
    }
  }

  /// Porelo's run role, `AUTHOR` or `REVIEWER`; null where Start's default, an author, holds.
  final String? runRole;

  /// Where the run works; null for the task's own gate push, and where [placeUnreadable].
  final PlanningRunPlace? place;

  /// Whether the planner served a place the screen refuses, which is shown as unreadable and never as a default.
  final bool placeUnreadable;

  /// The undecided held items Porelo's newest record of the task gave, as served; null where the planner read no
  /// record of the task, which is not none held. Shown, never judged.
  final int? held;

  /// The task, containerId, repository, state, done, mode, agent, model, role, since reported by the daemon.
  final String task, containerId, repository, state, done, mode, agent, model, role, since;

  /// The machine name as reported, preserved verbatim.
  final String? machine;

  /// The issues reported by the daemon.
  final List<String> issues;

  /// The atWork reported by the daemon.
  final bool? atWork;

  /// The recorded outcome, absent while a task has no outcome.
  final WorkEnded? ended;
}

/// A saved per-issue reading allowance.
class PlanIssueReadingRounds {
  /// Reads the fields reported by the daemon.
  PlanIssueReadingRounds.from(Map<String, dynamic> map)
    : issue = _planText(map, 'issue'),
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null;

  /// The issue reported by the daemon.
  final String issue;

  /// The rounds reported by the daemon.
  final int? rounds;
}

/// A durable issue result, including the reason for its stop.
class PlanWorkIssue {
  /// Reads the fields reported by the daemon.
  PlanWorkIssue.from(Map<String, dynamic> map)
    : issue = _planText(map, 'issue'),
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null,
      roundsMost = validRound(map['roundsMost'], minimum: 1) ? map['roundsMost'] as int : null,
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      needsYou = map['needsYou'] is bool ? map['needsYou'] as bool : null,
      readingStopped = map['readingStopped'] is bool ? map['readingStopped'] as bool : null,
      findings = _planList(map['findings'], PlanFinding.from),
      reason = _planText(map, 'reason'),
      startRefusal = PlanningStartRefusal.read(map['startRefusal']);

  /// The issue, reason reported by the daemon.
  final String issue, reason;

  /// What Porelo refused the Start of the issue's offered task with, while that refusal stands; null otherwise.
  final PlanningStartRefusal? startRefusal;

  /// The rounds, roundsMost reported by the daemon.
  final int? rounds, roundsMost;

  /// The atWork, needsYou, readingStopped reported by the daemon.
  final bool? atWork, needsYou, readingStopped;

  /// The findings reported by the daemon.
  final List<PlanFinding> findings;
}

/// Who works an issue: an agent through the tasks the planner starts, or a person by hand.
enum IssueWorker {
  /// An agent, through the tasks the planner starts.
  agent('AGENT'),

  /// A person, by hand.
  manual('MANUAL');

  const IssueWorker(this.wire);

  /// The planner's name for it.
  final String wire;

  /// The worker the planner names, or null for anything else.
  static IssueWorker? read(Object? value) => values.where((worker) => worker.wire == value).firstOrNull;
}

/// One issue on the board, with or without a story.
class PlanIssue {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanIssue.withState(PlanIssue source, this.state)
    : id = source.id,
      version = source.version,
      repository = source.repository,
      title = source.title,
      size = source.size,
      blockedBy = source.blockedBy,
      blockedByReported = source.blockedByReported,
      waitsOn = source.waitsOn,
      waitsOnReported = source.waitsOnReported,
      link = source.link,
      parent = source.parent,
      rounds = source.rounds,
      roundsMost = source.roundsMost,
      readingStopped = source.readingStopped,
      atWork = source.atWork,
      needsYou = source.needsYou,
      noRunHere = source.noRunHere,
      attention = source.attention,
      worker = source.worker,
      workerMalformed = source.workerMalformed,
      findings = source.findings,
      whatMustBeTrue = source.whatMustBeTrue,
      subAgents = source.subAgents,
      warnings = source.warnings,
      labels = source.labels,
      // A moved issue's minutes are the planner's to say again, on the board read after the save.
      minutesLeft = state == source.state ? source.minutesLeft : null;

  /// Reads fields leniently.
  PlanIssue.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      version = _planText(map, 'version'),
      repository = _planText(map, 'repository'),
      title = _planText(map, 'title'),
      state = _planText(map, 'state'),
      size = _planText(map, 'size'),
      blockedBy = _planStrings(map['blockedBy']),
      blockedByReported = _planStringsReported(map['blockedBy']),
      waitsOn = _planList(map['waitsOn'], PlanWait.from),
      waitsOnReported = _planMapsReported(map['waitsOn']),
      link = _planText(map, 'link'),
      parent = map['parent'] is String && (map['parent'] as String).isNotEmpty ? map['parent'] as String : null,
      rounds = validRound(map['rounds']) ? map['rounds'] as int : null,
      roundsMost = validRound(map['roundsMost'], minimum: 1) ? map['roundsMost'] as int : null,
      readingStopped = map['readingStopped'] is bool ? map['readingStopped'] as bool : null,
      atWork = map['atWork'] is bool ? map['atWork'] as bool : null,
      needsYou = map['needsYou'] is bool ? map['needsYou'] as bool : null,
      noRunHere = map['noRunHere'] == true,
      attention = map['attention'] is String ? map['attention'] as String : '',
      worker = IssueWorker.read(map['worker']),
      workerMalformed = map.containsKey('worker') && IssueWorker.read(map['worker']) == null,
      findings = _planList(map['findings'], PlanFinding.from),
      whatMustBeTrue = map['whatMustBeTrue'] is String ? map['whatMustBeTrue'] as String : null,
      subAgents = map['subAgents'] is List ? _planList(map['subAgents'], PlanWorker.from) : null,
      warnings = PlanWarning.read(map['warnings']),
      labels = _planStrings(map['labels']),
      minutesLeft = _planMinutes(map['minutesLeft']);

  /// The issue's identifier.
  final String id;

  /// Its repository.
  final String repository;

  /// Its title.
  final String title;

  /// Its reported state.
  final String state;

  /// Its size, empty when unsaid.
  final String size;

  /// The version it stands at, which a write that changes it sends back as seen; empty when unsaid.
  final String version;

  /// The identifiers blocking it.
  final List<String> blockedBy;

  /// Whether blockers were actually reported.
  final bool blockedByReported;

  /// The issues it waits on that the board does not hold, such as those of another repository.
  final List<PlanWait> waitsOn;

  /// Whether the planner said what it waits on; an older planner says nothing, which is not "nothing".
  final bool waitsOnReported;

  /// The waits that still hold it back: not known to be done.
  List<PlanWait> get waitsHolding => waitsOn.where((wait) => wait.done != true).toList();

  /// The atWork, needsYou reported by the daemon.
  final bool? atWork, needsYou;

  /// Whether an agent's issue stands in a working state with no run of the planner, so it is worked elsewhere.
  final bool noRunHere;

  /// Why the planner raised it, the run's reason; empty when unsaid and `NONE` when not raised.
  final String attention;

  /// Who works it, or null when the planner named no worker it describes.
  final IssueWorker? worker;

  /// Whether the planner named a worker that is neither `AGENT` nor `MANUAL`; an older planner names none at all.
  final bool workerMalformed;

  /// The findings reported by the daemon.
  final List<PlanFinding> findings;

  /// The whatMustBeTrue reported by the daemon.
  final String? whatMustBeTrue;

  /// The subAgents reported by the daemon.
  final List<PlanWorker>? subAgents;

  /// What the store could not read in it, which its row shows; none when the planner named none.
  final List<PlanWarning> warnings;

  /// Its labels on the tracker, the topic labels and any of the person's own, in the planner's order; none when the
  /// planner named none.
  final List<String> labels;

  /// For an issue left to build, the minutes expected of it less those its work took so far; null when not left to
  /// build, when nothing gives an expectation, or when the planner did not say.
  final double? minutesLeft;

  /// Its web link.
  final String link;

  /// The issue it is a sub-issue of, or null for a head issue.
  final String? parent;

  /// Whether a person moves it by hand, into and through the working states too.
  bool get byHand => worker == IssueWorker.manual;

  /// The reading round as reported, or null when unsaid.
  final int? rounds;

  /// The machine's reading round limit, or null when unsaid.
  final int? roundsMost;

  /// Whether the machine stopped this issue in Reading, or null when unsaid.
  final bool? readingStopped;
}

/// One story, whose state is always the machine's answer.
class PlanStory {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanStory.withMoves(PlanStory source, Map<String, String> moves, String? destination)
    : id = source.id,
      version = source.version,
      title = source.title,
      state = destination ?? source.state,
      // The day it was closed is known again only from the planner's read after a move of the story itself.
      closedAt = destination == null ? source.closedAt : null,
      priority = source.priority,
      size = source.size,
      description = source.description,
      descriptionFormat = source.descriptionFormat,
      issues = [for (final issue in source.issues) PlanIssue.withState(issue, moves[issue.id] ?? issue.state)],
      issuesReported = source.issuesReported,
      link = source.link,
      work = source.work,
      workReported = source.workReported,
      warnings = source.warnings,
      labels = source.labels,
      // The figures known before the move stay shown until the planner's read after the save replaces them.
      open = source.open;

  /// Reads fields leniently and preserves unknown priorities.
  PlanStory.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      version = _planText(map, 'version'),
      title = _planText(map, 'title'),
      state = _planText(map, 'state'),
      closedAt = map['closedAt'] is String ? DateTime.tryParse(map['closedAt'] as String) : null,
      priority = map['priority'] is String ? map['priority'] as String : null,
      size = _planText(map, 'size'),
      description = _withoutMarker(_planText(map, 'description')),
      descriptionFormat = _planText(map, 'descriptionFormat'),
      issues = _planList(map['issues'], PlanIssue.from),
      issuesReported = _planMapsReported(map['issues']),
      link = _planText(map, 'link'),
      work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null,
      workReported = map.containsKey('work'),
      warnings = PlanWarning.read(map['warnings']),
      labels = _planStrings(map['labels']),
      open = PlanOpenWork.read(map['open']);

  /// Its identifier.
  final String id;

  /// The version it stands at, which a write that changes it sends back as seen; empty when unsaid.
  final String version;

  /// Its title.
  final String title;

  /// Its reported state.
  final String state;

  /// When it was closed as Finished, the day its test passed; null while it is open, rejected or unsaid.
  final DateTime? closedAt;

  /// Its reported priority, empty when unsaid.
  final String? priority;

  /// Its reported size, empty when unsaid or when it has none.
  final String size;

  /// Full story text from GetStory.
  final String description;

  /// The format the store wrote [description] in, such as MARKDOWN; empty when unsaid, which reads as plain text.
  final String descriptionFormat;

  /// Its issues, empty when unsaid.
  final List<PlanIssue> issues;

  /// Whether the daemon actually sent an issue list.
  final bool issuesReported;

  /// Its web link.
  final String link;

  /// Whether the watch supplied work status, including an explicitly absent work record.
  final bool workReported;

  /// What is still open of its issues, as the planner counted it; null when it did not say or a move made it stale.
  final PlanOpenWork? open;

  /// What the store could not read in it, which its card shows; none when the planner named none.
  final List<PlanWarning> warnings;

  /// Its labels on the tracker, the topic labels and any of the person's own, in the planner's order; none when the
  /// planner named none.
  final List<String> labels;

  /// Whether the watch supplied the ended outcome, including an explicitly cleared outcome.
  bool get endedReported => workReported;

  /// Its work, when reported.
  /// The work record returned by the daemon.
  final PlanWork? work;

  /// Why the story stopped before reaching its done state.
  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => work?.ended;

  /// The issues a move of this story to [to] closes with it, as the planner carries them: none for a reopen, those in
  /// the last done state for the first closed state, and every open one for a later closed state; an issue held back
  /// by a warning, its own or a parent's among the story's, is never carried. Null when its issues are not all known
  /// in the workflow.
  List<PlanIssue>? closedIssues(List<PlanState> states, List<PlanWorkRule> work, String to) {
    int place(PlanIssue issue) => states.indexWhere((state) => state.name == issue.state);
    if (!issuesReported || issues.any((issue) => place(issue) < 0)) return null;
    if (!states.any((state) => state.name == to && state.kind == 'CLOSED')) return const [];
    if (_completes(states, to)) {
      final done = _lastDone(states, work);
      return [
        for (final issue in issues)
          if (issue.state == done && !_held(issue)) issue,
      ];
    }
    return [
      for (final issue in issues)
        if (states[place(issue)].kind != 'CLOSED' && !_held(issue)) issue,
    ];
  }

  /// Whether a warning holds [issue] back, its own or one of a parent the story holds too, as the planner leaves such
  /// an issue to its own move.
  bool _held(PlanIssue issue) {
    final seen = <String>{};
    PlanIssue? at = issue;
    while (at != null && seen.add(at.id)) {
      if (PlanWarning.holding(at.warnings)) return true;
      final parent = at.parent;
      at = parent == null ? null : issues.where((other) => other.id == parent).firstOrNull;
    }
    return false;
  }

  /// Whether [to] completes the work: the workflow's first closed state, whatever a later one is named.
  static bool _completes(List<PlanState> states, String to) =>
      states.where((state) => state.kind == 'CLOSED').firstOrNull?.name == to;

  /// The state the last of the work rules leads to in workflow order, or null when there is none.
  static String? _lastDone(List<PlanState> states, List<PlanWorkRule> work) {
    int place(String state) => states.indexWhere((each) => each.name == state);
    String? last;
    for (final rule in work) {
      if (last == null || place(rule.done) > place(last)) last = rule.done;
    }
    return last;
  }

  /// The issues that are part of the story's work: every one but those rejected, which nobody builds.
  List<PlanIssue> get workIssues => [
    for (final issue in issues)
      if (issue.state != 'Rejected') issue,
  ];

  /// The number of [workIssues] in Review or Finished, when the list and every issue state were reported.
  int? get completedIssues => issuesReported && issues.every((issue) => issue.state.isNotEmpty)
      ? issues.where((issue) => issue.state == 'Review' || issue.state == 'Finished').length
      : null;
}

/// An open issue of one of the project's repositories that is no item of the project, as one made by hand on the
/// tracker is; the screen only shows it.
class PlanUnlistedIssue {
  /// Reads the fields the planner reports.
  PlanUnlistedIssue.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      version = _planText(map, 'version'),
      repository = _planText(map, 'repository'),
      title = _planText(map, 'title'),
      story = _planText(map, 'kind') == 'story',
      link = _planText(map, 'link'),
      author = _planText(map, 'author'),
      authorAssociation = _planText(map, 'authorAssociation');

  /// Its tracker reference, `<repository>#<number>`.
  final String id;

  /// The version it stands at, which its add sends back as seen; empty when unsaid.
  final String version;

  /// Its repository.
  final String repository;

  /// Its title, the tracker's text, shown as plain text.
  final String title;

  /// Whether the planner names it a story; any other kind reads as an issue.
  final bool story;

  /// Its web link; empty when none was reported.
  final String link;

  /// The login of the account that opened it, shown as plain text; empty when the planner named none.
  final String author;

  /// How that account stands to the issue's repository, as the tracker names it, shown as plain text; empty when the
  /// planner named none.
  final String authorAssociation;
}

/// One breach of the stories' rules a whole board names: shown to the person, never holding a move back.
class PlanRuleBreach {
  /// Reads the fields the planner reports.
  PlanRuleBreach.from(Map<String, dynamic> map)
    : item = _planText(map, 'item'),
      rule = _planText(map, 'rule'),
      words = _planText(map, 'words');

  /// The story's or the issue's id.
  final String item;

  /// The rule broken, as the planner names it.
  final String rule;

  /// The breach in the planner's words.
  final String words;
}

/// One command of a save the planner keeps for the person, told by its kind, its target and where it stands; what it
/// would change is in its save's [PlanHeldSave.changes].
class PlanHeldCommand {
  /// Reads the fields the planner reports.
  PlanHeldCommand.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      kind = _planText(map, 'kind'),
      target = _planText(map, 'target'),
      state = _planText(map, 'state'),
      reason = _planText(map, 'reason'),
      subject = _planText(map, 'subject');

  /// The command's id.
  final String id;

  /// What it writes, as the planner names its kind, such as WRITE or SET_TEXT.
  final String kind;

  /// The issue or story it writes, or the repository it writes to.
  final String target;

  /// Where it stands: QUEUED, DONE, REFUSED or HELD.
  final String state;

  /// Why it is refused or held, as the planner names the refusal; NONE for one queued or done.
  final String reason;

  /// What the reason is about; empty when it names nothing.
  final String subject;
}

/// What the planner knew of a field a held save would change, when it last read the board.
enum PlanHeldValueRead {
  /// The planner never read it, or tells no time of its read: no value is known.
  never,

  /// It was read, and empty.
  empty,

  /// It was read, holding [PlanHeldChange.now].
  value,
}

/// One field of one item a held save would change: the value it would write beside the value the planner last read.
class PlanHeldChange {
  /// Reads the fields the planner reports; a value without its time of reading, or with a time that is no time, reads
  /// as never read, since a value shown without when it was read would look current.
  PlanHeldChange.from(Map<String, dynamic> map)
    : target = _planText(map, 'target'),
      field = _planText(map, 'field'),
      saved = map['saved'] is String ? map['saved'] as String : null,
      read = _heldRead(map),
      now = _heldRead(map) == PlanHeldValueRead.value ? map['now'] as String : null,
      nowAt = _heldRead(map) == PlanHeldValueRead.never ? null : _heldTime(map['nowAt']);

  /// The issue or story the field belongs to.
  final String target;

  /// The field, by the name its item carries it under on the board, or for a value the board does not hold by its own.
  final String field;

  /// What the planner knew of the value when it last read the board.
  final PlanHeldValueRead read;

  /// The value last read; null unless [read] is [PlanHeldValueRead.value].
  final String? now;

  /// When the board holding [now] was read, in UTC; null when [read] is [PlanHeldValueRead.never].
  final DateTime? nowAt;

  /// The value the save would write; null when it removes or empties the value.
  final String? saved;
}

/// A time the planner reports, in UTC; null where it is no time or names no zone, which would be read as the screen's
/// own local time.
DateTime? _heldTime(Object? value) {
  if (value is! String || !RegExp(r'(Z|[+-]\d\d:?\d\d)$').hasMatch(value)) return null;
  return DateTime.tryParse(value)?.toUtc();
}

PlanHeldValueRead _heldRead(Map<String, dynamic> map) {
  if (_heldTime(map['nowAt']) == null) return PlanHeldValueRead.never;
  if (map['now'] is String) return PlanHeldValueRead.value;
  return map['nowUnset'] == true ? PlanHeldValueRead.empty : PlanHeldValueRead.never;
}

/// The forge's own answer to a refused call, word for word as the planner keeps it.
class PlanHeldAnswer {
  /// Takes the answer's parts.
  PlanHeldAnswer._(this.status, this.text, this.requestId);

  /// The answer in [value], or null where it is absent or carries neither a status, nor words, nor a request id. A
  /// status that is missing, or outside 0 to 999, reads as 0, so the forge's words are kept whatever its status.
  static PlanHeldAnswer? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final given = value['status'];
    final status = given is int && given >= 0 && given <= 999 ? given : 0;
    final text = _planText(value, 'text');
    final id = value['requestId'];
    final requestId = id is String && id.isNotEmpty ? id : null;
    if (status == 0 && text.isEmpty && requestId == null) return null;
    return PlanHeldAnswer._(status, text, requestId);
  }

  /// The HTTP status the forge answered with; 0 when it answered none.
  final int status;

  /// The forge's words, its token removed and its control characters escaped; empty when it gave none.
  final String text;

  /// The id the forge gave the request, for its support; null when it gave none.
  final String? requestId;
}

/// One try of a held save that ended refused or held.
class PlanHeldTry {
  /// Reads the fields the planner reports.
  PlanHeldTry.from(Map<String, dynamic> map)
    : at = _heldTime(map['at']),
      result = _planText(map, 'result'),
      reason = _planText(map, 'reason'),
      answer = PlanHeldAnswer.read(map['answer']);

  /// When it ended, in UTC; null where the planner gave no time, or one without its zone.
  final DateTime? at;

  /// How it ended: REFUSED or HELD.
  final String result;

  /// Why, in one plain sentence of the planner's words.
  final String reason;

  /// The forge's answer to it; null when it gave none.
  final PlanHeldAnswer? answer;
}

/// A save the planner keeps for the person, a group of commands not written whole: refused before it wrote, or held
/// after a command that may stand in part. Every one can be dropped; one that is [resendable] can be resent. A line
/// may fold several saves of one field, value and source: [saves] names each, and the line acts on all of them.
class PlanHeldSave {
  /// Reads the fields the planner reports.
  PlanHeldSave.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      run = map['run'] == true,
      earlier = map['earlier'] == true,
      // A save kept from elsewhere is never written here, whatever a board says.
      resendable = map['resendable'] == true && map['earlier'] != true,
      repository = _planText(map, 'repository'),
      branch = _planText(map, 'branch'),
      commands = _planList(map['commands'], PlanHeldCommand.from),
      reason = _planText(map, 'reason'),
      changes = _planList(map['changes'], PlanHeldChange.from),
      answer = PlanHeldAnswer.read(map['answer']),
      tries = _planList(map['tries'], PlanHeldTry.from),
      saves = _heldSaves(map);

  /// The save's id, which a resend or a drop names.
  final String id;

  /// Whether a run saves it, rather than a window.
  final bool run;

  /// Whether it was kept from another repository or branch the project named before.
  final bool earlier;

  /// Whether the planner resends it; false when the planner did not say, and always when [earlier].
  final bool resendable;

  /// The repository it was to be written to, named only when [earlier]; empty otherwise.
  final String repository;

  /// The branch it was to be written to, named only when [earlier] and that source named one; empty otherwise.
  final String branch;

  /// Every command of it in order, those already written included.
  final List<PlanHeldCommand> commands;

  /// Why it is held, in one plain sentence of the planner's words.
  final String reason;

  /// What it would change, one line per field of an item; empty when it changes no field the board names.
  final List<PlanHeldChange> changes;

  /// The forge's answer to its last try; null when that try kept none.
  final PlanHeldAnswer? answer;

  /// Every try that ended refused or held, oldest first.
  final List<PlanHeldTry> tries;

  /// Every save folded into this line, oldest first, [id] last; [id] alone when nothing folds with it. Empty when the
  /// planner's list cannot be read, see [savesUnreadable].
  final List<String> saves;

  /// Whether the planner's list of the saves this line folds cannot be read: missing, empty, holding anything but
  /// ids, or not ending with [id]. Such a line is neither resent nor dropped, since the saves either would reach are
  /// not known.
  bool get savesUnreadable => saves.isEmpty;
}

/// The saves a line folds, oldest first and its own id last; none where the planner's list is missing, empty, holds
/// anything but ids, or does not end with the line's own id.
List<String> _heldSaves(Map<String, dynamic> map) {
  final id = _planText(map, 'id');
  final named = map['saves'];
  if (id.isEmpty || !_planStringsReported(named) || (named as List).isEmpty || named.last != id) return const [];
  return List.unmodifiable(named.cast<String>());
}

/// The longest directory the planner takes, in bytes of UTF-8.
const planningDirectoryMostBytes = 4095;

/// The planner's words for a path longer than [planningDirectoryMostBytes].
const planningDirectoryTooLong = 'The path is longer than 4095 bytes in UTF-8.';

/// The planner's words for a path outside its rule otherwise.
const planningDirectoryNotNormal = 'The path is not an absolute path in normal form.';

/// Whether [path] is a directory as the planner takes one; see [planningDirectoryFault].
bool planningDirectory(String path) => planningDirectoryFault(path) == null;

/// Why [path] is no directory the planner takes, in the planner's own words, or null when it is one: an absolute path
/// in normal form of at most 4095 bytes in UTF-8, with no `.` or `..` segment, no empty segment and no trailing
/// separator but the root's; no control character (C0, C1), line or paragraph separator, bidirectional control or
/// unpaired surrogate; no white space at either end, which is refused, never trimmed. On Linux it starts with `/`, on
/// Windows it is a drive path `X:\` with `\` alone as its separator; a UNC or device path is refused.
String? planningDirectoryFault(String path) {
  if (utf8.encode(path).length > planningDirectoryMostBytes) return planningDirectoryTooLong;
  if (path.isEmpty || path.runes.any(_unplain) || _blank(path.runes.first) || _blank(path.runes.last)) {
    return planningDirectoryNotNormal;
  }
  final String rest;
  final String separator;
  if (path.startsWith('/')) {
    rest = path.substring(1);
    separator = '/';
  } else if (RegExp(r'^[A-Za-z]:\\').hasMatch(path) && !path.contains('/')) {
    rest = path.substring(3);
    separator = r'\';
  } else {
    return planningDirectoryNotNormal;
  }
  if (rest.isEmpty) return null;
  final normal = rest.split(separator).every((step) => step.isNotEmpty && step != '.' && step != '..');
  return normal ? null : planningDirectoryNotNormal;
}

bool _unplain(int c) =>
    c < 0x20 ||
    (c >= 0x7f && c <= 0x9f) ||
    c == 0x2028 ||
    c == 0x2029 ||
    (c >= 0x202a && c <= 0x202e) ||
    (c >= 0x2066 && c <= 0x2069) ||
    // An unpaired surrogate is answered as its own rune.
    (c >= 0xd800 && c <= 0xdfff);

bool _blank(int c) =>
    c == 0x20 ||
    (c >= 0x09 && c <= 0x0d) ||
    c == 0x85 ||
    c == 0xa0 ||
    c == 0x1680 ||
    (c >= 0x2000 && c <= 0x200a) ||
    c == 0x2028 ||
    c == 0x2029 ||
    c == 0x202f ||
    c == 0x205f ||
    c == 0x3000;

/// What keeps a project's stories and issues, as the planner names it.
enum PlanningTrackerKind {
  /// GitHub's tracker.
  github,

  /// The Git store, as Markdown files in the project's repository.
  git,

  /// A directory of this computer, which the person may choose.
  local,
}

/// Where the planner keeps a project's stories and issues: GitHub's tracker, the Git store, or a directory of this
/// computer the person may choose.
@immutable
class PlanningTracker {
  /// Kept by GitHub's tracker.
  const PlanningTracker.github() : kind = PlanningTrackerKind.github, items = null, defaultItems = null;

  /// Kept by the Git store.
  const PlanningTracker.git() : kind = PlanningTrackerKind.git, items = null, defaultItems = null;

  /// Kept in [items], a directory of this computer, beside the project's [defaultItems].
  const PlanningTracker.local(String this.items, String this.defaultItems) : kind = PlanningTrackerKind.local;

  /// Reads the planner's `tracker`; null where it is absent or malformed: an unknown kind, a directory beside a kind
  /// that keeps none, or a local one without both directories as [planningDirectory] takes them.
  static PlanningTracker? from(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    return switch ((json['kind'], json['items'], json['default'])) {
      ('github', null, null) => const PlanningTracker.github(),
      ('git', null, null) => const PlanningTracker.git(),
      ('local', final String items, final String standard)
          when planningDirectory(items) && planningDirectory(standard) =>
        PlanningTracker.local(items, standard),
      _ => null,
    };
  }

  /// What keeps them.
  final PlanningTrackerKind kind;

  /// The directory the planner uses for a local project, its default included; null for every other kind.
  final String? items;

  /// The local project's default directory, which a choice of null goes back to; null for every other kind.
  final String? defaultItems;

  /// Whether a local project keeps another directory than its default, so going back to the default means something.
  bool get awayFromDefault => local && items != defaultItems;

  /// Whether the planner keeps them in a directory of this computer.
  bool get local => kind == PlanningTrackerKind.local;

  @override
  bool operator ==(Object other) =>
      other is PlanningTracker && other.kind == kind && other.items == items && other.defaultItems == defaultItems;

  @override
  int get hashCode => Object.hash(kind, items, defaultItems);

  @override
  String toString() => local ? 'PlanningTracker.local($items, $defaultItems)' : 'PlanningTracker.${kind.name}()';
}

/// A workflow or a whole board reply; absent fields stay empty.
class PlanningSnapshot {
  /// Applies destinations explicitly confirmed by the save answer.
  PlanningSnapshot.saved(
    PlanningSnapshot source,
    this.revision,
    Map<String, String> moves,
    Map<String, String> destinations,
  ) : states = source.states,
      phases = source.phases,
      tracker = source.tracker,
      work = source.work,
      faults = source.faults,
      unlisted = source.unlisted,
      repositories = source.repositories,
      repositoriesReported = source.repositoriesReported,
      repositoryWarnings = source.repositoryWarnings,
      repositoryWarningsReported = source.repositoryWarningsReported,
      heldSaves = source.heldSaves,
      // A save may mend or break a rule; none is shown until the next whole board names them anew.
      rules = const [],
      rulesReported = false,
      reasons = source.reasons,
      statesReported = source.statesReported,
      phasesReported = source.phasesReported,
      storiesReported = source.storiesReported,
      // The figures known before the save stay shown until the planner's read after it replaces them.
      open = source.open,
      stories = [for (final story in source.stories) PlanStory.withMoves(story, moves, destinations[story.id])];

  /// Reads a snapshot without deriving story states.
  PlanningSnapshot.from(Map<String, dynamic> map)
    : work = _planList(map['work'], PlanWorkRule.from),
      tracker = PlanningTracker.from(map['tracker']),
      faults = _planStrings(map['faults']),
      unlisted = _planList(map['unlisted'], PlanUnlistedIssue.from),
      heldSaves = [
        for (final save in _planList(map['heldSaves'], PlanHeldSave.from))
          if (save.id.isNotEmpty) save,
      ],
      rules = [
        for (final breach in _planList(map['rules'], PlanRuleBreach.from))
          if (breach.item.isNotEmpty && breach.words.isNotEmpty) breach,
      ],
      rulesReported = map['rules'] is List,
      reasons = map['reasons'] == true,
      repositories = _planStrings(map['repositories']),
      repositoriesReported = _planStringsReported(map['repositories']),
      repositoryWarnings = PlanRepositoryWarning.readAll(map['repositoryWarnings']),
      repositoryWarningsReported = map['repositoryWarnings'] is List,
      states = _planList(map['states'], PlanState.from),
      phases = _planList(map['phases'], PlanPhase.from),
      stories = _planList(map['stories'], PlanStory.from),
      revision = _planText(map, 'revision'),
      statesReported = _planMapsReported(map['states']),
      phasesReported = _planMapsReported(map['phases']),
      storiesReported = _planMapsReported(map['stories']),
      open = PlanOpenWork.read(map['open']);

  /// Combines the separate workflow and story reads without deriving any states.
  PlanningSnapshot.fromParts(PlanningSnapshot workflow, PlanningSnapshot board)
    : work = workflow.work,
      // A whole board names it; a story list leaves the workflow's standing.
      tracker = board.tracker ?? workflow.tracker,
      faults = workflow.faults,
      // A whole board names them; a story list leaves the last whole board's standing.
      unlisted = board.statesReported ? board.unlisted : workflow.unlisted,
      // A whole board names them; a story list leaves the last whole board's standing.
      heldSaves = board.statesReported ? board.heldSaves : workflow.heldSaves,
      repositories = board.repositoriesReported ? board.repositories : workflow.repositories,
      repositoriesReported = board.repositoriesReported || workflow.repositoriesReported,
      // Only a whole board names them; a story list leaves the last whole board's standing.
      repositoryWarnings = board.repositoryWarningsReported ? board.repositoryWarnings : workflow.repositoryWarnings,
      repositoryWarningsReported = board.repositoryWarningsReported || workflow.repositoryWarningsReported,
      // Only a whole board names the breaches; a story list read beside the workflow names none.
      rules = board.rules,
      rulesReported = board.rulesReported,
      // The workflow says whether its store keeps reasons; a story list does not.
      reasons = board.statesReported ? board.reasons : workflow.reasons,
      states = workflow.states,
      phases = workflow.phases,
      stories = board.stories,
      revision = board.revision,
      statesReported = workflow.statesReported,
      phasesReported = workflow.phasesReported,
      storiesReported = board.storiesReported,
      open = board.open;

  /// [source] with the breaches a whole board named at its revision, since the breaches follow from the board alone.
  PlanningSnapshot.ruledBy(PlanningSnapshot source, PlanningSnapshot whole)
    : work = source.work,
      tracker = source.tracker,
      faults = source.faults,
      unlisted = source.unlisted,
      heldSaves = source.heldSaves,
      repositories = source.repositories,
      repositoriesReported = source.repositoriesReported,
      repositoryWarnings = source.repositoryWarnings,
      repositoryWarningsReported = source.repositoryWarningsReported,
      rules = whole.rules,
      rulesReported = whole.rulesReported,
      reasons = source.reasons,
      states = source.states,
      phases = source.phases,
      stories = source.stories,
      revision = source.revision,
      statesReported = source.statesReported,
      phasesReported = source.phasesReported,
      storiesReported = source.storiesReported,
      open = source.open;

  /// The work reported by the daemon.
  final List<PlanWorkRule> work;

  /// Where the planner keeps the project's stories and issues; null when it did not say.
  final PlanningTracker? tracker;

  /// The faults reported by the daemon.
  final List<String> faults;

  /// The open issues of the project's repositories that are no items of the project; empty when none were reported.
  final List<PlanUnlistedIssue> unlisted;

  /// The saves the planner keeps for the person, its own source's first; empty when none were reported.
  final List<PlanHeldSave> heldSaves;

  /// The project's repositories, its own first, as a whole board names them; empty when none were reported.
  final List<String> repositories;

  /// What the store found wrong in the project's repositories themselves; empty when none were reported.
  final List<PlanRepositoryWarning> repositoryWarnings;

  /// Whether the planner sent the repositories' warnings, as a whole board does.
  final bool repositoryWarningsReported;

  /// Each breach of the stories' rules the whole board named; empty when it named none or this is no whole board.
  final List<PlanRuleBreach> rules;

  /// Whether a whole board named the breaches, so [rules] says what this revision breaks.
  final bool rulesReported;

  /// Whether the store keeps a move's reason with the item it closes; false when the planner did not say.
  final bool reasons;

  /// Whether the planner sent the repository list, which a streamed whole board must carry.
  final bool repositoriesReported;

  /// Whether the daemon sent the state list.
  final bool statesReported;

  /// Whether the daemon sent the phase list.
  final bool phasesReported;

  /// Whether the daemon sent the story list.
  final bool storiesReported;

  /// Whether this is a complete whole-board reply.
  bool get complete => statesReported && phasesReported && storiesReported;

  /// The workflow's state order.
  final List<PlanState> states;

  /// The workflow's phase order.
  final List<PlanPhase> phases;

  /// The complete story list when present.
  final List<PlanStory> stories;

  /// The daemon's opaque revision.
  final String revision;

  /// What is still open of the whole board, as the planner counted it; null when it did not say.
  final PlanOpenWork? open;

  /// The versions this board shows of [ids], by id: each story, issue of a story and issue made outside the project
  /// it holds with a version; an id it does not show is left out, so the planner names it as missing.
  Map<String, String> versionsOf(Iterable<String> ids) {
    final wanted = ids.toSet();
    final versions = <String, String>{};
    void add(String id, String version) {
      if (wanted.contains(id) && version.isNotEmpty) versions.putIfAbsent(id, () => version);
    }

    for (final story in stories) {
      add(story.id, story.version);
      for (final issue in story.issues) {
        add(issue.id, issue.version);
      }
    }
    for (final issue in unlisted) {
      add(issue.id, issue.version);
    }
    return versions;
  }
}

/// What is still open of a story or a board, as the planner counts it: its issues per state, those an open issue
/// blocks, the sizes and minutes of what is left to build, and what waits for the person's review.
class PlanOpenWork {
  PlanOpenWork._(
    this.states,
    this.blocked,
    this.sizes,
    this.unsized,
    this.left,
    this.minutes,
    this.unestimated,
    this.review,
  );

  /// Reads the figures, or null when [value] is none the planner sends, so no count is ever shown as 0 unknowingly.
  static PlanOpenWork? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final blocked = _planCount(value['blocked']);
    final unsized = _planCount(value['unsized']);
    final left = _planCount(value['left']);
    final unestimated = _planCount(value['unestimated']);
    final review = _planCount(value['review']);
    final states = _planCounts(value['states']);
    final sizes = _planCounts(value['sizes']);
    if (blocked == null ||
        unsized == null ||
        left == null ||
        unestimated == null ||
        review == null ||
        states == null ||
        sizes == null) {
      return null;
    }
    return PlanOpenWork._(states, blocked, sizes, unsized, left, _planMinutes(value['minutes']), unestimated, review);
  }

  /// Each state of the workflow, in order, with its issues that are not blocked.
  final Map<String, int> states;

  /// The issues left to build that an open issue blocks.
  final int blocked;

  /// Each size of the issues left to build, smallest first, only those that occur.
  final Map<String, int> sizes;

  /// The issues left to build without a size.
  final int unsized;

  /// The issues left to build, the blocked ones included.
  final int left;

  /// The minutes left over the issues left to build that have them; null when none has.
  final double? minutes;

  /// The issues left to build without minutes.
  final int unestimated;

  /// The issues waiting for the person's review.
  final int review;
}

int? _planCount(Object? value) => value is int && value >= 0 ? value : null;

double? _planMinutes(Object? value) => value is num && value.isFinite && value >= 0 ? value.toDouble() : null;

Map<String, int>? _planCounts(Object? value) {
  if (value is! List) return null;
  final counts = <String, int>{};
  for (final entry in value) {
    if (entry is! Map<String, dynamic>) return null;
    final name = entry['name'];
    final issues = _planCount(entry['issues']);
    if (name is! String || issues == null || counts.containsKey(name)) return null;
    counts[name] = issues;
  }
  return counts;
}

/// One destination in an atomic planning save.
sealed class PlanningMove {
  /// Allows story and issue destinations in the same reviewed batch.
  const PlanningMove();

  /// The work identifier.
  String get id;

  /// The requested state.
  String get to;

  /// The person's one sentence why the item is closed, sent only on a move into a closed state; null for none.
  String? get reason;

  /// This move with [reason], or with none for null or a blank one.
  PlanningMove withReason(String? reason);

  /// The wire fields identifying the kind of work.
  Map<String, dynamic> toMap();
}

/// The reason as it is sent: trimmed, and none when blank.
String? planningReason(String? reason) {
  final trimmed = reason?.trim() ?? '';
  return trimmed.isEmpty ? null : trimmed;
}

/// The most characters, counted as Unicode code points after trimming, a move's reason holds.
const planningMostReason = 280;

/// The planner's words refusing [reason]'s text, or null when it may be sent: at most [planningMostReason] code points
/// after trimming, and no character the planner refuses.
///
/// The planner refuses by Unicode category: control, format, surrogate, unassigned and private-use characters and
/// line and paragraph separators, and U+115F, U+2800 and U+3164, which show as blank. Dart has no category table, so
/// the screen knows the format characters by their list, and of the unassigned ones only the noncharacters; the
/// planner's refusal names the rest.
String? planningReasonRefusal(String reason) {
  final trimmed = reason.trim();
  if (trimmed.runes.length > planningMostReason) return 'A reason holds at most $planningMostReason characters.';
  if (trimmed.runes.any(planningReasonRefuses)) {
    return 'A reason holds no control, format, line or paragraph separator, private, unassigned or blank-looking '
        'character.';
  }
  return null;
}

/// Whether a reason may not hold the code point [c], as far as the screen can tell; see [planningReasonRefusal].
bool planningReasonRefuses(int c) =>
    c < 0x20 ||
    (c >= 0x7F && c <= 0x9F) ||
    // Format characters.
    c == 0x00AD ||
    (c >= 0x0600 && c <= 0x0605) ||
    c == 0x061C ||
    c == 0x06DD ||
    c == 0x070F ||
    c == 0x0890 ||
    c == 0x0891 ||
    c == 0x08E2 ||
    c == 0x180E ||
    (c >= 0x200B && c <= 0x200F) ||
    (c >= 0x202A && c <= 0x202E) ||
    (c >= 0x2060 && c <= 0x2064) ||
    (c >= 0x2066 && c <= 0x206F) ||
    c == 0xFEFF ||
    (c >= 0xFFF9 && c <= 0xFFFB) ||
    c == 0x110BD ||
    c == 0x110CD ||
    (c >= 0x13430 && c <= 0x1343F) ||
    (c >= 0x1BCA0 && c <= 0x1BCA3) ||
    (c >= 0x1D173 && c <= 0x1D17A) ||
    c == 0xE0001 ||
    (c >= 0xE0020 && c <= 0xE007F) ||
    // Surrogates, private use and the noncharacters.
    (c >= 0xD800 && c <= 0xF8FF) ||
    c >= 0xF0000 ||
    (c >= 0xFDD0 && c <= 0xFDEF) ||
    (c & 0xFFFE) == 0xFFFE ||
    // Line and paragraph separators, and the characters that show as blank.
    c == 0x2028 ||
    c == 0x2029 ||
    c == 0x115F ||
    c == 0x2800 ||
    c == 0x3164;

/// One story destination in an atomic board save.
class PlanningStoryMove extends PlanningMove {
  /// Keeps the daemon's story identifier and destination: a closed state closes it, an open one reopens it.
  const PlanningStoryMove(this.story, this.to, {this.reason});

  @override
  final String? reason;

  @override
  PlanningStoryMove withReason(String? reason) => PlanningStoryMove(story, to, reason: planningReason(reason));

  /// The story to move.
  final String story;

  /// The requested state.
  @override
  /// The to reported by the daemon.
  final String to;

  @override
  /// The reported value presented to the window.
  String get id => story;

  /// The wire fields.
  @override
  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {'story': story, 'to': to, 'reason': ?planningReason(reason)};
}

/// One issue destination sent as part of an atomic issue move.
class PlanningIssueMove extends PlanningMove {
  /// Keeps the daemon's issue identifier and destination.
  const PlanningIssueMove(this.issue, this.to, {this.reason});

  @override
  final String? reason;

  @override
  PlanningIssueMove withReason(String? reason) => PlanningIssueMove(issue, to, reason: planningReason(reason));

  /// The issue to move.
  final String issue;

  /// The destination state.
  @override
  /// The to reported by the daemon.
  final String to;

  @override
  /// The reported value presented to the window.
  String get id => issue;

  /// The wire fields.
  @override
  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {'issue': issue, 'to': to, 'reason': ?planningReason(reason)};
}

/// The daemon's confirmation, without inventing a missing result or revision.
class PlanningMoveResult {
  /// Reads the fields reported by the daemon.
  PlanningMoveResult.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      moved = _planStrings(map['moved']),
      complete = _planStringsReported(map['moved']),
      stopped = _planList(map['stopped'], PlanningStoppedRun.from);

  /// Each run a confirmed save stopped, whose tasks the window ends; empty when it stopped none.
  final List<PlanningStoppedRun> stopped;

  /// The revision reported by the daemon.
  final String revision;

  /// The moved reported by the daemon.
  final List<String> moved;

  /// The complete reported by the daemon.
  final bool complete;
}

/// A run a confirmed save of moves stopped, with each task at work the window must end.
class PlanningStoppedRun {
  /// Reads one entry of a save's answer.
  PlanningStoppedRun.from(Map<String, dynamic> map)
    : story = _planText(map, 'story'),
      run = _planText(map, 'run'),
      tasks = [
        if (map['tasks'] case final List<dynamic> tasks)
          for (final task in tasks.whereType<Map<String, dynamic>>())
            (task: _planText(task, 'task'), container: _planText(task, 'containerId')),
      ];

  /// The run's story.
  final String story;

  /// The run's id.
  final String run;

  /// Each task at work, with the container it runs in.
  final List<({String task, String container})> tasks;
}

/// The correction of one warning on exactly one story, one issue or one repository, with the person's choice.
@immutable
class PlanningCorrection {
  /// Takes the item or repository, the warning's kind and the choice; exactly one of [story], [issue] and
  /// [repository] is given, and [choice] is left out only for a correction that offers none.
  const PlanningCorrection({required this.warning, this.choice, this.story, this.issue, this.repository})
    : assert(
        (story == null ? 0 : 1) + (issue == null ? 0 : 1) + (repository == null ? 0 : 1) == 1,
        'exactly one story, one issue or one repository',
      );

  /// The story it corrects, or null.
  final String? story;

  /// The issue it corrects, or null.
  final String? issue;

  /// The repository it corrects, or null.
  final String? repository;

  /// The kind of the warning it sets right.
  final String warning;

  /// The value picked among the correction's choices, or null for one that offers none.
  final String? choice;

  /// The id of the item, or the name of the repository, it corrects.
  String get id => story ?? issue ?? repository!;

  /// The correction as the planner reads it.
  Map<String, dynamic> toMap() => {
    'story': ?story,
    'issue': ?issue,
    'repository': ?repository,
    'warning': warning,
    'choice': ?choice,
  };
}

/// What a correction would write, answered before it is saved, with the digest its save sends back.
class PlanningCorrectionPreview {
  /// Takes a preview's parts.
  const PlanningCorrectionPreview({
    required this.revision,
    required this.digest,
    required this.from,
    required this.to,
    this.story,
    this.issue,
    this.repository,
    this.warning = '',
    this.targets = const {},
    this.calls = const [],
  });

  /// Reads a preview as the planner answers it.
  PlanningCorrectionPreview.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      digest = _planText(map, 'digest'),
      from = _planText(map, 'from'),
      to = _planText(map, 'to'),
      story = map['story'] is String ? map['story'] as String : null,
      issue = map['issue'] is String ? map['issue'] as String : null,
      repository = map['repository'] is String ? map['repository'] as String : null,
      warning = _planText(map, 'warning'),
      targets = planningTargets(map['targets']) ?? const {},
      calls = PlanLabelCall.readAll(map['calls']) ?? const [];

  /// The revision the preview was read at.
  final String revision;

  /// What the save sends back, so it saves only what was shown.
  final String digest;

  /// The value now, empty when none is told, and the one it is written with.
  final String from, to;

  /// The story, the issue or the repository it sets right, the others null, as the planner names them.
  final String? story, issue, repository;

  /// The kind of the warning it sets right, as the planner names it.
  final String warning;

  /// Every item the correction changes with the version it stands at, by id; the save sends them back as seen.
  final Map<String, String> targets;

  /// Every call a repository's Labels makes on its forge, in its order; empty for every other kind.
  final List<PlanLabelCall> calls;

  /// Whether it previews exactly [correction]: its item or repository, its warning and its choice, none for a
  /// repository's Labels.
  bool answers(PlanningCorrection correction) =>
      story == correction.story &&
      issue == correction.issue &&
      repository == correction.repository &&
      warning == correction.warning &&
      to == (correction.choice ?? '');
}

/// The board's revision after a correction was saved; a correction writes no state, so it stops no run.
class PlanningCorrectionSaved {
  /// Reads the planner's answer.
  PlanningCorrectionSaved.from(Map<String, dynamic> map) : revision = _planText(map, 'revision');

  /// The revision after the save.
  final String revision;
}

/// An issue a move carries, or one a warning names, with its state now and after the move.
class PlanningMovedIssue {
  /// Takes what the planner answered.
  const PlanningMovedIssue(this.id, this.repository, this.from, this.to);

  /// Reads one entry of a preview.
  PlanningMovedIssue.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      repository = _planText(map, 'repository'),
      from = _planText(map, 'from'),
      to = _planText(map, 'to');

  /// The issue's id.
  final String id;

  /// The repository that holds it.
  final String repository;

  /// Its state now.
  final String from;

  /// Its state after the move.
  final String to;
}

/// One harm a move can do, which the person confirms or cancels before it is saved.
class PlanningMoveWarning {
  /// Takes a warning's parts; [local] marks one the window adds of its own knowledge, which no digest covers.
  const PlanningMoveWarning(
    this.kind,
    this.item, {
    this.from = '',
    this.to = '',
    this.run,
    this.tasks = const [],
    this.offers = const [],
    this.issues = const [],
    this.skipped = const [],
    this.local = false,
  });

  /// Reads one warning of a preview.
  PlanningMoveWarning.from(Map<String, dynamic> map)
    : kind = _planText(map, 'kind'),
      item = _planText(map, 'item'),
      from = _planText(map, 'from'),
      to = _planText(map, 'to'),
      run = map['run'] is String ? map['run'] as String : null,
      tasks = _planStrings(map['tasks']),
      offers = _planStrings(map['offers']),
      issues = [
        if (map['issues'] case final List<dynamic> issues)
          for (final issue in issues.whereType<Map<String, dynamic>>()) PlanningMovedIssue.from(issue),
      ],
      skipped = _planStrings(map['skipped']),
      local = false;

  /// Running agent tasks a stop ends before the move.
  static const runningWork = 'RUNNING_WORK';

  /// Issues a story's move carries along from a working state or another repository.
  static const issuesAlong = 'ISSUES_ALONG';

  /// A move forward past waiting states.
  static const skipsStates = 'SKIPS_STATES';

  /// The kind, one of the constants above; another is shown as it is named.
  final String kind;

  /// The story or issue it is about.
  final String item;

  /// The item's state now.
  final String from;

  /// The state the move sets.
  final String to;

  /// The run whose tasks [runningWork] names, or null.
  final String? run;

  /// The tasks at work a stop ends.
  final List<String> tasks;

  /// The offers not yet started a stop voids.
  final List<String> offers;

  /// The issues it names.
  final List<PlanningMovedIssue> issues;

  /// The waiting states a move forward passes.
  final List<String> skipped;

  /// Whether the window added it of its own knowledge.
  final bool local;
}

/// What a move would do, answered before it is saved, with the digest its save sends back.
class PlanningMovePreview {
  /// Takes a preview's parts.
  const PlanningMovePreview({
    required this.revision,
    required this.digest,
    this.targets = const {},
    this.issues = const [],
    this.warnings = const [],
  });

  /// Reads a preview as the planner answers it.
  PlanningMovePreview.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      digest = _planText(map, 'digest'),
      targets = planningTargets(map['targets']) ?? const {},
      issues = [
        if (map['issues'] case final List<dynamic> issues)
          for (final issue in issues.whereType<Map<String, dynamic>>()) PlanningMovedIssue.from(issue),
      ],
      warnings = [
        if (map['warnings'] case final List<dynamic> warnings)
          for (final warning in warnings.whereType<Map<String, dynamic>>()) PlanningMoveWarning.from(warning),
      ];

  /// The revision the preview was read at.
  final String revision;

  /// What the save sends back, so it saves only what was shown.
  final String digest;

  /// Every item the move changes with the version it stands at, by id; the save sends them back as seen.
  final Map<String, String> targets;

  /// Every issue the move carries.
  final List<PlanningMovedIssue> issues;

  /// What the move can do harm by; empty when it is saved without asking.
  final List<PlanningMoveWarning> warnings;

  /// A copy with [more] warnings the window adds of its own knowledge.
  PlanningMovePreview withWarnings(List<PlanningMoveWarning> more) => more.isEmpty
      ? this
      : PlanningMovePreview(
          revision: revision,
          digest: digest,
          targets: targets,
          issues: issues,
          warnings: [...warnings, ...more],
        );
}

/// A preview's targets as the planner answers them, by id, or null unless each is an id with a version, named once.
Map<String, String>? planningTargets(Object? targets) {
  if (targets is! List) return null;
  final read = <String, String>{};
  for (final target in targets) {
    if (target is! Map<String, dynamic>) return null;
    final id = target['id'];
    final version = target['version'];
    if (id is! String || id.isEmpty || version is! String || version.isEmpty || read.containsKey(id)) return null;
    read[id] = version;
  }
  return read;
}

/// A new issue the planner wrote: its id and the board's revision after the save.
class PlanningIssueAdded {
  /// Takes what the planner answered.
  const PlanningIssueAdded({required this.id, required this.revision});

  /// The id the planner gave it.
  final String id;

  /// The board's revision after the save.
  final String revision;
}

/// What the planner answered a saved worker.
class PlanningWorkerSaved {
  /// Takes what the planner answered.
  const PlanningWorkerSaved({required this.revision});

  /// The board's revision after the save.
  final String revision;
}

/// Whether an issue's durable [reason] asks for a person: `NONE` does not, nor `MANUAL`, an issue a person works.
bool planReasonNeedsAttention(String reason) => reason.isNotEmpty && reason != 'NONE' && reason != 'MANUAL';

/// An issue an issue waits on that the board does not hold.
class PlanWait {
  /// Reads fields leniently: anything but a reference, a flag or a link is taken as unsaid.
  PlanWait.from(Map<String, dynamic> map)
    : reference = map['reference'] is String && (map['reference'] as String).trim().isNotEmpty
          ? map['reference'] as String
          : null,
      done = map['done'] is bool ? map['done'] as bool : null,
      link = map['link'] is String ? map['link'] as String : null;

  /// The issue as `owner/repository#number`, or null when the person may not read it.
  final String? reference;

  /// Whether it is done, or null when it could not be read.
  final bool? done;

  /// Where a person reads it, or null when not known.
  final String? link;

  /// The wait in a person's words.
  String get spoken => reference ?? 'something not readable';

  /// Its state in a person's words.
  String get stateSpoken => switch (done) {
    true => 'done',
    false => 'open',
    null => 'not read',
  };
}

/// One finding from the reader's last report, preserving unknown severities.
class PlanFinding {
  /// Reads optional locations without manufacturing a file or line.
  PlanFinding.from(Map<String, dynamic> map)
    : number = map['number'] is int ? map['number'] as int : null,
      severity = _planText(map, 'severity'),
      text = _planText(map, 'text'),
      file = map['file'] is String && (map['file'] as String).isNotEmpty ? map['file'] as String : null,
      line = map['line'] is int ? map['line'] as int : null;

  /// The reported number, absent when malformed or unsaid.
  final int? number;

  /// BLOCKING, NOTE, an unknown value, or empty when unsaid.
  final String severity;

  /// The reader's words.
  final String text;

  /// The file, if given.
  final String? file;

  /// The line, if given.
  final int? line;
}

/// A sub-agent reported for an issue.
class PlanWorker {
  /// Preserves each field independently.
  PlanWorker.from(Map<String, dynamic> map)
    : name = _planText(map, 'name'),
      role = _planText(map, 'role'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      brief = _planText(map, 'brief'),
      effort = map['effort'] is String ? map['effort'] as String : null,
      chosenBy = map['chosenBy'] is String ? map['chosenBy'] as String : null,
      offered = List.unmodifiable(
        _planList(map['offered'], PlanOffer.from).where((offer) => offer.agent.isNotEmpty && offer.model.isNotEmpty),
      );

  /// The effort proposed, null for none.
  final String? effort;

  /// Where the rule's choice of agent, model and effort came from, as the planner says it; null when unsaid.
  final String? chosenBy;

  /// Every agent the rule offers with its model, in the rule's order, the proposed first; empty from an older planner.
  final List<PlanOffer> offered;

  /// Its name.
  final String name;

  /// Its role.
  final String role;

  /// Its agent.
  final String agent;

  /// Its model.
  final String model;

  /// Its brief.
  final String brief;
}

/// An agent the rule offers a task, with the model it offers it.
class PlanOffer {
  /// Reads the agent and model, empty when unsaid.
  PlanOffer.from(Map<String, dynamic> map) : agent = _planText(map, 'agent'), model = _planText(map, 'model');

  /// The agent, as a start names it.
  final String agent;

  /// The model the rule offers the agent.
  final String model;
}

/// The standalone story and its source text.
class PlanStoryDetail {
  /// Reads the fields reported by the daemon.
  PlanStoryDetail.from(Map<String, dynamic> map)
    : story = map['story'] is Map<String, dynamic> ? PlanStory.from(map['story'] as Map<String, dynamic>) : null,
      revision = _planText(map, 'revision');

  /// The full story returned by the daemon.
  final PlanStory? story;

  /// The revision reported by the daemon.
  final String revision;
}

/// A separately read issue list; an omitted list is never an empty list.
class PlanIssueList {
  /// Reads the list and its presence.
  PlanIssueList.from(Map<String, dynamic> map)
    : issues = _planList(map['issues'], PlanIssue.from),
      reported = _planMapsReported(map['issues']),
      supplied = map['issues'] != null,
      revision = _planText(map, 'revision');

  /// The revision reported by the daemon.
  final String revision;

  /// The issues supplied.
  final List<PlanIssue> issues;

  /// Whether the daemon supplied a complete list.
  final bool reported;

  /// Whether any issue-list payload was supplied.
  final bool supplied;
}

/// The standalone issue, its sub-issues, acceptance criteria, last findings and sub-agents.
class PlanIssueDetail {
  /// Reads additive fields leniently without inventing absent reports.
  factory PlanIssueDetail.from(Map<String, dynamic> map) => PlanIssueDetail._(
    map['issue'] is Map<String, dynamic> ? map['issue'] as Map<String, dynamic> : <String, dynamic>{},
    _planText(map, 'revision'),
  );
  PlanIssueDetail._(Map<String, dynamic> map, this.revision)
    : issue = map.isEmpty ? null : PlanIssue.from(map),
      whatMustBeTrue = map['whatMustBeTrue'] is String ? [map['whatMustBeTrue'] as String] : [],
      criteriaReported = map['whatMustBeTrue'] is String,
      criteriaSupplied = map['whatMustBeTrue'] != null,
      findings = _planList(map['findings'], PlanFinding.from),
      findingsReported = _planMapsReported(map['findings']),
      findingsSupplied = map['findings'] != null,
      subAgents = _planList(map['subAgents'], PlanWorker.from),
      subAgentsReported = _planMapsReported(map['subAgents']),
      subAgentsSupplied = map['subAgents'] != null,
      subIssues = _planStrings(map['subIssues']),
      subIssuesReported = _planStringsReported(map['subIssues']),
      text = _planText(map, 'text'),
      textFormat = _planText(map, 'textFormat'),
      textReported = map['text'] is String;

  /// The revision reported by the daemon.
  final String revision;

  /// The issue's text, empty when its file is gone.
  final String text;

  /// Whether the planner sent the text, an empty one included.
  final bool textReported;

  /// The format the store wrote [text] in, such as MARKDOWN; empty when unsaid, which reads as plain text.
  final String textFormat;

  /// The issue when supplied.
  /// The issue record returned by the daemon.
  final PlanIssue? issue;

  /// Its acceptance criteria.
  final List<String> whatMustBeTrue;

  /// Whether acceptance criteria were supplied completely.
  final bool criteriaReported;

  /// Whether any acceptance criteria payload was supplied.
  final bool criteriaSupplied;

  /// The reader's last findings, in the reported order.
  final List<PlanFinding> findings;

  /// Whether the findings report is complete.
  final bool findingsReported;

  /// Whether any findings payload was supplied.
  final bool findingsSupplied;

  /// The reported sub-agents.
  final List<PlanWorker> subAgents;

  /// Whether the sub-agent list is complete.
  final bool subAgentsReported;

  /// Whether any sub-agent payload was supplied.
  final bool subAgentsSupplied;

  /// The sub-issue identifiers from the standalone issue read, in reported order.
  final List<String> subIssues;

  /// Whether the planner supplied every sub-issue identifier, including an explicitly empty list.
  final bool subIssuesReported;

  /// BLOCKING first, NOTE next, then unknown severities; report order within each group.
  List<PlanFinding> get orderedFindings => [
    ...findings.where((finding) => finding.severity == 'BLOCKING'),
    ...findings.where((finding) => finding.severity == 'NOTE'),
    ...findings.where((finding) => !{'BLOCKING', 'NOTE'}.contains(finding.severity)),
  ];
}

/// The machine's reason for an ended story run.
class WorkEnded {
  /// Reads the fields reported by the daemon.
  WorkEnded.from(Map<String, dynamic> map)
    : at = _planText(map, 'at'),
      finished = map['finished'] is bool ? map['finished'] as bool : null,
      unpushed = map['unpushed'] is int ? map['unpushed'] as int : null,
      why = _planText(map, 'why'),
      answer = _planText(map, 'answer'),
      outcome = map['outcome'] is String ? map['outcome'] as String : null;

  /// The original end record, without mapping the agent’s result.
  final String at, why, answer;
  final bool? finished;
  final int? unpushed;

  /// The outcome the task declared, absent when it declared none.
  final String? outcome;

  /// Whether the task declared its work blocked or failed, which no finished run overrides.
  bool get declaredStop => outcome == 'BLOCKED' || outcome == 'FAILED';

  /// The reported value presented to the window.
  String get reason => [why, answer].where((text) => text.isNotEmpty).join('\n');
}

/// One proposed participant, including the reader.
class PlanAssignment {
  /// Reads the fields reported by the daemon.
  PlanAssignment.from(Map<String, dynamic> map)
    : id = _planText(map, 'id'),
      issue = _planText(map, 'issue'),
      name = _planText(map, 'name'),
      kind = _planText(map, 'kind'),
      agent = _planText(map, 'agent'),
      model = _planText(map, 'model'),
      workingState = _planText(map, 'workingState'),
      brief = _planText(map, 'brief'),
      choice = _planText(map, 'choice'),
      effort = map['effort'] is String ? map['effort'] as String : null,
      chosenBy = map['chosenBy'] is String ? map['chosenBy'] as String : null;

  /// The effort proposed, null for none.
  final String? effort;

  /// Where the rule's choice of agent, model and effort came from, as the planner says it; null when unsaid.
  final String? chosenBy;

  /// The id, issue, name, role, agent, model, workingState, brief, choice reported by the daemon.
  final String id, issue, name, kind, agent, model, workingState, brief, choice;

  /// The assignment kind, presented beside worker roles.
  String get role => kind;

  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {
    'id': id,
    'issue': issue,
    'name': name,
    'kind': kind,
    'agent': agent,
    'model': model,
    'workingState': workingState,
    'brief': brief,
    'choice': choice,
    'effort': ?effort,
  };
}

/// A repository's task and its proposed participants.
class PlanRepositoryWork {
  /// Reads the fields reported by the daemon.
  PlanRepositoryWork.from(Map<String, dynamic> map)
    : repository = _planText(map, 'repository'),
      issues = _planStrings(map['issues']),
      main = PlanWorker.from(map['main'] is Map<String, dynamic> ? map['main'] as Map<String, dynamic> : {}),
      subAgents = _planList(map['subAgents'], PlanAssignment.from);

  /// The repository reported by the daemon.
  final String repository;

  /// The issues reported by the daemon.
  final List<String> issues;

  /// The required main worker for this repository.
  final PlanWorker main;

  /// The subAgents reported by the daemon.
  final List<PlanAssignment> subAgents;

  /// The reported value presented to the window.
  String get agent => main.agent;

  /// The reported value presented to the window.
  String get model => main.model;

  /// The reported value presented to the window.
  String get task => main.name;

  /// The reported value presented to the window.
  String get workOrder => main.brief;

  /// Serializes the complete record with its stable identity.
  Map<String, dynamic> toMap() => {
    'repository': repository,
    'issues': issues,
    'main': {
      'name': main.name,
      'role': main.role,
      'agent': main.agent,
      'model': main.model,
      'brief': main.brief,
      'effort': ?main.effort,
    },
    'subAgents': subAgents.map((sub) => sub.toMap()).toList(),
  };
}

/// An issue whose reading limit can be overridden at start.
class StoryWorkIssue {
  /// Reads the machine’s reported fields.
  StoryWorkIssue.from(Map<String, dynamic> map) : id = _planText(map, 'id'), repository = _planText(map, 'repository');

  /// The issue identifier supplied by the machine.
  final String id;

  /// The repository name supplied by the machine.
  final String repository;
}

/// The complete proposal for a start; missing limits are never invented.
class StoryWorkProposal {
  /// Reads the fields reported by the daemon.
  StoryWorkProposal.from(Map<String, dynamic> map)
    : modes = _planStrings(map['modes']),
      repositories = _planList(map['repositories'], PlanRepositoryWork.from),
      coordinator = _planText(map, 'coordinator'),
      comment = _planText(map, 'comment'),
      readingRounds = map['readingRounds'] is int ? map['readingRounds'] as int : null,
      issueReadingRounds = _planList(map['issueReadingRounds'], PlanIssueReadingRounds.from),
      revision = _planText(map, 'revision'),
      manual = _planStrings(map['manual']),
      held = _heldOnce(_planList(map['held'], StoryWorkHeld.from));

  /// The modes reported by the daemon.
  final List<String> modes;

  /// The repositories reported by the daemon.
  final List<PlanRepositoryWork> repositories;

  /// The coordinator, comment, revision reported by the daemon.
  final String coordinator, comment, revision;

  /// The readingRounds reported by the daemon.
  final int? readingRounds;

  /// The issueReadingRounds reported by the daemon.
  final List<PlanIssueReadingRounds> issueReadingRounds;

  /// The story's open issues a person works, which no task of the proposal names; empty from an older planner.
  final List<String> manual;

  /// The story's open issues the board holds back, such as by a blocker not done, each with why; no task of the
  /// proposal names them.
  final List<StoryWorkHeld> held;

  /// The proposed issue identities grouped by repository.
  List<StoryWorkIssue> get issues => [
    for (final repo in repositories)
      for (final issue in repo.issues) StoryWorkIssue.from({'id': issue, 'repository': repo.repository}),
  ];
}

/// An open issue of the story the board holds back from the proposal's work, and why.
/// Each held issue once, in the order served, leaving out an entry that names no issue.
List<StoryWorkHeld> _heldOnce(List<StoryWorkHeld> all) {
  final seen = <String>{};
  return [
    for (final held in all)
      if (held.issue.isNotEmpty && seen.add(held.issue)) held,
  ];
}

class StoryWorkHeld {
  /// Reads one held issue as served.
  StoryWorkHeld.from(Map<String, dynamic> map) : issue = _planText(map, 'issue'), reason = _planText(map, 'reason');

  /// The held issue.
  final String issue;

  /// Why it is held, in the planner's words, such as the blocker it waits on.
  final String reason;
}

/// The daemon's words describing one way of working.

/// A proposed coordinator and whether it is started or told about the story.

/// The main agent and model chosen for one repository.
class StoryWorkMainAgent {
  /// Keeps the repository identity and explicit main-agent choice.
  const StoryWorkMainAgent({required this.repository, required this.agent, required this.model, this.effort});

  /// The repository the choice applies to.
  final String repository;

  /// The chosen agent identifier.
  final String agent;

  /// The chosen model identifier.
  final String model;

  /// The chosen effort, null for none.
  final String? effort;
}

/// The explicit choice for one proposed sub-agent.
class StoryWorkChoice {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkChoice({
    required this.repository,
    required this.name,
    required this.choice,
    this.agent,
    this.model,
    this.effort,
  });

  /// The repository name supplied by the machine.
  final String repository;

  /// The proposed participant’s name within its repository.
  final String name;

  /// The explicit KEEP, CHANGE or DROP choice.
  final String choice;

  /// The agent identifier named by the machine or selected for a replacement.
  final String? agent;

  /// The model identifier, including an explicitly empty default.
  final String? model;

  /// The effort chosen for a replacement, null for none.
  final String? effort;
}

/// A revision-bound start decision.
class StoryWorkRequest {
  /// Keeps the source facts and choices for this view or decision.
  const StoryWorkRequest({
    required this.project,
    required this.story,
    required this.to,
    required this.mode,
    required this.seen,
    required this.subAgents,
    this.repositories = const [],
    this.proposedRepositories = const [],
    this.comment,
    this.readingRounds,
    this.issueReadingRounds = const {},
    this.issues,
    this.replaces,
    this.passOver,
  });

  /// The story's issues the work is for, or null for the story's own work.
  final List<String>? issues;

  /// The run this Start replaces, as the board showed it, or null where the board showed none.
  final String? replaces;

  /// The reason of one issue this Start answers with a new run, or null for a Start from the story.
  final PlanningPassOver? passOver;

  /// The project name sent to the machine.
  final String project;

  /// The story identifier reported by the board.
  final String story;

  /// The target working state named by the workflow.
  final String to;

  /// The explicitly selected wire mode.
  final String mode;

  /// The versions the person saw of the story and its issues, by id, which bind this decision.
  final Map<String, String> seen;

  /// The proposed participants, or the explicit choices sent at start.
  final List<StoryWorkChoice> subAgents;

  /// The chosen main agent and model for every proposed repository.
  final List<StoryWorkMainAgent> repositories;

  /// The proposedRepositories reported by the daemon.
  final List<PlanRepositoryWork> proposedRepositories;

  /// The explanation for changed or dropped participants.
  final String? comment;

  /// The proposed or selected overall reading limit, null when unsaid.
  final int? readingRounds;

  /// Explicit issue limits, omitted on the wire when there are no overrides.
  final Map<String, int> issueReadingRounds;

  Map<String, dynamic> _selectedRepository(PlanRepositoryWork repo) {
    final main = repositories.where((each) => each.repository == repo.repository).firstOrNull;
    final worker = {
      ...(repo.toMap()['main'] as Map<String, dynamic>),
      if (main != null) 'agent': main.agent,
      if (main != null) 'model': main.model,
    };
    // The effort chosen is sent back as chosen; none is no effort, never the proposal's.
    if (main != null) {
      worker.remove('effort');
      if (main.effort != null) worker['effort'] = main.effort;
    }
    return {
      ...repo.toMap(),
      'main': worker,
      'subAgents': [for (final sub in repo.subAgents) _selectedAssignment(repo.repository, sub)],
    };
  }

  Map<String, dynamic> _selectedAssignment(String repository, PlanAssignment sub) {
    final choice = subAgents.where((each) => each.repository == repository && each.name == sub.id).firstOrNull;
    final selected = {
      ...sub.toMap(),
      'choice': sub.role != 'BUILDER' ? 'DROP' : choice?.choice ?? sub.choice,
      if (choice?.choice == 'CHANGE') 'agent': choice!.agent,
      if (choice?.choice == 'CHANGE') 'model': choice!.model,
    };
    if (choice?.choice == 'CHANGE') {
      selected.remove('effort');
      if (choice!.effort != null) selected['effort'] = choice.effort;
    }
    return selected;
  }

  /// Serializes the exact fields sent to the daemon, omitting absent overrides.
  Map<String, dynamic> toMap() => {
    'project': project,
    'story': story,
    'to': to,
    'mode': mode,
    'seen': seen,
    if (proposedRepositories.isNotEmpty)
      'repositories': [for (final repo in proposedRepositories) _selectedRepository(repo)],
    'comment': ?comment,
    if (readingRounds != null) 'readingRounds': readingRounds,
    if (issueReadingRounds.isNotEmpty)
      'issueReadingRounds': [
        for (final each in issueReadingRounds.entries) {'issue': each.key, 'rounds': each.value},
      ],
    'issues': ?issues,
    'replaces': ?replaces,
    if (passOver case final passOver?) 'passOver': passOver.toMap(),
  };
}

/// The reason of one issue a Start from its row answers, as the row showed it.
@immutable
class PlanningPassOver {
  /// Takes the issue, its reason and the run the row showed.
  const PlanningPassOver({required this.issue, required this.reason, required this.run});

  /// The reasons a row's Start may pass over; a blocked task said it cannot go on unless something changes.
  static const reasons = {'NEXT_STEP_UNAVAILABLE', 'SAVE_REFUSED', 'STOPPED', 'FAILED'};

  /// The issue whose row it is.
  final String issue;

  /// The reason the row showed.
  final String reason;

  /// The run the row showed.
  final String run;

  /// The fields sent to the planner.
  Map<String, dynamic> toMap() => {'issue': issue, 'reason': reason, 'run': run};

  @override
  bool operator ==(Object other) =>
      other is PlanningPassOver && other.issue == issue && other.reason == reason && other.run == run;

  @override
  int get hashCode => Object.hash(issue, reason, run);
}

/// The work and any ended outcome returned by GetStoryWork.
class StoryWorkStatus {
  /// Reads the fields reported by the daemon.
  StoryWorkStatus.from(Map<String, dynamic> map)
    : work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null;

  /// The work record returned by the daemon.
  final PlanWork? work;

  /// The first recorded outcome requiring attention, if any.
  WorkEnded? get ended => work?.ended;
}

/// The findings returned by GetIssue, separate from the compact board summary.
class PlanningIssueDetail {
  /// Reads the machine’s reported fields.
  factory PlanningIssueDetail.from(Map<String, dynamic> map) => PlanningIssueDetail._(
    map['issue'] is Map<String, dynamic> ? map['issue'] as Map<String, dynamic> : {},
    _planText(map, 'revision'),
  );
  PlanningIssueDetail._(Map<String, dynamic> map, this.revision)
    : issue = PlanIssue.from(map),
      findings = _planList(map['findings'], PlanFinding.from),
      findingsReported = reportedFindings(map['findings']);

  /// The exact revision and issue whose findings were read.
  final String revision;
  final PlanIssue issue;

  /// Whether the reply supplied a complete, correctly shaped findings list.
  final bool findingsReported;

  /// The last findings returned by the machine.
  final List<PlanFinding> findings;

  /// Findings in reported order within blocking and remaining severities.
  List<PlanFinding> get blockingFirst => [
    ...findings.where((each) => each.severity == 'BLOCKING'),
    ...findings.where((each) => each.severity != 'BLOCKING'),
  ];
}

/// Signed work settings for a working state.
class PlanWorkRule {
  /// Reads the fields reported by the daemon.
  PlanWorkRule.from(Map<String, dynamic> map)
    : state = _planText(map, 'state'),
      from = _planStrings(map['from']),
      done = _planText(map, 'done'),
      then = map['then'] is String ? map['then'] as String : null,
      blocked = map['blocked'] is String ? map['blocked'] as String : null,
      per = _planText(map, 'per'),
      modes = _planStrings(map['modes']),
      order = _planText(map, 'order'),
      models = {
        if (map['models'] case final Map<String, dynamic> models)
          for (final entry in models.entries)
            if (entry.value case final String model) entry.key: model,
      };

  /// The state, done, per, order reported by the daemon.
  final String state, done, per, order;

  /// The then, blocked reported by the daemon.
  final String? then, blocked;

  /// The from, modes reported by the daemon.
  final List<String> from, modes;

  /// Each agent's model, in the rule's order of preference.
  final Map<String, String> models;
}

/// The authoritative outcome of a reading decision.
class ReadingResolution {
  /// Reads the fields reported by the daemon.
  ReadingResolution.from(Map<String, dynamic> map)
    : issue = map['issue'] is Map<String, dynamic> ? PlanIssue.from(map['issue'] as Map<String, dynamic>) : null,
      work = map['work'] is Map<String, dynamic> ? PlanWork.from(map['work'] as Map<String, dynamic>) : null,
      revision = _planText(map, 'revision'),
      tasks = readFollowUps(map, readWork(map['work']));

  /// Checks the exact issue and role authorized by the reading choice before Start.
  void checkDecision(String decidedIssue, String choice) {
    final role = switch (choice) {
      'ONE_MORE_ROUND' => 'READER',
      'REWORK' => 'BUILDER',
      _ => null,
    };
    if (issue?.id != decidedIssue ||
        issue?.readingStopped != false ||
        tasks.any((task) => task.role != role || task.issues.single != decidedIssue)) {
      throw const FormatException('The reading answer does not confirm this decision.');
    }
  }

  /// All checked follow-up Start arguments.
  final List<PlanningTaskOrder> tasks;

  /// The issue record returned by the daemon.
  final PlanIssue? issue;

  /// The work record returned by the daemon.
  final PlanWork? work;

  /// The revision reported by the daemon.
  final String revision;
}

/// Where a task's work landed, copied unchanged from Porelo's task row; the planner checks it.
class PlanningLanding {
  const PlanningLanding({required this.branch, required this.ref, required this.remote, required this.commit});

  /// The branch, the ref Porelo serves it under, the name a reader sees after a plain fetch, and the commit.
  final String branch, ref, remote, commit;

  /// The exact fields Porelo supplied.
  Map<String, dynamic> toMap() => {'branch': branch, 'ref': ref, 'remote': remote, 'commit': commit};
}

/// What Porelo refused a Start with, as the window reports it: Porelo's name for the refusal, its parameters and its
/// words. The planner keeps any well-shaped name as given and judges none.
class PlanningStartRefusal {
  const PlanningStartRefusal({required this.name, this.parameters = const {}, this.detail = ''});

  /// Reads the refusal the planner answers an issue with; null when the issue stands under none.
  static PlanningStartRefusal? read(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final parameters = value['parameters'];
    return PlanningStartRefusal(
      name: _planText(value, 'name'),
      parameters: parameters is Map
          ? {
              for (final entry in parameters.entries)
                if (entry.key is String && entry.value is String) entry.key as String: entry.value as String,
            }
          : const {},
      detail: _planText(value, 'detail'),
    );
  }

  /// Porelo's name for the refusal, 1 to 64 letters and digits.
  final String name;

  /// The refusal's parameters, at most 16, each key 1 to 64 characters and each value at most 500.
  final Map<String, String> parameters;

  /// Porelo's words, at most 1000 characters; line breaks allowed.
  final String detail;

  /// The exact fields Porelo supplied.
  Map<String, dynamic> toMap() => {'name': name, 'parameters': parameters, 'detail': detail};

  /// Whether this refusal lies within the planner's own bounds: a name of 1 to 64 ASCII letters and digits, at most 16
  /// parameters, each key 1 to 64 and each value at most 500 code points, a detail of at most 1000, and no control
  /// character nor line or paragraph separator, but for a line break in the detail.
  bool get fitsPlanner {
    if (!RegExp(r'^[A-Za-z0-9]{1,64}$').hasMatch(name) ||
        parameters.length > 16 ||
        !_fits(detail, 0, 1000, lines: true)) {
      return false;
    }
    return parameters.entries.every((each) => _fits(each.key, 1, 64) && _fits(each.value, 0, 500));
  }

  /// Whether [text] counts [least] to [most] code points, as Java counts them, and holds no ISO control character,
  /// U+2028 or U+2029; [lines] lets `\n` and `\r` pass.
  static bool _fits(String text, int least, int most, {bool lines = false}) {
    final points = text.runes.toList();
    return points.length >= least &&
        points.length <= most &&
        !points.any(
          (point) =>
              !(lines && (point == 0x0a || point == 0x0d)) &&
              (point <= 0x1f || (point >= 0x7f && point <= 0x9f) || point == 0x2028 || point == 0x2029),
        );
  }
}

/// Porelo's original end record, sent without interpreting or rewriting any field.
class PlanningTaskEnded {
  const PlanningTaskEnded({
    required this.at,
    required this.finished,
    required this.why,
    required this.answer,
    required this.unpushed,
    this.landed = const [],
    this.outcome,
  });

  final String at, why, answer;
  final bool finished;
  final int unpushed;

  /// The outcome the task declared, Porelo's `DONE`, `BLOCKED` or `FAILED`; null when it declared none.
  final String? outcome;

  /// Where the work landed; empty while it still waits at Porelo's gate.
  final List<PlanningLanding> landed;

  /// The exact fields Porelo supplied.
  Map<String, dynamic> toMap() => {
    'at': at,
    'finished': finished,
    'why': why,
    'answer': answer,
    'unpushed': unpushed,
    if (landed.isNotEmpty) 'landed': [for (final landing in landed) landing.toMap()],
    if (outcome != null) 'outcome': outcome,
  };
}

/// A checked task report answer, including every still-valid follow-up offer.
class TaskEventResolution {
  TaskEventResolution.from(Map<String, dynamic> map)
    : revision = _planText(map, 'revision'),
      work = readWork(map['work']),
      tasks = readFollowUps(map, readWork(map['work']));

  final String revision;
  final PlanWork work;
  final List<PlanningTaskOrder> tasks;
}
