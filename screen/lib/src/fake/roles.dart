/// The roles the stand-in board's work rules define, by name, each as the planner serves a role: how its task ends
/// and the parts of its start. Beside the author and the reviewer they hold a tester and a screener, so the screen is
/// held to roles it knows no word of, an analyst, whose stage hands back a result, and a reader, which reads a build's
/// landed work.
Map<String, dynamic> mockRole(String name) => switch (name) {
  'author' => _role(name, 'PUSHES_WORK', 'own', services: true, writeAs: 'author', rights: ['push', 'reply']),
  'reviewer' => _role(name, 'GIVES_POINTS', 'foreign', rights: ['reply', 'openThreads']),
  'tester' => _role(name, 'REPORTS_FINDINGS', 'foreign', rights: ['openThreads', 'report']),
  'screener' => _role(name, 'GIVES_POINTS', 'foreign', rights: ['reply', 'openThreads', 'reject']),
  'analyst' => _role(name, 'HANDS_BACK_RESULT', 'readOnly', services: true, writeAs: 'author', rights: ['report']),
  'reader' => _role(name, 'REPORTS_FINDINGS', 'readOnly', readLanded: true, services: true, rights: ['report']),
  _ => throw ArgumentError.value(name, 'name', 'The stand-in board defines no such role'),
};

Map<String, dynamic> _role(
  String name,
  String endKind,
  String workspace, {
  required List<String> rights,
  bool readLanded = false,
  bool services = false,
  String writeAs = 'review',
}) => {
  'name': name,
  'endKind': endKind,
  'workspace': workspace,
  'readLanded': readLanded,
  'projectServices': services,
  'writeAs': writeAs,
  'rights': rights,
};

/// Start's workspace members for a role's [workspace], as the planner names them: each only as true.
Map<String, dynamic> planningWorkspaceMembers(String workspace) => {
  if (workspace == 'readOnly' || workspace == 'allRepositories') 'readOnly': true,
  if (workspace == 'allRepositories') 'allRepositories': true,
  if (workspace == 'none') 'noGate': true,
};
