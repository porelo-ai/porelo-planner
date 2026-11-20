import 'dart:async';

import 'package:flutter/foundation.dart';

import 'client.dart';
import 'hooks.dart';
import 'models.dart';
import 'planning_board.dart';
import 'story_work_order.dart';

/// A partial Start must retain confirmed tasks alongside the task that refused.
class StoryWorkTask {
  StoryWorkTask(this.order);

  final PlanningTaskOrder order;
  PlanningTaskStarted? started;
  Object? error;

  PlanningRefusal? get refusal => error is PlanningRefusal ? error as PlanningRefusal : null;
  String? get problem => error == null ? null : planningFailureWords(error!);
}

/// A proposal and start bound to one watched machine and board revision.
class StoryWork extends ChangeNotifier {
  /// Captures the initial source and the watched alternatives for the same project.
  StoryWork(
    this.board,
    this.story,
    this.to, {
    required this.startTask,
    required this.agentChoices,
    Map<PlanningBoard, String>? machines,
    this.unavailableMachines = const [],
    this.onStarted,
    this.issues,
    this.passOver,
  }) : backend = board.backend,
       project = board.project,
       revision = board.snapshot?.revision ?? '',
       machines = machines ?? {board: board.backend.label} {
    _stopFollowing = board.follow(_changed);
  }

  /// The currently chosen machine's board.
  PlanningBoard board;

  /// The story being started, as reported by this source.
  PlanStory story;

  /// The selected working state.
  String to;

  /// The story's issues the work is for, as a move of those issues chose them; null for the story's own work.
  final List<String>? issues;

  /// The reason of one issue this start answers from its Needs you row, or null for a start from the story.
  final PlanningPassOver? passOver;

  /// Watched providers of this project, labeled by their machine identities.
  final Map<PlanningBoard, String> machines;

  /// Watched machines that have not supplied this project's planning.
  final List<String> unavailableMachines;

  /// How the start reaches the window.
  final PlanningStartTask startTask;

  /// Where the agents a start may choose come from.
  final PlanningAgents agentChoices;

  /// The project name sent to the chosen machine.
  String project;

  /// The opaque board revision used by this proposal.
  String revision;

  /// The chosen machine's backend.
  PlanningSource backend;

  /// The daemon's proposal, once supplied.
  StoryWorkProposal? proposal;

  /// The explicitly selected involvement mode.
  String? mode;

  /// The explanation for any changed or dropped participant.
  String comment = '';

  /// The overall reading limit retained as text for validation.
  String rounds = '';

  /// Explicit per-issue reading limits.
  final Map<String, String> issueRounds = {};

  /// The main agent and model selected per repository.
  final Map<String, StoryWorkMainAgent> repositories = {};

  /// The explicit choice for every proposed sub-agent.
  final Map<(String, String), StoryWorkChoice> choices = {};

  /// Installed agents reported by the chosen machine.
  List<PlanningAgent> agents = [];

  /// Per repository, why the agent preselected is not the proposal's, in words shown above the choice.
  final Map<String, String> passedOver = {};

  /// The current refusal or incomplete-answer explanation.
  String? problem;

  /// Whether the proposal or start is awaiting its answer.
  bool busy = false;

  /// Whether a submitted start has not answered yet; its machine cannot change.
  bool starting = false;

  /// Keeps the checked arguments even when only part of the order starts.
  List<StoryWorkTask> tasks = const [];

