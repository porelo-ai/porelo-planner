import 'dart:async';

import 'package:flutter/material.dart';

import '../hooks.dart';
import '../models.dart';
import '../planning_board.dart';
import '../story_work.dart';
import 'planning_detail_widgets.dart' show planningKindText, planningRoleText;
import 'style.dart';

/// What a proposal the board has moved past says.
const _stale = 'The board changed or is no longer live. Cancel and open Start work again.';

/// Shows Start work for [work] through the window's [wizards] when it has them, else in the screen's own frame, and
/// answers whether the planner took the order.
Future<bool> showStoryWork(BuildContext context, StoryWork work, {PlanningWizards? wizards}) async {
  if (wizards == null) {
    final started = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => StoryWorkWizard(work),
    );
    return started ?? false;
  }
  unawaited(work.load());
  return wizards.show(
    context,
    title: 'Start work',
    steps: _StoryWorkSteps(work).steps,
    changes: work,
    startLabel: () => work.startLabel,
    start: work.start,
    canCancel: () => _canCancel(work),
  );
}

/// Cancel waits only while a start the planner may already have taken is answered.
bool _canCancel(StoryWork work) => !(work.busy && work.proposal != null);

/// What an agent or a repository wrote, as the screen shows it.
String _said(String text, {bool multiline = false}) =>
    text.isEmpty ? 'not reported' : planningShown(text, multiline: multiline);

/// A list with its label above it; the list keeps its own key, as the window's tests find it by that.
Widget _labeled(String label, Widget list) => InputDecorator(
  decoration: InputDecoration(labelText: label),
  child: DropdownButtonHideUnderline(child: list),
);

/// A wait the person sees as one: the screen's still mark beside its words.
Widget _waiting(String words) => Row(
  spacing: Space.small,
  children: [
    // A still mark: an endless animation would keep a waiting dialog from ever settling.
    const Icon(Icons.hourglass_empty, size: PlanningStyle.mark),
    Flexible(child: Text(words)),
  ],
);

/// The modes the screen knows how to start in, among those the planner offered.
List<String> _modes(StoryWork work) =>
    work.proposal?.modes.where((mode) => ['UNATTENDED', 'AGENT'].contains(mode)).toSet().toList() ?? const [];

String _modeName(String mode) => mode == 'UNATTENDED' ? 'Unattended' : 'With you';

String _modeWords(String mode) =>
    mode == 'AGENT' ? 'Work with the agents in the terminal.' : 'The agents work unattended.';

/// Start work's steps over one form: where, how, who and how the work is read.
class _StoryWorkSteps {
  _StoryWorkSteps(this.work);

  final StoryWork work;

  late final List<PlanningWizardStep> steps = [
    PlanningWizardStep(
      title: 'Where',
      words: 'The machine the work runs on and the planner’s proposal for it.',
      panel: _where,
      check: _whereProblem,
      summary: () => [if (_machineChoice) 'Runs on ${_said(_machine)}', 'Working state: ${_said(work.to)}'],
      shown: () => _machineChoice || _targets.length > 1 || _whereProblem() != null,
    ),
    PlanningWizardStep(
      title: 'How',
      words: 'Whether the agents work unattended or with you.',
      panel: (context) => _proposed(context, _how),
      check: _howProblem,
      summary: () => [if (_modes(work).contains(work.mode)) _modeName(work.mode!) else 'No mode chosen'],
      shown: () => _modes(work).length > 1 || _howProblem() != null,
    ),
    PlanningWizardStep(
      title: 'Who',
      words: 'The agent, model and effort of each task, and its sub-agents.',
      panel: (context) => _proposed(context, _who),
      check: () => work.whoProblem,
      summary: _whoSummary,
    ),
    PlanningWizardStep(
      title: 'Reading',
      words: 'How often the work is read, and why a sub-agent changed.',
      panel: (context) => _proposed(context, _reading),
      check: () => work.readingProblem,
      summary: () => [
        'Most reading rounds: ${work.rounds.isEmpty ? 'not given' : _said(work.rounds)}',
        for (final each in work.issueRounds.entries)
          if (each.value.isNotEmpty) 'Reading rounds of ${_said(each.key)}: ${_said(each.value)}',
        work.comment.trim().isEmpty ? 'No comment' : 'Comment: ${_said(work.comment, multiline: true)}',
      ],
    ),
  ];

