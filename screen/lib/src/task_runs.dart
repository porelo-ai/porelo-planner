import 'dart:async';

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
    List<PlanningLanding> landed = const [],
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
      landed: landed,
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

  /// The window's start of the offers the planner made without its asking, the work's `starts`; none starts them
  /// while it is null.
  PlanningStartTask? startOffered;
  PlanningTaskChanges? changes;
  Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)? onStarted;

  /// Invalidates pending batches before a stop goes over HTTP.
  void cancel() => epoch++;

  /// Offers handed to Start whose start the planner has not been told of yet; the planner cannot warn of them.
  final Set<String> _unreported = {};

  /// Whether a start of this run is in flight that the planner does not report yet.
  bool get starting => _unreported.isNotEmpty;

  /// A recorded stop cannot leave an earlier batch authorized to start; the offers the planner made without the
  /// window's asking are started through [startOffered].
  void observe(PlanWork? work) {
    if (work?.issues.any((issue) => issue.reason == 'STOPPED') == true) {
      // A stopped run starts nothing more, not even what the planner offered before the stop.
      cancel();
      return;
    }
    final starts = work?.starts ?? const <PlanningTaskOrder>[];
    if (starts.isNotEmpty && startOffered != null) unawaited(startOfferedOnce(starts));
  }

  /// The refusals of a start that the next read tries again, quietly: the machine or its watch is not ready yet.
  static const retriedStarts = {'NoMachine', 'NotWatchedYet'};

  /// Starts each offer the planner made without the window's asking once: one already handed to Start, or refused as
  /// consumed, runs; one refused for a machine not ready is tried again on the next read, saying nothing; any other
  /// refusal stands and is not tried again.
  Future<void> startOfferedOnce(List<PlanningTaskOrder> starts) async {
    final hook = startOffered;
    if (hook == null) return;
    final expected = epoch;
    for (final order in starts) {
      final offer = order.offer;
      if (offer == null || (_offers[source]?.containsKey(offer) ?? false)) continue;
      if (epoch != expected) return;
      final PlanningTaskStarted started;
      try {
        started = await start(order, hook, expected, remember: false);
      } catch (error) {
        if (retriedStarts.any((name) => planningRefused(error, name))) {
          // Not reserved: the next read's starts ask again.
          _offers[source]?.remove(offer);
        }
        continue;
      }
      try {
        await confirmed([(order, started)], expected, onStarted: onStarted);
      } catch (_) {
        // The task started and stays reserved; the planner's next read says how it stands.
      }
    }
  }

  /// Reserves an offer permanently before Start; failure never permits replaying it. [asked] is told right before
  /// the window's Start is asked. The hook given becomes the one a reply's follow-ups start through unless
  /// [remember] is false, as for the offers the planner made without the window's asking.
  Future<PlanningTaskStarted> start(
    PlanningTaskOrder order,
    PlanningStartTask startTask,
    int expected, {
    void Function()? asked,
    bool remember = true,
  }) async {
    if (epoch != expected) throw const PlanningRefusal('Cancelled');
    final offer = order.offer;
    final offers = _offers[source] ??= {};
    if (offer == null || offers.containsKey(offer)) {
      throw const PlanningRefusal('OfferConsumed', {}, 'This offer has already been handed to Start.');
    }
    if (remember) this.startTask = startTask;
    final binding = _Offer(this);
    offers[offer] = binding;
    _unreported.add(offer);
    try {
      asked?.call();
      final started = await startTask(source, project, story, order);
      binding.started = started;
      return started;
    } catch (error) {
      binding.failure = error;
      _unreported.remove(offer);
      rethrow;
    }
  }

  /// Starts a taken order's tasks in order, then ends the board's start with [ended] exactly once and confirms the
  /// tasks that started; owned by this run alone, so only its epoch (a Stop) cuts it, never a closed form.
  ///
  /// The answer is true once the first task's start is asked of the window, false where nothing was asked; the rest
  /// goes on in [done]. [onTask] is told each task's start or failure; [onProblem] a failure of a start or of the
  /// confirmation, which no form may be left to show.
  ({Future<bool> asked, Future<void> done}) startOrder(
    List<PlanningTaskOrder> tasks,
    PlanningStartTask startTask,
    int expected, {
    required void Function({required bool accepted}) ended,
    void Function(int index, PlanningTaskStarted? started, Object? error)? onTask,
    void Function(Object error)? onProblem,
    Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)? onStarted,
  }) {
    final asked = Completer<bool>();
    void ask() {
      if (!asked.isCompleted) asked.complete(true);
    }

    Future<void> run() async {
      final started = <(PlanningTaskOrder, PlanningTaskStarted)>[];
      try {
        for (final (index, task) in tasks.indexed) {
          if (epoch != expected) return;
          try {
            final answer = await start(task, startTask, expected, asked: ask);
            started.add((task, answer));
            onTask?.call(index, answer, null);
          } catch (error) {
            onTask?.call(index, null, error);
            // Before anything was asked the form is still open and says it; a Stop's cut is no fault.
            if (asked.isCompleted && !planningRefused(error, 'Cancelled')) onProblem?.call(error);
            return;
          }
        }
      } finally {
        if (!asked.isCompleted) asked.complete(false);
        ended(accepted: started.isNotEmpty);
        try {
          await confirmed(started, expected, onStarted: onStarted);
        } catch (error) {
          onProblem?.call(error);
        }
      }
    }

    return (asked: asked.future, done: run());
  }

  /// Tracks partial starts, sending late starts directly to Stop without authorizing reports.
  Future<void> confirmed(
    List<(PlanningTaskOrder, PlanningTaskStarted)> tasks,
    int expected, {
    Future<void> Function(PlanningSource, String, String, List<(PlanningTaskOrder, PlanningTaskStarted)>)? onStarted,
  }) async {
    if (tasks.isEmpty) return;
    if (epoch != expected) {
      _unreported.removeAll([for (final task in tasks) ?task.$1.offer]);
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
    List<PlanningLanding> landed = const [],
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
        landed: landed,
      );
      boundHere = false;
      if (event == PlanningTaskEvent.started) run._unreported.remove(offer);
      run.observe(answer.work);
      await run.follow(answer.tasks, expected);
    } catch (error) {
      if (boundHere) binding.machine = null;
      if (planningRefused(error, 'SaveRefused')) run.cancel();
      rethrow;
    }
  }
}
