import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// The mark the planner ends a story's body with when it made the story from issues that stand.
const _marker =
    '<!-- porelo-planner story from issues ${'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'} -->';

void main() {
  test('a story made from issues that stand shows its description without the planner\'s marker', () {
    // Harm: the story page, a card or the detail shows the planner's hidden mark as text, since the screen never
    // renders HTML.
    final story = PlanStory.from({
      'id': 'ST9',
      'description': '**What a user gains.** People find stories\n\n**The test.** Tag one\n\n$_marker\n',
    });

    expect(story.description, '**What a user gains.** People find stories\n\n**The test.** Tag one\n');
  });

  test('a comment a person wrote, or a marker inside a line, stays as written', () {
    // Harm: text a person wrote dropped because it looks like the planner's mark.
    const written = 'Keep <!-- porelo-planner story from issues abc --> this.\n\n<!-- a note -->\n';

    expect(PlanStory.from({'id': 'ST9', 'description': written}).description, written);
  });
}
