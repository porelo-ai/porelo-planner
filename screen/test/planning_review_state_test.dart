import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// A workflow whose analysis rule ends in Analyzed and whose build rule ends in Review, as Porelo's own does.
const _workflow = [
  {'name': 'Accepted', 'kind': 'WAITING'},
  {'name': 'In analysis', 'kind': 'WORKING'},
  {'name': 'Analyzed', 'kind': 'WAITING'},
  {'name': 'Estimated', 'kind': 'WAITING'},
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Implementing', 'kind': 'WORKING'},
  {'name': 'Review', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
];

Map<String, dynamic> _rule(String state, String from, String done) => {
  'state': state,
  'from': [from],
  'done': done,
  'per': 'ISSUE',
  'modes': <String>[],
  'order': '',
};

void main() {
  final snapshot = PlanningSnapshot.from({
    'revision': 'r1',
    'states': _workflow,
    'phases': [
      {'name': 'All', 'states': _workflow.map((state) => state['name']).toList()},
    ],
    'work': [_rule('In analysis', 'Accepted', 'Analyzed'), _rule('Implementing', 'Backlog', 'Review')],
    'faults': <Object>[],
    'stories': <Object>[],
  });

  // Harm: work waiting for the person's own next step, an analysis or an estimate, marked as waiting for their review,
  // so Open work and the cards ask the person to review what holds nothing to review.
  test('only the state the last work ends in is review, and an analysis end shows by its own kind', () {
    final review = planningReviewStates(snapshot);
    expect(review, {'Review'}, reason: 'the end of analysis is no call on the person to review');
    final analyzed = snapshot.states.firstWhere((state) => state.name == 'Analyzed');
    expect(planningStateKind(analyzed, review), PlanningStateKind.analysis, reason: 'Analyzed shows by its state');
    expect(planningStoryReviewState(snapshot), 'Review', reason: 'the review count keeps its state');
  });
}
