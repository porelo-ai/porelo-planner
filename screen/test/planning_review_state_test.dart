import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/ui/open_work_rows.dart';

import 'support/porelo_workflow.dart';

void main() {
  final snapshot = PlanningSnapshot.from(
    poreloBoard([
      {
        'id': 'SA',
        'title': 'Analyzed only',
        'state': 'Analyzed',
        'priority': 'High',
        'issues': <Object>[],
        'open': poreloOpen(states: {'Analyzed': 2}, left: 2),
      },
      {
        'id': 'SR',
        'title': 'In review',
        'state': 'Review',
        'priority': 'High',
        'issues': <Object>[],
        'open': poreloOpen(states: {'Review': 1}, review: 1),
      },
    ]),
  );
  final stories = {for (final story in snapshot.stories) story.id: story};

  // Harm: work waiting for the person's own next step, an analysis or an estimate, marked as waiting for their review,
  // so Open work and the cards ask the person to review what holds nothing to review.
  test('under Porelo\'s own rules only Review is review, and an analysis or estimate end shows by its own kind', () {
    final review = planningReviewStates(snapshot);
    expect(review, {'Review'}, reason: 'building, reading and rework end in Review, the latest end');
    final kinds = {for (final state in snapshot.states) state.name: planningStateKind(state, review)};
    expect(kinds['Analyzed'], PlanningStateKind.analysis, reason: 'Analyzed shows by its state');
    expect(kinds['Estimated'], isNot(PlanningStateKind.review), reason: 'nor is an estimate review');
    expect(planningStoryReviewState(snapshot), 'Review', reason: 'the review count keeps its state');
  });

  // Harm: the board's Open work filter leaves out what the planner counts as left, an analyzed issue, or the Review
  // filter keeps a story whose analysis waits for the person's next step.
  test('the board keeps an analyzed story under Open work and never under Review', () {
    final filter = PlanningBoardFilter(
      snapshot.states,
      planningReviewStates(snapshot),
      storyReview: planningStoryReviewState(snapshot),
    );
    expect(filter.states(PlanningFilter.openWork), containsAll(['Analyzed', 'Estimated']), reason: 'left to build');
    expect(filter.states(PlanningFilter.review), ['Review'], reason: 'only the last work\'s end');
    expect(filter.matches(stories['SA']!, PlanningFilter.openWork), isTrue);
    expect(filter.matches(stories['SA']!, PlanningFilter.review), isFalse, reason: 'its card is no review card');
    expect(filter.matches(stories['SR']!, PlanningFilter.review), isTrue);
  });

  // Harm: an Open work row whose issues are all analyzed drops off, or reads as review, though the planner counts
  // them as left.
  test('an Open work row of analyzed issues alone is listed as left, its issues under analysis', () {
    final list = planningOpenWork(snapshot.stories, snapshot.states, planningReviewStates(snapshot), known: true);
    final row = list.rows.singleWhere((row) => row.story.id == 'SA');
    expect(row.open?.left, 2, reason: 'listed as left');
    expect(row.now, [(PlanningStateKind.analysis, 2)], reason: 'counted by their own kind, never as review');
  });
}
