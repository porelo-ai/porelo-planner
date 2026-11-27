import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/task_runs.dart';

import 'support/fake_planning.dart';
import 'support/served_story_work.dart';

/// An offer of [role] at [place], by default the gate push of the author's task, with the members the planner names
/// there: the task an author at its own work runs again, and the account it writes as at a pull request.
Map<String, dynamic> _rework(int number, {Map<String, dynamic>? place, String role = 'author'}) {
  final at =
      place ??
      {
        'gatePush': {'name': 'author-task'},
      };
  final bound = mockRole(role);
  final order = servedFollowUp(number, role: role)..['place'] = at;
  if (bound['workspace'] == 'own') {
    if (at['gatePush'] case final Map<String, dynamic> gate) order['task'] = gate['name'];
    if (at['ownPullRequest'] == true) order['task'] = 'author-task';
  }
  if (at.containsKey('pullRequest') || at['ownPullRequest'] == true) order['writeAs'] = bound['writeAs'];
  return order;
}

/// The building work of an issue its run's start held back, of [role]: at no place, under a task name of the
/// window's own.
Map<String, dynamic> _heldBack(int number, {String role = 'author'}) =>
    servedFollowUp(number, role: role)..['ownTask'] = true;

PlanWork _work(List<Map<String, dynamic>> starts) => PlanWork.from(servedWork()..['starts'] = starts);

void main() {
  test('the work reads only work sent back to a gate push as a start, each offer once', () {
    // Harm: the window starts an order's own offer, a task with no place to resume, or one offer twice.
    final work = _work([
      _rework(1),
      _rework(1),
      _rework(3, place: {'pullRequest': 'https://github.com/acme/shop/pull/3'}),
      _rework(
        4,
        place: {
          'gatePush': {'name': 'bad name'},
        },
      ),
      servedFollowUp(5),
      _rework(6, role: 'analyst')..['result'] = 'analysis.json',
      _rework(10)..['result'] = 'analysis.json',
      _rework(11)..['issues'] = ['PR1', 'PR1.1'],
      _rework(12)..['repository'] = 'elsewhere',
      _rework(13)..['issues'] = ['PR9'],
    ]);
    // Harm too: a reading at no place, a stage handing back a result, or work beyond one issue the run works in its
    // own repository is started without the person.
    expect(work.starts.map((order) => order.offer), [servedOffer(1)]);
    expect(work.starts.single.role?.name, 'author');
    expect(work.starts.single.place?.gatePush, 'author-task');
    expect(work.starts.single.task, 'author-task', reason: 'the task waiting there is run again');
    expect(work.starts.single.place?.toJson(), {
      'gatePush': {'name': 'author-task'},
    });
    expect(PlanWork.from(servedWork()).starts, isEmpty, reason: 'a planner without starts offers none');
  });

  test("the work reads work sent back to its task's own pull request as a start, only work that pushes", () {
    // Harm: the author's rework at its own pull request is never started, so the issue stays in Rework with its offer
    // waiting; or a reading, which never reads its own task's work, or a stage handing back a result starts there
    // without the person.
    final own = {'ownPullRequest': true};
    final work = _work([
      _rework(30, place: own),
      _rework(31, place: own, role: 'tester'),
      _rework(32, place: own, role: 'analyst')..['result'] = 'analysis.json',
      _rework(33, place: own, role: 'screener'),
    ]);
    expect(work.starts.map((order) => order.offer), [servedOffer(30)]);
    expect(work.starts.single.role?.pushesWork, isTrue);
    expect(work.starts.single.place?.ownPullRequest, isTrue);
    expect(work.starts.single.place?.toJson(), own);
  });

  test("the work reads a task that reads the work at another task's place as a start, whatever its role's name", () {
    // Harm: a reading the planner offered is never started, so the work waiting at its place is never read; or a
    // task that would build at another's pull request, or a reading without a place, starts without the person.
    final work = _work([
      _rework(20, role: 'tester'),
      _rework(21, role: 'screener', place: {'pullRequest': 'https://github.com/acme/shop/pull/12'}),
      _rework(22, place: {'pullRequest': 'https://github.com/acme/shop/pull/12'}),
      servedFollowUp(23, role: 'tester'),
    ]);
    expect(work.starts.map((order) => order.offer), [servedOffer(20), servedOffer(21)]);
    expect(work.starts.map((order) => order.role?.name), ['tester', 'screener']);
    expect(work.starts.last.place?.pullRequest, 'https://github.com/acme/shop/pull/12');
  });

  test('the work reads the building of an issue its run held back as a start, at no place, only work that pushes', () {
    // Harm: an issue freed of its blocker is never started, so the story stalls; or a reading, a stage handing back a
    // result, or work beyond one issue the run lists starts at no place without the person.
    final work = _work([
      _heldBack(40),
      _heldBack(41, role: 'tester'),
      _heldBack(42, role: 'reviewer'),
      servedFollowUp(44, role: 'analyst')..['result'] = 'analysis.json',
      _heldBack(45)..['issues'] = ['PR9'],
      _heldBack(46)..['issues'] = ['PR1', 'PR1.1'],
    ]);
    expect(work.starts.map((order) => order.offer), [servedOffer(40)]);
    expect(work.starts.single.role?.name, 'author');
    expect(work.starts.single.place, isNull);
  });

  test('the work starts none of the offers when more than one would open the terminal', () {
    // Harm: two tasks open the window's terminal, so the person works with one and the other runs unseen, or the
    // window picks one the planner did not mean.
    Map<String, dynamic> opening(int number) => _rework(number)..['opensTerminal'] = true;
    expect(_work([opening(50), _rework(51)]).starts.map((order) => order.offer), [servedOffer(50), servedOffer(51)]);
    expect(_work([opening(50), opening(51)]).starts, isEmpty);
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
