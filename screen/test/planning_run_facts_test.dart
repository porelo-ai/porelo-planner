import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

PlanWorkTask _task([Map<String, dynamic> extra = const {}]) =>
    PlanWorkTask.from({...servedRunTask(task: 'run-1'), 'held': 0, ...extra});

void main() {
  // Harm: a reviewer's run is shown as an author's, or an absent role is shown as something else than Start's default.
  test('the run role is the one served, and an author where none is served', () {
    expect(planningRunFacts(_task({'runRole': 'REVIEWER'})), contains('run role reviewer'));
    expect(planningRunFacts(_task({'runRole': 'AUTHOR'})), contains('run role author'));
    expect(planningRunFacts(_task()), contains('run role author'));
    expect(_task({'runRole': 'REVIEWER'}).runRole, 'REVIEWER');
    expect(_task().runRole, isNull);
  });

  // Harm: the place is shown as another one, or the task's default gate push is left unsaid.
  test('each place served is said in its own words and none served is the own gate push', () {
    PlanningRunPlace place(Map<String, dynamic> map) => PlanningRunPlace.fromJson(map);
    expect(planningPlaceText(null), "the task's own gate push");
    expect(
      planningPlaceText(
        place({
          'gatePush': {'name': 'cart', 'repository': 'docs'},
        }),
      ),
      'gate push of task cart in docs',
    );
    expect(
      planningPlaceText(
        place({
          'gatePush': {'name': 'cart'},
        }),
      ),
      'gate push of task cart',
    );
    expect(
      planningPlaceText(place({'pullRequest': 'https://github.com/owner/shop/pull/7'})),
      'pull request https://github.com/owner/shop/pull/7',
    );
    expect(planningPlaceText(place({'ownPullRequest': true})), "the task's own pull request");
    expect(
      planningFactsOf({
        'place': {'ownPullRequest': true},
      }),
      contains("at the task's own pull request"),
    );
  });

  // Harm: an address from outside, however long, fills the card or carries characters that reorder its text.
  test('a pull request address is cut to a bounded length', () {
    final long = 'https://github.com/owner/shop/pull/${'1' * 1500}';
    final text = planningPlaceText(PlanningRunPlace.fromJson({'pullRequest': long}));
    expect(text.length, lessThanOrEqualTo('pull request '.length + planningMostPlaceText + 1));
    expect(text, endsWith('…'));
  });

  // Harm: a held count judged or altered by the screen, or one that does not read, breaks the whole run's answer.
  test('the held count is shown as served, 0 included, and as unknown where none was given', () {
    expect(planningRunFacts(PlanWorkTask.from(servedRunTask())), endsWith('held unknown'));
    expect(planningRunFacts(_task({'held': 3})), endsWith('held 3'));
    expect(planningRunFacts(_task({'held': 0})), endsWith('held 0'), reason: 'a count read, none held');
    expect(_task({'held': -1}).held, isNull);
    expect(_task({'held': '2'}).held, isNull);
    expect(PlanWorkTask.from(servedRunTask()).held, isNull, reason: 'a planner that served none');
  });

  // Harm: a place the screen refuses fails the whole work answer, or is shown as the task's own gate push, so the
  // person reads a place the run does not work at.
  test('a place that cannot be read leaves the task read and is said unreadable', () {
    final task = _task({
      'place': {'pullRequest': 'ftp://example.org/x'},
    });
    expect(task.place, isNull);
    expect(task.placeUnreadable, isTrue);
    expect(task.task, 'run-1');
    expect(planningRunFacts(task), contains('a place the screen cannot read'));
    expect(planningRunFacts(task), isNot(contains('own gate push')));
    expect(_task().placeUnreadable, isFalse);
  });
}

String planningFactsOf(Map<String, dynamic> extra) => planningRunFacts(_task(extra));
