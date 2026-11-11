import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

void main() {
  // Harm: omitted fields manufacture low priority, stopped work, or completed progress.
  test('missing and unknown planning fields remain unsaid', () {
    final story = PlanStory.from({});
    expect(story.id, isEmpty);
    expect(story.state, isEmpty);
    expect(story.priority, isNull);
    expect(story.work, isNull);
    expect(story.issuesReported, isFalse);
    expect(story.completedIssues, isNull);
    expect(PlanWork.from({}).atWork, isNull);
    expect(
      PlanStory.from({
        'issues': [
          {'id': 'A'},
        ],
      }).completedIssues,
      isNull,
    );
    expect(PlanState.from({'kind': 'LATER'}).kind, 'LATER');
    expect(PlanStory.from({'priority': 'Urgent'}).priority, 'Urgent');
    expect(PlanStory.from({'issues': <Map<String, dynamic>>[]}).completedIssues, 0);
    expect(PlanWork.from({'atWork': false}).atWork, isFalse);
    final snapshot = PlanningSnapshot.from({'states': 'wrong', 'phases': null, 'stories': false});
    expect(snapshot.complete, isFalse);
    expect(snapshot.states, isEmpty);
    expect(snapshot.stories, isEmpty);
  });

  // Harm: missing or malformed reading metadata becomes an invented round or a false stop signal.
  test('reading metadata preserves reported values and keeps absent fields unsaid', () {
    final absent = PlanIssue.from({});
    expect(absent.rounds, isNull);
    expect(absent.roundsMost, isNull);
    expect(absent.readingStopped, isNull);
    final malformed = PlanIssue.from({'rounds': '2', 'roundsMost': 4.5, 'readingStopped': 'false'});
    expect(malformed.rounds, isNull);
    expect(malformed.roundsMost, isNull);
    expect(malformed.readingStopped, isNull);
    final reported = PlanIssue.from({'rounds': 2, 'roundsMost': 5, 'readingStopped': true});
    expect(reported.rounds, 2);
    expect(reported.roundsMost, 5);
    expect(reported.readingStopped, isTrue);
    final zero = PlanIssue.from({'rounds': 0, 'roundsMost': 0, 'readingStopped': false});
    expect(zero.rounds, 0);
    expect(zero.roundsMost, isNull); // The allowance has a minimum of one; completed rounds can be zero.
    expect(zero.readingStopped, isFalse);
  });
}
