import 'package:porelo_planning/porelo_planning.dart';

/// A proposal with the MAIN and sub-issue BUILDER served by the planner.
Map<String, dynamic> servedProposal() => {
  'revision': 'r1',
  'modes': ['AGENT', 'UNATTENDED'],
  'coordinator': 'START',
  'comment': '',
  'readingRounds': 2,
  'issueReadingRounds': <Map<String, dynamic>>[],
  'repositories': [
    {
      'repository': 'repo',
      'issues': ['PR1', 'PR1.1'],
      'main': {'name': 'main', 'role': 'MAIN', 'agent': 'codex', 'model': 'main-model', 'brief': 'Complete the story.'},
      'subAgents': [
        {
          'id': 'builder',
          'issue': 'PR1.1',
          'name': 'builder',
          'kind': 'BUILDER',
          'agent': 'codex',
          'model': 'builder-model',
          'workingState': 'Building',
          'brief': 'Build the sub-issue.',
          'choice': 'KEEP',
        },
      ],
    },
  ],
};

/// An order with complete Start arguments and no invented runtime work.
Map<String, dynamic> servedOrder() => {
  'revision': 'r1',
  'tasks': [
    {
      'repository': 'repo',
      'issues': ['PR1', 'PR1.1'],
      'role': 'MAIN',
      'agent': 'codex',
      'model': 'main-model',
      'mode': 'AGENT',
      'prompt': '  Complete the story.\n```\n{literal}\n```\n',
    },
    {
      'repository': 'repo',
      'issues': ['PR1.1'],
      'role': 'BUILDER',
      'agent': 'codex',
      'model': 'builder-model',
      'mode': 'AGENT',
      'prompt': 'Build the sub-issue.\n',
    },
  ],
};

/// The complete revision-bound choices corresponding to the served order.
StoryWorkRequest servedRequest({List<StoryWorkChoice> choices = const [], bool defaults = false}) => StoryWorkRequest(
  project: 'demo',
  story: 'ST1',
  to: 'Building',
  mode: 'AGENT',
  revision: 'r1',
  subAgents: choices,
  proposedRepositories: defaults ? const [] : StoryWorkProposal.from(servedProposal()).repositories,
);
