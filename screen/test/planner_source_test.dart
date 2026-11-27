import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';
import 'support/signed_planner.dart';

const _token = 'tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz';
const _digest = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

/// The versions a write saw, as the window sends them back.
const _seen = {'ST1': servedVersion};

Map<String, dynamic> _workflow(String revision) => {
  'revision': revision,
  'tracker': {'kind': 'github'},
  'states': [
    {'name': 'Backlog', 'kind': 'WAITING'},
    {'name': 'Building', 'kind': 'WORKING'},
    {'name': 'Finished', 'kind': 'CLOSED'},
  ],
  'phases': [
    {
      'name': 'Plan',
      'states': ['Backlog'],
    },
    {
      'name': 'Build',
      'states': ['Building', 'Finished'],
    },
  ],
  'work': [
    {
      'state': 'Building',
      'from': ['Backlog'],
      'done': 'Finished',
      'then': null,
      'blocked': null,
      'per': 'story',
      'modes': ['AGENT'],
      'order': 'Build it.',
      'models': <String, dynamic>{},
    },
  ],
  'faults': ['a fault'],
};

Map<String, dynamic> _issue(String id, String state) => {
  'id': id,
  'repository': 'repo',
  'title': 'Issue $id',
  'state': state,
  'size': null,
  'blockedBy': <String>[],
  'parent': null,
  'link': null,
  'worker': 'AGENT',
  'atWork': false,
  'needsYou': false,
  'readingStopped': false,
};

Map<String, dynamic> _story(String id, String state) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'priority': 'High',
  'link': null,
  'issues': [_issue('PR1', state)],
  'work': null,
};

Map<String, dynamic> _stories(String revision) => {
  'revision': revision,
  'stories': [_story('ST1', 'Backlog'), _story('ST2', 'Building')],
};

Map<String, dynamic> _problem(String name, Map<String, dynamic> parameters, String detail, int status) => {
  'type': 'urn:porelo-planner:$name',
  'title': name,
  'status': status,
  'detail': detail,
  'parameters': parameters,
};

/// One answer of the fake planner.
class _Reply {
  _Reply(this.status, [this.body, this.type = 'application/json']) : raw = null, headers = const {}, more = null;
  _Reply.problem(String name, Map<String, dynamic> parameters, String detail, this.status)
    : body = _problem(name, parameters, detail, status),
      type = 'application/problem+json',
      raw = null,
      headers = const {},
      more = null;

  /// Sends [raw] as it is under [headers], then whatever [more] gives.
  _Reply.raw(this.status, List<int> this.raw, {this.headers = const {}, this.more}) : body = null, type = '';

  final int status;
  final Object? body;
  final String type;
  final List<int>? raw;
  final Map<String, Object> headers;
  final Stream<List<int>>? more;
}

/// One call the fake planner received.
typedef _Call = ({String method, String target, bool signed, String? authorization, String body, String? lastEventId});

/// A planner on 127.0.0.1 that answers the board as the REST description gives it and records every call.
class _Planner {
  _Planner([String token = _token]) : signing = SignedPlanner(token);

  late final HttpServer _server;
  final SignedPlanner signing;
  final calls = <_Call>[];
  final Map<String, FutureOr<_Reply> Function()> routes = {};
  String workflowRevision = 'r1';
  String storiesRevision = 'r1';

  Uri get base => Uri(scheme: 'http', host: '127.0.0.1', port: _server.port);

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen((request) async {
      final bytes = [for (final chunk in await request.toList()) ...chunk];
      if (await signing.proof(request, bytes)) return;
      final body = utf8.decode(bytes, allowMalformed: true);
      final target = request.uri.toString();
      final nonce = signing.verified(request, bytes);
      calls.add((
        method: request.method,
        target: target,
        signed: nonce != null,
        authorization: request.headers.value(HttpHeaders.authorizationHeader),
        body: body,
        lastEventId: request.headers.value('last-event-id'),
      ));
      if (nonce == null) {
        request.response.statusCode = HttpStatus.unauthorized;
        await request.response.close();
        return;
      }
      final route = '${request.method} ${request.uri.path}';
      final reply = await (routes[route] ?? () => answer(route))();
      request.response.statusCode = reply.status;
      if (reply.raw case final raw?) {
        // Each chunk sent at once, as the planner flushes each event.
        request.response.bufferOutput = false;
        reply.headers.forEach(request.response.headers.set);
        final stream = '${reply.headers['content-type']}'.startsWith('text/event-stream');
        signing.prove(request.response, nonce, stream ? null : raw);
        try {
          request.response.add(raw);
          if (reply.more case final more?) await request.response.addStream(more);
          await request.response.close();
        } on Object {
          // The client closed the answer early, as it should for one past its bound.
        }
        return;
      }
      final sent = reply.body == null
          ? const <int>[]
          : utf8.encode(reply.body is String ? reply.body as String : jsonEncode(reply.body));
      signing.prove(request.response, nonce, sent);
      if (reply.body != null) {
        request.response.headers.contentType = ContentType.parse(reply.type);
        request.response.add(sent);
      }
      await request.response.close();
    });
  }

  /// The planner's own answer to [route], a method and a path.
  _Reply answer(String route) => switch (route) {
    'PUT /projects/demo' => _Reply(204),
    'GET /projects/demo/workflow' => _Reply(200, _workflow(workflowRevision)),
    'GET /projects/demo/stories' => _Reply(200, _stories(storiesRevision)),
    'GET /projects/demo/stories/ST1' => _Reply(200, {
      'revision': 'r1',
      'story': {..._story('ST1', 'Backlog'), 'description': 'The story text.'},
    }),
    'GET /projects/demo/stories/ST1/issues' => _Reply(200, {
      'revision': 'r1',
      'issues': [_issue('PR1', 'Backlog'), _issue('PR2', 'Finished')],
    }),
    'GET /projects/demo/issues/PR1' => _Reply(200, {
      'revision': 'r1',
      'issue': {
        ..._issue('PR1', 'Backlog'),
        'size': 'S',
        'text': 'The issue text.',
        'subIssues': <String>[],
        'whatMustBeTrue': 'It works.',
      },
    }),
    _ => _Reply(404, 'nothing here', 'text/plain'),
  };

  /// The connections the planner holds open now.
  int get connections => _server.connectionsInfo().total;

  List<String> get targets => [for (final call in calls) '${call.method} ${call.target}'];

  Future<void> stop() => _server.close(force: true);
}

Future<(_Planner, HttpPlanningSource)> _started({
  Duration timeout = const Duration(seconds: 30),
  Duration? writeTimeout,
  Duration loadStale = const Duration(seconds: 20),
  int? maxBody,
  PlannerProjects? projects,
  Duration silence = const Duration(seconds: 45),
  Duration backoff = const Duration(seconds: 1),
  Duration backoffMost = const Duration(seconds: 30),
}) async {
  final planner = _Planner();
  await planner.start();
  addTearDown(planner.stop);
  final source = HttpPlanningSource(
    planner.base,
    _token,
    projects ?? (project) => PlannerProject('https://example.org/$project.git', 'main'),
    timeout: timeout,
    writeTimeout: writeTimeout ?? timeout,
    loadStale: loadStale,
    maxBody: maxBody ?? 16 * 1024 * 1024,
    silence: silence,
    backoff: backoff,
    backoffMost: backoffMost,
  );
  addTearDown(source.close);
  return (planner, source);
}

/// One `board` event as the planner sends it: the workflow's and the stories' fields and the repositories in one
/// object.
List<int> _event(
  String revision, {
  Object? repositories = const ['porelo-project', 'porelo-other'],
  Map<String, Object?> more = const {},
}) => utf8.encode(
  'event:board\nid:$revision\ndata:${jsonEncode({..._workflow(revision), 'stories': _stories(revision)['stories'], 'repositories': ?repositories, ...more})}\n\n',
);

/// The planner's event streams, one per connection the source opened, each fed by the test.
/// One `load` event as the planner sends it while it reads a board.
List<int> _load(Map<String, Object?> data) => utf8.encode('event: load\ndata: ${jsonEncode(data)}\n\n');

class _Streams {
  _Streams(this.planner, {this.first = true}) {
    planner.routes['GET /projects/demo/events'] = () {
      final stream = StreamController<List<int>>();
      opened.add(stream);
      at.add(DateTime.now());
      return _Reply.raw(
        200,
        first ? _event(planner.storiesRevision) : const [],
        headers: {'content-type': 'text/event-stream'},
        more: stream.stream,
      );
    };
  }

  final _Planner planner;
  final bool first;
  final opened = <StreamController<List<int>>>[];
  final at = <DateTime>[];

  /// Sends [revision] on the newest connection; the planner's board is at it from then on.
  void send(String revision) {
    planner.storiesRevision = revision;
    opened.last.add(_event(revision));
  }

  /// Ends the newest connection as a planner that stops would.
  Future<void> end() => opened.last.close();

  List<String?> get lastEventIds => [
    for (final call in planner.calls)
      if (call.target == '/projects/demo/events') call.lastEventId,
  ];
}

Future<void> _until(bool Function() done, [String what = 'the condition']) async {
  for (var wait = 0; wait < 200; wait++) {
    if (done()) return;
    await Future<void>.delayed(const Duration(milliseconds: 25));
  }
  fail('$what did not come within 5 s');
}

/// A runtime directory with its `porelo-planner` directory, both owner-only as the planner makes them.
Future<Directory> _ownRuntime() async {
  final runtime = await Directory.systemTemp.createTemp('planner-address');
  addTearDown(() async {
    await Process.run('chmod', ['-R', 'u+rwx', runtime.path]);
    await runtime.delete(recursive: true);
  });
  await Directory('${runtime.path}/porelo-planner').create();
  await Process.run('chmod', ['700', runtime.path, '${runtime.path}/porelo-planner']);
  return runtime;
}

Future<int> _uid() async => int.parse(((await Process.run('id', ['-u'])).stdout as String).trim());

Future<PlanningRefusal> _refusal(Future<Object?> call) async {
  try {
    await call;
  } on PlanningRefusal catch (refusal) {
    return refusal;
  }
  fail('the call was not refused');
}