  /// The window receives only identities actually confirmed by Start.
  /// Told of the tasks that reached Start once the order ends, even when a later one was refused.
  final Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)?
  onStarted;

  List<(PlanningTaskOrder, PlanningTaskStarted)> get startedTasks => List.unmodifiable([
    for (final task in tasks)
      if (task.started case final started?) (task.order, started),
  ]);
  bool _disposed = false;
  int _generation = 0;
  late VoidCallback _stopFollowing;

  /// Attaches a view while this form exists, with disposal-safe cancellation.
  VoidCallback follow(VoidCallback listener) {
    if (_disposed) return () {};
    addListener(listener);
    return () {
      if (!_disposed) removeListener(listener);
    };
  }

  /// Whether the form can still use its captured board revision.
  bool get sourceCurrent =>
      !_disposed &&
      !board.isDisposed &&
      revision.isNotEmpty &&
      identical(board.backend, backend) &&
      board.project == project &&
      board.live &&
      board.snapshot?.complete == true &&
      board.snapshot?.revision == revision;

  /// Whether the selected story and target exist on the captured source.
  bool get current =>
      sourceCurrent && board.stories.any((each) => each.id == story.id) && board.state(to)?.kind == 'WORKING';

  /// Whether a sub-agent was dropped or selected for a change.
  bool get needsComment => choices.values.any((each) => each.choice != 'KEEP');

  /// The agent the window listed by [name], or null when it listed none by that name.
  PlanningAgent? _listed(String name) => agents.where((each) => each.name == name).firstOrNull;

  /// Why the window says [name] cannot work on the machine, or null when it does not say so.
  String? _unready(String name) => switch (_listed(name)) {
    null => 'the window does not list it there',
    final listed => listed.unready,
  };

  /// Why [name] cannot work on the machine, in the window's words, or null when it can or the window cannot say. Where
  /// the planner offered agents to choose from, an agent the window does not list cannot work there either.
  String? unreadyWhy(String name) {
    final offering = proposal?.repositories.any((repo) => repo.main.offered.isNotEmpty) ?? false;
    return offering ? _unready(name) : _listed(name)?.unready;
  }

  /// Keeps the proposal's agent for [repo] where it can work on the machine, else preselects the first agent the rule
  /// offers that can, with its offered model, or none, and says which were passed over and why.
  void _preselect(PlanRepositoryWork repo) {
    final main = repo.main;
    // An older planner offers no list, so its proposal stands as it did.
    if (main.offered.isEmpty || _unready(main.agent) == null) return;
    final candidates = [
      PlanOffer.from({'agent': main.agent, 'model': main.model}),
      ...main.offered.where((offer) => offer.agent != main.agent),
    ];
    final passed = <String>[];
    for (final offer in candidates) {
      final why = _unready(offer.agent);
      if (why == null) {
        repositories[repo.repository] = StoryWorkMainAgent(
          repository: repo.repository,
          agent: offer.agent,
          model: offer.model.trim(),
          effort: main.effort,
        );
        passedOver[repo.repository] =
            'Preselected ${planningShown(offer.agent)}, the first agent the rule offers that can work there. '
            'Passed over: ${passed.join('; ')}.';
        return;
      }
      passed.add('${planningShown(offer.agent)}, ${planningShown(why, multiline: false)}');
    }
    repositories[repo.repository] = StoryWorkMainAgent(repository: repo.repository, agent: '', model: '');
    passedOver[repo.repository] =
        'No agent the rule offers can work there, so none is preselected: ${passed.join('; ')}.';
  }

  /// The model the proposal offers [agent] in [repo], or empty when it offers none.
  static String _offeredModel(PlanRepositoryWork? repo, String agent) {
    if (repo == null) return '';
    if (agent == repo.main.agent) return repo.main.model.trim();
    return repo.main.offered.where((offer) => offer.agent == agent).firstOrNull?.model.trim() ?? '';
  }

  /// The proposed effort for [agent]: kept where the agent lists it or its efforts are unknown, else none.
  String? _effortFor(String agent, String? proposed) {
    final efforts = _listed(agent)?.efforts;
    return proposed == null || efforts == null || efforts.contains(proposed) ? proposed : null;
  }

  /// Why [what]'s agent, model or effort cannot start, or null when they can.
  String? _assignmentProblem(String what, String? agent, String? model, String? effort) {
    if (agent == null || agent.isEmpty) return 'Choose an agent for $what.';
    if (unreadyWhy(agent) case final unready?) {
      return '${planningShown(agent)} cannot work there: ${planningShown(unready)}';
    }
    if (model == null || model.trim().isEmpty) return 'Choose a model for $what.';
    if (model.contains(RegExp(r'[\r\n\u2028\u2029]'))) return 'The model for $what must be one line.';
    if (!_soundEffort(effort)) return 'The effort for $what must be one line of at most 128 characters.';
    return null;
  }

  /// Why the chosen agents, models and efforts cannot start, in words, or null when they can.
  String? get whoProblem {
    final proposal = this.proposal;
    if (proposal == null) return 'The planner has not proposed the work yet.';
    for (final repo in proposal.repositories) {
      final name = planningShown(repo.repository);
      if (!(repo.main.role == 'MAIN' || repo.main.role == 'READER' && repo.subAgents.isEmpty)) {
        return 'The planner proposed no main agent for $name.';
      }
      final main = repositories[repo.repository];
      if (main == null) return 'Choose the agent for $name.';
      if (_assignmentProblem(name, main.agent, main.model, main.effort) case final problem?) return problem;
      for (final sub in repo.subAgents) {
        final chosen = choices[(repo.repository, sub.id)];
        final what = '${planningShown(sub.name)} in $name';
        switch (chosen?.choice) {
          case 'KEEP':
            if (sub.role != 'BUILDER' || sub.agent.isEmpty || sub.model.isEmpty) return 'Change or drop $what.';
            if (_assignmentProblem(what, sub.agent, sub.model, sub.effort) case final problem?) return problem;
          case 'DROP':
            break;
          case 'CHANGE':
            if (sub.role != 'BUILDER') return 'Drop $what.';
            if (_assignmentProblem(what, chosen!.agent, chosen.model, chosen.effort) case final problem?) {
              return problem;
            }
            if (chosen.agent == sub.agent && chosen.model == sub.model && chosen.effort == sub.effort) {
              return 'Change the agent, model or effort of $what, or keep it.';
            }
          default:
            return 'Keep, change or drop $what.';
        }
      }
    }
    return null;
  }

  /// Why the comment or the reading rounds cannot start, in words, or null when they can.
  String? get readingProblem {
    if (needsComment && comment.trim().isEmpty) return 'Say in the comment why a sub-agent is changed or dropped.';
    if (_rounds(rounds) == null) return 'The most reading rounds must be a whole number from 1 to 10.';
    for (final each in issueRounds.entries) {
      if (each.value.isNotEmpty && _rounds(each.value) == null) {
        return 'The reading rounds of ${planningShown(each.key)} must be a whole number from 1 to 10.';
      }
    }
    return null;
  }

  /// An effort typed empty is none.
  static String? _chosenEffort(String typed) => typed.trim().isEmpty ? null : typed;

  /// Whether an effort may reach Start: none, or one line of at most 128 characters.
  static bool _soundEffort(String? effort) =>
      effort == null ||
      effort.runes.length <= 128 && !RegExp(r'[\u0000-\u001F\u007F-\u009F\u2028\u2029]').hasMatch(effort);

  static int? _rounds(String text) {
    final value = int.tryParse(text);
    return value != null && value >= 1 && value <= 10 ? value : null;
  }

  /// Whether every required choice and source fact permits a start.
  bool get canStart =>
      current &&
      !busy &&
      problem == null &&
      tasks.isEmpty &&
      proposal != null &&
      proposal!.repositories.isNotEmpty &&
      board.startUnavailable(story, passOver: passOver) == null &&
      proposal!.modes.contains(mode) &&
      ['UNATTENDED', 'AGENT'].contains(mode) &&
      repositories.length == proposal!.repositories.length &&
      whoProblem == null &&
      readingProblem == null;

  /// The daemon's action-specific label, or a count from its proposed tasks.
  String get startLabel => proposal == null
      ? 'Start work'
      : proposal!.repositories.length == 1
      ? 'Start $to'
      : 'Start ${proposal!.repositories.length} ${proposal!.repositories.length == 1 ? 'task' : 'tasks'}';

  /// Reads the proposal and agent list from the same captured machine.
  Future<void> load() async {
    if (_disposed || starting || tasks.isNotEmpty) return;
    if (!current) {
      problem = !board.stories.any((each) => each.id == story.id)
          ? 'The planner did not report story ${story.id}.'
          : 'The planner did not report a current working state for this story.';
      _changed();
      return;
    }
    final generation = ++_generation;
    final source = backend;
    final name = project;
    final id = story.id;
    final target = to;
    bool valid() => !_disposed && generation == _generation;
    busy = true;
    problem = null;
    tasks = const [];
    proposal = null;
    repositories.clear();
    passedOver.clear();
    choices.clear();
    issueRounds.clear();
    agents = [];
    _changed();
    try {
      final answer = await source.proposeStoryWork(name, id, target, issues: issues);
      if (!valid()) return;
      mode = answer.modes.where((each) => ['UNATTENDED', 'AGENT'].contains(each)).firstOrNull;
      rounds = answer.readingRounds?.toString() ?? '';
      comment = answer.comment;
      for (final override in answer.issueReadingRounds) {
        issueRounds[override.issue] = override.rounds?.toString() ?? '';
      }
      if (answer.revision != revision) {
        problem = 'The planning revision changed. Reopen Start work.';
        unawaited(board.reload());
        return;
      }
      for (final repo in answer.repositories) {
        repositories[repo.repository] = StoryWorkMainAgent(
          repository: repo.repository,
          agent: repo.agent,
          model: repo.model.trim(),
          effort: repo.main.effort,
        );
        for (final sub in repo.subAgents) {
          choices[(repo.repository, sub.id)] = StoryWorkChoice(
            repository: repo.repository,
            name: sub.id,
            choice: sub.role != 'BUILDER' ? 'DROP' : sub.choice,
          );
        }
      }
      final listed = await agentChoices.on(source);
      if (!valid()) return;
      agents = listed.agents;
      for (final repo in answer.repositories) {
        _preselect(repo);
      }
      for (final main in repositories.values.toList()) {
        final effort = _effortFor(main.agent, main.effort);
        if (effort != main.effort) {
          repositories[main.repository] = StoryWorkMainAgent(
            repository: main.repository,
            agent: main.agent,
            model: main.model,
            effort: effort,
          );
        }
      }
      // Shown only once the agents are preselected, so no agent the window may pass over stands as the choice.
      proposal = answer;
      if (listed.failures.isNotEmpty) {
        problem = listed.failures.entries
            .map((each) => planningShown('${each.key}: ${each.value}', multiline: false))
            .join('\n');
      }
    } catch (error) {
      if (valid()) {
        problem = planningFailureWords(error);
        if (planningRefused(error, 'NotYetBuilt') || planningRefused(error, 'NotOffered')) {
          board.unavailableActions['ProposeStoryWork'] = problem!;
        }
      }
    } finally {
      if (valid()) {
        busy = false;
        _changed();
      }
    }
  }

  /// Changes the machine and re-asks its own proposal at its own revision.
  void changeMachine(PlanningBoard next) {
    if (_disposed || starting || tasks.isNotEmpty || !machines.containsKey(next) || next.isDisposed) return;
    final id = story.id;
    _generation++;
    _stopFollowing();
    board = next;
    backend = next.backend;
    project = next.project;
    revision = next.snapshot?.revision ?? '';
    final reported = next.stories.where((each) => each.id == id).firstOrNull;
    story = reported ?? PlanStory.from({'id': id});
    _stopFollowing = next.follow(_changed);
    final working = next.states.where((state) => state.kind == 'WORKING');
    if (!working.any((state) => state.name == to) && working.isNotEmpty) to = working.first.name;
    proposal = null;
    repositories.clear();
    passedOver.clear();
    choices.clear();
    issueRounds.clear();
    agents = [];
    mode = null;
    busy = false;
    if (reported == null) {
      problem = 'The planner did not report story $id.';
      _changed();
    } else if (working.isEmpty) {
      problem = 'No working state was reported by the planner.';
      _changed();
    } else {
      unawaited(load());
    }
  }

  /// Asks a fresh proposal for another working state on this machine.
  void changeTarget(String target) {
    if (busy ||
        tasks.isNotEmpty ||
        !sourceCurrent ||
        !board.stories.any((each) => each.id == story.id) ||
        board.state(target)?.kind != 'WORKING') {
      return;
    }
    to = target;
    unawaited(load());
  }

  /// Selects a repository's main agent or model.
  void chooseMain(String repository, {String? agent, String? model, String? effort}) {
    if (!current || busy) return;
    final previous = repositories[repository];
    if (previous == null) return;
    final proposed = proposal?.repositories.where((each) => each.repository == repository).firstOrNull;
    final next = agent ?? previous.agent;
    // Another agent's model and effort were proposed for the one before, so they start again from the proposal.
    final changed = next != previous.agent;
    repositories[repository] = StoryWorkMainAgent(
      repository: repository,
      agent: next,
      model: model?.trim() ?? (!changed ? previous.model : _offeredModel(proposed, next)),
      effort: effort != null
          ? _chosenEffort(effort)
          : changed
          ? _effortFor(next, proposed?.main.effort)
          : previous.effort,
    );
    _changed();
  }

  /// Chooses one of the known modes offered by the proposal.
  void chooseMode(String value) {
    if (current && !busy) {
      mode = value;
      _changed();
    }
  }

  /// Retains the person's explanation as typed.
  void setComment(String value) {
    if (current && !busy) {
      comment = value;
      _changed();
    }
  }

  /// Retains the overall or per-issue reading limit.
  void setRounds(String value, [String? issue]) {
    if (!current || busy) return;
    if (issue == null) {
      rounds = value;
    } else {
      issueRounds[issue] = value;
    }
    _changed();
  }

  /// Selects Keep, Change or Drop; Change requires an explicitly chosen model.
  void choose(String repository, PlanAssignment sub, String choice, {String? agent, String? model, String? effort}) {
    if (busy || !current || sub.role != 'BUILDER') return;
    final previous = choices[(repository, sub.id)];
    final before = previous?.choice == 'CHANGE' ? previous : null;
    final next = agent ?? previous?.agent ?? sub.agent;
    // Another agent's model and effort were proposed for the one before, so they start again from the proposal.
    final changed = next != (before?.agent ?? sub.agent);
    choices[(repository, sub.id)] = StoryWorkChoice(
      repository: repository,
      name: sub.id,
      choice: choice,
      agent: choice == 'CHANGE' ? next : null,
      model: choice != 'CHANGE'
          ? null
          : model?.trim() ??
                (!changed ? before?.model ?? sub.model.trim() : (next == sub.agent ? sub.model.trim() : '')),
      effort: choice != 'CHANGE'
          ? null
          : effort != null
          ? _chosenEffort(effort)
          : before != null && !changed
          ? before.effort
          : _effortFor(next, sub.effort),
    );
    _changed();
  }

  /// A checked order authorizes Start only while its original binding still holds.
  Future<bool> start() async {
    if (!canStart) return false;
    final origin = board;
    final source = backend;
    final name = project;
    final id = story.id;
    final generation = _generation;
    final boardGeneration = origin.generation;
    final request = StoryWorkRequest(
      project: name,
      story: id,
      to: to,
      mode: mode!,
      revision: revision,
      subAgents: choices.values.toList(),
      repositories: repositories.values.toList(),
      proposedRepositories: proposal!.repositories,
      comment: comment.isEmpty ? null : comment,
      readingRounds: _rounds(rounds),
      issueReadingRounds: {
        for (final each in issueRounds.entries)
          if (each.value.isNotEmpty) each.key: _rounds(each.value)!,
      },
      issues: issues,
      replaces: origin.workOf(story)?.run,
      passOver: passOver,
    );
    Object? token;
    final run = origin.runOf(id)..startTask = startTask;
    run.onStarted = onStarted;
    final epoch = run.epoch;
    bool owned() =>
        run.epoch == epoch &&
        !_disposed &&
        generation == _generation &&
        identical(board, origin) &&
        origin.generation == boardGeneration &&
        identical(backend, source) &&
        project == name &&
        story.id == id;
    bool valid() =>
        owned() &&
        revision == request.revision &&
        current &&
        origin.startUnavailable(story, ownStart: token, passOver: passOver) == null;
    token = origin.beginStoryStart(story, passOver: passOver);
    if (token == null) return false;
    busy = true;
    starting = true;
    _changed();
    var handed = false;
    try {
      final order = await source.startStoryWork(request);
      // An order for a board that moved on is not shown, so the form stays open to choose again.
      if (!valid()) return false;
      tasks = List.unmodifiable(order.tasks.map(StoryWorkTask.new));
      _changed();
      // Once the planner took the order, its tasks' starts belong to the run: a closed form no longer cuts them.
      final shown = tasks;
      final handover = run.startOrder(
        order.tasks,
        startTask,
        epoch,
        ended: ({required accepted}) => origin.endStoryStart(source, name, id, token!, accepted: accepted),
        onTask: (index, started, error) {
          shown[index]
            ..started = started
            ..error = error;
          _changed();
        },
        onProblem: (error) => origin.reportStartProblem(id, error),
        onStarted: onStarted,
      );
      handed = true;
      _ended = handover.done.whenComplete(() {
        if (!_disposed && generation == _generation) {
          starting = false;
          busy = false;
          _changed();
        }
      });
      return await handover.asked;
    } catch (error) {
      if (valid()) {
        problem = planningFailureWords(error);
        if (planningRefused(error, 'NotYetBuilt') || planningRefused(error, 'NotOffered')) {
          origin.unavailableActions['StartStoryWork'] = problem!;
        }
        if (planningRefused(error, 'StaleRevision')) unawaited(origin.reload());
      }
      return false;
    } finally {
      if (!handed) {
        origin.endStoryStart(source, name, id, token, accepted: false);
        if (!_disposed && generation == _generation) {
          starting = false;
          busy = false;
          _changed();
        }
      }
    }
  }

  Future<void>? _ended;

  /// Completes once a handed-over start's tasks started, failed or were cut, and their confirmation ended; at once
  /// where nothing was handed over.
  Future<void> get ended => _ended ?? Future.value();

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _stopFollowing();
    _disposed = true;
    _generation++;
    super.dispose();
  }
}