  bool get _enabled => work.current && !work.busy && work.tasks.isEmpty;

  String get _machine => work.machines[work.board] ?? work.backend.label;

  bool get _machineChoice => work.machines.length > 1 || work.unavailableMachines.isNotEmpty;

  List<String> get _targets => [
    for (final state in work.board.states)
      if (state.kind == 'WORKING') state.name,
  ];

  String? _whereProblem() {
    if (work.busy && work.proposal == null) return 'Waiting for the planner’s answer.';
    // A refusal is the answer; a board that moved since leaves it as it stands. A proposal the window holds goes stale
    // as well, so its problem comes first and the stale line after it.
    if (work.problem case final problem?) return !work.current && work.proposal != null ? '$problem\n$_stale' : problem;
    if (!work.current) return _stale;
    if (work.proposal == null) return 'The planner has not proposed the work yet.';
    return null;
  }

  String? _howProblem() {
    if (work.proposal == null) return 'The planner has not proposed the work yet.';
    final modes = _modes(work);
    if (modes.isEmpty) return 'The planner offered no supported modes.';
    return modes.contains(work.mode) ? null : 'Choose how to work.';
  }

  /// The story and its move, heading every step.
  Widget _head() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('${_said(work.story.id)} — ${_said(work.story.title)}'),
      Text('${_said(work.story.state)} → ${_said(work.to)}'),
      const SizedBox(height: Space.normal),
    ],
  );

  /// A panel drawn from the proposal, its fields started again when another proposal arrives.
  Widget _proposed(BuildContext context, Widget Function(BuildContext, StoryWorkProposal) panel) {
    final proposal = work.proposal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _head(),
        if (proposal == null)
          const Text('The planner has not proposed the work yet.')
        else
          KeyedSubtree(key: ObjectKey(proposal), child: panel(context, proposal)),
      ],
    );
  }

  Widget _where(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _head(),
      if (_machineChoice)
        _labeled(
          'Runs on',
          DropdownButton<PlanningBoard>(
            key: const Key('story-work-machine'),
            value: work.board,
            isExpanded: true,
            hint: const Text('Machine'),
            items: [
              for (final source in work.machines.entries)
                DropdownMenuItem(
                  value: source.key,
                  enabled:
                      !source.key.isDisposed &&
                      source.key.live &&
                      source.key.snapshot?.complete == true &&
                      source.key.snapshot!.revision.isNotEmpty,
                  child: Text(
                    source.key.live && source.key.snapshot?.complete == true
                        ? source.value
                        : '${source.value} — planning not live',
                  ),
                ),
              for (final machine in work.unavailableMachines)
                DropdownMenuItem<PlanningBoard>(
                  enabled: false,
                  child: Text('$machine — project planning not reported'),
                ),
            ],
            onChanged: work.starting || work.tasks.isNotEmpty
                ? null
                : (source) {
                    if (source != null) work.changeMachine(source);
                  },
          ),
        ),
      if (_targets.length > 1 || !_targets.contains(work.to))
        _labeled(
          'Working state',
          DropdownButton<String>(
            key: const Key('story-work-target'),
            value: _targets.contains(work.to) ? work.to : null,
            isExpanded: true,
            hint: const Text('Working state'),
            items: [for (final state in _targets) DropdownMenuItem(value: state, child: Text(_said(state)))],
            onChanged: !work.sourceCurrent || work.busy || work.tasks.isNotEmpty
                ? null
                : (value) {
                    if (value != null) work.changeTarget(value);
                  },
          ),
        )
      else
        Text('Working state: ${_said(work.to)}', key: const Key('story-work-target-name')),
      if (work.busy) _waiting('Waiting for the planner’s answer…'),
      // Only a proposal the window holds goes stale; a refused or missing one has nothing to go stale.
      if (!work.current && work.proposal != null) const Text(_stale),
      if (work.problem case final problem?) ...[
        Text(problem, key: const Key('story-work-error')),
        TextButton(
          key: const Key('story-work-retry'),
          onPressed: work.busy ? null : () => unawaited(work.load()),
          child: const Text('Retry proposal'),
        ),
      ],
    ],
  );

  Widget _how(BuildContext context, StoryWorkProposal proposal) {
    final modes = _modes(work);
    if (modes.isEmpty) return const Text('The planner offered no supported modes.');
    if (modes.length == 1) {
      return Text('${_modeName(modes.single)}: ${_modeWords(modes.single)}', key: const Key('story-work-mode-name'));
    }
    final cards = [for (final mode in modes) _mode(context, mode)];
    return MediaQuery.sizeOf(context).width < PlanningStyle.compactWidth
        ? Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: cards)
        : Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [for (final card in cards) Expanded(child: card)],
          );
  }

  Widget _mode(BuildContext context, String mode) => Card(
    key: Key('story-work-mode $mode'),
    color: work.mode == mode ? Theme.of(context).colorScheme.primaryContainer : null,
    child: InkWell(
      onTap: !_enabled ? null : () => work.chooseMode(mode),
      child: Padding(
        padding: const EdgeInsets.all(Space.normal),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(work.mode == mode ? Icons.radio_button_checked : Icons.radio_button_unchecked),
                const SizedBox(width: Space.small),
                Flexible(child: Text(_modeName(mode))),
              ],
            ),
            Text(_modeWords(mode)),
          ],
        ),
      ),
    ),
  );

  String get _coordinator => switch (work.proposal?.coordinator) {
    'START' => 'Start the project coordinator.',
    'TELL' => 'Tell the running project coordinator about this story.',
    final other => 'Coordinator: ${_said(other ?? '')}',
  };

  String _effortSaid(String? effort) => effort == null ? 'the agent’s default effort' : 'effort ${_said(effort)}';

  List<String> _whoSummary() {
    final proposal = work.proposal;
    if (proposal == null) return const ['The planner has not proposed the work yet.'];
    return [
      for (final repo in proposal.repositories) ...[
        if (work.repositories[repo.repository] case final main?)
          '${_said(repo.repository)}: ${_said(main.agent)} · ${_said(main.model)} · ${_effortSaid(main.effort)}',
        for (final sub in repo.subAgents)
          if (work.choices[(repo.repository, sub.id)] case final chosen?)
            '${_said(repo.repository)} ${_said(sub.name)}: ${switch (chosen.choice) {
              'KEEP' => 'keep ${_said(sub.agent)} · ${_said(sub.model)} · ${_effortSaid(sub.effort)}',
              'CHANGE' => 'change to ${_said(chosen.agent ?? '')} · ${_said(chosen.model ?? '')} · '
                  '${_effortSaid(chosen.effort)}',
              _ => 'drop',
            }}',
      ],
      if (proposal.manual.isNotEmpty) 'Worked by a person, with no task: ${proposal.manual.join(', ')}',
      if (proposal.held.isNotEmpty) 'Stays behind: ${proposal.held.map((held) => _said(held.issue)).join(', ')}',
      _coordinator,
    ];
  }

  Widget _who(BuildContext context, StoryWorkProposal proposal) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final repo in proposal.repositories) ...[
        const SizedBox(height: Space.normal),
        Text(
          repo.repository,
          key: Key('story-work-task ${repo.repository}'),
          style: Theme.of(context).textTheme.titleSmall,
        ),
        Text(
          'Issues: ${repo.issues.isEmpty ? 'none' : repo.issues.join(', ')}',
          key: Key('story-work-issues ${repo.repository}'),
        ),
        Text(planningRoleText(repo.main.role), key: Key('story-work-role ${repo.repository}')),
        if (work.passedOver[repo.repository] case final passed?)
          Text(passed, key: Key('story-work-passed-over ${repo.repository}')),
        ..._assignment(
          what: repo.repository,
          agent: work.repositories[repo.repository]!.agent,
          model: work.repositories[repo.repository]!.model,
          effort: work.repositories[repo.repository]!.effort,
          proposed: (
            agent: repo.main.agent,
            model: repo.main.model,
            effort: repo.main.effort,
            chosenBy: repo.main.chosenBy,
          ),
          help: 'Model proposed for ${_said(work.to)}.',
          keys: ('story-work-main-agent', 'story-work-main-model', 'story-work-main-models', 'story-work-main-effort'),
          enabled: _enabled,
          choose: ({agent, model, effort}) =>
              work.chooseMain(repo.repository, agent: agent, model: model, effort: effort),
        ),
        for (final sub in repo.subAgents) _sub(repo.repository, sub, _enabled && sub.isHelper),
        ExpansionTile(
          title: Text('The work order for ${_said(repo.task)}'),
          children: [
            SelectableText(_said(repo.workOrder, multiline: true), key: Key('story-work-order ${repo.repository}')),
          ],
        ),
      ],
      if (proposal.manual.isNotEmpty) ...[
        const SizedBox(height: Space.normal),
        Text('Worked by a person, with no task: ${proposal.manual.join(', ')}', key: const Key('story-work-manual')),
      ],
      if (proposal.held.isNotEmpty) ...[
        const SizedBox(height: Space.normal),
        Text('Stays behind', key: const Key('story-work-held'), style: Theme.of(context).textTheme.titleSmall),
        for (final held in proposal.held)
          Text('${_said(held.issue)}: ${_said(held.reason)}', key: Key('story-work-held ${held.issue}')),
      ],
      const SizedBox(height: Space.normal),
      Text(_coordinator, key: const Key('story-work-coordinator')),
    ],
  );

  /// The agent, model and effort of one task or changed sub-agent, keyed by [keys] and [what].
  List<Widget> _assignment({
    required String what,
    required String agent,
    required String model,
    required String? effort,
    required ({String agent, String model, String? effort, String? chosenBy}) proposed,
    required (String, String, String, String) keys,
    required bool enabled,
    required void Function({String? agent, String? model, String? effort}) choose,
    String? help,
  }) {
    final listed = work.agents.where((each) => each.name == agent).firstOrNull;
    final ownProposal = agent == proposed.agent;
    final efforts = listed?.efforts;
    return [
      _agentPicker(
        key: Key('${keys.$1} $what'),
        value: agent,
        proposed: proposed.agent,
        enabled: enabled,
        choose: (value) => choose(agent: value),
      ),
      PlanningModelField(
        fieldKey: Key('${keys.$2} $what'),
        listKey: Key('${keys.$3} $what'),
        initialValue: model,
        models: listed?.models ?? const [],
        proposed: ownProposal ? proposed.model : null,
        help: _modelHelp(help, listed?.modelsWhy),
        hint: 'Choose a model',
        enabled: enabled,
        onChanged: (value) => choose(model: value),
      ),
      KeyedSubtree(
        // Another agent starts its effort again from the proposal, so the field is drawn anew.
        key: ValueKey(('effort', what, agent)),
        child: efforts == null
            ? _effortField(
                key: Key('${keys.$4} $what'),
                value: effort,
                chosenBy: proposed.chosenBy,
                enabled: enabled,
                onChanged: (value) => choose(effort: value),
              )
            : _effortList(
                key: Key('${keys.$4} $what'),
                value: effort,
                efforts: efforts,
                proposed: proposed.effort,
                chosenBy: proposed.chosenBy,
                enabled: enabled,
                onChanged: (value) => choose(effort: value),
              ),
      ),
    ];
  }

  Widget _agentName(String name) =>
      work.agents.where((agent) => agent.name == name).firstOrNull?.choice ?? Text(_said(name));

  Widget _agentItem(String name) {
    final shown = _agentName(name);
    final unready = work.unreadyWhy(name);
    if (unready == null) return shown;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [shown, Text('Cannot work there: ${planningShown(unready)}')],
    );
  }

  Widget _agentPicker({
    required Key key,
    required String value,
    required String proposed,
    required bool enabled,
    required ValueChanged<String> choose,
  }) {
    final names = {
      if (proposed.isNotEmpty) proposed,
      ...work.agents.map((agent) => agent.name),
      if (value.isNotEmpty) value,
    };
    return DropdownButtonFormField<String>(
      key: key,
      initialValue: value.isEmpty ? null : value,
      isExpanded: true,
      itemHeight: null,
      decoration: const InputDecoration(labelText: 'Agent', hintText: 'not reported', border: OutlineInputBorder()),
      items: [for (final name in names) DropdownMenuItem(value: name, child: _agentItem(name))],
      // The chosen agent's reason, if any, stands under the step, so the field shows its name alone.
      selectedItemBuilder: (_) => [for (final name in names) _agentName(name)],
      onChanged: !enabled
          ? null
          : (value) {
              if (value != null) choose(value);
            },
    );
  }

  Widget _sub(String repository, PlanAssignment sub, bool enabled) {
    final chosen = work.choices[(repository, sub.id)]!;
    return Padding(
      key: Key('story-work-sub $repository ${sub.id}'),
      padding: const EdgeInsets.only(top: Space.small),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('${_said(sub.name)} · ${planningKindText(sub.kind)} · ${_said(sub.agent)} · ${_said(sub.model)}'),
          if (sub.brief.isNotEmpty) Text(_said(sub.brief, multiline: true)),
          Wrap(
            spacing: Space.small,
            children: [
              for (final choice in ['KEEP', 'CHANGE', 'DROP'])
                ChoiceChip(
                  key: Key('story-work-choice $repository ${sub.id} $choice'),
                  label: Text(switch (choice) {
                    'KEEP' => 'Keep',
                    'CHANGE' => 'Change',
                    _ => 'Drop',
                  }),
                  selected: chosen.choice == choice,
                  onSelected: !enabled ? null : (_) => work.choose(repository, sub, choice),
                ),
            ],
          ),
          if (chosen.choice == 'CHANGE')
            ..._assignment(
              what: '$repository ${sub.id}',
              agent: chosen.agent ?? '',
              model: chosen.model ?? '',
              effort: chosen.effort,
              proposed: (agent: sub.agent, model: sub.model, effort: sub.effort, chosenBy: sub.chosenBy),
              keys: ('story-work-agent', 'story-work-model', 'story-work-models', 'story-work-effort'),
              enabled: enabled,
              choose: ({agent, model, effort}) =>
                  work.choose(repository, sub, 'CHANGE', agent: agent, model: model, effort: effort),
            ),
        ],
      ),
    );
  }

  Widget _reading(BuildContext context, StoryWorkProposal proposal) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      TextFormField(
        key: const Key('story-work-comment'),
        initialValue: planningShown(work.comment),
        maxLines: null,
        enabled: _enabled,
        decoration: InputDecoration(
          labelText: 'Comment',
          helperText: work.needsComment ? 'Required when a sub-agent is changed or dropped.' : 'Optional',
        ),
        onChanged: work.setComment,
      ),
      TextFormField(
        key: const Key('story-work-rounds'),
        initialValue: work.rounds,
        keyboardType: TextInputType.number,
        enabled: _enabled,
        decoration: const InputDecoration(labelText: 'Most reading rounds', helperText: 'A whole number, 1 or more.'),
        onChanged: (value) => work.setRounds(value),
      ),
      if (proposal.issues.isNotEmpty)
        ExpansionTile(
          title: const Text('Per issue'),
          children: [
            for (final issue in proposal.issues)
              TextFormField(
                key: Key('story-work-rounds ${issue.id}'),
                initialValue: work.issueRounds[issue.id],
                keyboardType: TextInputType.number,
                enabled: _enabled,
                decoration: InputDecoration(labelText: '${issue.id} · ${issue.repository}', hintText: work.rounds),
                onChanged: (value) => work.setRounds(value, issue.id),
              ),
          ],
        ),
    ],
  );
}

