/// Porelo's own workflow, as the planner serves it: the states of porelo-project's `stories/workflow.yml`, each with
/// the kind the planner gives it, and the work rules a tracked project runs, which porelo-project keeps no file of.
/// Analysis ends in Analyzed, estimation in Estimated, and building, reading and rework all in Review.
library;

/// The states in workflow order; a state a rule works in is WORKING, a closed one CLOSED, every other WAITING.
const poreloStates = [
  {'name': 'New', 'kind': 'WAITING'},
  {'name': 'Accepted', 'kind': 'WAITING'},
  {'name': 'In analysis', 'kind': 'WORKING'},
  {'name': 'Analyzed', 'kind': 'WAITING'},
  {'name': 'Under estimation', 'kind': 'WORKING'},
  {'name': 'Estimated', 'kind': 'WAITING'},
  {'name': 'Backlog', 'kind': 'WAITING'},
  {'name': 'Implementing', 'kind': 'WORKING'},
  {'name': 'Reading', 'kind': 'WORKING'},
  {'name': 'Reworking', 'kind': 'WORKING'},
  {'name': 'Review', 'kind': 'WAITING'},
  {'name': 'Rework', 'kind': 'WAITING'},
  {'name': 'Finishing', 'kind': 'WAITING'},
  {'name': 'Finished', 'kind': 'CLOSED'},
  {'name': 'Rejected', 'kind': 'CLOSED'},
];

Map<String, dynamic> _rule(String state, List<String> from, String done, {String? then, String? blocked}) => {
  'state': state,
  'from': from,
  'done': done,
  'then': ?then,
  'blocked': ?blocked,
  'per': 'REPOSITORY',
  'modes': ['UNATTENDED'],
  'order': '',
};

/// The work rules, as the planner serves them.
final poreloWork = [
  _rule('In analysis', ['New', 'Accepted'], 'Analyzed'),
  _rule('Under estimation', ['Analyzed'], 'Estimated'),
  _rule('Implementing', ['Backlog', 'Rework'], 'Review', then: 'Reading'),
  _rule('Reading', ['Implementing', 'Reworking', 'Review'], 'Review', blocked: 'Reworking'),
  _rule('Reworking', ['Reading'], 'Review', then: 'Reading'),
];

/// A story's figures as the planner counts them, [states] by name.
Map<String, dynamic> poreloOpen({Map<String, int> states = const {}, int left = 0, int review = 0}) => {
  'states': [
    for (final state in poreloStates) {'name': state['name'], 'issues': states[state['name']] ?? 0},
  ],
  'blocked': 0,
  'sizes': [
    if (left > 0) {'name': 'S', 'issues': left},
  ],
  'unsized': 0,
  'left': left,
  'minutes': null,
  'unestimated': left,
  'review': review,
};

/// A board of [stories] under Porelo's own workflow.
Map<String, dynamic> poreloBoard(List<Map<String, dynamic>> stories) => {
  'revision': 'r1',
  'states': poreloStates,
  'phases': [
    {'name': 'All', 'states': poreloStates.map((state) => state['name']).toList()},
  ],
  'work': poreloWork,
  'faults': <Object>[],
  'stories': stories,
};
