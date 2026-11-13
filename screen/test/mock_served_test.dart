import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/planning_fake.dart';
import 'package:porelo_planning/porelo_planning.dart';

import 'support/signed_planner.dart';

const _token = 'tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz';

/// The planner's mock mode as recorded by the planner's own test, which fails when the mock answers otherwise.
final _served = Directory('test/served/mock');

String _file(String name) => File('${_served.path}/$name').readAsStringSync();

/// The bodies the planner's own test sent for the answers recorded, as the planner reads them.
final _sent = <String, Object>{
  'PUT /projects/demo': {'repository': 'https://example.org/porelo/porelo.git', 'branch': 'main'},
  'POST /projects/demo/moves/preview': {
    'revision': 'mock@1',
    'moves': [
      {'issue': 'B542', 'to': 'Review'},
    ],
  },
  'POST /projects/demo/moves': {
    'revision': 'mock@1',
    'moves': [
      {'issue': 'B542', 'to': 'Review'},
    ],
    'digest': (jsonDecode(_file('preview-B542.json')) as Map<String, dynamic>)['digest'],
    'confirm': false,
  },
};

/// Serves the recorded answers at the paths the screen calls, signed under the token, refusing anything else.
class _RecordedMock {
  late final HttpServer _server;
  final unexpected = <String>[];
  final refused = <String>[];

  Uri get base => Uri(scheme: 'http', host: '127.0.0.1', port: _server.port);

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen(_answer);
  }

  Future<void> stop() => _server.close(force: true);

  final signing = SignedPlanner(_token);

  Future<void> _answer(HttpRequest request) async {
    final call = '${request.method} ${request.uri.path}';
    final bytes = [for (final chunk in await request.toList()) ...chunk];
    if (await signing.proof(request, bytes)) return;
    final body = utf8.decode(bytes);
    final response = request.response;
    final nonce = signing.verified(request, bytes);
    if (nonce == null) {
      refused.add(call);
      response.statusCode = HttpStatus.unauthorized;
      await response.close();
      return;
    }
    final file = switch (call) {
      'PUT /projects/demo' => null,
      'GET /projects/demo/workflow' => 'workflow.json',
      'GET /projects/demo/stories' => 'stories.json',
      'GET /projects/demo/issues/B541' => 'issue-B541.json',
      'POST /projects/demo/moves/preview' => 'preview-B542.json',
      'POST /projects/demo/moves' => 'move-B542.json',
      'GET /projects/demo/events' => 'board-event.json',
      _ => '',
    };
    if (file == '') {
      unexpected.add(call);
      response.statusCode = HttpStatus.notFound;
      signing.prove(response, nonce, const []);
      await response.close();
      return;
    }
    // The recorded answer holds only for the request the planner's own test sent to get it.
    if (_sent.containsKey(call) && jsonEncode(jsonDecode(body)) != jsonEncode(_sent[call])) {
      unexpected.add('$call $body');
      response.statusCode = HttpStatus.badRequest;
      signing.prove(response, nonce, const []);
      await response.close();
      return;
    }
    if (file == null) {
      response.statusCode = HttpStatus.noContent;
      signing.prove(response, nonce, const []);
      await response.close();
      return;
    }
    if (file == 'board-event.json') {
      final data = jsonEncode(jsonDecode(_file(file)));
      final revision = (jsonDecode(data) as Map<String, dynamic>)['revision'];
      response.headers.contentType = ContentType('text', 'event-stream');
      signing.prove(response, nonce, null);
      response.bufferOutput = false;
      response.add(utf8.encode('event:board\nid:$revision\ndata:$data\n\n'));
      await response.flush();
      // The stream stays open, as the planner's does, until the test ends.
      return;
    }
    final answer = utf8.encode(_file(file));
    response.headers.contentType = ContentType.json;
    signing.prove(response, nonce, answer);
    response.add(answer);
    await response.close();
  }
}

/// What the board shows of a snapshot, in the order served.
List<String> _shown(PlanningSnapshot snapshot) => [
  for (final state in snapshot.states) 'state ${state.name} ${state.kind}',
  for (final phase in snapshot.phases) 'phase ${phase.name} ${phase.states}',
  for (final story in snapshot.stories) ...[
    'story ${story.id} ${story.state} ${story.title}',
    for (final issue in story.issues)
      'issue ${issue.id} ${issue.repository} ${issue.state} ${issue.worker?.wire} ${issue.blockedBy} ${issue.parent}',
  ],
];

Future<(_RecordedMock, HttpPlanningSource)> _started() async {
  final mock = _RecordedMock();
  await mock.start();
  addTearDown(mock.stop);
  final source = HttpPlanningSource(
    mock.base,
    _token,
    (project) => PlannerProject('https://example.org/porelo/porelo.git', 'main'),
  );
  addTearDown(source.close);
  return (mock, source);
}

void main() {
  // Harm: the screen tested only against answers written by hand reads a field of the real planner under another
  // name, and the window's board shows it empty or refuses the planner's answer.
  test('the screen reads the mock\'s board as the board the window\'s mock tests assert', () async {
    final (mock, source) = await _started();
    final reference = PlanningSnapshot.from(mockPlanningBoard());

    final read = await source.stories('demo');

    expect(read.revision, 'mock@1', reason: 'the revision the mock serves first');
    expect(read.states, isNotEmpty, reason: 'the workflow\'s states were read');
    expect(read.stories, isNotEmpty, reason: 'the stories were read');
    expect(_shown(read), _shown(reference), reason: 'every state, phase, story and issue as the reference has it');
    final issue = read.stories.expand((story) => story.issues).firstWhere((issue) => issue.id == 'B541');
    expect(
      '${(issue.repository, issue.worker, issue.blockedBy)}',
      '${('porelo', IssueWorker.agent, ['B540'])}',
      reason: 'an issue\'s repository, worker and what it waits on, as the mock serves them',
    );
    expect(mock.unexpected, isEmpty, reason: 'the screen called only what the mock recorded');
    expect(mock.refused, isEmpty, reason: 'every call carried the token');
  });

  test('the screen reads the mock\'s board event as the board it read', () async {
    final (mock, source) = await _started();

    final watched = await source.watchStories('demo').first.timeout(const Duration(seconds: 10));

    expect(_shown(watched), _shown(await source.stories('demo')), reason: 'the event carries the board the reads do');
    expect(watched.repositories, isNotEmpty, reason: 'the event names the project\'s repositories');
    expect(mock.unexpected, isEmpty, reason: 'the screen called only what the mock recorded');
  });

  test('the screen reads one issue and one move as the mock answers them', () async {
    final (mock, source) = await _started();

    final detail = await source.getIssue('demo', 'B541');
    const moves = [PlanningIssueMove('B542', 'Review')];
    final preview = await source.previewMoves('demo', moves, 'mock@1');
    final moved = await source.saveMoves('demo', moves, 'mock@1', digest: preview.digest);

    expect(
      '${(detail.issue?.id, detail.issue?.state, detail.whatMustBeTrue)}',
      '${('B541', 'Implementing', ['The work meets the story description.'])}',
      reason: 'the issue\'s fields as the mock serves them',
    );
    expect(
      '${(moved.revision, moved.moved)}',
      '${('mock@2', ['B542'])}',
      reason: 'the move\'s answer as the mock gives it',
    );
    expect(mock.unexpected, isEmpty, reason: 'the screen called only what the mock recorded');
  });
}
