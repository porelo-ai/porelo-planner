import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

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
typedef _Call = ({String method, String target, String? authorization, String body});

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
      ));
      final route = '${request.method} ${request.uri.path}';
      final reply = await (routes[route] ?? () => answer(route))();
      request.response.statusCode = reply.status;
      if (reply.raw case final raw?) {
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
        request.response.write(reply.body is String ? reply.body : jsonEncode(reply.body));
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
  );
  addTearDown(source.close);
  return (planner, source);
}

Future<PlanningRefusal> _refusal(Future<Object?> call) async {
  try {
    await call;
  } on PlanningRefusal catch (refusal) {
    return refusal;
  }
  fail('the call was not refused');
}

void main() {
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

  // Harm: the board's watch never gives the first board, or hangs open with nothing behind it.
  test('the watch answers the board once and ends', () async {
    final (_, source) = await _started();

    final replies = await source.watchStories('demo').toList();

    expect(replies.single.stories.length, 2, reason: 'one whole board, then the end of the stream');
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

  // Harm: the screen offers a feature the planner cannot do, and the person's change is lost.
  test('what the planner does not offer is unsupported', () async {
    final (planner, source) = await _started();

    final calls = <Future<Object?> Function()>[
      () => source.saveMoves('demo', const [], 'r1'),
      () => source.proposeStoryWork('demo', 'ST1', 'Building'),
      () => source.getStoryWork('demo', 'ST1'),
      () => source.stopStoryWork('demo', 'ST1'),
      () => source.getPlanningIssue('demo', 'PR1'),
      () => source.resolveIssueReading('demo', 'ST1', 'PR1', 1, 'accept', 'r1'),
    ];

    for (final call in calls) {
      await expectLater(call(), throwsA(isA<PlanningUnsupported>()));
    }
    expect(planner.calls, isEmpty, reason: 'nothing unsupported reaches the planner');
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
    final runtime = await Directory.systemTemp.createTemp('planner-address');
    addTearDown(() => runtime.delete(recursive: true));
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

  // Harm: without XDG_RUNTIME_DIR the window looks elsewhere than the planner wrote, and finds no planner.
  test(
    'without a runtime directory the address is read where the planner falls back to, by this account\'s uid',
    () async {
      final runUser = await Directory.systemTemp.createTemp('run-user');
      addTearDown(() => runUser.delete(recursive: true));
      final uid = int.parse(((await Process.run('id', ['-u'])).stdout as String).trim());
      final file = File('${runUser.path}/$uid/porelo-planner/planner.json');
      await file.create(recursive: true);
      await file.writeAsString('{"port": 4712, "token": "$_token"}');
      await Process.run('chmod', ['600', file.path]);

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
    final runtime = await Directory.systemTemp.createTemp('planner-address');
    addTearDown(() => runtime.delete(recursive: true));
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
