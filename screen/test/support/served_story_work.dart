import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

/// A proposal with the author's main task and a helper that builds the sub-issue, served by the planner.
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
      'main': {
        'name': 'main',
        'role': mockRole('author'),
        'agent': 'codex',
        'model': 'main-model',
        'brief': 'Complete the story.',
      },
      'subAgents': [
        {
          'id': 'builder',
          'issue': 'PR1.1',
          'name': 'builder',
          'kind': planningHelper,
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
      ...servedStart('author'),
      'offer': servedOffer(1),
      'opensTerminal': true,
      'repository': 'repo',
      'issues': ['PR1', 'PR1.1'],
      'agent': 'codex',
      'model': 'main-model',
      'mode': 'AGENT',
      'prompt': '  Complete the story.\n```\n{literal}\n```\n',
    },
    {
      ...servedStart('author'),
      'offer': servedOffer(2),
      'repository': 'repo',
      'issues': ['PR1.1'],
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
  seen: const {'ST1': servedVersion},
  subAgents: choices,
  proposedRepositories: defaults ? const [] : StoryWorkProposal.from(servedProposal()).repositories,
);

/// Stable UUIDs that distinguish offers without inventing runtime identities.
String servedOffer(int number) => '00000000-0000-4000-8000-${number.toRadixString(16).padLeft(12, '0')}';

/// The members of a work order bound to the stand-in board's role [role]: its kind, the role, its rights, what it
/// reads besides, whether it has the project's services and its workspace in Start's words, as the planner hands
/// them to Start. A role at another task's work is started under a task name of the window's own; its place is the
/// caller's to name.
Map<String, dynamic> servedStart(String role, {List<String>? readLanded}) {
  final bound = mockRole(role);
  return {
    'kind': 'work',
    'role': bound,
    'rights': bound['rights'],
    // A role that reads landed work reads the builder's besides, as core names it; no other role names any.
    'readLanded': readLanded ?? (bound['readLanded'] == true ? ['builder-task'] : <String>[]),
    'projectServices': bound['projectServices'],
    ...planningWorkspaceMembers(bound['workspace'] as String),
    if (bound['workspace'] == 'foreign') 'ownTask': true,
  };
}

/// A single-issue continuation of the role [role], distinct from the initial main and helper order: by default a
/// reader, which reads the builder's landed work.
Map<String, dynamic> servedFollowUp(int number, {String role = 'reader'}) => {
  ...servedStart(role),
  'offer': servedOffer(number),
  'repository': 'repo',
  'issues': ['PR1'],
  'agent': 'codex',
  'model': role == 'author' ? 'builder-model' : 'reader-model',
  'mode': 'AGENT',
  'prompt': 'Continue this issue.\n',
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
  'role': mockRole('reader'),
  'readLanded': ['builder-task'],
  'since': '',
  'atWork': active,
  'ended': ended,
};

/// A version as the planner names one, standing for the story the served decisions saw.
const servedVersion = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2';