/// Start work in the screen's own frame, for a window without a wizard: its steps one by one, then a review of every
/// choice with a Change per step and the Start.
class StoryWorkWizard extends StatefulWidget {
  /// Uses a form owned by the caller until the frame closes, and asks its proposal.
  const StoryWorkWizard(this.work, {super.key});

  /// The current proposal, source, choices and refusal.
  final StoryWork work;

  @override
  State<StoryWorkWizard> createState() => _StoryWorkWizardState();
}

class _StoryWorkWizardState extends State<StoryWorkWizard> {
  StoryWork get work => widget.work;
  late final _parts = _StoryWorkSteps(work);
  late final List<PlanningWizardStep> _steps = _parts.steps;
  late final VoidCallback _stopFollowing;

  /// The step shown, or the steps' count for the review.
  int _index = 0;

  @override
  void initState() {
    super.initState();
    _stopFollowing = work.follow(_changed);
    unawaited(work.load());
    _passOver();
  }

  /// A step with nothing to choose is passed over once it says so, never while it waits or was refused.
  void _passOver() {
    if (_index < _steps.length && !_steps[_index].shown()) _index = _next(_index);
  }

  void _changed() {
    if (!mounted) return;
    setState(_passOver);
  }

  @override
  void dispose() {
    _stopFollowing();
    super.dispose();
  }

