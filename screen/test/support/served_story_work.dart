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
      'offer': servedOffer(1),
      'repository': 'repo',
      'issues': ['PR1', 'PR1.1'],
      'role': 'MAIN',
      'agent': 'codex',
      'model': 'main-model',
      'mode': 'AGENT',
      'prompt': '  Complete the story.\n```\n{literal}\n```\n',
    },
    {
      'offer': servedOffer(2),
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

/// Stable UUIDs that distinguish offers without inventing runtime identities.
String servedOffer(int number) => '00000000-0000-4000-8000-${number.toRadixString(16).padLeft(12, '0')}';

/// A single-issue continuation, distinct from the initial MAIN and helper order.
Map<String, dynamic> servedFollowUp(int number, {String role = 'READER'}) => {
  'offer': servedOffer(number),
  'repository': 'repo',
  'issues': ['PR1'],
  'role': role,
  'agent': 'codex',
  'model': role == 'READER' ? 'reader-model' : 'builder-model',
  'mode': 'AGENT',
  'prompt': 'Continue this issue.\n',
  'reads': role == 'READER' ? ['builder-task'] : <String>[],
};

/// After the last running task ends, an OFFERED continuation does not count as active work.
Map<String, dynamic> servedContinuationWork({bool anotherAtWork = false}) => servedWork(atWork: anotherAtWork)
  ..['tasks'] = [
    servedRunTask(
      active: false,
      ended: {'at': '', 'finished': true, 'why': '', 'answer': 'Findings:\n', 'unpushed': 0},
    ),
    if (anotherAtWork) servedRunTask(task: 'other-reader')..['issues'] = ['PR1.1'],
  ];

/// A durable run whose task activity is independent of its permission to continue.
Map<String, dynamic> servedWork({bool atWork = true}) => {
  'run': '00000000-0000-4000-9000-000000000001',
  'tasks': <Map<String, dynamic>>[],
  'coordinator': null,
  'since': '',
  'atWork': atWork,
  'repositories': servedProposal()['repositories'],
  'comment': '',
  'readingRounds': 2,
  'issueReadingRounds': <Map<String, dynamic>>[],
  'issues': <Map<String, dynamic>>[],
};

/// A stopped issue whose round and findings are the source facts for a decision.
Map<String, dynamic> servedReadingIssue({bool stopped = true, int round = 2}) => {
  'id': 'PR1',
  'repository': 'repo',
  'title': 'Read the changes',
  'state': 'Reading',
  'rounds': round,
  'roundsMost': 12,
  'readingStopped': stopped,
  'atWork': false,
  'needsYou': stopped,
  'worker': 'AGENT',
  'findings': [
    {'number': 1, 'severity': 'BLOCKING', 'text': 'Keep credentials inside.'},
  ],
};

/// The runtime record the planner returns after the window reports a confirmed start.
Map<String, dynamic> servedRunTask({String task = 'main-task', bool active = true, Map<String, dynamic>? ended}) => {
  'task': task,
  'containerId': 'container-$task',
  'machine': 'Machine 🚀',
  'repository': 'repo',
  'issues': ['PR1'],
  'state': 'Reading',
  'done': 'Review',
  'mode': 'AGENT',
  'agent': 'codex',
  'model': 'main-model',
  'role': 'READER',
  'since': '',
  'atWork': active,
  'ended': ended,
};