/// The last source facts for a story outcome or a stopped reading.
class PlanningAttention {
  /// Keeps the source facts and choices for this view or decision.
  PlanningAttention(this.board, this.story, {this.issue, this.ended, this.reason = ''})
    : project = board.project,
      source = board.backend,
      revision = board.snapshot?.revision ?? '';

  /// The project identity when these facts were observed.
  final String project;

  /// The provider that supplied these facts.
  final PlanningSource source;

  /// The revision that supplied this row and its reading round.
  final String revision;

  /// The board whose source and revision this decision uses.
  final PlanningBoard board;

  /// The story reported by that board.
  final PlanStory story;

  /// The issue whose reading stopped, when this row is a reading decision.
  final PlanIssue? issue;

  /// The machine’s reported reason and instant for an ended story run.
  final WorkEnded? ended;

  /// The durable issue reason, independent of the agent’s finished flag.
  final String reason;

  /// What the window keeps when a person puts this issue's row away on [machine], or null for a row with an end, which
  /// only [endOn] marks, so a later end is never hidden by an older put-away.
  PlanningPutAway? putAwayOn(String machine) => switch ((issue, ended)) {
    (final issue?, null) => PlanningPutAway(
      machine: machine,
      project: project,
      issue: issue.id,
      reason: planReasonNeedsAttention(reason) ? reason : issue.attention,
      state: issue.state,
    ),
    _ => null,
  };