  int _next(int from) {
    for (var index = from + 1; index < _steps.length; index++) {
      if (_steps[index].shown()) return index;
    }
    return _steps.length;
  }

  int? _previous(int from) {
    for (var index = from - 1; index >= 0; index--) {
      if (_steps[index].shown()) return index;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    // Once a start is under way, the review shows how far it is.
    final index = work.starting || work.tasks.isNotEmpty ? _steps.length : _index;
    final review = index == _steps.length;
    final step = review ? null : _steps[index];
    final problem = step?.check();
    final previous = _previous(index);
    final canCancel = _canCancel(work);
    return PopScope(
      canPop: canCancel,
      child: AlertDialog(
        key: const Key('story-work-dialog'),
        title: Text(review ? 'Start work — Review' : 'Start work — ${step!.title}'),
        content: SizedBox(
          width: PlanningStyle.dialog,
          child: SingleChildScrollView(
            child: KeyedSubtree(
              key: ValueKey(index),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (step == null)
                    _review(context)
                  else ...[
                    if (step.words.isNotEmpty) Text(step.words),
                    step.panel(context),
                    if (problem != null) ...[
                      const SizedBox(height: Space.normal),
                      Text(problem, key: const Key('story-work-step-problem')),
                    ],
                  ],
                ],
              ),
            ),
          ),
        ),
        actions: [
          TextButton(
            key: const Key('story-work-cancel'),
            onPressed: canCancel ? () => Navigator.pop(context, false) : null,
            child: const Text('Cancel'),
          ),
          TextButton(
            key: const Key('story-work-back'),
            onPressed: previous == null || work.starting || work.tasks.isNotEmpty
                ? null
                : () => setState(() => _index = previous),
            child: const Text('Back'),
          ),
          if (review)
            FilledButton(
              key: const Key('story-work-start'),
              onPressed: !work.canStart || _steps.any((step) => step.check() != null)
                  ? null
                  : () async {
                      final started = await work.start();
                      if (context.mounted && started) Navigator.pop(context, true);
                    },
              child: Text(_said(work.startLabel)),
            )
          else
            FilledButton(
              key: const Key('story-work-next'),
              onPressed: problem != null ? null : () => setState(() => _index = _next(index)),
              child: const Text('Next'),
            ),
        ],
      ),
    );
  }

  Widget _review(BuildContext context) {
    final proposal = work.proposal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _parts._head(),
        for (final (index, step) in _steps.indexed) ...[
          Row(
            children: [
              Expanded(child: Text(step.title, style: Theme.of(context).textTheme.titleSmall)),
              if (step.shown())
                TextButton(
                  key: Key('story-work-change $index'),
                  onPressed: work.starting || work.tasks.isNotEmpty ? null : () => setState(() => _index = index),
                  child: const Text('Change'),
                ),
            ],
          ),
          for (final line in step.summary()) Text(line),
          const SizedBox(height: Space.small),
        ],
        if (proposal != null)
          ExpansionTile(
            title: const Text('The work order the agent gets'),
            children: [
              SelectableText(
                planningShown(proposal.repositories.map((repo) => repo.workOrder).join('\n\n')),
                key: const Key('story-work-order'),
              ),
            ],
          ),
        if (work.problem case final problem?) Text(problem, key: const Key('story-work-error')),
        for (final task in work.tasks) ...[
          Text(
            _said(
              '${task.order.repository} · ${planningRoleText(task.order.role, readLanded: task.order.readLanded)}'
              ' · ${task.order.issues.join(', ')}',
            ),
            key: Key('story-work-ordered ${task.order.offer}'),
          ),
          if (task.started case final started?) Text(_said('Started ${started.task} in ${started.container}')),
          if (task.problem case final problem?) Text(_said(problem, multiline: true)),
          if (task.started == null && task.problem == null)
            work.starting ? _waiting('Waiting for Start; the window shows how far it is.') : const Text('Not started'),
        ],
      ],
    );
  }
}

