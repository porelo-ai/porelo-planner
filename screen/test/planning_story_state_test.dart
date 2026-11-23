import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/src/fake/story_state.dart';

/// Porelo's own workflow as the planner serves it, its order and its measured states.
final _porelo = [
  for (final (name, kind, measured) in [
    ('Idea', 'WAITING', false),
    ('Accepted', 'WAITING', false),
    ('In analysis', 'WORKING', false),
    ('Analyzed', 'WAITING', false),
    ('Under estimation', 'WORKING', false),
    ('Estimated', 'WAITING', false),
    ('Backlog', 'WAITING', false),
    ('Implementing', 'WORKING', true),
    ('Reading', 'WORKING', true),
    ('Reworking', 'WORKING', true),
    ('Review', 'WAITING', true),
    ('Rework', 'WAITING', false),
    ('Finishing', 'WORKING', true),
    ('Finished', 'CLOSED', true),
    ('Rejected', 'CLOSED', false),
  ])
    {'name': name, 'kind': kind, 'measured': measured},
];

final _rule = PlanningStoryRule.of(_porelo)!;

/// The window's mock board: Reworking comes after Review and Rework.
final _mock = [
  for (final (name, kind, measured) in [
    ('New', 'WAITING', false),
    ('Backlog', 'WAITING', false),
    ('Implementing', 'WORKING', true),
    ('Reading', 'WORKING', true),
    ('Review', 'WAITING', true),
    ('Rework', 'WAITING', false),
    ('Reworking', 'WORKING', true),
    ('Finishing', 'WORKING', true),
    ('Finished', 'CLOSED', true),
    ('Rejected', 'CLOSED', false),
  ])
    {'name': name, 'kind': kind, 'measured': measured},
];

final _mockRule = PlanningStoryRule.of(_mock)!;

List<String> _times(int count, String state) => List.filled(count, state);

