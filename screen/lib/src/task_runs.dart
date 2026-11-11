import 'client.dart';
import 'hooks.dart';
import 'models.dart';
import 'story_work_order.dart';

/// Implements the client's report routing using the offers actually handed to the window's Start.
///
/// Window clients can mix this in: reports and follow-ups keep their originating source, project and story even
/// when the selected board changes. A machine is bound verbatim by the first STARTED report.
mixin PlanningTaskReports implements PlanningClient {
  final Set<PlanningSource> _reportSources = Set.identity();

  /// Retains providers with active offers when the window's current selection changes.
  void retainPlanningSources() => _reportSources.addAll(providers.map((provider) => provider.source));
  @override
  Future<void> taskEvent(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
  }) {
    retainPlanningSources();
    return PlanningRun.report(
      project,
      task,
      event,
      offer: offer,
      container: container,
      machine: machine,
      ended: ended,
      sources: _reportSources,
    );
  }
}

class _Offer {
  _Offer(this.run);
  final PlanningRun run;
  PlanningTaskStarted? started;
  String? machine;
  Object? failure;
}

/// Shared start cancellation and offer ownership for one source's story run.
class PlanningRun {
  PlanningRun._(this.source, this.project, this.story);

  static final _runs = Expando<Map<(String, String), PlanningRun>>();
  static final _offers = Expando<Map<String, _Offer>>();

  /// Returns the same cancellation state to forms, reports and stops for this origin.
  static PlanningRun on(PlanningSource source, String project, String story) =>
      (_runs[source] ??= {}).putIfAbsent((project, story), () => PlanningRun._(source, project, story));

  final PlanningSource source;
  final String project, story;
  int epoch = 0;
  PlanningStartTask? startTask;
  PlanningTaskChanges? changes;
  Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)? onStarted;

  /// Invalidates pending batches before a stop goes over HTTP.
  void cancel() => epoch++;

  /// A recorded stop cannot leave an earlier batch authorized to start.
  void observe(PlanWork? work) {
    if (work?.issues.any((issue) => issue.reason == 'STOPPED') == true) cancel();
  }

  /// Reserves an offer permanently before Start; failure never permits replaying it.
  Future<PlanningTaskStarted> start(PlanningTaskOrder order, PlanningStartTask startTask, int expected) async {
    if (epoch != expected) throw const PlanningRefusal('Cancelled');
    final offer = order.offer;
    final offers = _offers[source] ??= {};
    if (offer == null || offers.containsKey(offer)) {
      throw const PlanningRefusal('OfferConsumed', {}, 'This offer has already been handed to Start.');
    }
    this.startTask = startTask;
    final binding = _Offer(this);
    offers[offer] = binding;
    try {
      final started = await startTask(source, project, story, order);
      binding.started = started;
      return started;
    } catch (error) {
      binding.failure = error;
      rethrow;
    }
  }

  /// Tracks partial starts, sending late starts directly to Stop without authorizing reports.
  Future<void> confirmed(
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
    int expected, {
    Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)? onStarted,
  }) async {
    if (tasks.isEmpty) return;
    if (epoch != expected) {
      await changes?.stop(source, project, story, [for (final task in tasks) task.$2]);
      return;
    }
    try {
      if (onStarted != null) {
        await onStarted(source, project, story, tasks);
      } else {
        await changes?.started(source, project, story, tasks);
      }
    } finally {
      if (epoch != expected) await changes?.stop(source, project, story, [for (final task in tasks) task.$2]);
    }
  }

  /// Starts a fully checked batch once, retaining every confirmed partial start on failure.
  Future<void> follow(List<PlanningTaskOrder> tasks, int expected) async {
    final confirmedTasks = <(PlanningTaskOrder, PlanningTaskStarted)>[];
    try {
      for (final task in tasks) {
        if (epoch != expected) return;
        // A replay is safe: its consumed offers are never handed to Start again, even across stories.
        if (_offers[source]?[task.offer] case final prior?) {
          if (!identical(prior.run, this)) {
            throw const PlanningRefusal('TaskEventConflict', {}, 'The offer belongs to another project or story.');
          }
          if (prior.failure case final error?) throw error;
          continue;
        }
        final hook = startTask;
        if (hook == null) throw const PlanningRefusal('StartUnavailable', {}, 'The window has no Start hook.');
        final started = await start(task, hook, expected);
        confirmedTasks.add((task, started));
      }
    } finally {
      await confirmed(confirmedTasks, expected, onStarted: onStarted);
    }
  }

  /// Reports only the offer's confirmed runtime identity, never the current provider's selection.
  static Future<void> report(
    String project,
    String task,
    PlanningTaskEvent event, {
    required String offer,
    required String container,
    required String machine,
    PlanningTaskEnded? ended,
    required Set<PlanningSource> sources,
  }) async {
    // Do not permanently bind an offer to a machine name the planner must refuse.
    final points = machine.runes.toList();
    if (points.isEmpty ||
        points.length > 128 ||
        points.any((point) => point >= 0xd800 && point <= 0xdfff) ||
        RegExp(r'[\x00-\x1f\x7f-\x9f\u2028\u2029]').hasMatch(machine)) {
      throw const PlanningRefusal('InvalidSettings', {}, 'The machine name is invalid.');
    }
    final candidates = <_Offer>[for (final source in sources) ?_offers[source]?[offer]];
    final matching = candidates
        .where(
          (binding) =>
              binding.run.project == project &&
              binding.started?.task == task &&
              binding.started?.container == container &&
              (binding.machine == null || binding.machine == machine),
        )
        .toList();
    if (matching.length != 1 || matching.single.machine == null && event != PlanningTaskEvent.started) {
      throw const PlanningRefusal('TaskEventConflict', {}, 'The offer does not identify this started task.');
    }
    final binding = matching.single;
    // Reserve the machine before yielding, so competing reports cannot change the first binding.
    var boundHere = binding.machine == null;
    binding.machine ??= machine;
    final run = binding.run;
    final expected = run.epoch;
    try {
      final answer = await run.source.taskEvent(
        project,
        task,
        event,
        offer: offer,
        container: container,
        machine: machine,
        ended: ended,
      );
      boundHere = false;
      run.observe(answer.work);
      await run.follow(answer.tasks, expected);
    } catch (error) {
      if (boundHere) binding.machine = null;
      if (planningRefused(error, 'SaveRefused')) run.cancel();
      rethrow;
    }
  }
}