  /// What the window keeps when a person marks this row's end seen on [machine]: the story's run's end, or an issue's
  /// task's end, by the story and the end's time; null for a row without an end.
  PlanningStoryEnd? endOn(String machine) => switch (ended) {
    final ended? => PlanningStoryEnd(machine: machine, project: project, story: story.id, at: ended.at),
    null => null,
  };
}

/// What a person marked seen in Needs you, kept by the window alone; the screen asks it and keeps no copy.
///
/// It notifies its listeners when a mark is added or taken back, so the cards and Needs you follow.
abstract interface class PlanningSeen implements Listenable {
  /// Whether the person marked this issue's row seen with this reason and state.
  bool putAwaySeen(PlanningPutAway row);

  /// Whether the person marked this story's run seen as ended at this time.
  bool endSeen(PlanningStoryEnd end);
}

/// A story's ended run a person marked seen, kept by the window alone.
///
/// It names the end's time, so a later end of the story is seen anew.
@immutable
class PlanningStoryEnd {
  /// Takes the run's source, project, story and the time its end was reported at.
  const PlanningStoryEnd({required this.machine, required this.project, required this.story, required this.at});

  /// The provider that reported the end.
  final String machine;

  /// The project the story belongs to.
  final String project;

  /// The story's identifier.
  final String story;

