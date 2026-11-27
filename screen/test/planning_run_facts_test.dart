import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

PlanWorkTask _task([Map<String, dynamic> extra = const {}]) =>
    PlanWorkTask.from({...servedRunTask(task: 'run-1'), 'held': 0, ...extra});

void main() {
  // Harm: a task's role is shown as another one than it was started with, or one the screen cannot read is taken as
  // some role at all, a builder by default.
  test('the role is the one served, with what it reads besides, and a task whose role cannot be read refuses', () {
    final reviewer = _task({'role': mockRole('reviewer'), 'readLanded': <String>[]});
    expect(reviewer.role.name, 'reviewer');
    expect(planningRunFacts(reviewer), startsWith('role reviewer · gives points · in another task\'s work'));
    expect(planningRunFacts(reviewer), isNot(contains('also reads')));
    expect(planningRunFacts(_task()), contains('also reads the work of builder-task'));
    for (final role in <Object?>[
      'tester',
      null,
      {'name': 'x'},
    ]) {
      expect(() => _task({'role': role}), throwsFormatException, reason: '$role');
    }
    expect(
      () => PlanWork.from(servedWork()..['tasks'] = [servedRunTask()..remove('role')]),
      throwsFormatException,
      reason: 'the whole work is refused, not read with a builder in its place',
    );
  });

  // Harm: an offer's role is shown without what it may do, so the person starts a task whose rights they never saw.
  test('an offered role says how it ends, its workspace, rights, account and services', () {
    expect(
      planningRoleText(PlanningTaskRole.fromJson(mockRole('author'))),
      "role author · pushes work · in its own work · rights: push, reply · writes as the author · "
      "with the project's services",
    );
    expect(
      planningRoleText(PlanningTaskRole.fromJson({...mockRole('tester'), 'readLanded': true})),
      contains('also reads the work of the tasks whose build it reads'),
      reason: 'an offer names no tasks yet, only that its role reads them',
    );
    expect(
      planningRoleText(PlanningTaskRole.fromJson({...mockRole('reviewer'), 'rights': <String>[]})),
      contains('holds no right'),
    );
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

  // Harm: a refusal's words from outside the screen turn the text around them, hide a part, or run on without end, so
  // the person reads another refusal than Porelo's; or a part served empty reads as a word.
  test("a start refusal's name, parameters and words are shown cleaned and bounded, an empty part as not named", () {
    String words(String name, {Map<String, String> parameters = const {}, String detail = ''}) =>
        planningStartRefusalWords(PlanningStartRefusal(name: name, parameters: parameters, detail: detail));
    expect(
      words('StartRuleBroken', parameters: {'rule': 'rejectWithPush', 'detail': 'rights'}, detail: 'Ask the owner.'),
      'Porelo refused the start: it breaks the rule rejectWithPush: rights. Ask the owner.',
    );
    for (final name in ['Start\u202ERuleBroken', 'Bad name', 'é', 'x' * 65, '']) {
      expect(words(name), 'Porelo refused the start: a refusal the screen cannot name', reason: name);
    }
    expect(
      words('StartRuleBroken', parameters: {'rule': 'pu\u202Esh\u200B\u0000', 'detail': '\u2066\u200B'}),
      'Porelo refused the start: it breaks the rule push: not named',
      reason: 'a value empty once cleaned is not named',
    );
    expect(
      words('StartRuleBroken', parameters: {'rule': '', 'detail': 'd' * 500}),
      'Porelo refused the start: it breaks the rule not named: ${'d' * planningMostPlaceText}…',
    );
    expect(words('Other', detail: '\u202Ea\nb'), 'Porelo refused the start: Other. a\nb', reason: 'lines stay');
    expect(words('Other', detail: 'w' * 1200), 'Porelo refused the start: Other. ${'w' * 1000}…');
    expect(words('Other', detail: '\u200B\u2066 '), 'Porelo refused the start: Other', reason: 'no words, none said');
  });

  // Harm: a task named as one read besides is shown as text the planner never names a task with, so a hostile name
  // reads as a real task.
  test('a task read besides is named only by a task label', () {
    final task = _task({
      'readLanded': ['builder-task', 'a\u202Eb', '../x', 'builder-task'],
    });
    expect(planningRunFacts(task), contains('also reads the work of builder-task, a task the screen cannot name'));
    expect(planningRunFacts(task), isNot(contains('\u202E')));
  });

  // Harm: a helper's kind from outside the screen turns or hides the line around it, or runs on without end.
  test("a helper's kind is shown cleaned and bounded, and an empty one as not reported", () {
    expect(planningKindText(planningHelper), planningHelper);
    expect(planningKindText('HEL\u202EPER\u200B\uFEFF'), 'HELPER');
    expect(planningKindText('K' * 80), '${'K' * planningMostKindText}…');
    expect(planningKindText('\u2066\u200B'), 'not reported');
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
