import '../client.dart';

/// A refusal as the daemon words one: its full [name], such as `org.fuin.porelo.Tasks1.StaleRevision`, and what it
/// sent, so the screen reads the same words it reads from a daemon.
PlanningRefusal mockRefusal(String name, [Map<String, dynamic> parameters = const {}]) => PlanningRefusal(
  name.substring(name.lastIndexOf('.') + 1),
  parameters,
  parameters.isEmpty ? name : '$name $parameters',
  name == 'org.varlink.service.MethodNotFound',
);