  /// The end's reported time, as the planner wrote it.
  final String at;

  @override
  bool operator ==(Object other) =>
      other is PlanningStoryEnd &&
      other.machine == machine &&
      other.project == project &&
      other.story == story &&
      other.at == at;

  @override
  int get hashCode => Object.hash(machine, project, story, at);
}

/// An issue's Needs you row a person put away, kept by the window alone.
///
/// It names the reason and the state the row was put away with, so the row comes back once either changes.
@immutable
class PlanningPutAway {
  /// Takes the row's source, issue, reason and state.
  const PlanningPutAway({
    required this.machine,
    required this.project,
    required this.issue,
    required this.reason,
    required this.state,
  });

  /// The provider that reported the row.
  final String machine;

  /// The project the issue belongs to.
  final String project;

  /// The issue's identifier.
  final String issue;

  /// The reason's wire name the row showed.
  final String reason;

  /// The issue's state when it was put away.
  final String state;

  @override
  bool operator ==(Object other) =>
      other is PlanningPutAway &&
      other.machine == machine &&
      other.project == project &&
      other.issue == issue &&
      other.reason == reason &&
      other.state == state;

  @override
  int get hashCode => Object.hash(machine, project, issue, reason, state);
}

/// The state of one stopped reading's decision, shared by every view of it on one board.
class _SharedReading extends ChangeNotifier {
  _SharedReading(this._key, this._all);

