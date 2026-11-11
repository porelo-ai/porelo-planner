import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/served_story_work.dart';

const _token = 'tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz';

Map<String, dynamic> _workflow(String revision) => {
  'revision': revision,
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
};

Map<String, dynamic> _story(String id, String state) => {
  'id': id,
  'title': 'Story $id',
  'state': state,
  'stateGiven': false,
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
typedef _Call = ({String method, String target, String? authorization, String body, String? lastEventId});

/// A planner on 127.0.0.1 that answers the board as the REST description gives it and records every call.
class _Planner {
  late final HttpServer _server;
  final calls = <_Call>[];
  final Map<String, FutureOr<_Reply> Function()> routes = {};
  String workflowRevision = 'r1';
  String storiesRevision = 'r1';

  Uri get base => Uri(scheme: 'http', host: '127.0.0.1', port: _server.port);

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen((request) async {
      final body = await utf8.decodeStream(request);
      final target = request.uri.toString();
      calls.add((
        method: request.method,
        target: target,
        authorization: request.headers.value(HttpHeaders.authorizationHeader),
        body: body,
        lastEventId: request.headers.value('last-event-id'),
      ));
      final route = '${request.method} ${request.uri.path}';
      final reply = await (routes[route] ?? () => answer(route))();
      request.response.statusCode = reply.status;
      if (reply.raw case final raw?) {
        // Each chunk sent at once, as the planner flushes each event.
        request.response.bufferOutput = false;
        reply.headers.forEach(request.response.headers.set);
        try {
          request.response.add(raw);
          if (reply.more case final more?) await request.response.addStream(more);
          await request.response.close();
        } on Object {
          // The client closed the answer early, as it should for one past its bound.
        }
        return;
      }
      if (reply.body != null) {
        request.response.headers.contentType = ContentType.parse(reply.type);
        request.response.add(utf8.encode(reply.body is String ? reply.body as String : jsonEncode(reply.body)));
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
    maxBody: maxBody ?? 16 * 1024 * 1024,
    silence: silence,
    backoff: backoff,
    backoffMost: backoffMost,
  );
  addTearDown(source.close);
  return (planner, source);
}

/// One `board` event as the planner sends it: the workflow's and the stories' fields in one object.
List<int> _event(String revision) => utf8.encode(
  'event:board\nid:$revision\ndata:${jsonEncode({..._workflow(revision), 'stories': _stories(revision)['stories']})}\n\n',
);

/// The planner's event streams, one per connection the source opened, each fed by the test.
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

  // Harm: a proposal is requested for another target or malformed participants silently disappear.
  test('a proposal posts only its target and refuses malformed proposals whole', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/stories/ST1/proposal'] = () => _Reply(200, servedProposal());
    final proposal = await source.proposeStoryWork('demo', 'ST1', 'Building');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/stories/ST1/proposal']);
    expect(jsonDecode(planner.calls.last.body), {'to': 'Building'});
    expect(proposal.repositories.single.subAgents.single.role, 'BUILDER');
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
    expect(order.tasks.map((task) => task.role), ['MAIN', 'BUILDER']);
    expect(order.tasks.first.prompt, (servedOrder()['tasks'] as List).first['prompt']);
    final bad = servedOrder();
    ((bad['tasks'] as List).last as Map)['model'] = 'another-model';
    planner.routes['POST /projects/demo/stories/ST1/work'] = () => _Reply(200, bad);
    expect((await _refusal(source.startStoryWork(request))).name, 'BadAnswer');
  });

  // Harm: a story's task answered for an idea reaches Start, or a refused idea is taken for an offer.
  test('a discussion posts the idea and accepts only a discussion answered without a revision', () async {
    final (planner, source) = await _started();
    final served = {
      'offer': servedOffer(9),
      'issues': <String>[],
      'role': 'DISCUSSION',
      'agent': 'codex',
      'model': 'idea-model',
      'mode': 'AGENT',
      'prompt': 'Discuss the idea.\n',
      'result': 'idea.json',
    };
    planner.routes['POST /projects/demo/ideas/discussion'] = () => _Reply(200, served);
    final task = await source.discussIdea('demo', 'A board that\nremembers.');
    expect(planner.targets.last, 'POST /projects/demo/ideas/discussion', reason: 'the contract names this call');
    expect(jsonDecode(planner.calls.last.body), {'idea': 'A board that\nremembers.'}, reason: 'the idea goes as typed');
    expect([task.role, task.repository, task.result], ['DISCUSSION', null, 'idea.json'], reason: 'as served');
    planner.routes['POST /projects/demo/ideas/discussion'] = () => _Reply(200, servedFollowUp(1, role: 'MAIN'));
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
      () => source.saveMoves('demo', const [], 'r1'),
      () => source.proposeStoryWork('demo', 'ST1', 'Building'),
      () => source.startStoryWork(servedRequest()),
    ]) {
      expect((await _refusal(call())).name, 'ProjectChanged');
    }
    expect(planner.calls, hasLength(before));
    await source.getStory('demo', 'ST1');
    final afterDetail = planner.calls.length;
    expect((await _refusal(source.saveMoves('demo', const [], 'r1'))).name, 'ProjectChanged');
    expect(planner.calls, hasLength(afterDetail), reason: 'a detail read does not replace the board binding');
  });

  // Harm: a server restart either loses a safe write or causes an unbounded retry loop.
  test('only an explicit NoSuchProject retells and retries a move save once', () async {
    final (planner, source) = await _started();
    var attempts = 0;
    planner.routes['POST /projects/demo/moves'] = () => ++attempts == 1
        ? _Reply.problem('NoSuchProject', {'project': 'demo'}, 'No project.', 404)
        : _Reply(200, {'revision': 'r2', 'moved': <String>[]});
    await source.saveMoves('demo', const [], 'r1');
    expect(planner.targets, [
      'PUT /projects/demo',
      'POST /projects/demo/moves',
      'PUT /projects/demo',
      'POST /projects/demo/moves',
    ]);
    planner.routes['POST /projects/demo/moves'] = () => _Reply.problem('NoSuchProject', {}, 'Still unknown.', 404);
    final count = planner.calls.length;
    expect((await _refusal(source.saveMoves('demo', const [], 'r1'))).name, 'NoSuchProject');
    expect(planner.calls, hasLength(count + 3));
  });

  // Harm: losing a write's answer sends the same move to the store twice.
  test('a lost connection after a move save never replays the write', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () =>
        _Reply.raw(200, [123], headers: {'content-type': 'application/json', 'content-length': 100});
    expect((await _refusal(source.saveMoves('demo', const [], 'r1'))).name, 'PlannerUnreachable');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves']);
  });
  // Harm: a reviewed mixed batch is saved with different destinations or a different revision.
  test('a move save sends the exact ordered batch and reads the saved envelope', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () => _Reply(200, {
      'revision': 'r2',
      'moved': ['PR1', 'PR2'],
    });
    final saved = await source.saveMoves('demo', const [
      PlanningStoryMove('ST1', 'Finished'),
      PlanningIssueMove('PR2', 'Backlog'),
    ], 'r1');
    expect(planner.targets, ['PUT /projects/demo', 'POST /projects/demo/moves']);
    expect(jsonDecode(planner.calls.last.body), {
      'revision': 'r1',
      'moves': [
        {'story': 'ST1', 'to': 'Finished'},
        {'issue': 'PR2', 'to': 'Backlog'},
      ],
    });
    expect(planner.calls.last.authorization, 'Bearer $_token');
    expect(saved.revision, 'r2');
    expect(saved.moved, ['PR1', 'PR2']);
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
      expect((await _refusal(source.saveMoves('demo', const [PlanningStoryMove('ST1', 'Finished')], 'r1'))).name, name);
      expect(planner.calls.where((call) => call.method == 'POST'), hasLength(before + 1));
    }
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
    ]) {
      planner.routes['POST /projects/demo/moves'] = () => _Reply(200, answer);
      expect((await _refusal(source.saveMoves('demo', const [], 'r1'))).name, 'BadAnswer');
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
    final refusal = await _refusal(source.saveMoves('demo', const [], 'r1'));
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
      expect((await _refusal(source.saveMoves('demo', const [], 'r1'))).name, 'Http409');
    }
  });

  // Harm: an unavailable move action disables planning reads or unrelated story work.
  test('a served NotOffered answer remains a named action refusal', () async {
    final (planner, source) = await _started();
    planner.routes['POST /projects/demo/moves'] = () => _Reply.problem('NotOffered', {}, 'Moves are unavailable.', 400);
    final refusal = await _refusal(source.saveMoves('demo', const [], 'r1'));
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
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
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

  // Harm: an endless answer fills the window's memory.
  test('an answer larger than the bound is refused', () async {
    final (_, source) = await _started(maxBody: 64);

    final refusal = await _refusal(source.stories('demo'));

    expect(refusal.name, 'AnswerTooLarge');
  });

  // Harm: the token leaks into a URL, a log or an error text, where other programs or people read it.
  test('the token goes in the Authorization header alone', () async {
    final (planner, source) = await _started();
    planner.routes['GET /projects/demo/stories/ST9'] = () =>
        _Reply.problem('NoSuchStory', {'story': 'ST9'}, 'The project has no such story.', 404);

    await source.stories('demo');
    final refusal = await _refusal(source.getStory('demo', 'ST9'));

    expect(planner.calls, isNotEmpty);
    for (final call in planner.calls) {
      expect(call.authorization, 'Bearer $_token', reason: 'every call carries the token');
      expect(call.target, isNot(contains(_token)), reason: 'no URL carries the token');
      expect(call.body, isNot(contains(_token)), reason: 'no body carries the token');
    }
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
      final tasks = [
        if (choice != 'REVIEW_ANYWAY') servedFollowUp(10, role: choice == 'REWORK' ? 'BUILDER' : 'READER'),
      ];
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
        'r1',
        comment: choice == 'REWORK' ? '  Keep the key inside.\n' : null,
      );
      expect(jsonDecode(planner.calls.last.body), {
        'story': 'ST1',
        'round': 2,
        'choice': choice,
        'comment': choice == 'REWORK' ? '  Keep the key inside.\n' : '',
        'revision': 'r1',
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
        (await _refusal(source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', 'r1'))).name,
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
      (await _refusal(source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'REVIEW_ANYWAY', 'r1'))).name,
      'BadAnswer',
    );
  });

  // Harm: a reading response authorizes a different worker or issue than the recorded decision.
  test('HTTP reading decisions refuse mismatched roles and decided issues', () async {
    final (planner, source) = await _started();
    for (final (choice, task) in [
      ('ONE_MORE_ROUND', servedFollowUp(10, role: 'BUILDER')),
      ('ONE_MORE_ROUND', servedFollowUp(10)..['issues'] = ['PR1.1']),
      ('REWORK', servedFollowUp(10)),
      ('REWORK', servedFollowUp(10, role: 'BUILDER')..['issues'] = ['PR1.1']),
      ('REVIEW_ANYWAY', servedFollowUp(10)),
    ]) {
      planner.routes['POST /projects/demo/issues/PR1/reading'] = () => _Reply(200, {
        'revision': 'r2',
        'work': servedContinuationWork(anotherAtWork: true),
        'issue': servedReadingIssue(stopped: false),
        'tasks': [task],
      });
      expect(
        (await _refusal(source.resolveIssueReading('demo', 'ST1', 'PR1', 2, choice, 'r1', comment: 'Fix the finding.')))
            .name,
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
    );
    planner.routes['POST /projects/demo/tasks/main-task/events'] = () => _Reply(200, {
      'revision': 'r1',
      'work': servedContinuationWork(),
      'tasks': [servedFollowUp(10)],
    });
    for (final event in PlanningTaskEvent.values) {
      final answer = await source.taskEvent(
        'demo',
        'main-task',
        event,
        offer: servedOffer(1),
        container: 'container-main-task',
        machine: machine,
        ended: event == PlanningTaskEvent.ended ? ended : null,
      );
      expect(jsonDecode(planner.calls.last.body), {
        'offer': servedOffer(1),
        'event': event.name.toUpperCase(),
        'container': 'container-main-task',
        'machine': machine,
        if (event == PlanningTaskEvent.ended) 'ended': ended.toMap(),
      });
      expect(planner.calls.last.target, '/projects/demo/tasks/main-task/events');
      expect(answer.tasks.map((task) => task.offer), [servedOffer(10)]);
    }
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
    final decision = await source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', 'r1');
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
        () => source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', 'r1'),
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
        (401, 'Unauthorized'),
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
      () => source.resolveIssueReading('demo', 'ST1', 'PR1', 2, 'ONE_MORE_ROUND', 'r1'),
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
    planner.routes['GET /projects/demo/stories/ST1'] = () => refusals-- > 0
        ? _Reply.problem('NoSuchProject', {'project': 'demo'}, 'The project is not known.', 404)
        : planner.answer('GET /projects/demo/stories/ST1');

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
}
