import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/task_runs.dart';

import 'support/fake_planning.dart';
import 'support/served_story_work.dart';

Map<String, dynamic> _rework(int number, {String? runRole = 'AUTHOR', Object? place, String role = 'BUILDER'}) =>
    servedFollowUp(number, role: role)..addAll({
      'runRole': ?runRole,
      'place':
          place ??
          {
            'gatePush': {'name': 'task'},
          },
    });

PlanWork _work(List<Map<String, dynamic>> starts) => PlanWork.from(servedWork()..['starts'] = starts);

void main() {
  test('the work reads only an author sent back to a gate push as a start, each offer once', () {
    // Harm: the window starts an order's own offer, a task with no place to resume, or one offer twice.
    final work = _work([
      _rework(1),
      _rework(1),
      _rework(2, runRole: null),
      _rework(3, place: {'ownPullRequest': true}),
      _rework(
        4,
        place: {
          'gatePush': {'name': 'bad name'},
        },
      ),
      servedFollowUp(5),
      _rework(6, role: 'MAIN'),
      _rework(9, role: 'READER'),
      _rework(10)..['result'] = 'analysis.json',
      _rework(11)..['issues'] = ['PR1', 'PR1.1'],
      _rework(12)..['repository'] = 'elsewhere',
      _rework(13)..['issues'] = ['PR9'],
    ]);
    // Harm too: a main, a reader, a stage handing back a result, or work beyond one issue the run works in its own
    // repository is started without the person.
    expect(work.starts.map((order) => order.offer), [servedOffer(1)]);
    expect(work.starts.single.runRole, 'AUTHOR');
    expect(work.starts.single.place?.gatePush, 'task');
    expect(work.starts.single.place?.toJson(), {
      'gatePush': {'name': 'task'},
    });
    expect(PlanWork.from(servedWork()).starts, isEmpty, reason: 'a planner without starts offers none');
  });

  test('the work reads a reviewer of the work at its place as a start, never one with another duty', () {
    // Harm: a reviewer the planner offered is never started, so the work waiting at its place is never reviewed; or a
    // reviewer that would build, or one without a place, starts without the person.
    final work = _work([
      _rework(20, runRole: 'REVIEWER', role: 'READER'),
      _rework(21, runRole: 'REVIEWER', role: 'READER', place: {'pullRequest': 'https://github.com/acme/shop/pull/12'}),
      _rework(22, runRole: 'REVIEWER'),
      _rework(23, runRole: 'REVIEWER', role: 'READER')..remove('place'),
    ]);
    expect(work.starts.map((order) => order.offer), [servedOffer(20), servedOffer(21)]);
    expect(work.starts.map((order) => order.runRole), ['REVIEWER', 'REVIEWER']);
    expect(work.starts.last.place?.pullRequest, 'https://github.com/acme/shop/pull/12');
  });

  test('a start the planner offered is started once through its own hook, and only through it', () async {
    // Harm: the window's pressed start opens for an offer nobody pressed; a rework never starts; it starts twice; or
    // a machine not ready yet stops it for good.
    final source = FakePlanningSource(smallBoard());
    final run = PlanningRun.on(source, 'project', 'ST-offered');
    final work = _work([_rework(7)]);

    run.observe(work);
    await pumpEventQueue();

    final asked = <String?>[];
    final refusals = <Object>[const PlanningRefusal('NoMachine'), const PlanningRefusal('NotWatchedYet')];
    run.startOffered = (source, project, story, order) async {
      asked.add(order.offer);
      if (refusals.isNotEmpty) throw refusals.removeAt(0);
      return const PlanningTaskStarted(task: 'task', container: 'box');
    };
    run.observe(work);
    await pumpEventQueue();
    run.observe(work);
    await pumpEventQueue();
    run.observe(work);
    await pumpEventQueue();
    run.observe(work);
    await pumpEventQueue();
    expect(asked, [servedOffer(7), servedOffer(7), servedOffer(7)], reason: 'tried again quietly, then started once');

    final consumed = PlanningRun.on(source, 'project', 'ST-consumed');
    final tries = <String?>[];
    consumed.startOffered = (source, project, story, order) async {
      tries.add(order.offer);
      throw const PlanningRefusal('OfferConsumed');
    };
    final other = _work([_rework(8)]);
    consumed.observe(other);
    await pumpEventQueue();
    consumed.observe(other);
    await pumpEventQueue();
    expect(tries, [servedOffer(8)], reason: 'a consumed offer runs and is not started again');
  });

  test('a start the planner offered leaves the hook a reply\'s follow-ups start through as it was', () async {
    // Harm: once one offered start ran, every later follow-up of a task's reply starts through the hook for offered
    // starts, so the window's own Start is bypassed.
    final source = FakePlanningSource(smallBoard());
    final run = PlanningRun.on(source, 'project', 'ST-hooks');
    final pressed = <String?>[];
    final offered = <String?>[];
    run.startTask = (source, project, story, order) async {
      pressed.add(order.offer);
      return const PlanningTaskStarted(task: 'follow', container: 'box-2');
    };
    run.startOffered = (source, project, story, order) async {
      offered.add(order.offer);
      return const PlanningTaskStarted(task: 'task', container: 'box');
    };
    run.observe(_work([_rework(20)]));
    await pumpEventQueue();
    await run.follow([PlanningTaskOrder.from(servedFollowUp(21))], run.epoch);
    expect(offered, [servedOffer(20)]);
    expect(pressed, [servedOffer(21)], reason: 'the follow-up went through the window\'s Start');
  });

  test('a stopped run starts nothing the planner offered', () async {
    // Harm: a person's Stop is followed by the start of a rework the planner offered before it.
    final source = FakePlanningSource(smallBoard());
    final run = PlanningRun.on(source, 'project', 'ST-stopped');
    final asked = <String?>[];
    run.startOffered = (source, project, story, order) async {
      asked.add(order.offer);
      return const PlanningTaskStarted(task: 'task', container: 'box');
    };
    final stopped = PlanWork.from(
      servedWork()
        ..['starts'] = [_rework(22)]
        ..['issues'] = [
          {
            'id': 'PR1',
            'reason': 'STOPPED',
            'rounds': 0,
            'roundsMost': 2,
            'atWork': false,
            'needsYou': true,
            'readingStopped': false,
            'findings': <Object>[],
          },
        ],
    );
    expect(stopped.issues.single.reason, 'STOPPED');
    run.observe(stopped);
    await pumpEventQueue();
    expect(asked, isEmpty);
  });

  test('a started offer stays reserved when telling the planner of its start is refused for a machine', () async {
    // Harm: a started rework is handed to Start a second time on the next read, since its confirmation failed with a
    // refusal the start itself is retried on.
    final source = FakePlanningSource(smallBoard());
    final run = PlanningRun.on(source, 'project', 'ST-confirmed');
    final asked = <String?>[];
    run.startOffered = (source, project, story, order) async {
      asked.add(order.offer);
      return const PlanningTaskStarted(task: 'task', container: 'box');
    };
    run.onStarted = (source, project, story, tasks) async => throw const PlanningRefusal('NoMachine');
    final work = _work([_rework(23)]);
    run.observe(work);
    await pumpEventQueue();
    run.observe(work);
    await pumpEventQueue();
    expect(asked, [servedOffer(23)], reason: 'started once');
  });
}