  final (String, int?, String) _key;
  final Map<(String, int?, String), _SharedReading> _all;
  PlanningIssueDetail? detail;
  String? problem;
  bool busy = false;
  bool submitted = false;
  bool resolving = false;
  int generation = 0;
  int holders = 0;
  bool gone = false;

  void changed() {
    if (!gone) notifyListeners();
  }

  void release() {
    if (--holders > 0) return;
    if (identical(_all[_key], this)) _all.remove(_key);
    gone = true;
    dispose();
  }
}

/// Each board's shared reading decisions, by issue, reading round and revision.
final _sharedReadings = Expando<Map<(String, int?, String), _SharedReading>>();

/// Findings and a revision-bound reading decision. A success waits for the board's echo.
///
/// Every decision of one board's issue, round and revision shares its findings, its answer and whether it was sent,
/// so two views of one stopped reading cannot both send a decision.
class ReadingDecision extends ChangeNotifier {
  /// Keeps the source facts and choices for this view or decision.
  ReadingDecision(this.row) : revision = row.revision, backend = row.source, project = row.project {
    final all = _sharedReadings[row.board] ??= {};
    final key = (row.issue?.id ?? '', row.issue?.rounds, row.revision);
    _shared = all[key] ??= _SharedReading(key, all);
    _shared.holders++;
    _shared.addListener(_notify);
    _stopFollowing = row.board.follow(_notify);
  }

