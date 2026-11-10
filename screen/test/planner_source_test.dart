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

  // Harm: a connection that died without a word leaves the board frozen while it looks live.
  test('a stream that falls silent is opened again with the last revision', () async {
    final (planner, source) = await _started(silence: const Duration(milliseconds: 300), backoff: Duration.zero);
    final streams = _Streams(planner);
    final revisions = <String>[];
    final watching = source.watchStories('demo').listen((reply) => revisions.add(reply.revision));
    addTearDown(watching.cancel);

    await _until(() => revisions.isNotEmpty, 'the first board');
    streams.send('r2');
    await _until(() => streams.opened.length == 2, 'a second connection');
    streams.send('r3');
    await _until(() => revisions.length == 3, 'the board after the reconnect');

    expect(streams.lastEventIds, [null, 'r2'], reason: 'the reconnect names the last revision it saw');
    expect(revisions, ['r1', 'r2', 'r3']);
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
