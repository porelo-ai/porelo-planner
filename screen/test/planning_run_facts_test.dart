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

  // Harm: a place the planner refuses is started, or one it takes is refused, so the review never runs there.
  test('a pull request address is taken only as the planner takes it', () {
    expect(
      PlanningRunPlace.fromJson({'pullRequest': 'https://github.com/owner/shop/pull/7'}).pullRequest,
      'https://github.com/owner/shop/pull/7',
    );
    for (final address in [
      'https://u@h/x',
      'https://@h/x',
      'https://@/x',
      'https:///x',
      'https://h/x?',
      'https://h/x#',
      'https://h/x?a=1',
      'https://h/x#top',
    ]) {
      expect(() => PlanningRunPlace.fromJson({'pullRequest': address}), throwsFormatException, reason: address);
    }
  });

  // Harm: an address Java's URI reads no host from, or refuses outright, is started, and the planner refuses every
  // report of the run; or one it takes is refused here, so the review never runs there. Each verdict was read from
  // `java.net.URI` as the planner checks a place.
  test('a pull request address follows the host, port and path grammar of the planner\'s URI', () {
    String taken(String address) => PlanningRunPlace.fromJson({'pullRequest': address}).pullRequest!;
    final exact = 'https://example.com/${'a' * (2048 - 'https://example.com/'.length)}';
    for (final address in [
      'https://example.com',
      'https://example.com:8443/o/r/pull/1',
      'https://example.com:/x',
      'https://example.com:0000000000000000080/x',
      'https://example.com:2147483647/x',
      'https://git-hub.example.com/x',
      'https://192.168.1.20:3000/x',
      'https://0001.2.3.4/x',
      'https://999/x',
      'https://1.2.3.a/x',
      'https://example.com1/x',
      'https://a./x',
      'https://example.com./x',
      'https://EXAMPLE.com/x',
      "https://example.com/a;b,c:d@e&f=g+h\$i!j~k*l'm(n)o_p.q-r",
      'https://example.com/a%2Fb%e4',
      'https://example.com//x',
      exact,
    ]) {
      expect(taken(address), address, reason: address);
    }
    for (final address in [
      'https://exa_mple.com/x',
      'https://a..b/x',
      'https://.a/x',
      'https://-a.com/x',
      'https://a-.com/x',
      'https://a.-b.com/x',
      'https://example.com.1/x',
      'https://example.1com/x',
      'https://1.2.3/x',
      'https://1.2.3.4./x',
      'https://1.2.3.256/x',
      'https://1.2.3.4.5/x',
      'https://example.com:x/x',
      'https://example.com:2147483648/x',
      'https://ex%41mple.com/x',
      for (final c in ['"', '<', '>', r'\', '^', '`', '{', '|', '}', '[', ']']) 'https://example.com/a${c}b',
      'https://example.com/a%zz',
      'https://example.com/a%2',
      'https://example.com/%',
      '${exact}a',
    ]) {
      expect(() => taken(address), throwsFormatException, reason: address);
    }
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