  late final _SharedReading _shared;

  /// The source facts retained by this attention row.
  final PlanningAttention row;

  /// The opaque revision used to bind this decision.
  final String revision;

  /// The machine receiving this decision.
  final PlanningSource backend;

  /// The project name sent to the machine.
  final String project;

  /// The last findings returned by GetIssue.
  PlanningIssueDetail? get detail => _shared.detail;
  set detail(PlanningIssueDetail? value) => _shared.detail = value;

  /// The daemon’s refusal or an incomplete-answer explanation.
  String? get problem => _shared.problem;
  set problem(String? value) => _shared.problem = value;

  /// Whether a request is awaiting its answer.
  bool get busy => _shared.busy;
  set busy(bool value) => _shared.busy = value;

  /// Whether a decision was accepted and is awaiting the board’s echo.
  bool get submitted => _shared.submitted;
  set submitted(bool value) => _shared.submitted = value;

  bool get _resolving => _shared.resolving;
  set _resolving(bool value) => _shared.resolving = value;
  int get _generation => _shared.generation;
  set _generation(int value) => _shared.generation = value;
  bool _disposed = false;
  late final VoidCallback _stopFollowing;

  /// Whether this decision still belongs to a live board at its original revision.
  bool get current =>
      !_disposed &&
      !row.board.isDisposed &&
      revision.isNotEmpty &&
      identical(row.board.backend, backend) &&
      row.board.project == project &&
      row.board.live &&
      row.board.snapshot?.complete == true &&
      row.board.snapshot?.revision == revision &&
      row.issue?.readingStopped == true &&
      row.board.stories.any(
        (story) =>
            story.id == row.story.id &&
            PlanningBoards.attentionIssues(row.board, story).any(
              (issue) => issue.id == row.issue?.id && issue.readingStopped == true && issue.rounds == row.issue?.rounds,
            ),
      );

  /// Why the decision cannot run, retained beside the disabled choices.
  String? get unavailable =>
      row.board.unavailableActions['ResolveIssueReading'] ??
      (row.board.isDisposed
          ? 'The planning source is no longer watched.'
          : !current
          ? 'The board changed or is no longer live.'
          : busy
          ? 'Waiting for the planner’s answer.'
          : submitted
          ? 'Waiting for the board to confirm the decision.'
          : problem ??
                (detail?.findingsReported != true
                    ? 'The last findings have not been reported.'
                    : row.issue?.rounds == null
                    ? 'The reading round was not reported.'
                    : null));

  /// Whether current findings, round and revision allow a reading decision.
  bool get canResolve =>
      !row.board.unavailableActions.containsKey('ResolveIssueReading') &&
      current &&
      problem == null &&
      detail?.findingsReported == true &&
      detail?.revision == revision &&
      detail?.issue.id == row.issue?.id &&
      detail?.issue.readingStopped == true &&
      detail?.issue.rounds == row.issue?.rounds &&
      !busy &&
      !submitted &&
      row.issue?.rounds != null;