/// The effort a task starts with, typed, for an agent whose efforts the window does not know; empty is none.
Widget _effortField({
  required Key key,
  required String? value,
  required String? chosenBy,
  required bool enabled,
  required ValueChanged<String> onChanged,
}) => TextFormField(
  key: key,
  initialValue: value,
  enabled: enabled,
  decoration: InputDecoration(
    labelText: 'Effort',
    hintText: 'none',
    border: const OutlineInputBorder(),
    helperText: [
      if (chosenBy != null && chosenBy.isNotEmpty) 'Proposed by ${planningShown(chosenBy)}.',
      'An agent that knows no effort starts without it.',
    ].join(' '),
    helperMaxLines: 4,
  ),
  onChanged: onChanged,
);

/// The effort a task starts with, chosen from those the agent lists, or the agent's default (empty).
Widget _effortList({
  required Key key,
  required String? value,
  required List<String> efforts,
  required String? proposed,
  required String? chosenBy,
  required bool enabled,
  required ValueChanged<String> onChanged,
}) => DropdownButtonFormField<String>(
  key: key,
  initialValue: value != null && efforts.contains(value) ? value : '',
  isExpanded: true,
  decoration: InputDecoration(
    labelText: 'Effort',
    border: const OutlineInputBorder(),
    helperText: chosenBy != null && chosenBy.isNotEmpty ? 'Proposed by ${planningShown(chosenBy)}.' : null,
    helperMaxLines: 4,
  ),
  items: [
    const DropdownMenuItem(value: '', child: Text('The agent’s default')),
    for (final effort in efforts)
      DropdownMenuItem(
        value: effort,
        child: Text(effort == proposed ? '${planningShown(effort)} (proposed)' : planningShown(effort)),
      ),
  ],
  onChanged: !enabled
      ? null
      : (value) {
          if (value != null) onChanged(value);
        },
);

