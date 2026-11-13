import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'mock_address.dart';
import 'window_stand_in.dart';

export 'hook_log.dart';
export 'window_stand_in.dart';

/// The planning screen alone, beside forms for what the window hands it and a log of what it asks of the window.
class PlanningTestApp extends StatefulWidget {
  /// Shows the screen against [window]'s settings.
  const PlanningTestApp({required this.window, super.key});

  /// What the window hands the screen, and the log of the screen's calls.
  final WindowStandIn window;

  @override
  State<PlanningTestApp> createState() => _PlanningTestAppState();
}

class _PlanningTestAppState extends State<PlanningTestApp> {
  late final WindowStandIn window = widget.window;
  late final MockPlanningClient client = MockPlanningClient(
    () => window.planningProviders,
    changes: window,
    currentProvider: () => window.currentProvider,
    chosenProject: () => window.chosenProject,
  );
  late final PlanningBoards boards = PlanningBoards(client);
  late final RecordingHost host = RecordingHost(window.log);
  late final RecordingTasks tasks = RecordingTasks(window);
  late final RecordingAgents agents = RecordingAgents(window);

  @override
  void dispose() {
    boards.dispose();
    client.dispose();
    host.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Planning screen test app',
    home: Scaffold(
      body: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 440,
            child: DefaultTabController(
              length: 3,
              child: Column(
                children: [
                  const TabBar(
                    tabs: [
                      Tab(key: Key('app-tab-window'), text: 'From the window'),
                      Tab(key: Key('app-tab-events'), text: 'Events'),
                      Tab(key: Key('app-tab-log'), text: 'Log'),
                    ],
                  ),
                  Expanded(
                    child: TabBarView(
                      children: [
                        _WindowForm(window: window, host: host),
                        _EventsForm(window: window, client: client),
                        _LogView(window: window),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(
            child: PlanningScreen(
              boards: boards,
              host: host,
              startTask: window.startTask,
              taskChanges: tasks,
              agents: agents,
            ),
          ),
        ],
      ),
    ),
  );
}

Widget _field(String key, String label, TextEditingController controller, {int lines = 1}) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 4),
  child: TextField(
    key: Key(key),
    controller: controller,
    minLines: lines,
    maxLines: lines,
    decoration: InputDecoration(labelText: label, border: const OutlineInputBorder(), isDense: true),
  ),
);

Widget _heading(String text) => Padding(
  padding: const EdgeInsets.only(top: 16, bottom: 4),
  child: Text(text, style: const TextStyle(fontWeight: FontWeight.bold)),
);

String? _blank(String text) => text.trim().isEmpty ? null : text.trim();

/// Reads providers one a line, `name: project, project`, a leading `-` for one that does not answer.
List<ProviderEntry> parseProviders(String text) => [
  for (final line in const LineSplitter().convert(text))
    if (line.trim().isNotEmpty) _provider(line.trim()),
];

ProviderEntry _provider(String line) {
  final offline = line.startsWith('-');
  final body = offline ? line.substring(1).trim() : line;
  final colon = body.indexOf(':');
  final name = (colon < 0 ? body : body.substring(0, colon)).trim();
  final projects = colon < 0
      ? const <String>[]
      : body.substring(colon + 1).split(',').map((project) => project.trim()).where((p) => p.isNotEmpty).toList();
  return ProviderEntry(name, connected: !offline, projects: projects);
}

String _providersText(List<ProviderEntry> providers) =>
    providers.map((entry) => '${entry.connected ? '' : '- '}${entry.name}: ${entry.projects.join(', ')}').join('\n');

class _WindowForm extends StatefulWidget {
  const _WindowForm({required this.window, required this.host});

  final WindowStandIn window;
  final RecordingHost host;

  @override
  State<_WindowForm> createState() => _WindowFormState();
}

class _WindowFormState extends State<_WindowForm> {
  WindowStandIn get window => widget.window;
  late final providers = TextEditingController(text: _providersText(window.providers));
  late final current = TextEditingController(text: window.currentProvider ?? '');
  late final chosen = TextEditingController(text: window.chosenProject ?? '');
  late final agents = TextEditingController(text: window.agents.join(', '));
  late final refusal = TextEditingController(text: window.startRefusal);
  late final unavailable = TextEditingController(text: window.openUnavailable ?? '');
  final placeProject = TextEditingController();
  final placeSource = TextEditingController();
  final placeStory = TextEditingController();
  final placeIssue = TextEditingController();
  final directory = TextEditingController();
  String? sourceProblem;

  @override
  void dispose() {
    for (final controller in [
      providers,
      current,
      chosen,
      agents,
      refusal,
      unavailable,
      placeProject,
      placeSource,
      placeStory,
      placeIssue,
      directory,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  void _apply() {
    window
      ..agents = agents.text.split(',').map((agent) => agent.trim()).where((agent) => agent.isNotEmpty).toList()
      ..startRefusal = refusal.text
      ..openUnavailable = _blank(unavailable.text)
      ..providers = parseProviders(providers.text)
      ..currentProvider = _blank(current.text)
      ..chosenProject = _blank(chosen.text);
  }

  Future<void> _connect() async {
    try {
      final address = await PlannerAddress.read(runtimeDirectory: await mockDirectory(directory.text));
      // The mock serves its own board for any repository, so no real one is ever named.
      final source = HttpPlanningSource.at(
        address,
        (project) => const PlannerProject('https://example.org/porelo/mock.git'),
        label: 'mock planner',
      );
      if (!mounted) {
        source.close();
        return;
      }
      window.source = source;
      setState(() => sourceProblem = null);
    } catch (error) {
      if (mounted) setState(() => sourceProblem = '$error');
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: window,
    builder: (context, _) => ListView(
      padding: const EdgeInsets.all(12),
      children: [
        _heading('Source'),
        SegmentedButton<bool>(
          key: const Key('app-source'),
          segments: const [
            ButtonSegment(value: true, label: Text('In-package fake')),
            ButtonSegment(value: false, label: Text('--mock planner')),
          ],
          selected: {window.onFake},
          onSelectionChanged: (choice) {
            if (choice.single) window.source = window.fake;
            setState(() {});
          },
        ),
        _field('app-mock-directory', 'Runtime directory the --mock planner was started with', directory),
        TextButton(key: const Key('app-mock-connect'), onPressed: _connect, child: const Text('Connect')),
        if (sourceProblem case final problem?) Text(problem, key: const Key('app-source-problem')),
        _heading('Providers'),
        _field('app-providers', 'One a line: name: project, project (- for one not answering)', providers, lines: 3),
        _field('app-current-provider', 'Current provider', current),
        _field('app-chosen-project', 'Project chosen elsewhere', chosen),
        _heading('Agents and the window\'s answers'),
        _field('app-agents', 'Agents offered, comma separated', agents),
        SegmentedButton<StartAnswer>(
          key: const Key('app-start-answer'),
          segments: const [
            ButtonSegment(value: StartAnswer.started, label: Text('Started')),
            ButtonSegment(value: StartAnswer.refused, label: Text('Refused')),
            ButtonSegment(value: StartAnswer.never, label: Text('No answer')),
          ],
          selected: {window.startAnswer},
          onSelectionChanged: (choice) {
            window
              ..startAnswer = choice.single
              ..changed();
          },
        ),
        _field('app-start-refusal', 'Words of a refused Start', refusal),
        _field('app-open-unavailable', 'Why a task cannot be opened (empty: it can)', unavailable),
        FilledButton(key: const Key('app-window-apply'), onPressed: _apply, child: const Text('Apply')),
        _heading('Place'),
        _field('app-place-project', 'Project', placeProject),
        _field('app-place-source', 'Provider', placeSource),
        _field('app-place-story', 'Story', placeStory),
        _field('app-place-issue', 'Issue', placeIssue),
        TextButton(
          key: const Key('app-place-go'),
          onPressed: () => widget.host.setPlace(
            PlanningPlace(
              project: _blank(placeProject.text),
              source: _blank(placeSource.text),
              story: _blank(placeStory.text),
              issue: _blank(placeIssue.text),
            ),
          ),
          child: const Text('Move the screen there'),
        ),
      ],
    ),
  );
}

class _EventsForm extends StatefulWidget {
  const _EventsForm({required this.window, required this.client});

  final WindowStandIn window;
  final PlanningClient client;

  @override
  State<_EventsForm> createState() => _EventsFormState();
}

class _EventsFormState extends State<_EventsForm> {
  WindowStandIn get window => widget.window;
  StartedTask? chosen;
  bool finished = true;
  final machine = TextEditingController(text: 'test-machine');
  final why = TextEditingController(text: 'ended');
  final answer = TextEditingController();
  final unpushed = TextEditingController(text: '0');
  final project = TextEditingController(text: 'demo');
  int revisions = 0;

  @override
  void dispose() {
    for (final controller in [machine, why, answer, unpushed, project]) {
      controller.dispose();
    }
    super.dispose();
  }

  StartedTask? get task => window.started.contains(chosen) ? chosen : window.started.lastOrNull;

  void _fire(PlanningTaskEvent event) {
    final task = this.task;
    if (task == null) return;
    window.fire(
      widget.client,
      task,
      event,
      machine: machine.text,
      ended: event == PlanningTaskEvent.ended
          ? PlanningTaskEnded(
              at: DateTime.now().toUtc().toIso8601String(),
              finished: finished,
              why: why.text,
              answer: answer.text,
              unpushed: int.tryParse(unpushed.text.trim()) ?? 0,
            )
          : null,
    );
  }

  void _newRevision() {
    final fake = window.fake;
    final name = project.text.trim();
    final board = jsonDecode(jsonEncode(fake.board(name))) as Map<String, dynamic>;
    board['revision'] = '${board['revision']}:app-${++revisions}';
    fake.push(name, board);
    window.log.add('fake: new revision', {'project': name, 'revision': board['revision']});
  }

  void _dropWatch() {
    window.fake.fail(const PlanningRefusal('WatchLost', {}, 'The board\'s stream was dropped by hand.'));
    window.log.add('fake: watch dropped', const {});
  }

  void _refuseSaves(bool refuse) {
    window.fake.moveFailure = refuse
        ? const PlanningRefusal('StaleRevision', {}, 'Refused by hand: the board moved on.')
        : null;
    window.log.add('fake: saves refused', {'refused': refuse});
    setState(() {});
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: window,
    builder: (context, _) => ListView(
      padding: const EdgeInsets.all(12),
      children: [
        _heading('A task\'s event, as the window reports it'),
        if (window.started.isEmpty)
          const Text('No task started yet: start one from the board.', key: Key('app-event-none'))
        else
          DropdownButton<StartedTask>(
            key: const Key('app-event-task'),
            isExpanded: true,
            value: task,
            items: [
              for (final each in window.started)
                DropdownMenuItem(value: each, child: Text('${each.story} ${each.started.task}')),
            ],
            onChanged: (value) => setState(() => chosen = value),
          ),
        _field('app-event-machine', 'Machine', machine),
        Wrap(
          spacing: 8,
          children: [
            TextButton(
              key: const Key('app-event-started'),
              onPressed: () => _fire(PlanningTaskEvent.started),
              child: const Text('Started'),
            ),
            TextButton(
              key: const Key('app-event-stopped'),
              onPressed: () => _fire(PlanningTaskEvent.stopped),
              child: const Text('Stopped'),
            ),
          ],
        ),
        CheckboxListTile(
          key: const Key('app-ended-finished'),
          title: const Text('Finished'),
          value: finished,
          onChanged: (value) => setState(() => finished = value ?? false),
        ),
        _field('app-ended-why', 'Why it ended', why),
        _field('app-ended-answer', 'The answer', answer, lines: 3),
        _field('app-ended-unpushed', 'Unpushed commits', unpushed),
        TextButton(
          key: const Key('app-event-ended'),
          onPressed: () => _fire(PlanningTaskEvent.ended),
          child: const Text('Ended'),
        ),
        _heading('The board\'s own events'),
        if (!window.onFake)
          const Text('Only on the in-package fake: a --mock planner changes its board only as a planner does.')
        else ...[
          _field('app-board-project', 'Project', project),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                key: const Key('app-board-revision'),
                onPressed: _newRevision,
                child: const Text('New revision'),
              ),
              TextButton(key: const Key('app-board-drop'), onPressed: _dropWatch, child: const Text('Drop the stream')),
            ],
          ),
          SwitchListTile(
            key: const Key('app-board-refuse-saves'),
            title: const Text('Refuse saves'),
            value: window.fake.moveFailure != null,
            onChanged: _refuseSaves,
          ),
        ],
      ],
    ),
  );
}

class _LogView extends StatelessWidget {
  const _LogView({required this.window});

  final WindowStandIn window;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: window.log,
    builder: (context, _) => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            TextButton(
              key: const Key('app-log-copy'),
              onPressed: () => Clipboard.setData(ClipboardData(text: window.log.text)),
              child: const Text('Copy'),
            ),
            TextButton(key: const Key('app-log-clear'), onPressed: window.log.clear, child: const Text('Clear')),
          ],
        ),
        Expanded(
          child: ListView(
            key: const Key('app-log'),
            padding: const EdgeInsets.all(12),
            children: [for (final call in window.log.calls) SelectableText(call.line)],
          ),
        ),
      ],
    ),
  );
}