void main() {
  // Harm: the window's Needs you count unknown, or wrong, though the planner answered it.
  test('the stories read carries the board\'s open work as the planner answers it', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories'] = () => _Reply(200, {
      ..._stories(planner.storiesRevision),
      'open': {
        'states': [
          {'name': 'Backlog', 'issues': 1},
        ],
        'blocked': 0,
        'sizes': <Object>[],
        'unsized': 1,
        'left': 1,
        'minutes': null,
        'unestimated': 1,
        'review': 4,
      },
    });
    final snapshot = await source.stories('demo');
    expect(snapshot.open?.review, 4, reason: 'the board\'s review count');
    expect(snapshot.open?.left, 1, reason: 'the issues left to build');
  });

  // Harm: an old proposal is rebound to another repository that happens to share its revision.
  test('a known proposal cannot be submitted against a newly read project binding', () async {
    var repository = const PlannerProject('https://example.org/first.git');
    final (planner, source) = await _started(projects: (_) => repository);
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, servedOrder());
    await source.proposeStoryWork('demo', 'ST1', 'Building');
    repository = const PlannerProject('https://example.org/second.git');
    await source.stories('demo');
    final before = planner.calls.length;
    expect((await _refusal(source.startStoryWork(servedRequest()))).name, 'ProjectChanged');
    expect(planner.calls, hasLength(before));
    await source.proposeStoryWork('demo', 'ST1', 'Building');
    expect((await source.startStoryWork(servedRequest())).tasks, hasLength(2));
  });

  // Harm: a late order from a former project binding is accepted as tasks for the current project.
  test('an order answering after the project binding changes is refused whole', () async {
    var repository = const PlannerProject('https://example.org/first.git');
    final (planner, source) = await _started(projects: (_) => repository);
    final received = Completer<void>();
    final answer = Completer<_Reply>();
    planner.routes['POST /projects/demo/stories/ST1/work'] = () {
      received.complete();
      return answer.future;
    };
    final result = _refusal(source.startStoryWork(servedRequest()));
    await received.future;
    repository = const PlannerProject('https://example.org/second.git');
    answer.complete(_Reply(200, servedOrder()));
    expect((await result).name, 'ProjectChanged');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/stories/ST1/work']);
  });

  // Harm: a move of one issue proposes and starts the work of the whole story, so agents start on issues nobody moved.
  test('a proposal and an order for chosen issues name them, and a proposal for the story is kept apart', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, servedOrder());
    await source.proposeStoryWork('demo', 'ST1', 'Building', issues: ['PR1']);
    expect(jsonDecode(planner.calls.last.body), {
      'to': 'Building',
      'issues': ['PR1'],
    }, reason: 'the proposal names the chosen issue');
    await source.startStoryWork(
      StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        seen: const {'ST1': servedVersion},
        subAgents: const [],
        issues: const ['PR1'],
      ),
    );
    expect((jsonDecode(planner.calls.last.body) as Map)['issues'], ['PR1'], reason: 'the order names the chosen issue');
    final before = planner.calls.length;
    await source.startStoryWork(
      StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        seen: const {'ST1': servedVersion},
        subAgents: const [],
      ),
    );
    expect(
      planner.calls.sublist(before).map((call) => call.target),
      contains('/projects/demo/stories/ST1/proposal'),
      reason: "the story's own order is checked against its own proposal, never the chosen issue's",
    );
    expect((jsonDecode(planner.calls.last.body) as Map).containsKey('issues'), isFalse);
  });

  // Harm: a proposal is requested for another target or malformed participants silently disappear.
  test('a proposal posts only its target and refuses malformed proposals whole', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    final proposal = await source.proposeStoryWork('demo', 'ST1', 'Building');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/stories/ST1/proposal']);
    expect(jsonDecode(planner.calls.last.body), {'to': 'Building'});
    expect(proposal.repositories.single.subAgents.single.isHelper, isTrue);
    for (final bad in [
      servedProposal()..remove('readingRounds'),
      servedProposal()..['repositories'] = [false],
      servedProposal()..['readingRounds'] = 11,
      servedProposal()..['modes'] = ['UNKNOWN'],
      servedProposal()
        ..['issueReadingRounds'] = [
          {'issue': 'PR9', 'rounds': 2},
        ],
    ]) {
      planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, bad);
      expect((await _refusal(source.proposeStoryWork('demo', 'ST1', 'Building'))).name, 'BadAnswer');
    }
  });

  // Harm: an order accepts an altered task or starts with a rewritten prompt or scope.
  test('an order posts the exact choices and accepts only the complete matching answer', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, servedOrder());
    await source.proposeStoryWork('demo', 'ST1', 'Building');
    final request = servedRequest();
    final order = await source.startStoryWork(request);
    expect(jsonDecode(planner.calls.last.body), request.toMap());
    expect(planner.targets.last, 'POST /projects/demo/stories/ST1/work');
    expect(order.revision, 'r1');
    expect(order.tasks.map((task) => task.role?.name), ['author', 'author']);
    expect(order.tasks.first.prompt, (servedOrder()['tasks'] as List).first['prompt']);
    final bad = servedOrder();
    ((bad['tasks'] as List).last as Map)['model'] = 'another-model';
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, bad);
    expect((await _refusal(source.startStoryWork(request))).name, 'BadAnswer');
  });

  // Harm: a refresh that the planner never got, or refused, is taken for a fetch, and an old board for the current one.
  test('a refresh posts to the project and answers only a reported revision', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/refresh'] = () => _Reply(200, {'revision': 'r2'});
    expect(await source.refreshProject('demo'), 'r2', reason: 'the revision after the fetch');
    expect(planner.targets.last, 'POST /projects/demo/refresh', reason: 'the contract names this call');
    planner.routes['POST /projects/demo/refresh'] = () => _Reply(200, <String, dynamic>{'revision': null});
    expect(
      await source.refreshProject('demo'),
      isNull,
      reason: 'a read begun before the first board, answered at once',
    );
    planner.routes['POST /projects/demo/refresh'] = () => _Reply(200, <String, dynamic>{});
    expect((await _refusal(source.refreshProject('demo'))).name, 'BadAnswer', reason: 'no revision, no fetch');
    planner.routes['POST /projects/demo/refresh'] = () =>
        _Reply.problem('Unreachable', {}, 'The remote cannot be reached.', 504);
    expect((await _refusal(source.refreshProject('demo'))).name, 'Unreachable', reason: 'a refusal keeps its name');
    expect((await _refusal(source.refreshProject('../demo'))).name, 'InvalidName', reason: 'checked before it is sent');
  });

  // Harm: Reload planning, the one way out of a changed project, is refused for the change it should recover from.
  test('a refresh after the project repository changed tells the planner the new one and fetches it', () async {
    var repository = const PlannerProject('https://example.org/first.git');
    final (planner, source) = await _started(projects: (_) => repository);
    planner.routes['POST /projects/demo/refresh'] = () => _Reply(200, {'revision': 'r2'});
    await source.stories('demo');
    repository = const PlannerProject('https://example.org/second.git');
    final before = planner.targets.length;
    expect(await source.refreshProject('demo'), 'r2', reason: 'the fetch runs for the repository named now');
    expect(planner.targets.skip(before), [
      'PUT /projects/demo',
      'POST /projects/demo/refresh',
    ], reason: 'the planner is told the new repository before it fetches');
  });

  // Harm: a story's task answered for an idea reaches Start, or a refused idea is taken for an offer.
  test('a discussion posts the idea and accepts only a discussion answered without a revision', () async {
    final (planner, source) = await _started();
    final served = {
      'offer': servedOffer(9),
      'kind': 'discussion',
      'opensTerminal': true,
      'issues': <String>[],
      'agent': 'codex',
      'model': 'idea-model',
      'mode': 'AGENT',
      'prompt': 'Discuss the idea.\n',
      'readOnly': true,
      'allRepositories': true,
      'rights': ['report'],
      'readLanded': <String>[],
      'projectServices': true,
    };
    planner.routes['POST /projects/demo/ideas/discussion'] = () => _Reply(200, served);
    final task = await source.discussIdea('demo', 'A board that\nremembers.');
    expect(planner.targets.last, 'POST /projects/demo/ideas/discussion', reason: 'the contract names this call');
    expect(jsonDecode(planner.calls.last.body), {'idea': 'A board that\nremembers.'}, reason: 'the idea goes as typed');
    expect([task.kind, task.role, task.repository, task.result], ['discussion', null, null, null], reason: 'as served');
    planner.routes['POST /projects/demo/ideas/discussion'] = () => _Reply(200, servedFollowUp(1, role: 'author'));
    expect(
      (await _refusal(source.discussIdea('demo', 'An idea'))).name,
      'BadAnswer',
      reason: 'a story task is no discussion',
    );
    planner.routes['POST /projects/demo/ideas/discussion'] = () =>
        _Reply.problem('NoDiscussion', {}, 'The project has no sound work rule.', 400);
    final refusal = await _refusal(source.discussIdea('demo', 'An idea'));
    expect([refusal.name, refusal.description], ['NoDiscussion', 'The project has no sound work rule.']);
  });

  // Harm: default choices accept tasks that differ from the proposal the person reviewed.
  test('an order with omitted repositories still checks the proposed defaults', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, servedOrder());
    expect((await source.startStoryWork(servedRequest(defaults: true))).tasks, hasLength(2));
    expect(jsonDecode(planner.calls.last.body), servedRequest(defaults: true).toMap());
  });

  // Harm: a rejected proposal or order is retried or treated as tasks ready to start.
  test('proposal and order refusals retain their name and never replay the call', () async {
    final (planner, source) = await _started();
    for (final action in ['proposal', 'work']) {
      planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
      await source.proposeStoryWork('demo', 'ST1', 'Building');
      planner.routes['POST /projects/demo/stories/ST1/$action'] = () => _Reply.problem(
        'WorkRunning',
        {
          'tasks': ['active'],
        },
        'Work is running.',
        409,
      );
      final count = planner.calls.length;
      final refusal = await _refusal(
        action == 'proposal'
            ? source.proposeStoryWork('demo', 'ST1', 'Building')
            : source.startStoryWork(servedRequest()),
      );
      expect(refusal.name, 'WorkRunning');
      expect(refusal.parameters['tasks'], ['active']);
      expect(planner.calls, hasLength(count + 1));
    }
  });

  // Harm: changing the project's repository redirects a write made against the former board.
  test('writes refuse a changed project binding until its board has been read again', () async {
    var repository = const PlannerProject('https://example.org/first.git');
    final (planner, source) = await _started(projects: (_) => repository);
    await source.stories('demo');
    final before = planner.calls.length;
    repository = const PlannerProject('https://example.org/second.git');
    for (final call in <Future<Object?> Function()>[
      () => source.previewMoves('demo', const [PlanningStoryMove('ST1', 'Finished')]),
      () => source.saveMoves('demo', const [], _seen, digest: _digest),
      () => source.proposeStoryWork('demo', 'ST1', 'Building'),
      () => source.startStoryWork(servedRequest()),
    ]) {
      expect((await _refusal(call())).name, 'ProjectChanged');
    }
    expect(planner.calls, hasLength(before));
    await source.getStory('demo', 'ST1');
    final afterDetail = planner.calls.length;
    expect((await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest))).name, 'ProjectChanged');
    expect(planner.calls, hasLength(afterDetail), reason: 'a detail read does not replace the board binding');
  });

  // Harm: a server restart either loses a safe write or causes an unbounded retry loop.
  test('only an explicit NoSuchProject retells and retries a move save once', () async {
    final (planner, source) = await _started();
    var attempts = 0;
    planner.routes['POST /projects/demo/moves'] = () => ++attempts == 1
        ? _Reply.problem('NoSuchProject', {'project': 'demo'}, 'No project.', 404)
        : _Reply(200, {'revision': 'r2', 'moved': <String>[], 'stopped': <Object>[]});
    await source.saveMoves('demo', const [], _seen, digest: _digest);
    expect(planner.targets, [
      'PUT /projects/demo',
      'POST /projects/demo/moves',
      'PUT /projects/demo',
      'POST /projects/demo/moves',
    ]);
    planner.routes['POST /projects/demo/moves'] = () => _Reply.problem('NoSuchProject', {}, 'Still unknown.', 404);
    final count = planner.calls.length;
    expect((await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest))).name, 'NoSuchProject');
    expect(planner.calls, hasLength(count + 3));
  });

  // Harm: losing a write's answer sends the same move to the store twice.
  test('a lost connection after a move save never replays the write', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () =>
        _Reply.raw(200, [123], headers: {'content-type': 'application/json', 'content-length': 100});
    expect((await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest))).name, 'PlannerUnreachable');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves']);
  });
  // Harm: a reviewed mixed batch is saved with different destinations or a different revision.
  test('a move save sends the exact ordered batch and reads the saved envelope', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () => _Reply(200, {
      'revision': 'r2',
      'moved': ['PR1', 'PR2'],
      'stopped': [
        {
          'story': 'ST1',
          'run': 'run-1',
          'tasks': [
            {'task': 'build', 'containerId': 'box'},
          ],
        },
      ],
    });
    final saved = await source.saveMoves(
      'demo',
      const [PlanningStoryMove('ST1', 'Finished'), PlanningIssueMove('PR2', 'Backlog')],
      _seen,
      digest: _digest,
      confirm: true,
    );
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves']);
    expect(jsonDecode(planner.calls.last.body), {
      'seen': _seen,
      'moves': [
        {'story': 'ST1', 'to': 'Finished'},
        {'issue': 'PR2', 'to': 'Backlog'},
      ],
      'digest': _digest,
      'confirm': true,
    });
    expect(planner.calls.last.signed, isTrue);
    expect(saved.revision, 'r2');
    expect(saved.moved, ['PR1', 'PR2']);
    expect(
      [
        for (final run in saved.stopped)
          '${run.story} ${run.run} ${[for (final task in run.tasks) '${task.task}@${task.container}']}',
      ],
      ['ST1 run-1 [build@box]'],
      reason: 'the runs the save stopped, with the tasks the window ends',
    );
  });

  // Harm: a move confirmed over warnings other than those the planner previewed, or saved under another digest.
  test('a preview posts the batch and reads its digest, issues and warnings as the planner answers them', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves/preview'] = () => _Reply(200, {
      'revision': 'r1',
      'digest': _digest,
      'targets': [
        {'id': 'ST1', 'version': servedVersion},
      ],
      'issues': [
        {'id': 'F1', 'repository': 'porelo-frontend', 'from': 'Implementing', 'to': 'Review'},
      ],
      'stories': [
        {'id': 'ST1', 'from': 'Implementing', 'to': 'Review'},
      ],
      'warnings': [
        {
          'kind': 'RUNNING_WORK',
          'item': 'ST1',
          'from': 'Implementing',
          'to': 'Review',
          'run': 'run-1',
          'tasks': ['build'],
          'offers': ['o-2'],
          'issues': <Object>[],
          'skipped': <String>[],
        },
        {
          'kind': 'ISSUES_ALONG',
          'item': 'ST1',
          'from': 'Implementing',
          'to': 'Review',
          'run': null,
          'tasks': <String>[],
          'offers': <String>[],
          'issues': [
            {'id': 'F1', 'repository': 'porelo-frontend', 'from': 'Implementing', 'to': 'Review'},
          ],
          'skipped': <String>[],
        },
      ],
    });

    final preview = await source.previewMoves('demo', const [PlanningStoryMove('ST1', 'Review')]);

    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves/preview']);
    expect(jsonDecode(planner.calls.last.body), {
      'moves': [
        {'story': 'ST1', 'to': 'Review'},
      ],
    });
    expect(preview.digest, _digest);
    expect(
      [for (final issue in preview.issues) '${issue.id} ${issue.repository} ${issue.from} ${issue.to}'],
      ['F1 porelo-frontend Implementing Review'],
    );
    expect(
      [
        for (final warning in preview.warnings)
          '${warning.kind} ${warning.item} ${warning.run} ${warning.tasks} ${warning.offers} '
              '${warning.issues.map((issue) => issue.id).toList()}',
      ],
      ['RUNNING_WORK ST1 run-1 [build] [o-2] []', 'ISSUES_ALONG ST1 null [] [] [F1]'],
    );
  });

  // Harm: a preview the window cannot read lets a move go out unasked, or saves under something that is no digest.
  test('a preview without a digest, its targets with their versions or its lists is refused as a bad answer', () async {
    final (planner, source) = await _started();
    final good = {
      'revision': 'r1',
      'digest': _digest,
      'targets': [
        {'id': 'ST1', 'version': servedVersion},
      ],
      'issues': <Object>[],
      'stories': <Object>[],
      'warnings': <Object>[],
    };
    for (final answer in [
      {...good, 'digest': 'abc'},
      {...good, 'digest': _digest.toUpperCase()},
      {...good}..remove('targets'),
      {
        ...good,
        'targets': [
          {'id': 'ST1', 'version': 'r1'},
        ],
      },
      {
        ...good,
        'targets': [
          {'id': 'ST1', 'version': servedVersion},
          {'id': 'ST1', 'version': servedVersion},
        ],
      },
      {...good}..remove('issues'),
      {...good}..remove('warnings'),
      {
        ...good,
        'warnings': ['RUNNING_WORK'],
      },
    ]) {
      planner.routes['POST /projects/demo/moves/preview'] = () => _Reply(200, answer);
      expect(
        (await _refusal(source.previewMoves('demo', const [PlanningStoryMove('ST1', 'Review')]))).name,
        'BadAnswer',
        reason: '$answer',
      );
    }
    expect(planner.targets.where((target) => target == 'POST /projects/demo/moves'), isEmpty);
  });

  // Harm: an item the planner no longer knows is moved anyway, or the refusal is lost and the move shown as made.
  test('a preview answered 404 is a named refusal and sends no save', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves/preview'] = () =>
        _Reply.problem('NoSuchItem', {'item': 'ST9'}, 'No story ST9.', 404);

    final refusal = await _refusal(source.previewMoves('demo', const [PlanningStoryMove('ST9', 'Review')]));

    expect(refusal.name, 'NoSuchItem');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves/preview']);
  });

  // Harm: a new issue sent with another body than the planner reads, or a confirmation without an id, is taken as
  // written.
  test('a new issue posts exactly its members and refuses an answer without its id', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/issues'] = () => _Reply(201, {'id': 'PR3', 'revision': 'r2'});
    final added = await source.createIssue('demo', 'porelo-other', 'An idea', 'It holds.', null, _seen);
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/issues']);
    expect(jsonDecode(planner.calls.last.body), {
      'repository': 'porelo-other',
      'title': 'An idea',
      'mustBeTrue': 'It holds.',
      'worker': 'AGENT',
      'seen': _seen,
    }, reason: 'no size member when none is known, and an agent works it unless a person was chosen');
    expect((added.id, added.revision), ('PR3', 'r2'));
    await source.createIssue('demo', 'porelo-other', 'An idea', 'It holds.', 'S', _seen, worker: IssueWorker.manual);
    expect(jsonDecode(planner.calls.last.body)['size'], 'S', reason: 'a known size is sent');
    expect(jsonDecode(planner.calls.last.body)['worker'], 'MANUAL', reason: 'a person chosen is sent');
    // Harm: the proposing task's name lost on the way, or an empty member the planner refuses.
    await source.createIssue('demo', 'porelo-other', 'An idea', 'It holds.', null, _seen, task: 't-7.2');
    expect(jsonDecode(planner.calls.last.body)['task'], 't-7.2', reason: 'the task that proposed it is named');
    for (final answer in [
      {'revision': 'r2'},
      {'id': 'wrong', 'revision': 'r2'},
      {'id': 'PR3', 'revision': ''},
    ]) {
      planner.routes['POST /projects/demo/issues'] = () => _Reply(201, answer);
      expect((await _refusal(source.createIssue('demo', 'porelo-other', 'T', 'W', null, _seen))).name, 'BadAnswer');
    }
  });

  // Harm: a correction sent with another path or body than the planner reads is never saved, one saved without the
  // versions and the digest its preview showed writes over what the person did not see, and a preview the window
  // cannot read lets a correction go out unasked.
  test('a correction posts exactly its members, its save the versions and digest of its preview', () async {
    final (planner, source) = await _started();
    const correction = PlanningCorrection(story: 'ST1', warning: 'NoPriority', choice: 'High');
    final preview = {
      'revision': 'r1',
      'digest': _digest,
      'targets': [
        {'id': 'ST1', 'version': servedVersion},
      ],
      'story': 'ST1',
      'issue': null,
      'repository': null,
      'warning': 'NoPriority',
      'from': '',
      'to': 'High',
      'calls': <Object>[],
    };
    planner.routes['POST /projects/demo/corrections/preview'] = () => _Reply(200, preview);
    final previewed = await source.previewCorrection('demo', correction);
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/corrections/preview']);
    expect(jsonDecode(planner.calls.last.body), {'story': 'ST1', 'warning': 'NoPriority', 'choice': 'High'});
    expect(previewed.targets, _seen, reason: 'the targets the save sends back');
    expect(previewed.to, 'High');

    planner.routes['POST /projects/demo/corrections'] = () => _Reply(200, {'revision': 'r2'});
    final saved = await source.saveCorrection('demo', correction, previewed.targets, digest: previewed.digest);
    expect(planner.targets.last, 'POST /projects/demo/corrections');
    expect(jsonDecode(planner.calls.last.body), {
      'story': 'ST1',
      'warning': 'NoPriority',
      'choice': 'High',
      'seen': _seen,
      'digest': _digest,
    });
    expect(saved.revision, 'r2');

    planner.routes['POST /projects/demo/corrections/preview'] = () => _Reply(200, {...preview, 'digest': 'short'});
    expect(
      (await _refusal(source.previewCorrection('demo', correction))).name,
      'BadAnswer',
      reason: 'a preview without a readable digest',
    );
    for (final (other, what) in [
      ({...preview, 'to': 'Low'}, 'another choice'),
      ({...preview, 'story': 'ST2'}, 'another story'),
      ({...preview, 'story': null, 'issue': 'ST1'}, 'an issue for a story'),
      ({...preview, 'warning': 'UnknownPriority'}, 'another warning'),
    ]) {
      planner.routes['POST /projects/demo/corrections/preview'] = () => _Reply(200, other);
      expect(
        (await _refusal(source.previewCorrection('demo', correction))).name,
        'BadAnswer',
        reason: 'a preview of $what than asked',
      );
    }
    final calls = planner.calls.length;
    expect(
      (await _refusal(
        source.previewCorrection('demo', const PlanningCorrection(story: 'ST1', warning: 'NoPriority', choice: 'Hi\n')),
      )).name,
      'InvalidName',
      reason: 'a choice that is no name is never sent',
    );
    expect(planner.calls, hasLength(calls), reason: 'nothing reached the planner');
  });

  // Harm: a repository's labels written on its forge by calls the person was not shown, a repository correction
  // sent with a body the planner reads as another, or one bound to versions a repository does not have.
  test(
    'a repository correction posts no choice, saves with no versions and is previewed only with its calls',
    () async {
      final (planner, source) = await _started();
      const correction = PlanningCorrection(repository: 'porelo-host', warning: 'TopicLabels');
      final preview = {
        'revision': 'r1',
        'digest': _digest,
        'targets': <Object>[],
        'story': null,
        'issue': null,
        'repository': 'porelo-host',
        'warning': 'TopicLabels',
        'from': '',
        'to': '',
        'calls': [
          {'action': 'Delete', 'label': 'bug', 'color': null, 'description': null},
          {'action': 'Create', 'label': 'documentation', 'color': '0075ca', 'description': 'Guides'},
          {'action': 'Update', 'label': 'tests', 'color': 'fbca04', 'description': 'Tests'},
        ],
      };
      planner.routes['POST /projects/demo/corrections/preview'] = () => _Reply(200, preview);
      final previewed = await source.previewCorrection('demo', correction);
      expect(jsonDecode(planner.calls.last.body), {'repository': 'porelo-host', 'warning': 'TopicLabels'});
      expect(
        [for (final call in previewed.calls) '${call.action} ${call.label} ${call.color}'],
        ['Delete bug null', 'Create documentation 0075ca', 'Update tests fbca04'],
        reason: 'every call it would make, in its order',
      );
      expect(previewed.targets, isEmpty);

      planner.routes['POST /projects/demo/corrections'] = () => _Reply(200, {'revision': 'r2'});
      await source.saveCorrection('demo', correction, previewed.targets, digest: previewed.digest);
      expect(jsonDecode(planner.calls.last.body), {
        'repository': 'porelo-host',
        'warning': 'TopicLabels',
        'seen': <String, Object>{},
        'digest': _digest,
      }, reason: 'a repository has no item version, so it sends none');

      for (final (unreadable, why) in [
        ({...preview, 'calls': null}, 'no calls'),
        (
          {
            ...preview,
            'calls': [
              {'action': 'Rename', 'label': 'bug', 'color': null, 'description': null},
            ],
          },
          'a call of an unknown action',
        ),
        (
          {
            ...preview,
            'calls': [
              {'action': 'Create', 'label': 'docs\u202e', 'color': '0075ca', 'description': 'Guides'},
            ],
          },
          'a label that is no name',
        ),
        (
          {
            ...preview,
            'calls': [
              {'action': 'Delete', 'label': 'bug', 'color': '0075ca', 'description': null},
            ],
          },
          'a delete that writes a color',
        ),
        (
          {
            ...preview,
            'calls': [
              for (var at = 0; at <= PlanLabelCall.mostCalls; at++)
                {'action': 'Delete', 'label': 'l$at', 'color': null, 'description': null},
            ],
          },
          'more calls than any correction makes',
        ),
        ({...preview, 'repository': 'porelo-other'}, 'a preview of another repository'),
      ]) {
        planner.routes['POST /projects/demo/corrections/preview'] = () => _Reply(200, unreadable);
        expect((await _refusal(source.previewCorrection('demo', correction))).name, 'BadAnswer', reason: why);
      }

      final calls = planner.calls.length;
      expect(
        (await _refusal(
          source.previewCorrection('demo', const PlanningCorrection(repository: '/home/x:pw', warning: 'TopicLabels')),
        )).name,
        'InvalidName',
        reason: 'a repository that is no name is never sent',
      );
      expect(planner.calls, hasLength(calls), reason: 'nothing reached the planner');
    },
  );

  // Harm: a priority sent with another method, path or body than the planner reads is never saved, and one answered
  // without a revision is taken as saved.
  test('a priority puts exactly its members on the story and reads the revision it answers', () async {
    final (planner, source) = await _started();
    planner.routes['PUT /projects/demo/stories/ST1/priority'] = () => _Reply(200, {'revision': 'r2'});
    expect(await source.setPriority('demo', 'ST1', 'Lowest', _seen), 'r2', reason: 'the revision after the save');
    expect(planner.targets, ['PUT /projects/demo', 'PUT /projects/demo/stories/ST1/priority']);
    expect(jsonDecode(planner.calls.last.body), {'priority': 'Lowest', 'seen': _seen}, reason: 'only its members');
    planner.routes['PUT /projects/demo/stories/ST1/priority'] = () => _Reply(200, {'revision': ''});
    expect(
      (await _refusal(source.setPriority('demo', 'ST1', 'Lowest', _seen))).name,
      'BadAnswer',
      reason: 'an answer without a revision is no save',
    );
    final calls = planner.calls.length;
    expect(
      (await _refusal(source.setPriority('demo', 'ST1', 'Urgent', _seen))).name,
      'PriorityNotAllowed',
      reason: 'a sixth priority is never sent',
    );
    expect(planner.calls, hasLength(calls), reason: 'nothing reached the planner');
  });

  // Harm: labels written to another item than the one chosen, a story's to an issue of the same reference, or a label
  // the planner sets for no one sent at all.
  test('labels put exactly their members on the item\'s text edit, a story\'s on the story\'s', () async {
    final (planner, source) = await _started();
    planner.routes['PUT /projects/demo/issues/repo%237/text'] = () => _Reply(200, {'revision': 'r2'});
    planner.routes['PUT /projects/demo/stories/repo%237/text'] = () => _Reply(200, {'revision': 'r3'});
    expect(await source.setLabels('demo', 'repo#7', ['tests'], _seen, story: false), 'r2', reason: 'its revision');
    expect(planner.targets.last, 'PUT /projects/demo/issues/repo%237/text', reason: 'the issue\'s text edit');
    expect(jsonDecode(planner.calls.last.body), {
      'labels': ['tests'],
      'seen': _seen,
    }, reason: 'only its members');
    expect(await source.setLabels('demo', 'repo#7', const [], _seen, story: true), 'r3', reason: 'its revision');
    expect(planner.targets.last, 'PUT /projects/demo/stories/repo%237/text', reason: 'the story\'s text edit');
    expect(jsonDecode(planner.calls.last.body), {'labels': <String>[], 'seen': _seen}, reason: 'none takes all off');
    final calls = planner.calls.length;
    final refused = await _refusal(source.setLabels('demo', 'repo#7', ['tests', 'bug'], _seen, story: false));
    expect((refused.name, refused.parameters['label']), ('LabelNotAllowed', 'bug'), reason: 'refused by name');
    expect(
      (await _refusal(source.setLabels('demo', 'repo#7', ['tests', 'tests'], _seen, story: false))).name,
      'LabelNotAllowed',
      reason: 'a label twice is refused too',
    );
    expect(
      (await _refusal(source.setLabels('demo', '../x', ['tests'], _seen, story: false))).name,
      'InvalidName',
      reason: 'checked before it is sent',
    );
    expect(planner.calls, hasLength(calls), reason: 'nothing reached the planner');
  });

  // Harm: a worker sent elsewhere than the planner reads it, or a refusal taken as saved, leaves an issue to the wrong
  // worker while the person believes otherwise.
  test('a worker change puts exactly its members on the issue and keeps a refusal\'s words', () async {
    final (planner, source) = await _started();
    planner.routes['PUT /projects/demo/issues/PR1/worker'] = () => _Reply(200, {'revision': 'r2'});
    final saved = await source.setWorker('demo', 'PR1', IssueWorker.manual, _seen);
    expect(planner.targets, ['PUT /projects/demo', 'PUT /projects/demo/issues/PR1/worker']);
    expect(jsonDecode(planner.calls.last.body), {'worker': 'MANUAL', 'seen': _seen}, reason: 'only its members');
    expect(saved.revision, 'r2', reason: 'the revision after the save is read');
    planner.routes['PUT /projects/demo/issues/PR1/worker'] = () => _Reply(200, {'revision': ''});
    expect(
      (await _refusal(source.setWorker('demo', 'PR1', IssueWorker.agent, _seen))).name,
      'BadAnswer',
      reason: 'an answer without a revision is no save',
    );
    planner.routes['PUT /projects/demo/issues/PR1/worker'] = () =>
        _Reply.problem('MoveNotAllowed', {'item': 'PR1'}, 'PR1 stands in Building, which only its work enters.', 422);
    final refused = await _refusal(source.setWorker('demo', 'PR1', IssueWorker.agent, _seen));
    expect(
      (refused.name, refused.description),
      ('MoveNotAllowed', 'PR1 stands in Building, which only its work enters.'),
      reason: 'the refusal keeps its name and words',
    );
    expect(
      (await _refusal(source.setWorker('demo', '../PR1', IssueWorker.agent, _seen))).name,
      'InvalidName',
      reason: 'checked before it is sent',
    );
  });

  // Harm: an uncertain or refused write is replayed and saves the same move twice.
  test('a move save is never replayed after a timeout or a refusal', () async {
    final (planner, source) = await _started(timeout: const Duration(milliseconds: 300));
    final held = Completer<_Reply>();
    addTearDown(() => held.complete(_Reply(204)));
    for (final name in ['Busy', 'PartlySaved', 'TimedOut']) {
      planner.routes['POST /projects/demo/moves'] = () => name == 'TimedOut'
          ? held.future
          : _Reply.problem(name, {'pushed': 'repo', 'failed': 'other'}, 'Save refused.', 409);
      final before = planner.calls.where((call) => call.method == 'POST').length;
      expect(
        (await _refusal(source.saveMoves('demo', const [PlanningStoryMove('ST1', 'Finished')], _seen, digest: _digest)))
            .name,
        name,
      );
      expect(planner.calls.where((call) => call.method == 'POST'), hasLength(before + 1));
    }
  });

  // Harm: a save that takes the planner longer than a read may is given up on, so the planner's own answer, a partly
  // saved move among them, never reaches the card, which then waits on the board for a save that will not show.
  test('a save is waited for past the wait of a read, and the planner\'s refusal reaches the screen', () async {
    final (planner, source) = await _started(
      timeout: const Duration(milliseconds: 200),
      writeTimeout: const Duration(seconds: 5),
    );
    planner.routes['POST /projects/demo/moves'] = () async {
      await Future<void>.delayed(const Duration(milliseconds: 600));
      return _Reply.problem('PartlySaved', {'pushed': 'repo', 'failed': 'other'}, 'Part of the save was written.', 502);
    };

    final refusal = await _refusal(
      source.saveMoves('demo', const [PlanningStoryMove('ST1', 'Finished')], _seen, digest: _digest),
    );

    expect(refusal.name, 'PartlySaved', reason: 'the planner\'s answer, not a wait that ran out');
  });

  // Harm: a large project's first read is shown as "the planner sent no board" while it is still being read.
  test('a first read that keeps telling is waited for past the time a board is waited for', () async {
    final (planner, source) = await _started(timeout: const Duration(milliseconds: 300));
    final streams = _Streams(planner, first: false);
    final watch = source.watchStories('demo') as PlanningWatch;
    final revisions = <String>[];
    Object? failed;
    final watching = watch.listen((reply) => revisions.add(reply.revision), onError: (Object e) => failed = e);
    addTearDown(watching.cancel);
    await _until(() => streams.opened.isNotEmpty, 'the stream opened');

    for (var told = 0; told < 6; told++) {
      streams.opened.last.add(_load({'state': 'reading', 'since': '2026-11-21T15:00:00Z', 'read': told, 'total': 6}));
      await Future<void>.delayed(const Duration(milliseconds: 150));
    }
    streams.send(planner.storiesRevision);

    await _until(() => revisions.isNotEmpty, 'the first board after a read longer than the wait');
    expect(failed, isNull, reason: 'no failure while the read kept telling');
  });

  // Harm: a planner whose read stopped telling but keeps its line open holds the screen waiting for a board forever.
  test('before its first board a planner that told a read and then only keeps the line open is timed out', () async {
    final (planner, source) = await _started(timeout: const Duration(milliseconds: 300));
    final streams = _Streams(planner, first: false);
    final watch = source.watchStories('demo') as PlanningWatch;
    Object? failed;
    final watching = watch.listen((_) {}, onError: (Object e) => failed = e);
    addTearDown(watching.cancel);
    await _until(() => streams.opened.isNotEmpty, 'the stream opened');
    streams.opened.last.add(_load({'state': 'reading', 'since': '2026-11-21T15:00:00Z', 'read': 1, 'total': 6}));

    for (var beat = 0; beat < 8 && failed == null; beat++) {
      streams.opened.last.add(utf8.encode(': keep-alive\n\n'));
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }

    await _until(() => failed != null, 'the watch timed out');
    expect((failed! as PlanningRefusal).name, 'TimedOut', reason: 'a keep-alive is no board and no read');
  });

  // Harm: a read that ended without a word holds its bar, and Reload planning stays off until the window restarts.
  test('a read the planner tells no more ends after a while', () async {
    final (planner, source) = await _started(backoff: Duration.zero, loadStale: const Duration(milliseconds: 300));
    final streams = _Streams(planner);
    final watch = source.watchStories('demo') as PlanningWatch;
    final revisions = <String>[];
    final watching = watch.listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);
    await _until(() => revisions.isNotEmpty, 'the first board');

    streams.opened.last.add(_load({'state': 'reading', 'since': '2026-11-21T15:00:00Z', 'read': 100, 'total': 1384}));
    await _until(() => watch.loading.value != null, 'the read told');
    await _until(() => watch.loading.value == null, 'the untold read ended');
  });

  // Harm: an incomplete or disguised refusal is accepted as a successful save.
  test('a move save refuses every malformed success envelope', () async {
    final (planner, source) = await _started();
    for (final answer in [
      {'revision': 'r2'},
      {'revision': '', 'moved': <String>[]},
      {
        'revision': 'r2',
        'moved': ['PR1', 3],
      },
      {
        'revision': 'r2',
        'moved': ['wrong'],
      },
      {..._problem('Busy', {}, 'No.', 409), 'revision': 'r2', 'moved': <String>[]},
      // A save that may have stopped work must say which, or the window cannot end its tasks.
      {'revision': 'r2', 'moved': <String>[]},
      {
        'revision': 'r2',
        'moved': <String>[],
        'stopped': [
          {
            'story': 'ST1',
            'run': 'run-1',
            'tasks': [
              {'task': 'build'},
            ],
          },
        ],
      },
    ]) {
      planner.routes['POST /projects/demo/moves'] = () => _Reply(200, answer);
      expect((await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest))).name, 'BadAnswer');
    }
  });

  // Harm: a refusal loses the task identities or counts needed to explain why work was refused.
  test('a refusal retains bounded typed parameters and recognizes only bounded names', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () => _Reply.problem(
      'PartlySaved',
      {
        'tasks': ['one', 'two'],
        'round': 0,
        'rounds': 2147483647,
        'negative': -1,
        'large': 2147483648,
        'fraction': 1.5,
        'nested': {'x': 'y'},
        'pushed': 'repo',
        'failed': 'other',
        'longList': List.filled(500, 'task'),
      },
      'Some saves failed.',
      502,
    );
    final refusal = await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest));
    expect(refusal.parameters['tasks'], ['one', 'two']);
    expect(refusal.parameters['round'], 0);
    expect(refusal.parameters['rounds'], 2147483647);
    expect(
      refusal.parameters.keys,
      isNot(anyOf(contains('negative'), contains('large'), contains('fraction'), contains('nested'))),
    );
    expect((refusal.parameters['longList'] as List).length, lessThanOrEqualTo(200));
    expect(refusal.description, 'Some saves failed.');
    for (final name in ['A', 'A' * 65, 'Bad9']) {
      planner.routes['POST /projects/demo/moves'] = () => _Reply.problem(name, {}, 'No.', 409);
      expect((await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest))).name, 'Http409');
    }
  });

  // Harm: an unavailable move action disables planning reads or unrelated story work.
  test('a served NotOffered answer remains a named action refusal', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () => _Reply.problem('NotOffered', {}, 'Moves are unavailable.', 400);
    final refusal = await _refusal(source.saveMoves('demo', const [], _seen, digest: _digest));
    expect(refusal.name, 'NotOffered');
    expect(refusal.notOffered, isTrue);
    expect((await source.stories('demo')).revision, 'r1');
  });

  // Harm: a read reaches a planner that does not know the project yet, and the board stays empty.
  test('the project is named before the first read and once more after the planner forgets it', () async {
    final (planner, source) = await _started();
    var forgotten = true;
    planner.routes['GET /projects/demo/issues/PR1'] = () {
      if (!forgotten) return planner.answer('GET /projects/demo/issues/PR1');
      forgotten = false;
      return _Reply.problem('NoSuchProject', {'project': 'demo'}, 'The project is not known.', 404);
    };

    await source.getStory('demo', 'ST1');
    final issue = await source.getIssue('demo', 'PR1');

    expect(planner.targets, [
      'PUT /projects/demo',
      'GET /projects/demo/stories/ST1',
      'GET /projects/demo/issues/PR1',
      'PUT /projects/demo',
      'GET /projects/demo/issues/PR1',
    ], reason: 'the project is named once before reading, and again once after NoSuchProject');
    expect(jsonDecode(planner.calls.first.body), {
      'repository': 'https://example.org/demo.git',
      'branch': 'main',
    }, reason: 'the planner is told the repository the window names');
    expect(issue.issue?.id, 'PR1', reason: 'the read retried after naming the project answers');
  });

  // Harm: a planner that keeps forgetting the project makes the source loop instead of saying so.
  test('a project the planner forgets again is refused after one retry', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST1'] = () =>
        _Reply.problem('NoSuchProject', {'project': 'demo'}, 'The project is not known.', 404);

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'NoSuchProject', reason: 'the second refusal reaches the screen');
    expect(planner.targets.where((target) => target.startsWith('PUT')).length, 2, reason: 'one retry, no loop');
  });

  // Harm: the screen shows a board that is not the planner's, with states, kinds or stories lost on the way.
  test('the workflow and the stories make one snapshot with the planner\'s states, columns and stories', () async {
    final (_, source) = await _started();

    final board = await source.stories('demo');
    final workflow = await source.planningWorkflow('demo');

    for (final snapshot in [board, workflow]) {
      expect(snapshot.complete, isTrue, reason: 'states, phases and stories are all reported');
      expect(snapshot.revision, 'r1');
      expect(
        [for (final state in snapshot.states) '${state.name}:${state.kind}'],
        ['Backlog:WAITING', 'Building:WORKING', 'Finished:CLOSED'],
        reason: 'the states and their kinds in workflow order',
      );
      expect([for (final phase in snapshot.phases) phase.name], ['Plan', 'Build']);
      expect(snapshot.work.single.state, 'Building', reason: 'the work rules arrive');
      expect(snapshot.faults, ['a fault'], reason: 'the workflow faults arrive');
      expect([for (final story in snapshot.stories) '${story.id}:${story.state}'], ['ST1:Backlog', 'ST2:Building']);
      expect(snapshot.stories.first.issues.single.id, 'PR1', reason: 'each story keeps its issues');
    }
  });

  // Harm: a board mixes the workflow of one revision with the stories of another.
  test('a snapshot whose two reads disagree on the revision is read again, then refused', () async {
    final (planner, source) = await _started();
    planner.storiesRevision = 'r2';

    final refusal = await _refusal(source.stories('demo'));

    expect(refusal.name, 'RevisionMoved', reason: 'two disagreeing reads never make a board');
    expect(
      planner.targets.where((target) => target == 'GET /projects/demo/workflow').length,
      2,
      reason: 'the source reads once more before it refuses',
    );
  });

  // Harm: a push made elsewhere never reaches the window's board, or one board is drawn twice.
  test('the watch answers the board at once and each new revision once', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner);
    final revisions = <String>[];
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);

    await _until(() => revisions.isNotEmpty, 'the first board');
    streams
      ..send('r2')
      ..send('r2')
      ..send('r3');
    await _until(() => revisions.length >= 3, 'the later boards');
    await Future<void>.delayed(const Duration(milliseconds: 100));

    expect(revisions, ['r1', 'r2', 'r3'], reason: 'the first board, then each new revision once');
    expect(planner.targets.where((target) => target == 'PUT /projects/demo'), hasLength(1));
  });

  // Harm: an internally reconnecting stream leaves the board authorizing work on stale planning.
  test('reconnect notifications last until a board arrives even at the same revision', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    final states = <bool>[];
    void changed() => states.add(source.isReconnecting('demo'));
    source.addListener(changed);
    addTearDown(() => source.removeListener(changed));
    final revisions = <String>[];
    final watch = source.watchStories('demo');
    expect(watch, isA<PlanningWatch>(), reason: 'the watch tells its own reconnect');
    final own = (watch as PlanningWatch).reconnecting;
    final ownStates = <bool>[];
    own.addListener(() => ownStates.add(own.value));
    final watching = watch.listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);
    await _until(() => revisions.isNotEmpty, 'the first board');
    expect(source.isReconnecting('demo'), isFalse);

    // Hold the replacement connection open without a board, including a heartbeat.
    planner.routes['GET /projects/demo/events'] = () {
      final stream = StreamController<List<int>>();
      streams.opened.add(stream);
      return _Reply.raw(
        200,
        utf8.encode(': keep-alive\n\n'),
        headers: {'content-type': 'text/event-stream'},
        more: stream.stream,
      );
    };
    await streams.end();
    await _until(() => streams.opened.length == 2, 'the replacement connection');
    expect(states, [true]);
    expect(source.isReconnecting('demo'), isTrue);
    expect(source.isReconnecting('other'), isFalse);
    streams.send('r1');
    await _until(() => revisions.length == 2, 'the restored board at the same revision');
    expect(states, [true, false]);
    expect(source.isReconnecting('demo'), isFalse);
    expect(revisions, ['r1', 'r1']);

    await streams.end();
    await _until(() => source.isReconnecting('demo'), 'a second reconnect');
    await watching.cancel();
    expect(source.isReconnecting('demo'), isFalse);
    expect(states, [true, false, true, false]);
    expect(ownStates, [true, false, true, false], reason: 'the watch tells each of its own reconnects');
  });

  // Harm: a connection that died without a word leaves the board frozen while it looks live.
  test('a stream that falls silent is opened again with the last revision', () async {
    final (planner, source) = await _started(silence: const Duration(milliseconds: 300), backoff: Duration.zero);
    final streams = _Streams(planner);
    final revisions = <String>[];
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);

    await _until(() => revisions.isNotEmpty, 'the first board');
    streams.send('r2');
    await _until(() => revisions.length == 3, 'the board restoring the second connection');
    streams.send('r3');
    await _until(() => revisions.length == 4, 'the board after the reconnect');

    expect(streams.lastEventIds, [null, 'r2'], reason: 'the reconnect names the last revision it saw');
    expect(revisions, ['r1', 'r2', 'r2', 'r3']);
  });

  // Harm: a planner that restarts or fails for a while is called in a tight loop, or the window waits for minutes.
  test(
    'a lost stream is opened again after a wait that doubles up to its bound and starts over after a board',
    () async {
      final (planner, source) = await _started(
        backoff: const Duration(milliseconds: 100),
        backoffMost: const Duration(milliseconds: 200),
      );
      final streams = _Streams(planner);
      var failures = 4;
      final events = planner.routes['GET /projects/demo/events']!;
      planner.routes['GET /projects/demo/events'] = () {
        if (streams.opened.isNotEmpty && failures > 0) {
          failures--;
          streams.at.add(DateTime.now());
          return _Reply(503, 'restarting', 'text/plain');
        }
        return events();
      };
      final revisions = <String>[];
      final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
      addTearDown(watching.cancel);

      await _until(() => revisions.isNotEmpty, 'the first board');
      planner.storiesRevision = 'r2';
      await streams.end();
      await _until(() => streams.opened.length == 2, 'the stream after four failures');
      await streams.end();
      await _until(() => streams.opened.length == 3, 'the stream after the next loss');

      final gaps = [
        for (var i = 1; i < streams.at.length; i++) streams.at[i].difference(streams.at[i - 1]).inMilliseconds,
      ];
      expect(gaps, hasLength(6));
      expect(gaps[0], greaterThanOrEqualTo(90), reason: 'the first wait');
      expect(gaps[1], greaterThanOrEqualTo(190), reason: 'twice as long');
      expect(gaps.sublist(2, 5).every((gap) => gap >= 190), isTrue, reason: 'the bound holds the later waits: $gaps');
      expect(
        gaps.sublist(0, 5).fold<int>(0, (all, gap) => all + gap),
        lessThan(1500),
        reason: 'never past the bound: $gaps',
      );
      expect(gaps[5], inInclusiveRange(90, 189), reason: 'a board starts the waits over at the first wait: $gaps');
    },
  );

  // Harm: a planner that restarted forgets the project, and the board never gets a stream again.
  test('a stream refused for an unknown project names the project again once and opens again', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    var forgotten = true;
    final events = planner.routes['GET /projects/demo/events']!;
    final revisions = <String>[];
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);
    await _until(() => revisions.isNotEmpty, 'the first board');
    planner.routes['GET /projects/demo/events'] = () {
      if (forgotten) {
        forgotten = false;
        return _Reply.problem('NoSuchProject', {'project': 'demo'}, 'no project demo', 404);
      }
      return events();
    };
    planner.storiesRevision = 'r2';

    await streams.end();
    await _until(() => revisions.length == 2, 'the board after the project was named again');

    expect(planner.targets.where((target) => target == 'PUT /projects/demo'), hasLength(2));
  });

  // Harm: a refusal the window must show is retried for ever behind a board that looks live.
  test('a refusal or a planner gone before the first board ends the watch with its words', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    planner.routes['GET /projects/demo/events'] = () =>
        _Reply.problem('CredentialRefused', {'repository': 'porelo-store'}, 'refused', 403);

    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect((errors.single as PlanningRefusal).name, 'CredentialRefused');

    await planner.stop();
    final refusal = await _refusal(source.watchStories('demo').first);
    expect(refusal.name, 'PlannerUnreachable', reason: 'a planner gone before any board is said at once');
  });

  // Harm: a planner gone for good leaves a board that looks live and current for ever.
  test('a lost stream not restored within the silence bound ends the watch', () async {
    final (planner, source) = await _started(
      silence: const Duration(milliseconds: 300),
      backoff: const Duration(milliseconds: 50),
      backoffMost: const Duration(milliseconds: 100),
    );
    final streams = _Streams(planner);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    planner.routes['GET /projects/demo/events'] = () => _Reply(503, 'stopping', 'text/plain');

    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect((errors.single as PlanningRefusal).name, 'Http503', reason: 'the last failure, for the board to show');
  });

  // Harm: a redirect takes the token to another address.
  test('a stream answered with a redirect is not followed', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/events'] = () =>
        _Reply.raw(302, const [], headers: {'location': 'http://192.0.2.1/projects/demo/events'});

    final refusal = await _refusal(source.watchStories('demo').first);

    expect(refusal.name, 'Http302');
    expect(planner.targets.where((target) => target.contains('events')), hasLength(1));
  });

  // Harm: a window that ran for a while loses its live board on the first short drop.
  test('a connection lost long after the last loss is opened again', () async {
    final (planner, source) = await _started(silence: const Duration(milliseconds: 300), backoff: Duration.zero);
    final streams = _Streams(planner);
    final errors = <Object>[];
    source.watchStories('demo').listen((_) {}, onError: errors.add);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    await streams.end();
    await _until(() => streams.opened.length == 2, 'the second connection');
    for (var beat = 0; beat < 5; beat++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      streams.opened.last.add(utf8.encode(': keep-alive\n\n'));
    }
    await streams.end();

    await _until(() => streams.opened.length == 3, 'a third connection');
    expect(errors, isEmpty, reason: 'the watch goes on');
  });

  // Harm: a connection that trickles bytes without ever ending a line holds a board that looks live.
  test('a stream that sends no whole line within the silence bound is opened again', () async {
    final (planner, source) = await _started(silence: const Duration(milliseconds: 300), backoff: Duration.zero);
    final streams = _Streams(planner);
    final watching = source.watchStories('demo').listen((_) {});
    addTearDown(watching.cancel);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    final trickle = Timer.periodic(const Duration(milliseconds: 50), (_) {
      if (streams.opened.length == 1) streams.opened.first.add(utf8.encode('x'));
    });
    addTearDown(trickle.cancel);

    await _until(() => streams.opened.length == 2, 'a second connection');
  });

  // Harm: a first read of a large board takes longer than a call may, and the window says the planner did not answer
  // while it reads, with nothing to tell how far it is.
  test('a planner that tells its read is waited for past the wait for a first board, and says how far it is', () async {
    final (planner, source) = await _started(
      timeout: const Duration(milliseconds: 200),
      silence: const Duration(seconds: 2),
      backoff: Duration.zero,
    );
    final streams = _Streams(planner, first: false);
    final watch = source.watchStories('demo') as PlanningWatch;
    final revisions = <String>[];
    final errors = <Object>[];
    final watching = watch.listen((reply) => revisions.add(reply.revision), onError: errors.add);
    addTearDown(watching.cancel);
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    for (var page = 1; page <= 6; page++) {
      streams.opened.last.add(
        _load({
          'state': 'reading',
          'since': '2026-11-21T15:00:00Z',
          'read': page * 100,
          'total': 1384,
          'from': 'GitHub',
          'lastRead': 'porelo-project#200',
        }),
      );
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    final told = watch.loading.value;
    streams.send('r1');
    await _until(() => revisions.isNotEmpty, 'the board after the read');

    expect(errors, isEmpty, reason: 'a planner telling its read is not one that did not answer');
    expect(told?.read, 600);
    expect(told?.total, 1384);
    expect(told?.words, 'Loading the planning from GitHub: 600 of 1,384 entries');
    expect(told?.done, closeTo(600 / 1384, 1e-9));
    expect(watch.loading.value, isNull, reason: 'the board ends the read');
  });

  // Harm: a background read that changed nothing leaves its bar running, since no board ends it.
  test('a read the planner says is done ends its progress without a board', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    final watch = source.watchStories('demo') as PlanningWatch;
    final revisions = <String>[];
    final watching = watch.listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);
    await _until(() => revisions.isNotEmpty, 'the first board');

    streams.opened.last.add(_load({'state': 'reading', 'since': '2026-11-21T15:00:00Z', 'read': 100, 'total': 1384}));
    await _until(() => watch.loading.value != null, 'the read told');
    streams.opened.last.add(_load({'state': 'done'}));
    await _until(() => watch.loading.value == null, 'the read ended');

    expect(revisions, hasLength(1), reason: 'no board came with it');
  });

  // Harm: a background read that failed drops the live board and ends the watch, or is not said at all.
  test('a read that failed after the first board is said, and the watch and its board stay', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    final watch = source.watchStories('demo') as PlanningWatch;
    final revisions = <String>[];
    final errors = <Object>[];
    var done = false;
    final watching = watch.listen(
      (reply) => revisions.add(reply.revision),
      onError: errors.add,
      onDone: () => done = true,
    );
    addTearDown(watching.cancel);
    await _until(() => revisions.isNotEmpty, 'the first board');

    streams.opened.last.add(
      _load({
        'state': 'failed',
        'problem': 'Unreachable',
        'failure': 'Repository porelo-frontend could not be reached: GitHub answered 502.',
        'parameters': {'repository': 'porelo-frontend'},
      }),
    );
    await _until(() => watch.readFailed.value != null, 'the failed read told');

    expect(watch.readFailed.value!.name, 'Unreachable');
    expect(watch.readFailed.value!.parameters['repository'], 'porelo-frontend');
    expect(errors, isEmpty, reason: 'the watch is not ended');
    expect(done, isFalse);
    streams.send('r2');
    await _until(() => revisions.length == 2, 'the next board');
    expect(watch.readFailed.value, isNull, reason: 'the next board ends it');
  });

  // Harm: a read that failed at the forge leaves the window waiting, or says only that the planner did not answer.
  test('a read the planner says failed ends the watch with its words', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner, first: false);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    streams.opened.last.add(_load({'state': 'failed', 'failure': 'GitHub refused the token'}));
    await ended.future.timeout(const Duration(seconds: 5));

    final refusal = errors.single as PlanningRefusal;
    expect(refusal.name, 'LoadFailed');
    expect(refusal.description, contains('GitHub refused the token'));
  });

  // Harm: a missing token found during a long first read is said only in words, and the board offers no token.
  test('a failed read the planner names is the refusal of that name, with its parameters', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner, first: false);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    streams.opened.last.add(
      _load({
        'state': 'failed',
        'problem': 'NoForgeAccess',
        'parameters': {'address': 'https://github.com', 'right': 'read', 'nested': <String, Object>{}},
        'failure': 'no token for github.com',
      }),
    );
    await ended.future.timeout(const Duration(seconds: 5));

    final refusal = errors.single as PlanningRefusal;
    expect(refusal.name, 'NoForgeAccess');
    expect(refusal.parameters, {
      'address': 'https://github.com',
      'right': 'read',
      'failure': 'no token for github.com',
    });
    expect(ForgeAccessOffer.addressOf(refusal), 'https://github.com', reason: 'the board offers the token as before');
  });

  // Harm: a planner that answers the stream and ends it at once is called again for ever, the board looking live.
  test('a stream that ends before its board ends the watch, after the first board too', () async {
    final (planner, source) = await _started(backoff: Duration.zero);
    final streams = _Streams(planner);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    planner.routes['GET /projects/demo/events'] = () =>
        _Reply.raw(200, utf8.encode(': keep-alive\n\n'), headers: {'content-type': 'text/event-stream'});

    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect((errors.single as PlanningRefusal).name, 'BadAnswer');
  });

  // Harm: a board without the project's repositories would offer a new issue only where an issue already stands.
  test('a streamed board without its repositories, or with one that is no name, ends the watch', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    final watched = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    streams.opened.last.add(_event('r1', repositories: null));
    expect((await _refusal(watched)).name, 'BadAnswer', reason: 'no repositories');

    final named = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 1, 'the second stream');
    streams.opened.last.add(_event('r1', repositories: ['../x']));
    expect((await _refusal(named)).name, 'BadAnswer', reason: 'a repository that is no name');

    final read = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 2, 'the third stream');
    streams.opened.last.add(_event('r1'));
    expect((await read).repositories, ['porelo-project', 'porelo-other'], reason: 'read in the planner\'s order');
  });

  // Harm: an issue in a state outside the workflow is dropped from its story, or read in a state it does not hold.
  test('a streamed board\'s issue in a state outside the workflow is read under its story in that state', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    final read = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    streams.opened.last.add(
      _event(
        'r1',
        more: {
          'stories': [
            {
              ..._story('porelo-project#1', 'Backlog'),
              'issues': [_issue('porelo-project#7', 'Accepted')],
            },
          ],
        },
      ),
    );
    final issue = (await read).stories.single.issues.single;
    expect(issue.id, 'porelo-project#7');
    expect(issue.state, 'Accepted');
  });

  // Harm: a board from a planner that sends no list of the issues outside the project is not read, a story made by
  // hand is shown as an issue, or one named into another repository is shown by that name.
  test('a streamed board\'s issues outside the project are read by kind, a board without them reads none, and one '
      'named into another repository ends the watch', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    Map<String, Object?> unlisted(String id, String kind) => {
      'unlisted': [
        {
          'id': id,
          'repository': 'porelo-project',
          'title': 'Made by hand',
          'kind': kind,
          'link': 'https://github.com/porelo-ai/porelo-project/issues/9',
        },
      ],
    };
    final without = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    streams.opened.last.add(_event('r1'));
    expect((await without).unlisted, isEmpty, reason: 'a board without the list reads none');

    final story = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 1, 'the second stream');
    streams.opened.last.add(_event('r1', more: unlisted('porelo-project#9', 'story')));
    final read = (await story).unlisted.single;
    expect(read.id, 'porelo-project#9');
    expect(read.repository, 'porelo-project');
    expect(read.title, 'Made by hand');
    expect(read.story, isTrue, reason: 'its kind is story');
    expect(read.link, 'https://github.com/porelo-ai/porelo-project/issues/9');

    final unknown = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 2, 'the third stream');
    streams.opened.last.add(_event('r1', more: unlisted('porelo-project#9', 'epic')));
    expect((await unknown).unlisted.single.story, isFalse, reason: 'a kind it does not know reads as an issue');

    final bad = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 3, 'the fourth stream');
    streams.opened.last.add(_event('r1', more: unlisted('porelo-other#9', 'issue')));
    expect((await _refusal(bad)).name, 'BadAnswer');
  });

  // Harm: an issue outside the project shown twice, or shown beside the story, issue or issue outside the workflow
  // the board already holds by its id, so the person cannot tell which one they act on.
  test('a streamed board\'s issue outside the project named twice or already on the board ends the watch', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    Map<String, Object?> unlisted(String id) => {
      'id': id,
      'repository': 'porelo-project',
      'title': 'Made by hand',
      'kind': 'issue',
      'link': 'https://github.com/porelo-ai/porelo-project/issues/9',
    };
    final stories = [
      {
        ..._story('porelo-project#1', 'Backlog'),
        'issues': [_issue('porelo-project#2', 'Backlog')],
      },
    ];
    final boards = <String, Map<String, Object?>>{
      'named twice': {
        'unlisted': [unlisted('porelo-project#9'), unlisted('porelo-project#9')],
      },
      'a story': {
        'stories': stories,
        'unlisted': [unlisted('porelo-project#1')],
      },
      'an issue of a story': {
        'stories': stories,
        'unlisted': [unlisted('porelo-project#2')],
      },
    };
    final read = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    streams.opened.last.add(
      _event(
        'r1',
        more: {
          'stories': stories,
          'unlisted': [unlisted('porelo-project#9')],
        },
      ),
    );
    expect((await read).unlisted.single.id, 'porelo-project#9', reason: 'an issue the board holds nowhere else');
    for (final MapEntry(key: name, value: more) in boards.entries) {
      final opened = streams.opened.length;
      final bad = source.watchStories('demo').first;
      await _until(() => streams.opened.length > opened, 'the stream for $name');
      streams.opened.last.add(_event('r1', more: more));
      expect((await _refusal(bad)).name, 'BadAnswer', reason: name);
    }
  });

  // Harm: who opened an issue offered to add never reaches the screen, or a text that is no login is shown as one.
  test('an offered issue\'s author and association are read, and one of no login\'s form ends the watch', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    Map<String, Object?> authored(String login, String association) => {
      'unlisted': [
        {
          'id': 'porelo-project#9',
          'repository': 'porelo-project',
          'title': 'Made by hand',
          'kind': 'issue',
          'link': 'https://github.com/porelo-ai/porelo-project/issues/9',
          'author': login,
          'authorAssociation': association,
        },
      ],
    };
    final read = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    streams.opened.last.add(_event('r1', more: authored('a-person', 'FIRST_TIMER')));
    final issue = (await read).unlisted.single;
    expect(issue.author, 'a-person');
    expect(issue.authorAssociation, 'FIRST_TIMER');

    final bad = source.watchStories('demo').first;
    await _until(() => streams.opened.length > 1, 'the second stream');
    streams.opened.last.add(_event('r1', more: authored('a person\nOWNER', 'NONE')));
    expect((await _refusal(bad)).name, 'BadAnswer');
  });

  // Harm: an id past its bound would go back to the planner as an endless header.
  test('an event id longer than 200 characters ends the watch', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    final watched = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    streams.opened.last.add(utf8.encode('id:${'r' * 201}\n'));

    expect((await _refusal(watched)).name, 'BadAnswer');
  });

  // Harm: a revision at the bound the description allows would end the live board.
  test('a revision of exactly 200 characters is followed as the board\'s id', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    final watched = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    streams.send('f' * 200);

    expect((await watched).revision, 'f' * 200, reason: 'the bound is 200 characters, inclusive');
  });

  // Harm: a refusal too large to read is retried for ever instead of being said.
  test('a 503 answer past the bound ends the watch', () async {
    final (planner, source) = await _started(maxBody: 4096, backoff: Duration.zero);
    final streams = _Streams(planner);
    final errors = <Object>[];
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: errors.add, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    planner.routes['GET /projects/demo/events'] = () => _Reply(503, 'x' * 5000, 'text/plain');

    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect((errors.single as PlanningRefusal).name, 'AnswerTooLarge');
  });

  // Harm: the window learns its planner is gone long after the bound it was promised.
  test('the wait before the last attempt never runs past the silence bound', () async {
    final (planner, source) = await _started(
      silence: const Duration(milliseconds: 300),
      backoff: const Duration(milliseconds: 250),
      backoffMost: const Duration(seconds: 2),
    );
    final streams = _Streams(planner);
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: (_) {}, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    planner.routes['GET /projects/demo/events'] = () => _Reply(503, 'stopping', 'text/plain');

    final lost = DateTime.now();
    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect(DateTime.now().difference(lost).inMilliseconds, lessThan(600), reason: 'the 300 ms bound and one attempt');
  });

  // Harm: a large retries factor stretching the reconnect wait past the silence it waits within, so a stream lost for
  // a moment is never opened again before the board is told as gone.
  test('a stream lost under a large retries factor is opened again within the silence', () async {
    final planner = _Planner();
    await planner.start();
    addTearDown(planner.stop);
    final source = HttpPlanningSource(
      planner.base,
      _token,
      (project) => PlannerProject('https://example.org/$project.git', 'main'),
      silence: const Duration(milliseconds: 600),
      limits: PlanningLimits(retries: 100),
    );
    addTearDown(source.close);
    final streams = _Streams(planner);
    final ended = Completer<void>();
    source.watchStories('demo').listen((_) {}, onError: (_) {}, onDone: ended.complete);
    await _until(() => streams.opened.isNotEmpty, 'the stream');
    final again = <DateTime>[];
    planner.routes['GET /projects/demo/events'] = () {
      again.add(DateTime.now());
      return _Reply(503, 'stopping', 'text/plain');
    };

    final lost = DateTime.now();
    await streams.end();
    await ended.future.timeout(const Duration(seconds: 5));

    expect(source.backoff, const Duration(seconds: 100), reason: 'the first wait times the retries factor');
    expect(again, isNotEmpty, reason: 'the stream opened again');
    expect(
      again.first.difference(lost).inMilliseconds,
      lessThan(500),
      reason: 'the first try again well within the 600 ms silence',
    );
  });

  // Harm: the window's factors never reaching the limits of the planner's calls.
  test('the limits of every factory follow their groups\' factors', () async {
    final limits = PlanningLimits(services: 2, retries: 0.5);
    final source = HttpPlanningSource(
      Uri.parse('http://127.0.0.1:9'),
      _token,
      (project) => PlannerProject('https://example.org/$project.git'),
      limits: limits,
    );
    addTearDown(source.close);
    final at = HttpPlanningSource.at(
      PlannerAddress(9, _token),
      (project) => PlannerProject('https://example.org/$project.git'),
      limits: limits,
    );
    addTearDown(at.close);
    final runtime = await _ownRuntime();
    final file = File('${runtime.path}/porelo-planner/planner.json');
    await file.writeAsString('{"port": 9, "token": "$_token"}\n');
    await Process.run('chmod', ['600', file.path]);
    final read = await HttpPlanningSource.fromAddressFile(
      (project) => PlannerProject('https://example.org/$project.git'),
      runtimeDirectory: runtime.path,
      limits: limits,
    );
    addTearDown(read.close);

    for (final made in [source, at, read]) {
      expect(made.limits, limits, reason: 'the factors each board made from it follows');
      expect(made.timeout, const Duration(seconds: 60), reason: 'a request, times services');
      expect(made.proofTimeout, const Duration(seconds: 120), reason: 'the proof, times services');
      expect(made.writeTimeout, const Duration(minutes: 4), reason: 'a write, times services');
      expect(made.backoff, const Duration(milliseconds: 500), reason: 'the first wait, times retries');
      expect(made.backoffMost, const Duration(seconds: 15), reason: 'the longest wait, times retries');
      expect(made.silence, const Duration(seconds: 45), reason: 'built on the planner\'s keep-alive, fixed');
      expect(made.loadStale, const Duration(seconds: 20), reason: 'built on the planner\'s progress, fixed');
    }
  });

  // Harm: the window's own call to the planner, an idea or a stage result, held at the default limits on a computer
  // whose factors ask for shorter ones, or longer, so the person waits past what they set or is cut off before it.
  test('the window\'s call waits for its proof and its answer as long as the services factor says', () async {
    final limits = PlanningLimits(services: 0.1);
    final planner = _Planner();
    await planner.start();
    addTearDown(planner.stop);
    final held = Completer<_Reply>();
    addTearDown(() => held.complete(_Reply(204)));
    planner.routes['POST /projects/demo/moves'] = () => held.future;

    final unanswered = await _refusal(
      callPlanner(
        'POST',
        const ['projects', 'demo', 'moves'],
        json: const {'move': 'm'},
        address: () async => PlannerAddress(planner.base.port, _token),
        timeout: limits.servicesOf(const Duration(milliseconds: 2200)),
        limits: limits,
      ),
    );
    expect(unanswered.name, 'TimedOut', reason: 'the planner proved itself, then held its answer');
    expect(
      unanswered.description,
      contains('within 0.22 s'),
      reason: 'the fractional limit the request kept, so the timeout can be diagnosed',
    );

    // A listener that never answers its proof.
    final silent = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requests = <HttpRequest>[];
    silent.listen(requests.add);
    addTearDown(() => silent.close(force: true));
    final started = DateTime.now();
    final unproven = await _refusal(
      callPlanner(
        'GET',
        const ['projects', 'demo', 'stories'],
        address: () async => PlannerAddress(silent.port, _token),
        limits: limits,
      ),
    ).timeout(const Duration(seconds: 20));
    expect(unproven, isA<PlannerNotProven>(), reason: 'the listener never proved itself');
    expect(
      DateTime.now().difference(started).inSeconds,
      inInclusiveRange(5, 15),
      reason: 'a tenth of the 60 s proof limit',
    );
  }, timeout: const Timeout(Duration(seconds: 40)));

  // Harm: one endless event fills the window's memory.
  test('an event past the bound ends the watch', () async {
    final (planner, source) = await _started(maxBody: 4096);
    final streams = _Streams(planner, first: false);
    final watched = source.watchStories('demo').first;
    await _until(() => streams.opened.isNotEmpty, 'the stream');

    streams.opened.last.add(utf8.encode('event:board\ndata:'));
    for (var chunk = 0; chunk < 8; chunk++) {
      streams.opened.last.add(List.filled(1024, 120));
    }

    expect((await _refusal(watched)).name, 'AnswerTooLarge');
  });

  // Harm: a board the window closed keeps a connection and calls the planner for ever.
  test('a watch cancelled closes its connection and calls no more', () async {
    // A short silence bound, so a watch left running would open the stream again within the wait below.
    final (planner, source) = await _started(silence: const Duration(milliseconds: 100), backoff: Duration.zero);
    final streams = _Streams(planner);
    final revisions = <String>[];
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
    await _until(() => revisions.isNotEmpty, 'the first board');

    await watching.cancel();
    // The server notices a closed connection when it writes, as the planner's keep-alive does.
    streams.opened.last.add(utf8.encode(': keep-alive\n\n'));
    await _until(() => planner.connections == 0, 'the connection closed');
    final calls = planner.calls.length;
    await Future<void>.delayed(const Duration(milliseconds: 300));

    expect(planner.calls.length, calls, reason: 'no reconnect after the cancel');
    expect(streams.opened, hasLength(1));
  });

  // Harm: an item a tracker keeps cannot be opened, or its '#' cuts the path into a fragment the planner never sees.
  test('a story and an issue named by a tracker reference are asked by their encoded path', () async {
    final (planner, source) = await _started();
    final carried = _issue('repo#8', 'Backlog');
    planner.routes['GET /projects/demo/stories/repo%237'] = () => _Reply(200, {
      'revision': 'r1',
      'story': {
        ..._story('repo#7', 'Backlog'),
        'issues': [carried],
        'description': '',
      },
    });
    planner.routes['GET /projects/demo/issues/repo%238'] = () => _Reply(200, {
      'revision': 'r1',
      'issue': {...carried, 'text': '', 'subIssues': <String>[]},
    });

    final story = await source.getStory('demo', 'repo#7');
    final issue = await source.getIssue('demo', 'repo#8');

    expect(planner.targets, containsAll(['GET /projects/demo/stories/repo%237', 'GET /projects/demo/issues/repo%238']));
    expect(story.story?.issues.single.id, 'repo#8', reason: 'the story\'s issue under its reference');
    expect(issue.issue?.id, 'repo#8', reason: 'the issue under its reference');
    expect(
      () => source.getIssue('demo', 'repo#08'),
      throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'InvalidName')),
      reason: 'a reference with a leading zero is no id and is never sent',
    );
  });

  // Harm: a list read from the wrong path or with fields lost hides issues or changes their meaning.
  test('issues in no story are requested by query and retain every issue list field', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/issues'] = () => _Reply(200, {
      'revision': 'r7',
      'issues': [
        {
          ..._issue('B900', 'Backlog'),
          'size': 'S',
          'blockedBy': ['PR1'],
          'parent': 'PR2',
          'link': 'https://example.org/issues/B900',
          'worker': 'AGENT',
          'rounds': 2,
          'roundsMost': 4,
          'atWork': true,
          'needsYou': true,
          'readingStopped': true,
          'noRunHere': false,
          'attention': 'STOPPED',
          'findings': [
            {'number': 1, 'severity': 'BLOCKING', 'text': 'Fix the save', 'file': 'lib/board.dart', 'line': 8},
          ],
        },
        {..._issue('PR3', 'Finished'), 'worker': 'MANUAL'},
        {..._issue('PR4', 'Building'), 'noRunHere': true},
      ],
    });
    final list = await source.listIssues('demo');
    expect(planner.targets, contains('GET /projects/demo/issues?story=none'));
    expect(list.revision, 'r7');
    expect(list.reported, isTrue);
    expect(list.supplied, isTrue);
    expect(list.issues.map((issue) => issue.id), ['B900', 'PR3', 'PR4']);
    final issue = list.issues.first;
    expect(issue.repository, 'repo');
    expect(issue.title, 'Issue B900');
    expect(issue.state, 'Backlog');
    expect(issue.size, 'S');
    expect(issue.blockedBy, ['PR1']);
    expect(issue.blockedByReported, isTrue);
    expect(issue.parent, 'PR2');
    expect(issue.link, 'https://example.org/issues/B900');
    expect(issue.worker, IssueWorker.agent);
    expect(issue.rounds, 2);
    expect(issue.roundsMost, 4);
    expect(issue.atWork, isTrue);
    expect(issue.needsYou, isTrue);
    expect(issue.readingStopped, isTrue);
    expect(issue.noRunHere, isFalse);
    expect(list.issues[1].worker, IssueWorker.manual);
    expect(list.issues[2].noRunHere, isTrue);
    expect(issue.attention, 'STOPPED');
    final finding = issue.findings.single;
    expect(finding.number, 1);
    expect(finding.severity, 'BLOCKING');
    expect(finding.text, 'Fix the save');
    expect(finding.file, 'lib/board.dart');
    expect(finding.line, 8);
  });

  // Harm: an append writes a different story or drops the revision that protects its other issues.
  test('setting a story issue list sends the whole list and board revision to the encoded story path', () async {
    final (planner, source) = await _started();
    planner.routes['PUT /projects/demo/stories/repo%237/issues'] = () => _Reply(200, {'revision': 'r2'});
    expect(await source.setStoryIssues('demo', 'repo#7', ['PR1', 'B900'], _seen), 'r2');
    final call = planner.calls.last;
    expect(call.method, 'PUT');
    expect(call.target, '/projects/demo/stories/repo%237/issues');
    expect(jsonDecode(call.body), {
      'seen': _seen,
      'issues': ['PR1', 'B900'],
    });
  });

  // Harm: an add writes another issue, drops the revision that keeps a person's change since, or sends a fixed alias
  // the planner cannot add.
  test('an add of an issue made outside the project sends its reference and the revision, and no alias', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/items'] = () => _Reply(200, {'revision': 'r2'});
    expect(await source.addUnlistedIssue('demo', 'porelo-lib#7', _seen), 'r2');
    final call = planner.calls.last;
    expect(call.method, 'POST');
    expect(call.target, '/projects/demo/items');
    expect(jsonDecode(call.body), {'issue': 'porelo-lib#7', 'seen': _seen});

    final calls = planner.calls.length;
    await expectLater(source.addUnlistedIssue('demo', 'PL4', _seen), throwsA(isA<PlanningRefusal>()));
    expect(planner.calls, hasLength(calls), reason: 'an alias is refused before it is sent');
  });

  // Harm: a story, its issues or an issue reach the screen without their text, state or criteria.
  test('a story, its issues and an issue arrive as the screen\'s records', () async {
    final (_, source) = await _started();

    final story = await source.getStory('demo', 'ST1');
    final issues = await source.listStoryIssues('demo', 'ST1');
    final issue = await source.getIssue('demo', 'PR1');

    expect(story.story?.description, 'The story text.');
    expect(story.revision, 'r1');
    expect(issues.reported, isTrue, reason: 'the issue list is complete');
    expect([for (final each in issues.issues) '${each.id}:${each.state}'], ['PR1:Backlog', 'PR2:Finished']);
    expect(issue.issue?.size, 'S');
    expect(issue.whatMustBeTrue, ['It works.'], reason: 'the criteria reach the issue view');
  });

  // Harm: an answer the contract does not allow is shown as if sound.
  // Harm: a planner older than the screen leaves the person without a board.
  test('an issue answered with a worker the description does not name is refused, one without any is read', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner, first: false);
    final changes = <String, void Function(Map<String, dynamic>)>{
      'no worker': (issue) => issue.remove('worker'),
      'a null worker': (issue) => issue['worker'] = null,
      'a worker in lower case': (issue) => issue['worker'] = 'agent',
      'an unknown worker': (issue) => issue['worker'] = 'NOBODY',
      'a worker that is no text': (issue) => issue['worker'] = 1,
    };
    for (final MapEntry(key: what, value: change) in changes.entries) {
      Map<String, dynamic> changed(Map<String, dynamic> issue) {
        change(issue);
        return issue;
      }

      Map<String, dynamic> issue(String id, String state) => changed(_issue(id, state));
      final story = {
        ..._story('ST1', 'Backlog'),
        'issues': [issue('PR1', 'Backlog')],
      };
      planner.routes['GET /projects/demo/stories'] = () => _Reply(200, {
        'revision': 'r1',
        'stories': [story],
      });
      planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply(200, {
        'revision': 'r1',
        'story': {...story, 'description': 'The story text.'},
      });
      planner.routes['GET /projects/demo/stories/ST1/issues'] = () => _Reply(200, {
        'revision': 'r1',
        'issues': [_issue('PR2', 'Finished'), issue('PR1', 'Backlog')],
      });
      planner.routes['GET /projects/demo/issues/PR1'] = () => _Reply(200, {
        'revision': 'r1',
        'issue': {...issue('PR1', 'Backlog'), 'text': 'The issue text.', 'subIssues': <String>[]},
      });
      planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, {
        'revision': 'r2',
        'issue': changed(servedReadingIssue(stopped: false)),
        'work': servedContinuationWork(),
        'tasks': <Map<String, dynamic>>[],
      });
      final absent = what == 'no worker';
      final calls = <String, Future<IssueWorker?> Function()>{
        'the board': () async => (await source.stories('demo')).stories.single.issues.single.worker,
        'a story': () async => (await source.getStory('demo', 'ST1')).story!.issues.single.worker,
        'a story\'s issues': () async => (await source.listStoryIssues('demo', 'ST1')).issues.last.worker,
        'an issue': () async => (await source.getIssue('demo', 'PR1')).issue!.worker,
        'an issue read for its work': () async => (await source.getPlanningIssue('demo', 'PR1')).issue.worker,
        'a reading choice': () async =>
            (await source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'REVIEW_ANYWAY', _seen)).issue!.worker,
      };
      for (final MapEntry(key: call, value: answer) in calls.entries) {
        if (absent) {
          expect(await answer(), isNull, reason: '$call answered with $what is read, its worker unknown');
        } else {
          expect((await _refusal(answer())).name, 'BadAnswer', reason: '$call answered with $what is refused');
        }
      }
      final watched = source.watchStories('demo').first;
      await _until(() => streams.opened.length == changes.keys.toList().indexOf(what) + 1, 'the stream');
      streams.opened.last.add(
        utf8.encode(
          'event:board\nid:r1\ndata:${jsonEncode({
            ..._workflow('r1'),
            'stories': [story],
            'repositories': ['porelo-project'],
          })}\n\n',
        ),
      );
      if (absent) {
        expect(
          (await watched).stories.single.issues.single.worker,
          isNull,
          reason: 'a streamed board with $what is read, its worker unknown',
        );
      } else {
        expect((await _refusal(watched)).name, 'BadAnswer', reason: 'a streamed board with $what ends the watch');
      }
    }
  });

  // Harm: a refusal reaches the screen without its name, so the screen cannot say what the planner said.
  test('a problem answer arrives as a refusal with its name, parameters and detail', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST9'] = () =>
        _Reply.problem('NoSuchStory', {'story': 'ST9'}, 'The project has no such story.', 404);

    final refusal = await _refusal(source.getStory('demo', 'ST9'));

    expect(refusal.name, 'NoSuchStory');
    expect(refusal.parameters, {'story': 'ST9'});
    expect(refusal.description, 'The project has no such story.');
  });

  // Harm: an answer that is no refusal of the planner's, such as a proxy's page, is shown in full or taken as one.
  test('another failed answer is refused by its status, with a bounded excerpt of its body', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply(502, 'x' * 100000, 'text/html');

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'Http502');
    expect(refusal.description.length, lessThan(300), reason: 'the body is not echoed beyond a bound');
  });

  // Harm: a planner that is gone leaves the screen waiting, or fails with an error the screen cannot name.
  test('a planner that does not run is refused as unreachable', () async {
    final (planner, source) = await _started();
    await planner.stop();

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'PlannerUnreachable', reason: 'apart from the planner\'s own Unreachable for a Git remote');
  });

  // Harm: a planner that never answers hangs the screen.
  test('a planner that does not answer in time is refused', () async {
    final (planner, source) = await _started(timeout: const Duration(milliseconds: 300));
    final held = Completer<_Reply>();
    addTearDown(() => held.complete(_Reply(204)));
    planner.routes['PUT /projects/demo'] = () => held.future;

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'TimedOut');
  });

  // Harm: a save that never left the window is told as still saving, and the person leaves a lost change behind.
  test('a planner that takes no connection in time is refused as unreachable, not as unanswered', () async {
    // A listener that never accepts, its queue filled, so the next connection waits in the handshake.
    final silent = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0, backlog: 1);
    addTearDown(silent.close);
    final waiting = <Socket>[];
    addTearDown(() {
      for (final socket in waiting) {
        socket.destroy();
      }
    });
    for (var i = 0; i < 4; i++) {
      try {
        waiting.add(await Socket.connect(silent.address, silent.port, timeout: const Duration(milliseconds: 200)));
      } on SocketException {
        break;
      }
    }
    final source = HttpPlanningSource(
      Uri.parse('http://127.0.0.1:${silent.port}'),
      _token,
      (project) => PlannerProject('https://example.org/$project.git', 'main'),
      timeout: const Duration(milliseconds: 300),
    );
    addTearDown(source.close);

    final refusal = await _refusal(
      source.saveMoves('demo', const [PlanningStoryMove('ST1', 'Finished')], _seen, digest: _digest),
    );

    expect(refusal.name, 'PlannerUnreachable', reason: 'nothing was sent, so nothing can still be saving');
  });

  // Harm: an endless answer fills the window's memory.
  test('an answer larger than the bound is refused', () async {
    final (_, source) = await _started(maxBody: 64);

    final refusal = await _refusal(source.stories('demo'));

    expect(refusal.name, 'AnswerTooLarge');
  });

  // Harm: the token leaks into a header, a URL, a body, a log or an error text, where other programs or people read it.
  test('the token is never sent, only signed under', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST9'] = () =>
        _Reply.problem('NoSuchStory', {'story': 'ST9'}, 'The project has no such story.', 404);

    await source.stories('demo');
    final refusal = await _refusal(source.getStory('demo', 'ST9'));

    expect(planner.calls, isNotEmpty);
    for (final call in planner.calls) {
      expect(call.signed, isTrue, reason: 'every call is signed under the token');
      expect(call.authorization, isNull, reason: 'no call carries an Authorization header');
      expect(call.target, isNot(contains(_token)), reason: 'no URL carries the token');
      expect(call.body, isNot(contains(_token)), reason: 'no body carries the token');
    }
    expect(planner.signing.proofBodies, isNotEmpty, reason: 'the planner was proven');
    expect(planner.signing.proofBodies.join(), isNot(contains(_token)), reason: 'no proof carries the token');
    expect(source.toString(), isNot(contains(_token)));
    expect(PlannerAddress(8080, _token).toString(), isNot(contains(_token)));
    expect(refusal.toString(), isNot(contains(_token)));
  });

  // Harm: no run is confused with a damaged record, or Stop clears activity before a runtime report.
  test('run reads and stop use work envelopes and empty request bodies', () async {
    final (planner, source) = await _started();
    final work = servedWork()..['tasks'] = [servedRunTask()];
    planner.routes['GET /projects/demo/stories/ST1/work'] = () => _Reply(200, {'work': null});
    expect((await source.getStoryWork('demo', 'ST1')).work, isNull);
    planner.routes['GET /projects/demo/stories/ST1/work'] = () => _Reply(200, {'work': work});
    planner.routes['POST /projects/demo/stories/ST1/work/stop'] = () => _Reply(200, {'work': work});
    final running = (await source.getStoryWork('demo', 'ST1')).work!;
    expect(running.tasks.single.atWork, isTrue);
    expect(running.tasks.single.machine, 'Machine 🚀');
    final stopped = await source.stopStoryWork('demo', 'ST1');
    expect(stopped.work!.tasks.single.atWork, isTrue);
    expect(planner.targets, [
      'PUT /projects/demo',
      'GET /projects/demo/stories/ST1/work',
      'GET /projects/demo/stories/ST1/work',
      'POST /projects/demo/stories/ST1/work/stop',
    ]);
    expect(planner.calls.skip(1).map((call) => call.body), everyElement(isEmpty));
  });

  // Harm: the window and the planner read a row's Start or Stop differently, so work starts over another run.
  test('a row\'s Start sends its pass-over and the run it replaces, and Stop names its run', () async {
    final (planner, source) = await _started();
    const run = '00000000-0000-4000-9000-000000000001';
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, servedOrder());
    planner.routes['POST /projects/demo/stories/ST1/work/stop'] = () => _Reply(200, {'work': servedWork()});
    await source.startStoryWork(
      StoryWorkRequest(
        project: 'demo',
        story: 'ST1',
        to: 'Building',
        mode: 'AGENT',
        seen: const {'ST1': servedVersion},
        subAgents: const [],
        replaces: run,
        passOver: const PlanningPassOver(issue: 'PR1', reason: 'STOPPED', run: run),
      ),
    );
    final order = jsonDecode(planner.calls.last.body) as Map;
    expect(order['replaces'], run, reason: 'the order names the run it replaces');
    expect(order['passOver'], {
      'issue': 'PR1',
      'reason': 'STOPPED',
      'run': run,
    }, reason: 'the order names the reason it passes over as the row showed it');
    await source.stopStoryWork('demo', 'ST1', run: run);
    expect(jsonDecode(planner.calls.last.body), {'run': run}, reason: 'the stop names the run it ends');
  });

  // Harm: a malformed work envelope is accepted as no run or as a successful cancellation.
  test('work reads and stops refuse malformed success answers', () async {
    final (planner, source) = await _started();
    for (final body in <Object?>[
      <String, dynamic>{},
      {'work': false},
      for (final field in [
        'tasks',
        'atWork',
        'repositories',
        'issues',
        'issueReadingRounds',
        'coordinator',
        'since',
        'comment',
        'readingRounds',
      ])
        {'work': servedWork()..remove(field)},
      for (final field in ['task', 'containerId', 'machine', 'atWork', 'ended'])
        {
          'work': servedWork()..['tasks'] = [servedRunTask()..remove(field)],
        },
      {
        'work': servedWork()..['tasks'] = [servedRunTask()..['task'] = '../foreign'],
      },
      {'work': <String, dynamic>{}},
      {
        'work': {
          'atWork': false,
          'tasks': [false],
        },
      },
      {'work': servedWork(), 'type': null},
      {'work': servedWork(), 'error': null},
    ]) {
      planner.routes['GET /projects/demo/stories/ST1/work'] = () => _Reply(200, body);
      planner.routes['POST /projects/demo/stories/ST1/work/stop'] = () => _Reply(200, body);
      expect((await _refusal(source.getStoryWork('demo', 'ST1'))).name, 'BadAnswer');
      expect((await _refusal(source.stopStoryWork('demo', 'ST1'))).name, 'BadAnswer');
    }
    planner.routes['GET /projects/demo/stories/ST1/work'] = () => _Reply(200, {'work': null}, 'text/plain');
    expect((await _refusal(source.getStoryWork('demo', 'ST1'))).name, 'BadAnswer');
  });

  // Harm: stopping a story with no recorded run is reported as a failed cancellation.
  test('stop accepts the planner null work answer for an unrecorded run', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/work/stop'] = () => _Reply(200, {'work': null});
    expect((await source.stopStoryWork('demo', 'ST1')).work, isNull);
    expect(planner.calls.last.method, 'POST');
    expect(planner.calls.last.target, '/projects/demo/stories/ST1/work/stop');
  });

  // Harm: findings from another issue or an omitted report authorize the wrong reading decision.
  test('reading findings retain the issue round and revision and leave bad findings unknown', () async {
    final (planner, source) = await _started();
    final issue = servedReadingIssue();
    planner.routes['GET /projects/demo/issues/PR1'] = () => _Reply(200, {'revision': 'r1', 'issue': issue});
    final detail = await source.getPlanningIssue('demo', 'PR1');
    expect(
      [detail.revision, detail.issue.id, detail.issue.rounds, detail.issue.readingStopped],
      ['r1', 'PR1', 2, true],
    );
    expect(detail.findingsReported, isTrue);
    expect(detail.findings.single.severity, 'BLOCKING');
    expect(planner.calls.last.body, isEmpty);
    for (final findings in <Object?>[
      null,
      false,
      [false],
      [
        {'severity': 'BLOCKING'},
      ],
    ]) {
      planner.routes['GET /projects/demo/issues/PR1'] = () => _Reply(200, {
        'revision': 'r1',
        'issue': {...issue, 'findings': findings},
      });
      expect((await source.getPlanningIssue('demo', 'PR1')).findingsReported, isFalse);
    }
    for (final body in <Map<String, dynamic>>[
      {'revision': 'r1'},
      {
        'revision': 'r1',
        'issue': {...issue, 'id': 'PR2'},
      },
      {'issue': issue},
      {'revision': ' ', 'issue': issue},
    ]) {
      planner.routes['GET /projects/demo/issues/PR1'] = () => _Reply(200, body);
      expect((await _refusal(source.getPlanningIssue('demo', 'PR1'))).name, 'BadAnswer');
    }
  });

  // Harm: the decision sent does not name the reviewed story, issue, round, revision or rework instructions.
  test('all reading choices send the served request and return checked follow-ups', () async {
    final (planner, source) = await _started();
    for (final choice in ['REVIEW_ANYWAY', 'ONE_MORE_ROUND', 'REWORK']) {
      final tasks = [if (choice != 'REVIEW_ANYWAY') servedFollowUp(10, role: choice == 'REWORK' ? 'author' : 'reader')];
      planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, {
        'revision': 'r2',
        'issue': servedReadingIssue(stopped: false),
        'work': servedContinuationWork(),
        'tasks': tasks,
      });
      final answer = await source.resolveIssueReading(
        'demo',
        'ST1',
        'PR1',
        2,
        choice,
        _seen,
        comment: choice == 'REWORK' ? '  Keep the key inside.\n' : null,
      );
      expect(jsonDecode(planner.calls.last.body), {
        'story': 'ST1',
        'round': 2,
        'choice': choice,
        'comment': choice == 'REWORK' ? '  Keep the key inside.\n' : '',
        'seen': _seen,
      });
      expect(planner.calls.last.target, '/projects/demo/issues/PR1/reading');
      expect(answer.tasks.length, choice == 'REVIEW_ANYWAY' ? 0 : 1);
      expect(answer.revision, 'r2');
    }
  });

  // Harm: one invalid follow-up starts alongside a valid one or a refused decision appears accepted.
  test('reading replies are refused whole before any follow-up is accepted', () async {
    final (planner, source) = await _started();
    final changes = <void Function(Map<String, dynamic>)>[
      for (final field in ['revision', 'work', 'issue', 'tasks']) (answer) => answer.remove(field),
      (answer) => answer['revision'] = ' ',
      (answer) => answer['issue'] = servedReadingIssue(stopped: false)..['id'] = 'PR2',
      (answer) => answer['issue'] = servedReadingIssue(),
      (answer) => answer['tasks'] = [false],
      (answer) => (answer['tasks'] as List).add((answer['tasks'] as List).first),
      for (final field in ['offer', 'repository', 'issues', 'role', 'prompt'])
        (answer) => ((answer['tasks'] as List).last as Map).remove(field),
      (answer) => ((answer['tasks'] as List).last as Map)['repository'] = 'foreign',
      (answer) => ((answer['tasks'] as List).last as Map)['issues'] = ['PR9'],
      (answer) => ((answer['tasks'] as List).last as Map)['role'] = 'FOREIGN',
    ];
    for (final (index, change) in changes.indexed) {
      final answer = {
        'revision': 'r2',
        'work': servedContinuationWork(),
        'issue': servedReadingIssue(stopped: false),
        'tasks': [servedFollowUp(10)],
      };
      change(answer);
      planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, answer);
      expect(
        (await _refusal(source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', _seen))).name,
        'BadAnswer',
        reason: 'mutation $index',
      );
    }
    planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, {
      'revision': 'r2',
      'work': servedContinuationWork(),
      'issue': servedReadingIssue(stopped: false),
      'tasks': [servedFollowUp(10)],
    });
    expect(
      (await _refusal(source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'REVIEW_ANYWAY', _seen))).name,
      'BadAnswer',
    );
  });

  // Harm: a reading response authorizes a different worker or issue than the recorded decision.
  test('HTTP reading decisions refuse mismatched roles and decided issues', () async {
    final (planner, source) = await _started();
    for (final (choice, task) in [
      ('ONE_MORE_ROUND', servedFollowUp(10, role: 'author')),
      ('ONE_MORE_ROUND', servedFollowUp(10)..['issues'] = ['PR1.1']),
      ('REWORK', servedFollowUp(10)),
      ('REWORK', servedFollowUp(10, role: 'author')..['issues'] = ['PR1.1']),
      ('REVIEW_ANYWAY', servedFollowUp(10)),
    ]) {
      planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, {
        'revision': 'r2',
        'work': servedContinuationWork(anotherAtWork: true),
        'issue': servedReadingIssue(stopped: false),
        'tasks': [task],
      });
      expect(
        (await _refusal(
          source.resolveIssueReading('demo', 'ST1', 'PR1', 2, choice, _seen, comment: 'Fix the finding.'),
        )).name,
        'BadAnswer',
        reason: '$choice $task',
      );
    }
  });

  // Harm: task reports lose Porelo's original end record or use the visible machine name as a URL component.
  test('task events send exact names and the unmapped end record', () async {
    final (planner, source) = await _started();
    final machine = '  Machine / 🚀 ${'🌍' * 110}  ';
    const ended = PlanningTaskEnded(
      at: '2026-01-01T00:00:00Z',
      finished: true,
      why: '  Raw why.\n',
      answer: 'é\n{answer}',
      unpushed: 3,
      landed: [_landing],
    );
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () => _Reply(200, {
      'revision': 'r1',
      'work': servedContinuationWork(),
      'tasks': [servedFollowUp(10)],
    });
    for (final event in PlanningTaskEvent.values.where((event) => event != PlanningTaskEvent.refused)) {
      final answer = await source.taskEvent(
        'demo',
        'main-task',
        event,
        offer: servedOffer(1),
        container: 'container-main-task',
        machine: machine,
        ended: event == PlanningTaskEvent.ended ? ended : null,
        landed: event == PlanningTaskEvent.landed ? const [_landing] : const [],
      );
      expect(jsonDecode(planner.calls.last.body), {
        'offer': servedOffer(1),
        'event': event.name.toUpperCase(),
        'container': 'container-main-task',
        'machine': machine,
        if (event == PlanningTaskEvent.ended)
          'ended': {
            ...ended.toMap(),
            'landed': [_landingMap],
          },
        if (event == PlanningTaskEvent.landed) 'landed': [_landingMap],
      }, reason: 'Porelo\'s landing reaches the planner unchanged, or the reader is never offered');
      expect(planner.calls.last.target, '/projects/demo/tasks/main-task/events');
      expect(answer.tasks.map((task) => task.offer), [servedOffer(10)]);
    }
  });

  // Harm: a refused Start reported with a container, or without Porelo's name and words, or a refusal riding on
  // another event, reaches the planner as a shape fault or is read as a started task.
  test('a refused Start travels with Porelo\'s refusal and no container, and no other event carries one', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () =>
        _Reply(200, {'revision': 'r1', 'work': servedContinuationWork(), 'tasks': <dynamic>[]});
    const refusal = PlanningStartRefusal(
      name: 'AdapterNotInstalled',
      parameters: {'adapter': 'maven'},
      detail: 'Install it.\nThen retry.',
    );

    await source.taskEvent(
      'demo',
      'main-task',
      PlanningTaskEvent.refused,
      offer: servedOffer(1),
      container: '',
      machine: 'Machine',
      refusal: refusal,
    );

    expect(jsonDecode(planner.calls.last.body), {
      'offer': servedOffer(1),
      'event': 'REFUSED',
      'machine': 'Machine',
      'refusal': {
        'name': 'AdapterNotInstalled',
        'parameters': {'adapter': 'maven'},
        'detail': 'Install it.\nThen retry.',
      },
    }, reason: 'no container, since Porelo started none');
    final calls = planner.calls.length;
    for (final (event, container, given) in [
      (PlanningTaskEvent.refused, '', null),
      (PlanningTaskEvent.refused, 'container-main-task', refusal),
      (PlanningTaskEvent.started, 'container-main-task', refusal),
      (PlanningTaskEvent.ended, 'container-main-task', refusal),
      (PlanningTaskEvent.stopped, '', null),
    ]) {
      await expectLater(
        source.taskEvent(
          'demo',
          'main-task',
          event,
          offer: servedOffer(1),
          container: container,
          machine: 'Machine',
          refusal: given,
        ),
        throwsArgumentError,
        reason: '$event with container "$container" and ${given == null ? 'no refusal' : 'a refusal'}',
      );
    }
    expect(planner.calls.length, calls, reason: 'a refused report reaches no planner');
  });

  // Harm: a refusal the planner refuses by its bounds is sent anyway, so every report of the offer answers 400 and the
  // issue never says why nothing ran.
  test('a refusal beyond the planner\'s bounds is refused before it is sent, and one within them travels', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () =>
        _Reply(200, {'revision': 'r1', 'work': servedContinuationWork(), 'tasks': <dynamic>[]});
    Future<void> report(PlanningStartRefusal refusal) => source.taskEvent(
      'demo',
      'main-task',
      PlanningTaskEvent.refused,
      offer: servedOffer(1),
      container: '',
      machine: 'Machine',
      refusal: refusal,
    );
    const astral = '\u{1F600}';
    final refused = <String, PlanningStartRefusal>{
      'a name with a dash': const PlanningStartRefusal(name: 'a-b'),
      'an empty name': const PlanningStartRefusal(name: ''),
      '17 parameters': PlanningStartRefusal(name: 'Refused', parameters: {for (var i = 0; i < 17; i++) 'k$i': 'v'}),
      'an empty key': const PlanningStartRefusal(name: 'Refused', parameters: {'': 'v'}),
      'a 501-rune value': PlanningStartRefusal(name: 'Refused', parameters: {'k': astral * 501}),
      'a 1001-rune detail': PlanningStartRefusal(name: 'Refused', detail: 'x' * 1001),
      'a control character': const PlanningStartRefusal(name: 'Refused', detail: 'a\u0007b'),
      'a line break in a value': const PlanningStartRefusal(name: 'Refused', parameters: {'k': 'a\nb'}),
      'a paragraph separator': const PlanningStartRefusal(name: 'Refused', detail: 'a\u2029b'),
    };
    final calls = planner.calls.length;
    for (final MapEntry(:key, :value) in refused.entries) {
      await expectLater(
        report(value),
        throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'InvalidSettings')),
        reason: '$key, refused as the planner answers it',
      );
    }
    expect(planner.calls.length, calls, reason: 'none reaches the planner');

    await report(PlanningStartRefusal(name: 'Refused', parameters: {'k': astral * 500}, detail: 'Line one.\r\nTwo.'));
    expect(
      planner.calls.skip(calls).map((call) => call.target),
      contains('/projects/demo/tasks/main-task/events'),
      reason: '500 code points and a line break in the detail travel',
    );
  });

  // Harm: a refusal at the planner's own bounds is refused here and never reported, or one past them is sent and every
  // report of the offer answers 400.
  test('a start refusal is taken up to the planner\'s own bounds and refused one past them', () {
    const astral = '\u{1F600}';
    final taken = <String, PlanningStartRefusal>{
      'a 64-character name': PlanningStartRefusal(name: 'N' * 64),
      'a 64-character key': PlanningStartRefusal(name: 'Refused', parameters: {'k' * 64: 'v'}),
      'a 64-code-point key': PlanningStartRefusal(name: 'Refused', parameters: {astral * 64: 'v'}),
      'a 1000-code-point detail': PlanningStartRefusal(name: 'Refused', detail: astral * 1000),
      '16 parameters': PlanningStartRefusal(name: 'Refused', parameters: {for (var i = 0; i < 16; i++) 'k$i': 'v'}),
    };
    final refused = <String, PlanningStartRefusal>{
      'a 65-character name': PlanningStartRefusal(name: 'N' * 65),
      'a 65-character key': PlanningStartRefusal(name: 'Refused', parameters: {'k' * 65: 'v'}),
      'a 1001-code-point detail': PlanningStartRefusal(name: 'Refused', detail: astral * 1001),
      'a delete': const PlanningStartRefusal(name: 'Refused', detail: 'a\u007fb'),
      'a C1 control': const PlanningStartRefusal(name: 'Refused', parameters: {'k': 'a\u009fb'}),
      'a line separator': const PlanningStartRefusal(name: 'Refused', detail: 'a b'),
      'a non-ASCII letter in the name': const PlanningStartRefusal(name: 'Refusé'),
    };
    for (final MapEntry(:key, :value) in taken.entries) {
      expect(value.fitsPlanner, isTrue, reason: key);
    }
    for (final MapEntry(:key, :value) in refused.entries) {
      expect(value.fitsPlanner, isFalse, reason: key);
    }
  });

  // Harm: the refusal the planner answers an issue with is read away, so the screen cannot say why nothing ran.
  test('an issue the planner answers refused reads Porelo\'s name, parameters and words', () {
    final issue = PlanWorkIssue.from({
      'issue': 'PR1',
      'reason': 'START_REFUSED',
      'rounds': 0,
      'roundsMost': 1,
      'atWork': false,
      'needsYou': true,
      'readingStopped': false,
      'findings': <dynamic>[],
      'startRefusal': {
        'name': 'UnknownForge',
        'parameters': {'forge': 'example.org'},
        'detail': 'No forge there.',
      },
    });

    expect(issue.reason, 'START_REFUSED');
    expect(issue.startRefusal!.name, 'UnknownForge');
    expect(issue.startRefusal!.parameters, {'forge': 'example.org'});
    expect(issue.startRefusal!.detail, 'No forge there.');
    expect(PlanWorkIssue.from({'issue': 'PR1', 'reason': 'NONE'}).startRefusal, isNull);
  });

  // Harm: a landing sent without its event, or a landed report naming none, offers a reader on nothing.
  test('a landing travels only with a landed report that names one', () async {
    final (planner, source) = await _started();
    final calls = planner.calls.length;
    for (final (event, landed) in [
      (PlanningTaskEvent.landed, const <PlanningLanding>[]),
      (PlanningTaskEvent.started, const [_landing]),
      (PlanningTaskEvent.ended, const [_landing]),
      (PlanningTaskEvent.stopped, const [_landing]),
    ]) {
      await expectLater(
        source.taskEvent(
          'demo',
          'main-task',
          event,
          offer: servedOffer(1),
          container: 'container-main-task',
          machine: 'Machine',
          landed: landed,
        ),
        throwsArgumentError,
        reason: '$event with ${landed.length} landing(s)',
      );
    }
    expect(planner.calls.length, calls, reason: 'a refused report reaches no planner');
  });

  // Harm: a damaged report answer or an oversized raw answer is retained as permission to start more work.
  test('task event answers reject incomplete envelopes invalid offers and excessive bytes', () async {
    final (planner, source) = await _started(maxBody: 128 * 1024);
    Future<TaskEventResolution> report() => source.taskEvent(
      'demo',
      'main-task',
      PlanningTaskEvent.started,
      offer: servedOffer(1),
      container: 'container-main-task',
      machine: 'Machine',
    );
    for (final change in <void Function(Map<String, dynamic>)>[
      for (final field in ['revision', 'work', 'tasks']) (answer) => answer.remove(field),
      (answer) => answer['tasks'] = [false],
      (answer) => ((answer['tasks'] as List).last as Map)['offer'] = '',
      (answer) => ((answer['tasks'] as List).last as Map)['issues'] = ['PR9'],
      (answer) => answer['error'] = 'refused',
    ]) {
      final answer = {
        'revision': 'r1',
        'work': servedContinuationWork(),
        'tasks': [
          servedFollowUp(10),
          servedFollowUp(11)..['issues'] = ['PR1.1'],
        ],
      };
      change(answer);
      planner.routes['POST /projects/demo/tasks/main-task/events'] = () => _Reply(200, answer);
      expect((await _refusal(report())).name, 'BadAnswer');
    }
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () => _Reply(200, {
      'revision': 'r1',
      'work': servedWork(atWork: false)
        ..['tasks'] = [
          for (var number = 0; number < 3; number++)
            servedRunTask(
              task: 'reader-$number',
              active: false,
              ended: {'at': '', 'finished': true, 'why': '', 'answer': 'é' * 32768, 'unpushed': 0},
            ),
        ],
      'tasks': <dynamic>[],
    });
    expect((await _refusal(report())).name, 'AnswerTooLarge');
  });

  // Harm: a committed task report is refused because its valid run and follow-up exceed 64 KiB together.
  test('task reports and reading decisions accept work answers over 64 KiB', () async {
    final (planner, source) = await _started();
    final work = servedContinuationWork();
    final rawAnswer = '${'é' * 32762}\nFindings:\n';
    (((work['tasks'] as List).single as Map)['ended'] as Map)['answer'] = rawAnswer;
    final followUp = servedFollowUp(10)..['prompt'] = 'x' * 65536;
    final answer = {
      'revision': 'r2',
      'work': work,
      'tasks': [followUp],
      'issue': servedReadingIssue(stopped: false),
    };
    expect(utf8.encode(jsonEncode(answer)).length, greaterThan(65536));
    planner.routes['GET /projects/demo/stories/ST1/work'] = () => _Reply(200, {'work': work});
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () => _Reply(200, answer);
    planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, answer);
    final read = await source.getStoryWork('demo', 'ST1');
    final report = await source.taskEvent(
      'demo',
      'main-task',
      PlanningTaskEvent.ended,
      offer: servedOffer(1),
      container: 'container-main-task',
      machine: 'Machine 🚀',
      ended: PlanningTaskEnded(at: '', finished: true, why: '', answer: rawAnswer, unpushed: 0),
    );
    final decision = await source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', _seen);
    for (final result in [report.work, decision.work!]) {
      expect(result.tasks.single.ended!.answer, read.work!.tasks.single.ended!.answer);
    }
    expect(report.tasks.single.prompt, followUp['prompt']);
    expect(decision.tasks.single.prompt, followUp['prompt']);
  });

  // Harm: a planner refusal on a read, stop, report or decision is taken as successful work.
  test('every run call preserves served refusal names and details', () async {
    final (planner, source) = await _started();
    final cases = <(String, Future<Object?> Function())>[
      ('GET /projects/demo/stories/ST1/work', () => source.getStoryWork('demo', 'ST1')),
      ('POST /projects/demo/stories/ST1/work/stop', () => source.stopStoryWork('demo', 'ST1')),
      ('GET /projects/demo/issues/PR1', () => source.getPlanningIssue('demo', 'PR1')),
      (
        'POST /projects/demo/issues/PR1/reading',
        () => source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', _seen),
      ),
      (
        'POST /projects/demo/tasks/main-task/events',
        () => source.taskEvent(
          'demo',
          'main-task',
          PlanningTaskEvent.started,
          offer: servedOffer(1),
          container: 'container-main-task',
          machine: 'Machine',
        ),
      ),
    ];
    for (final (route, call) in cases) {
      for (final (status, name) in [
        (400, 'InvalidSettings'),
        (404, 'NoSuchStory'),
        (409, 'ReadingRoundChanged'),
        (422, 'WorkNotAllowed'),
        (501, 'NotOffered'),
        (502, 'SaveRefused'),
        (503, 'RecordUnavailable'),
        (504, 'Unreachable'),
      ]) {
        planner.routes[route] = () => _Reply.problem(name, {'rounds': 2}, 'Refused detail.', status);
        final refusal = await _refusal(call());
        expect(refusal.name, name);
        expect(refusal.parameters, {'rounds': 2});
        expect(refusal.description, 'Refused detail.');
        expect(planningUnsupported(refusal), name == 'NotOffered');
      }
    }
  });

  // Harm: a run read or report silently retells a project for another repository.
  test('run calls refuse a changed repository instead of replacing the project', () async {
    var repository = const PlannerProject('https://example.org/first.git', 'main');
    final (planner, source) = await _started(projects: (_) => repository);
    await source.stories('demo');
    final before = planner.calls.length;
    repository = const PlannerProject('https://example.org/second.git', 'main');
    for (final call in <Future<Object?> Function()>[
      () => source.getStoryWork('demo', 'ST1'),
      () => source.getPlanningIssue('demo', 'PR1'),
      () => source.stopStoryWork('demo', 'ST1'),
      () => source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', _seen),
      () => source.taskEvent(
        'demo',
        'main-task',
        PlanningTaskEvent.started,
        offer: servedOffer(1),
        container: 'c',
        machine: 'Machine',
      ),
    ]) {
      expect((await _refusal(call())).name, 'ProjectChanged');
    }
    expect(planner.calls.length, before);
  });

  // Harm: task-only changes at an unchanged Git revision leave the board showing obsolete activity.
  test('watch delivers changed runs at the same revision and suppresses identical repeats', () async {
    final (planner, source) = await _started();
    final streams = _Streams(planner);
    final replies = <PlanningSnapshot>[];
    final watching = source.watchStories('demo').listen(replies.add);
    addTearDown(watching.cancel);
    await _until(() => replies.length == 1, 'first board');
    final board = {
      ..._workflow('r1'),
      'stories': [
        {
          ..._story('ST1', 'Building'),
          'work': servedWork()..['tasks'] = [servedRunTask()],
        },
      ],
      'repositories': ['porelo-project'],
    };
    final event = utf8.encode('event:board\nid:r1\ndata:${jsonEncode(board)}\n\n');
    streams.opened.last.add(event);
    await _until(() => replies.length == 2, 'changed run at the same revision');
    expect(replies.last.stories.single.work!.tasks.single.atWork, isTrue);
    streams.opened.last.add(event);
    streams.send('r2');
    await _until(() => replies.last.revision == 'r2', 'next revision');
    expect(replies, hasLength(3));
  });

  // Harm: the token is sent to another computer, which can then act on the person's planning.
  test('a planner address off the loopback interface is refused', () {
    PlanningSource at(String base) => HttpPlanningSource(Uri.parse(base), _token, (_) => const PlannerProject('x'));

    for (final base in ['http://192.0.2.1:8080', 'http://example.org:8080', 'https://127.0.0.1:8080']) {
      expect(() => at(base), throwsArgumentError, reason: '$base is refused');
    }
    expect(at('http://127.0.0.1:8080').label, 'planner', reason: 'the loopback address is taken');
    expect(at('http://[::1]:8080').label, 'planner', reason: 'the IPv6 loopback address is taken');
  });

  // Harm: a damaged or planted address file sends the token somewhere unexpected or into a broken header.
  test('an address file with a bad port or token is refused', () {
    Matcher refused = throwsA(isA<PlanningRefusal>().having((refusal) => refusal.name, 'name', 'BadAddress'));

    for (final text in [
      '{"port": 0, "token": "$_token"}',
      '{"port": 65536, "token": "$_token"}',
      '{"port": "8080", "token": "$_token"}',
      '{"port": 8080, "token": ""}',
      '{"port": 8080, "token": "a\\r\\nX-Other: b"}',
      '{"port": 8080}',
      'not json',
    ]) {
      expect(() => PlannerAddress.parse(text), refused, reason: text);
    }
    final address = PlannerAddress.parse('{"port": 8080, "token": "$_token"}');
    expect(address.base, Uri.parse('http://127.0.0.1:8080'), reason: 'the planner listens on the loopback address');
    expect(address.token, _token);
  });

  // Harm: an address file others could read or write is trusted, though its token may be theirs or leaked.
  test('the address file is read only when it is the account\'s own', () async {
    final runtime = await _ownRuntime();
    final file = File('${runtime.path}/porelo-planner/planner.json');

    final missing = await _refusal(PlannerAddress.read(runtimeDirectory: runtime.path));
    expect(missing.name, 'NoAddress', reason: 'no file, no planner');

    await file.create(recursive: true);
    await file.writeAsString('{"port": 4711, "token": "$_token"}\n');
    await Process.run('chmod', ['644', file.path]);
    final shared = await _refusal(PlannerAddress.read(runtimeDirectory: runtime.path));
    expect(shared.name, 'BadAddress', reason: 'a file others may read is refused');

    await Process.run('chmod', ['600', file.path]);
    final address = await PlannerAddress.read(runtimeDirectory: runtime.path);
    expect(address.port, 4711);
  });

  // Harm: a runtime directory pointed at a shared place hands the window another account's planner and its token.
  test('the address is read only from directories and a file this account owns and others cannot open', () async {
    final runtime = await _ownRuntime();
    final own = await _uid();
    final planner = '${runtime.path}/porelo-planner';
    final file = File('$planner/planner.json');
    await file.writeAsString('{"port": 4711, "token": "$_token"}');
    await Process.run('chmod', ['600', file.path]);
    Future<String> refused(String path, {int? uid}) async =>
        (await _refusal(PlannerAddress.read(runtimeDirectory: path, uid: () async => uid ?? own))).name;

    expect((await PlannerAddress.read(runtimeDirectory: runtime.path)).port, 4711, reason: 'all of it its own');

    await Process.run('chmod', ['755', runtime.path]);
    expect(await refused(runtime.path), 'BadAddress', reason: 'a runtime directory others may open');
    await Process.run('chmod', ['700', runtime.path]);

    await Process.run('chmod', ['755', planner]);
    expect(await refused(runtime.path), 'BadAddress', reason: 'a porelo-planner directory others may open');
    await Process.run('chmod', ['700', planner]);

    expect(await refused(runtime.path, uid: own + 1), 'BadAddress', reason: 'owned by another account');

    final elsewhere = await _ownRuntime();
    await Directory('${elsewhere.path}/porelo-planner').delete();
    await Link('${elsewhere.path}/porelo-planner').create(planner);
    expect(await refused(elsewhere.path), 'BadAddress', reason: 'porelo-planner a symbolic link');

    final linked = '${elsewhere.path}/runtime';
    await Link(linked).create(runtime.path);
    expect(await refused(linked), 'BadAddress', reason: 'the runtime directory a symbolic link');

    expect(await refused('relative/run'), 'NoAddress', reason: 'a relative runtime directory');
    expect(await refused('${runtime.path}/.'), 'BadAddress', reason: 'a path that would follow a link');
    expect(await refused('${elsewhere.path}/../${runtime.path.split('/').last}'), 'BadAddress', reason: 'a ..');
    final unknown = await _refusal(PlannerAddress.read(runtimeDirectory: runtime.path, uid: () async => null));
    expect(unknown.name, 'NoAddress', reason: 'no uid, nothing to compare the owners with');

    for (final (target, mode) in [(runtime.path, '750'), (planner, '705'), (file.path, '640'), (file.path, '604')]) {
      await Process.run('chmod', [mode, target]);
      expect(await refused(runtime.path), 'BadAddress', reason: '$target with mode $mode');
      await Process.run('chmod', [target == file.path ? '600' : '700', target]);
    }

    await file.rename('$planner/real.json');
    await Link(file.path).create('$planner/real.json');
    expect(await refused(runtime.path), 'BadAddress', reason: 'planner.json a symbolic link');

    await file.delete();
    await Process.run('mkfifo', ['-m', '600', file.path]);
    expect(await refused(runtime.path), 'BadAddress', reason: 'a pipe, whose read would wait for ever');
  });

  // Harm: a step on the way owned or changeable by another account lets it swap the address file after the checks.
  test('every directory on the way is owned by root or this account, and one others may write is sticky', () async {
    const own = 4242424;
    const directory = 0x41c0, file = 0x8180, link = 0xa1ff;
    Future<String> answer(Map<String, ({int owner, int mode})> differ) async {
      final read = PlannerAddress.read(
        runtimeDirectory: '/run/user/4242424',
        uid: () async => own,
        stat: (paths) async => [
          for (final path in paths)
            differ[path] ??
                (path == '/run/user/4242424/porelo-planner/planner.json'
                    ? (owner: own, mode: file)
                    : path.startsWith('/run/user/4242424')
                    ? (owner: own, mode: directory)
                    : (owner: 0, mode: 0x41ed)),
        ],
      );
      try {
        await read;
      } on PlanningRefusal catch (refusal) {
        return refusal.name;
      } on FileSystemException {
        return 'read';
      }
      return 'read';
    }

    expect(await answer({}), 'NoAddress', reason: 'all checks passed; the fake file does not exist to read');
    expect(await answer({'/run': (owner: 4242425, mode: 0x41ed)}), 'BadAddress', reason: 'an ancestor of another');
    expect(await answer({'/run/user': (owner: 0, mode: 0x41ff)}), 'BadAddress', reason: 'world-writable, not sticky');
    expect(await answer({'/run/user': (owner: 0, mode: 0x43ff)}), 'NoAddress', reason: 'sticky, as /tmp is');
    expect(await answer({'/run': (owner: 0, mode: link)}), 'NoAddress', reason: 'root\'s link, as /var/run is');
    expect(await answer({'/run': (owner: 4242425, mode: link)}), 'BadAddress', reason: 'another account\'s link');
    expect(
      await answer({'/run/user/4242424/porelo-planner': (owner: 4242425, mode: directory)}),
      'BadAddress',
      reason: 'porelo-planner of another',
    );
    expect(
      await answer({'/run/user/4242424/porelo-planner/planner.json': (owner: 4242425, mode: file)}),
      'BadAddress',
      reason: 'planner.json of another',
    );
  });

  // Harm: without XDG_RUNTIME_DIR the window looks elsewhere than the planner wrote, and finds no planner.
  test(
    'without a runtime directory the address is read where the planner falls back to, by this account\'s uid',
    () async {
      final runUser = await Directory.systemTemp.createTemp('run-user');
      addTearDown(() => runUser.delete(recursive: true));
      final uid = await _uid();
      final file = File('${runUser.path}/$uid/porelo-planner/planner.json');
      await file.create(recursive: true);
      await file.writeAsString('{"port": 4712, "token": "$_token"}');
      await Process.run('chmod', ['600', file.path]);
      await Process.run('chmod', ['700', '${runUser.path}/$uid', '${runUser.path}/$uid/porelo-planner']);
      // As /run/user is: no other account may change it.
      await Process.run('chmod', ['755', runUser.path]);

      final address = await PlannerAddress.read(runtimeDirectory: ' ', runUser: runUser.path);
      final unknown = await _refusal(
        PlannerAddress.read(runtimeDirectory: '', runUser: runUser.path, uid: () async => null),
      );

      expect(address.port, 4712, reason: 'the uid from /proc/self/status names the directory');
      expect(unknown.name, 'NoAddress', reason: 'no uid, no place to look');
    },
  );

  // Harm: an address file that cannot be read, or vanishes while it is read, crashes the window instead of saying so.
  test('an address file that cannot be read is no address, and one too large is refused', () async {
    final runtime = await _ownRuntime();
    final file = File('${runtime.path}/porelo-planner/planner.json');
    await file.create(recursive: true);
    await file.writeAsString('{"port": 4711, "token": "$_token"}${' ' * 5000}');
    await Process.run('chmod', ['600', file.path]);

    final large = await _refusal(PlannerAddress.read(runtimeDirectory: runtime.path));
    await Process.run('chmod', ['000', file.path]);
    final unreadable = await _refusal(PlannerAddress.read(runtimeDirectory: runtime.path));

    expect(large.name, 'BadAddress', reason: 'more than 4096 bytes is no address file the planner wrote');
    expect(unreadable.name, 'NoAddress', reason: 'a file system failure is a refusal, not a crash');
  });

  // Harm: a compressed answer is inflated in memory far past the bound on what is received.
  test('a compressed answer is not inflated', () async {
    final (planner, source) = await _started(maxBody: 64 * 1024);
    final inflated = utf8.encode(jsonEncode({..._stories('r1'), 'padding': ' ' * (1024 * 1024)}));
    planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply.raw(
      200,
      gzip.encode(inflated),
      headers: {'content-type': 'application/json', 'content-encoding': 'gzip'},
    );

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'BadAnswer', reason: 'the compressed bytes are read as they are, never inflated');
  });

  // Harm: an answer refused for its size keeps its connection open, and enough of them exhaust the window's sockets.
  test('an answer refused for its announced size closes its connection', () async {
    final (planner, source) = await _started(maxBody: 64);
    planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply.raw(
      200,
      const [32],
      headers: {'content-type': 'application/json', 'content-length': 1 << 26},
      more: Stream.periodic(const Duration(milliseconds: 10), (_) => List.filled(4096, 32)).take(3000),
    );

    final refusal = await _refusal(source.getStory('demo', 'ST1'));
    for (var wait = 0; wait < 40 && planner.connections > 0; wait++) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }

    expect(refusal.name, 'AnswerTooLarge');
    expect(planner.connections, 0, reason: 'the client closed the connection of the unread answer');
  });

  // Harm: a refusal with endless parameters fills the screen and the window's memory.
  test('a refusal keeps at most 16 parameters with short names', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply.problem(
      'NoSuchStory',
      {'k' * 65: 'long name', for (var each = 0; each < 40; each++) 'p$each': 'v$each'},
      'No such story.',
      404,
    );

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.parameters.length, 16, reason: 'at most 16 parameters are kept');
    expect(refusal.parameters.keys.where((key) => key.length > 64), isEmpty, reason: 'no long parameter name is kept');
  });

  // Harm: an answer that is not the planner's board, or an empty one, is shown as an empty board.
  test('a read answered without a JSON object and its revision is a bad answer', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST1'] = () => _Reply(200, {'story': _story('ST1', 'Backlog')});
    planner.routes['GET /projects/demo/stories/ST1/issues'] = () => _Reply(204);
    planner.routes['GET /projects/demo/issues/PR1'] = () => _Reply(200, '[]');

    for (final read in [
      () => source.getStory('demo', 'ST1'),
      () => source.listStoryIssues('demo', 'ST1'),
      () => source.getIssue('demo', 'PR1'),
    ]) {
      expect((await _refusal(read())).name, 'BadAnswer');
    }
  });

  // Harm: a damaged answer is reported as an unreachable planner, and the person looks for the fault in the wrong place.
  test('an answer with a malformed content type is a bad answer', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST1'] = () =>
        _Reply.raw(200, utf8.encode('{"revision": "r1"}'), headers: {'content-type': 'application/json; charset="x'});

    final refusal = await _refusal(source.getStory('demo', 'ST1'));

    expect(refusal.name, 'BadAnswer');
  });

  // Harm: reads refused together each name the project again, and a planner that drops its clones re-clones each time.
  test('reads refused together for an unknown project share one new PUT', () async {
    final (planner, source) = await _started();
    var refusals = 2;
    final both = Completer<void>();
    planner.routes['GET /projects/demo/stories/ST1'] = () async {
      if (refusals-- <= 0) return planner.answer('GET /projects/demo/stories/ST1');
      // Both reads are refused only once both arrived, however long each connection took to open.
      if (refusals == 0) both.complete();
      await both.future;
      return _Reply.problem('NoSuchProject', {'project': 'demo'}, 'The project is not known.', 404);
    };

    await Future.wait([source.getStory('demo', 'ST1'), source.getStory('demo', 'ST1')]);

    expect(planner.targets.where((target) => target.startsWith('PUT')).length, 2, reason: 'the first PUT and one more');
  });

  // Harm: the planner keeps reading the old repository or branch after the window named another.
  test('a project named with another repository or branch is given again before the next read', () async {
    var branch = 'main';
    final (planner, source) = await _started(
      projects: (project) => PlannerProject('https://example.org/x.git', branch),
    );

    await source.getStory('demo', 'ST1');
    await source.getStory('demo', 'ST1');
    branch = 'next';
    await source.getStory('demo', 'ST1');

    final puts = [
      for (final call in planner.calls)
        if (call.method == 'PUT') jsonDecode(call.body),
    ];
    expect(puts, [
      {'repository': 'https://example.org/x.git', 'branch': 'main'},
      {'repository': 'https://example.org/x.git', 'branch': 'next'},
    ], reason: 'named once, and again only when the branch changed');
  });

  // Harm: a name such as `..` becomes a path segment and reaches another resource of the planner.
  test('a project, story or issue name outside the description\'s patterns is refused before any call', () async {
    final (planner, source) = await _started();

    final refusals = [
      await _refusal(source.stories('..')),
      await _refusal(source.getStory('demo', '..')),
      await _refusal(source.listStoryIssues('demo', '.')),
      await _refusal(source.getIssue('demo', '../x')),
      await _refusal(source.getStory('Demo', 'ST1')),
    ];

    expect([for (final refusal in refusals) refusal.name], everyElement('InvalidName'));
    expect(refusals[1].parameters, {'story': '..'}, reason: 'the refusal names what it refused');
    expect(planner.calls, isEmpty, reason: 'nothing reaches the planner');
  });

  group('a local project\'s directory', () {
    const chosen = '/home/person/planning';
    const standard = '/home/person/.local/share/porelo-planner/items/demo';

    // Harm: the person's choice of directory not kept, or kept for a write the planner checks against items seen.
    test('a directory chosen is sent with an empty seen, and the default as null, the tracker read back', () async {
      final (planner, source) = await _started();
      planner.routes['PUT /projects/demo/tracker/items'] = () =>
          _Reply(200, {'kind': 'local', 'items': chosen, 'default': standard});

      final kept = await source.setItemsDirectory('demo', chosen, const {});
      await source.setItemsDirectory('demo', null, const {});

      expect(kept, const PlanningTracker.local(chosen, standard), reason: 'the tracker the planner answered');
      final sent = [
        for (final call in planner.calls)
          if (call.target == '/projects/demo/tracker/items') '${call.method} ${call.body}',
      ];
      expect(sent, [
        'PUT ${jsonEncode({'directory': chosen, 'seen': <String, String>{}})}',
        'PUT ${jsonEncode({'directory': null, 'seen': <String, String>{}})}',
      ]);
    });

    // Harm: a path outside the planner's rule reaches it, is taken in another form, or hides what it names.
    test(
      'a directory outside the planner\'s rule is refused before anything is sent, in the planner\'s words',
      () async {
        final (planner, source) = await _started();
        final refusals = <String, String>{
          '/${'d' * 4095}': planningDirectoryTooLong,
          '/${'ä' * 2048}': planningDirectoryTooLong,
          for (final directory in [
            '',
            'planning',
            '/home/person\nplanning',
            '/a/../b',
            '/a/./b',
            '/a//b',
            '//a',
            '/a/',
            ' /a',
            '/a ',
            '/a\u00a0',
            '/a\u0085b',
            '/a\u009bb',
            '/a\u2028b',
            '/a\u202eb',
            '/a\u2067b',
            'C:/a',
            'C:\\a\\',
            'C:\\a/b',
            '\\\\server\\share',
            '\\\\.\\pipe',
            '\\\\?\\C:\\a',
          ])
            directory: planningDirectoryNotNormal,
        };
        for (final MapEntry(key: directory, value: words) in refusals.entries) {
          final refusal = await _refusal(source.setItemsDirectory('demo', directory, const {}));
          expect(refusal.name, 'ItemsDirectoryRefused', reason: 'refused: $directory');
          expect(refusal.description, words, reason: 'the planner\'s words for $directory');
          expect(refusal.parameters, isNot(contains('directory')), reason: 'a path it cannot take is not named');
        }
        for (final directory in ['/', '/${'d' * 4094}', 'C:\\', 'C:\\Users\\person\\Planung ä']) {
          expect(planningDirectoryFault(directory), isNull, reason: 'taken: $directory');
        }
        expect(planner.targets.where((target) => target.contains('tracker')), isEmpty);
      },
    );

    // Harm: a long refused directory named shortened, so the person and the folder dialog are sent to another one.
    test('a refused directory is named whole, however long, and one that is no path is not named', () async {
      final (planner, source) = await _started();
      final long = '/home/person/${'d' * 2000}';
      var named = long;
      planner.routes['PUT /projects/demo/tracker/items'] = () => _Reply.problem(
        'ItemsDirectoryRefused',
        {'project': 'demo', 'directory': named},
        'The directory does not exist.',
        400,
      );

      final whole = await _refusal(source.setItemsDirectory('demo', long, const {}));
      named = 'elsewhere';
      final other = await _refusal(source.setItemsDirectory('demo', long, const {}));

      expect(whole.name, 'ItemsDirectoryRefused');
      expect(whole.parameters['directory'], long, reason: 'kept whole');
      expect(whole.description, 'The directory does not exist.', reason: 'the planner\'s words');
      expect(other.parameters, isNot(contains('directory')), reason: 'a parameter that is no path is dropped');
    });

    // Harm: a board read without knowing where it is kept, so a local project's directory cannot be shown or chosen.
    test('a workflow or a streamed board without a tracker it can name is refused as no answer', () async {
      final (planner, source) = await _started();
      for (final tracker in <Object?>[
        null,
        {'kind': 'local'},
        {'kind': 'local', 'items': 'planning', 'default': standard},
        {'kind': 'local', 'items': chosen},
        {'kind': 'local', 'items': chosen, 'default': '$standard/'},
        {'kind': 'forge'},
        {'kind': 'git', 'items': '/home/person/planning'},
      ]) {
        planner.routes['GET /projects/demo/workflow'] = () =>
            _Reply(200, {..._workflow(planner.workflowRevision), 'tracker': tracker});
        expect((await _refusal(source.planningWorkflow('demo'))).name, 'BadAnswer', reason: 'workflow with $tracker');
      }
      planner.routes['GET /projects/demo/workflow'] = () => _Reply(200, {
        ..._workflow(planner.workflowRevision),
        'tracker': {'kind': 'local', 'items': chosen, 'default': standard},
      });
      expect((await source.planningWorkflow('demo')).tracker, const PlanningTracker.local(chosen, standard));
      planner.routes['GET /projects/demo/workflow'] = () => _Reply(200, {
        ..._workflow(planner.workflowRevision),
        'tracker': {'kind': 'git'},
      });
      expect((await source.planningWorkflow('demo')).tracker, const PlanningTracker.git());

      final streams = _Streams(planner, first: false);
      final watched = source.watchStories('demo').first;
      await _until(() => streams.opened.isNotEmpty, 'the stream');
      streams.opened.last.add(_event('r1', more: {'tracker': null}));
      expect((await _refusal(watched)).name, 'BadAnswer', reason: 'a streamed board without a tracker');
    });

    // Harm: a kept directory gone, told as a failed read the person cannot answer, or answered from the default.
    test('a stream whose read refuses the kept directory ends the watch with the refusal naming it', () async {
      final (planner, source) = await _started();
      final streams = _Streams(planner, first: false);
      final watched = source.watchStories('demo').first;
      await _until(() => streams.opened.isNotEmpty, 'the stream');
      streams.opened.last.add(
        _load({
          'state': 'failed',
          'problem': 'ItemsDirectoryRefused',
          'failure': 'The directory does not exist.',
          'parameters': {'project': 'demo', 'directory': chosen},
        }),
      );
      final refusal = await _refusal(watched);
      expect(refusal.name, 'ItemsDirectoryRefused');
      expect(ItemsDirectoryHeader.refusedOf(refusal), (
        directory: chosen,
        words: 'The directory does not exist.',
      ), reason: 'the planner\'s phrase, not the description built around it');
    });

    // Harm: a kept directory moved away after the first board is not told with its directory, or a parameter the
    // planner streamed outside the rule or past its bound reaches the screen.
    test('a read refused after the first board is told with its directory in the rule and bounded words', () async {
      final (planner, source) = await _started(backoff: Duration.zero);
      final streams = _Streams(planner);
      final watch = source.watchStories('demo') as PlanningWatch;
      final revisions = <String>[];
      final watching = watch.listen((reply) => revisions.add(reply.revision));
      addTearDown(watching.cancel);
      await _until(() => revisions.isNotEmpty, 'the first board');

      streams.opened.last.add(
        _load({
          'state': 'failed',
          'problem': 'ItemsDirectoryRefused',
          'failure': 'The directory does not exist.',
          'parameters': {'directory': chosen, 'note': 'x' * 5000},
        }),
      );
      await _until(() => watch.readFailed.value != null, 'the failed read told');
      final told = watch.readFailed.value!;
      expect(ItemsDirectoryHeader.refusedOf(told), (directory: chosen, words: 'The directory does not exist.'));
      expect((told.parameters['note'] as String).length, lessThanOrEqualTo(1003), reason: 'bounded');

      streams.send('r2');
      await _until(() => revisions.length == 2, 'the next board');
      streams.opened.last.add(
        _load({
          'state': 'failed',
          'problem': 'ItemsDirectoryRefused',
          'failure': 'x' * 5000,
          'parameters': {'directory': '/a/\u202e/b'},
        }),
      );
      await _until(() => watch.readFailed.value != null, 'the second failed read told');
      expect(watch.readFailed.value!.parameters, isNot(contains('directory')), reason: 'outside the rule');
      expect((watch.readFailed.value!.parameters['failure'] as String).length, lessThanOrEqualTo(1003));
    });
  });
}

const _landing = PlanningLanding(
  branch: 'foo-1',
  ref: 'refs/porelo/landed/porelo-foo-project_foo-1/foo-1',
  remote: 'origin/landed/porelo-foo-project_foo-1/foo-1',
  commit: '0123456789abcdef0123456789abcdef01234567',
);

const _landingMap = {
  'branch': 'foo-1',
  'ref': 'refs/porelo/landed/porelo-foo-project_foo-1/foo-1',
  'remote': 'origin/landed/porelo-foo-project_foo-1/foo-1',
  'commit': '0123456789abcdef0123456789abcdef01234567',
};