/// The same cases as the planner's own tests of the rule, so the stand-in derives as the planner does.
void main() {
  // Harm: stories wait for review in a state the person does not review in.
  test('the rule finds Review as the last measured state before an unmeasured one', () {
    expect(_rule.review, 'Review');
  });

  // Harm: one issue left in Idea holds back a story whose work runs, so the board shows it waiting for an idea.
  test('an issue being built makes its story built however many still wait', () {
    expect(_rule.state([..._times(12, 'Implementing'), ..._times(29, 'Review'), 'Idea']), 'Implementing');
  });

  // Harm: a story stays Analyzed beside its issue in Implementing.
  test('a story follows its issue into work started elsewhere', () {
    expect(_rule.state(['Analyzed', 'Implementing']), 'Implementing');
    expect(_rule.state(['Implementing']), 'Implementing');
  });

  // Harm: a story whose issue is reworked shows as freshly implemented.
  test('a story being built stands in the furthest state of its issues being built', () {
    expect(_rule.state(['Implementing', 'Reworking', 'Reading', 'Backlog']), 'Reworking');
  });

  // Harm: a story whose issues are all done is shown closed, or in an earlier state, before a person closed it.
  test('a story waits in Review with every issue in Review, later or closed', () {
    expect(_rule.state(['Finished', 'Finished']), 'Review');
    expect(_rule.state(['Review', 'Rework', 'Finishing', 'Finished']), 'Review');
  });

  // Harm: a story with an idea not yet planned beside finished issues shows as an idea, or as finished.
  test('with no issue being built an issue in Review or later counts as Backlog', () {
    expect(_rule.state([..._times(5, 'Finished'), 'Idea']), 'Backlog');
    expect(_rule.state([..._times(5, 'Finished'), 'Analyzed']), 'Backlog');
    expect(_rule.state(['Rework', 'Estimated']), 'Backlog');
    expect(_rule.state(['Analyzed', 'Idea', 'Analyzed']), 'Analyzed');
  });

  // Harm: a rejected issue makes its story look done, or behind the issues that are planned.
  test('an issue rejected is left out', () {
    expect(_rule.state(['Rejected', 'Idea', 'Analyzed']), 'Analyzed');
    expect(_rule.state(['Rejected', 'Rejected']), isNull, reason: 'no issue left');
  });

  // Harm: a state written by hand moves its story to a state the workflow does not have.
  test('an issue in a state the workflow lacks is left out', () {
    expect(_rule.state(['Nowhere', 'Analyzed']), 'Analyzed');
    expect(_rule.state(['Nowhere']), isNull);
    expect(_rule.state([]), isNull);
  });

  // Harm: a story shown as built hides the issue still waiting in Idea, so nobody plans it.
  test('each issue before the story\'s state is named in one warning', () {
    final states = ['Idea', 'Implementing', 'Review', 'Analyzed'];
    final state = _rule.state(states)!;
    expect(state, 'Implementing');
    final behind = _rule.behind(state, ['A1', 'A2', 'A3', 'A4'], states);
    expect(behind, ['A1', 'A4']);
    expect(PlanningStoryRule.warning(behind, state), {
      'kind': 'IssueBehindStory',
      'text': 'Its issues A1 and A4 stand before its state Implementing.',
      'holds': false,
    });
  });

  // Harm: a finished issue is warned about as lagging behind a story it is ahead of.
  test('an issue counted as Backlog stands behind no story', () {
    final states = ['Finished', 'Idea'];
    final state = _rule.state(states)!;
    expect(state, 'Backlog');
    final behind = _rule.behind(state, ['F1', 'I1'], states);
    expect(PlanningStoryRule.warning(behind, state)['text'], 'Its issue I1 stands before its state Backlog.');
    expect(_rule.behind('Review', ['R1', 'F1'], ['Review', 'Finished']), isEmpty);
  });

  // Harm: on the mock board a story whose issue is reworked shows in Backlog or in Review.
  test('Reworking after Review is built as in the live order', () {
    expect(_mockRule.review, 'Review');
    expect(_mockRule.state(['Reworking', 'New']), 'Reworking');
    expect(_mockRule.state(['Reworking']), 'Reworking');
    expect(_mockRule.state(['Implementing', 'Reworking', 'Reading']), 'Reworking');
    expect(_mockRule.state(['Implementing', 'Review']), 'Implementing');
    expect(_mockRule.state(['Review', 'Rework', 'Finishing', 'Finished']), 'Review');
    expect(_mockRule.state(['Rework', 'New']), 'Backlog');
    expect(_mockRule.behind('Reworking', ['R1', 'W1'], ['Review', 'Rework']), isEmpty);
    expect(_mockRule.behind('Reworking', ['I1'], ['Implementing']), ['I1']);
  });

  // Harm: a story whose issues are all finishing shows as being built, not waiting for review.
  test('the last open state is never built', () {
    expect(_rule.state(['Finishing', 'Implementing']), 'Implementing');
    expect(_rule.state(['Finishing']), 'Review');
    expect(_mockRule.state(['Finishing']), 'Review');
  });

  // Harm: a workflow the rule cannot read gives stories a guessed state.
  test('a workflow without the states the rule needs gives no rule', () {
    List<Map<String, dynamic>> flow(List<String> names, Set<String> closed, Set<String> measured) => [
      for (final name in names)
        {'name': name, 'kind': closed.contains(name) ? 'CLOSED' : 'WAITING', 'measured': measured.contains(name)},
    ];
    const names = ['Idea', 'Building', 'Review', 'Done'];
    expect(PlanningStoryRule.of(flow(names, {'Done'}, {})), isNull, reason: 'nothing measured');
    expect(PlanningStoryRule.of(flow(names, {'Done'}, {'Idea', 'Building'})), isNull, reason: 'none before');
    expect(PlanningStoryRule.of(flow(names, {'Idea', 'Done'}, {'Building'})), isNull, reason: 'a closed one before');
  });

  // Harm: a story waits for review in a state its work has not reached, or past it.
  test('Review is the measured open state directly followed by an unmeasured open one', () {
    List<Map<String, dynamic>> flow(List<String> names) => [
      for (final name in names)
        {
          'name': name,
          'kind': name == 'Done' ? 'CLOSED' : 'WAITING',
          'measured': {'Building', 'Review', 'Done'}.contains(name),
        },
    ];
    expect(PlanningStoryRule.of(flow(['Idea', 'Building', 'Review', 'Rework', 'Done']))!.review, 'Review');
    expect(
      PlanningStoryRule.of(flow(['Idea', 'Building', 'Review', 'Done'])),
      isNull,
      reason: 'a closed state after it is not enough',
    );
  });
}