/// A model typed as it is named, or chosen from the agent's models, or the one a proposal names.
class PlanningModelField extends StatefulWidget {
  /// Takes the input's key, what to do with a change and what to show around it; with [models], a list beside the
  /// input fills it.
  const PlanningModelField({
    required this.fieldKey,
    required this.onChanged,
    this.initialValue,
    this.help,
    this.hint = 'The default',
    this.enabled = true,
    this.models = const [],
    this.proposed,
    this.listKey,
    super.key,
  });

  /// The key of the actual text input.
  final Key fieldKey;

  /// The explicit model choice to retain.
  final ValueChanged<String> onChanged;

  /// The model proposed, kept by the form; a changed value replaces what the input holds.
  final String? initialValue;

  /// The provider's explanation of the proposal.
  final String? help;

  /// The words shown for an unfilled model.
  final String hint;

  /// Whether the captured source still accepts choices.
  final bool enabled;

  /// The models the agent lists, offered beside the input; none offers no list.
  final List<PlanningModel> models;

  /// The model the proposal names, marked in the list.
  final String? proposed;

  /// The key of the list beside the input.
  final Key? listKey;

  @override
  State<PlanningModelField> createState() => _PlanningModelFieldState();
}

class _PlanningModelFieldState extends State<PlanningModelField> {
  late final _text = TextEditingController(text: widget.initialValue);