  /// Reads the proposal or findings from the captured machine.
  Future<void> load() async {
    if (_disposed || _resolving || submitted || row.issue?.readingStopped != true) return;
    final generation = ++_generation;
    // A read nobody watches any more is dropped quietly; one a view remounted meanwhile still lands.
    bool valid() => !_shared.gone && _shared.holders > 1 && generation == _generation;
    // The read holds the shared state until it settles, so a view mounted meanwhile takes its answer.
    final shared = _shared;
    shared.holders++;
    busy = true;
    problem = null;
    _changed();
    try {
      final answer = await backend.getPlanningIssue(project, row.issue!.id);
      if (valid()) {
        if (answer.findingsReported &&
            answer.revision == revision &&
            answer.issue.id == row.issue!.id &&
            answer.issue.readingStopped == true &&
            answer.issue.rounds == row.issue!.rounds) {
          detail = answer;
        } else {
          detail ??= answer;
          problem = 'The planner did not report the last findings.';
        }
      }
    } catch (error) {
      if (valid()) {
        problem = planningFailureWords(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          row.board.unavailableActions['ResolveIssueReading'] = problem!;
        }
        if (planningRefused(error, 'SaveRefused')) row.board.runOf(row.story.id).cancel();
        if ([
          'StaleRevision',
          'ReadingNotStopped',
          'ReadingRoundChanged',
          'ReworkNeedsFindings',
          'SaveRefused',
        ].any((name) => planningRefused(error, name))) {
          unawaited(row.board.reload());
        }
      }
    } finally {
      if (valid()) {
        busy = false;
        _changed();
      }
      shared.release();
    }
  }

  /// Whether the last findings hold a blocking one, which a rework takes without the person's words.
  bool get blocking => detail?.findings.any((finding) => finding.severity == 'BLOCKING') == true;

  /// The Reworking rule this reading's rework would run under, or null when the board reports none.
  PlanWorkRule? get reworking {
    final work = row.board.snapshot?.work ?? const <PlanWorkRule>[];
    final state = row.board.stories
        .where((story) => story.id == row.story.id)
        .expand((story) => story.issues)
        .where((issue) => issue.id == row.issue?.id)
        .firstOrNull
        ?.state;
    final blocked = work.where((rule) => rule.state == state).firstOrNull?.blocked;
    return blocked == null ? null : work.where((rule) => rule.state == blocked).firstOrNull;
  }

  /// Sends one explicit reading decision and waits for the board to clear the stop.
  Future<void> resolve(String choice, {String? comment, PlanningRework? rework}) async {
    if (_disposed ||
        !canResolve ||
        !['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK'].contains(choice) ||
        choice == 'REWORK' && !blocking && (comment == null || comment.trim().isEmpty) ||
        choice != 'REWORK' && rework != null ||
        (comment?.runes.length ?? 0) > 4000) {
      return;
    }
    // The decision holds the shared state until it settles, so no view mounted meanwhile can send it again.
    final shared = _shared;
    shared.holders++;
    busy = true;
    problem = null;
    _changed();
    _resolving = true;
    final run = row.board.runOf(row.story.id);
    final epoch = run.epoch;
    try {
      final answer = await backend.resolveIssueReading(
        project,
        row.story.id,
        row.issue!.id,
        row.issue!.rounds!,
        choice,
        revision,
        comment: choice == 'REWORK' ? comment : '',
        rework: rework,
      );
      answer.checkDecision(row.issue!.id, choice);
      run.observe(answer.work);
      await run.follow(answer.tasks, epoch);
      if (!_shared.gone) {
        submitted = true;
        await row.board.reload();
      }
    } catch (error) {
      if (!_shared.gone) {
        problem = planningFailureWords(error);
        if (planningUnsupported(error) || planningRefused(error, 'NotYetBuilt')) {
          row.board.unavailableActions['ResolveIssueReading'] = problem!;
        }
        if (planningRefused(error, 'SaveRefused')) row.board.runOf(row.story.id).cancel();
        if ([
          'StaleRevision',
          'ReadingNotStopped',
          'ReadingRoundChanged',
          'ReworkNeedsFindings',
          'SaveRefused',
        ].any((name) => planningRefused(error, name))) {
          unawaited(row.board.reload());
        }
      }
    } finally {
      _resolving = false;
      if (!_shared.gone) {
        busy = false;
        _changed();
      }
      shared.release();
    }
  }

  void _changed() => _shared.changed();

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _shared.removeListener(_notify);
    _shared.release();
    _stopFollowing();
    super.dispose();
  }
}

/// One entry of Needs you from planning: a story's ended run or an issue's stopped reading.
class PlanningNeedsYou {
  /// Takes the entry's stable identity, its provider and its facts.
  const PlanningNeedsYou(this.id, this.machine, this.attention);

  /// The stable identity, the same while the same facts stand.
  final String id;

  /// The provider that reported it.
  final String machine;

  /// The facts and the board they came from.
  final PlanningAttention attention;
}