  @override
  void didUpdateWidget(PlanningModelField old) {
    super.didUpdateWidget(old);
    final value = widget.initialValue ?? '';
    // A value set from outside (another agent, a list) is shown; one that only trims what is typed is not.
    if (widget.initialValue != old.initialValue && value.trim() != _text.text.trim()) _text.text = value;
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  void _choose(String model) {
    _text.text = model;
    widget.onChanged(model);
  }

  @override
  Widget build(BuildContext context) => TextFormField(
    key: widget.fieldKey,
    controller: _text,
    decoration: InputDecoration(
      labelText: 'Model',
      hintText: widget.hint,
      border: const OutlineInputBorder(),
      helperText: widget.help,
      helperMaxLines: 4,
      suffixIcon: widget.models.isEmpty
          ? null
          : PopupMenuButton<String>(
              key: widget.listKey,
              enabled: widget.enabled,
              tooltip: 'The agent’s models',
              icon: const Icon(Icons.arrow_drop_down),
              onSelected: _choose,
              itemBuilder: (_) => [
                for (final model in widget.models)
                  PopupMenuItem(
                    value: model.id,
                    child: Text(
                      [planningShown(model.label ?? model.id), if (model.id == widget.proposed) '(proposed)'].join(' '),
                    ),
                  ),
              ],
            ),
    ),
    enabled: widget.enabled,
    onChanged: widget.onChanged,
  );
}

/// The model field's help: the proposal's words, then why the agent's models could not be listed.
String? _modelHelp(String? help, String? modelsWhy) {
  final words = [
    if (help != null && help.isNotEmpty) help,
    if (modelsWhy != null && modelsWhy.isNotEmpty) planningShown(modelsWhy, multiline: false),
  ];
  return words.isEmpty ? null : words.join(' ');
}
