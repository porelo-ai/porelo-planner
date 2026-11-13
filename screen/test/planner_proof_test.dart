import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/http/planner_link.dart';

import 'support/signed_planner.dart';

const _token = 'tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz';

/// A listener on the loopback address: the account's planner, or a stranger on its port, recording what reached it.
class _Listener {
  _Listener(String token) : signing = SignedPlanner(token);

  final SignedPlanner signing;
  late HttpServer _server;

  /// Every request but a proof, as method and target, signed or not.
  final requests = <String>[];

  /// The bodies of those requests.
  final bodies = <String>[];

  /// Whether the next signed requests are refused with 401 though they hold.
  bool refuse = false;

  int get port => _server.port;

  PlannerAddress get address => PlannerAddress(port, signing.token);

  Future<void> start([int port = 0]) async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, port)
      ..idleTimeout = const Duration(milliseconds: 100);
    _server.listen((request) async {
      final bytes = [for (final chunk in await request.toList()) ...chunk];
      if (await signing.proof(request, bytes)) return;
      requests.add('${request.method} ${request.uri}');
      bodies.add(utf8.decode(bytes, allowMalformed: true));
      final nonce = signing.verified(request, bytes);
      final response = request.response;
      if (nonce == null || refuse) {
        response.statusCode = HttpStatus.unauthorized;
        await response.close();
        return;
      }
      if (request.uri.path.endsWith('/events')) {
        response.headers.contentType = ContentType('text', 'event-stream');
        signing.prove(response, nonce, null);
        response.bufferOutput = false;
        response.add(utf8.encode(': keep-alive\n\n'));
        await response.flush();
        return;
      }
      if (request.method == 'PUT') {
        response.statusCode = HttpStatus.noContent;
        signing.prove(response, nonce, const []);
        await response.close();
        return;
      }
      final answer = utf8.encode(jsonEncode({'revision': 'r1', 'id': 'ST7'}));
      response.headers.contentType = ContentType.json;
      signing.prove(response, nonce, answer);
      response.add(answer);
      await response.close();
    });
    addTearDown(stop);
  }

  Future<void> stop({bool force = true}) => _server.close(force: force);
}

Future<_Listener> _listening(String token, {bool stranger = false, int port = 0}) async {
  final listener = _Listener(token);
  listener.signing.stranger = stranger;
  await listener.start(port);
  return listener;
}

HttpPlanningSource _source(Future<PlannerAddress> Function() address, PlannerAddress first) {
  final source = HttpPlanningSource(
    first.base,
    first.token,
    (project) => PlannerProject('https://example.org/$project.git'),
    address: address,
    timeout: const Duration(seconds: 5),
    proofTimeout: const Duration(seconds: 5),
    backoff: const Duration(seconds: 30),
  );
  addTearDown(source.close);
  return source;
}

Future<PlanningRefusal> _refusal(Future<Object?> call) async {
  try {
    await call;
  } on PlanningRefusal catch (refusal) {
    return refusal;
  }
  fail('the call was not refused');
}

List<int> _count(int bytes) => List.generate(bytes, (at) => at);

void main() {
  // Harm: a screen and a planner that sign differently refuse each other, found only on the person's computer.
  test('the screen signs and checks the forms the planner signs, to the same fixed values', () {
    expect(
      PlannerSignature.request(
        _token,
        method: 'POST',
        target: '/projects/demo/ideas?x=1',
        host: '127.0.0.1:4711',
        contentType: 'application/json; charset=utf-8',
        body: utf8.encode('{"idea":"x"}'),
        time: 1800000000,
        nonce: _count(16),
      ),
      'porelo-planner-request-1;t=1800000000;n=AAECAwQFBgcICQoLDA0ODw;s=4Vur7C18Dz3uxlbrMbxTrL7VIRRQBCRjx-CBrJxuzaE',
      reason: 'the request\'s signature',
    );
    expect(
      PlannerSignature.answer(_token, _count(16), 201, utf8.encode('{"id":"ST1"}')),
      'porelo-planner-answer-1;s=nP2ZBcDt4afnTDrHz8reZCBadY-KOUD1Lkjil65U2Cc',
      reason: 'the answer\'s proof',
    );
    expect(
      PlannerSignature.answer(_token, _count(16), 200, null),
      'porelo-planner-answer-1;s=w3qfSAGgHu79JuGttleQ9q8npIjDKswo5wYO4D-LIZw',
      reason: 'the stream opening\'s proof',
    );
    expect(
      PlannerSignature.proof(_token, 4711, _count(32)),
      'oODykTsTVjhY8BiPkAiMsEQ9--YGs0x_nHExTNHyrXk',
      reason: 'the proof',
    );
  });

  // Harm: the project's name or an idea given to another account's process that took the planner's port.
  test('a listener that cannot prove the token receives no request', () async {
    final stranger = await _listening(_token, stranger: true);
    final source = _source(() async => stranger.address, stranger.address);

    final refusal = await _refusal(source.stories('demo'));

    expect(refusal, isA<PlannerNotProven>(), reason: 'the listener is named as not the account\'s planner');
    expect((refusal as PlannerNotProven).failure, PlannerProofFailure.proofFailed, reason: 'a proof failed');
    expect(refusal.port, stranger.port, reason: 'the log names the address file\'s port');
    expect(stranger.signing.proofs, 1, reason: 'the proof was asked');
    expect(stranger.requests, isEmpty, reason: 'nothing but the proof reached the stranger');
  });

  // Harm: a stranger that binds the freed port after the planner ended receives the next request.
  test('a listener that takes the port of a proven planner receives nothing after the change', () async {
    final planner = await _listening(_token);
    final address = planner.address;
    final source = _source(() async => address, address);
    await source.stories('demo').then<void>((_) {}, onError: (Object _) {});
    expect(planner.requests, isNotEmpty, reason: 'the account\'s planner was reached');

    // The planner closes its idle connection and ends, so the next request opens a new one with no error to see.
    await Future<void>.delayed(const Duration(milliseconds: 300));
    final port = planner.port;
    await planner.stop(force: false);
    final stranger = await _listening(_token, stranger: true, port: port);
    final read = await _refusal(source.getStory('demo', 'ST1'));

    expect(stranger.signing.proofs, 1, reason: 'the stranger was asked the proof before anything else');
    expect(stranger.requests, isEmpty, reason: 'nothing of the person\'s reached the stranger');
    expect(read, isA<PlannerNotProven>(), reason: 'the read names the stranger');
  });

  // Harm: a forged board or result from a process on the port is shown to the person as the planner's.
  test('an answer or a stream opening without its proof is refused', () async {
    final planner = await _listening(_token);
    planner.signing.proveAnswers = false;
    final source = _source(() async => planner.address, planner.address);

    final read = await _refusal(source.getStory('demo', 'ST1'));
    final streamed = await _refusal(_source(() async => planner.address, planner.address).watchStories('demo').first);

    expect(read, isA<PlannerNotProven>(), reason: 'an answer without its proof');
    expect(streamed, isA<PlannerNotProven>(), reason: 'a stream opening without its proof');
  });

  // Harm: a stranger on the port is asked again and again, or a planner started anew is not found until the wait ends.
  test('after a failed proof nothing is sent until the address file names another planner', () async {
    final stranger = await _listening(_token, stranger: true);
    var address = stranger.address;
    final source = _source(() async => address, stranger.address);
    await _refusal(source.stories('demo'));
    await _refusal(source.stories('demo'));
    expect(stranger.signing.proofs, 1, reason: 'no second proof within the back-off');

    final planner = await _listening('another-$_token');
    address = planner.address;
    await source.stories('demo').then<void>((_) {}, onError: (Object _) {});

    expect(planner.signing.proofs, 1, reason: 'the planner the address file names now is proven at once');
    expect(planner.requests, isNotEmpty, reason: 'and called');
    expect(stranger.requests, isEmpty, reason: 'the stranger got nothing');
  });

  // Harm: a planner started anew on the same port is taken for a stranger, or its own refusal is retried for ever.
  test('a 401 sends the request once more only to a planner proven anew with another token', () async {
    final planner = await _listening(_token);
    var address = planner.address;
    final source = _source(() async => address, planner.address);
    await source.stories('demo').then<void>((_) {}, onError: (Object _) {});
    final before = planner.requests.length;

    planner.signing.token = 'restarted-$_token';
    address = planner.address;
    await source.stories('demo').then<void>((_) {}, onError: (Object _) {});
    expect(
      planner.requests.length,
      before + 3,
      reason: 'the refused read of the workflow, sent once more, then the stories',
    );

    planner.refuse = true;
    final refusal = await _refusal(source.getStory('demo', 'ST1'));
    expect(refusal, isA<PlannerNotProven>(), reason: 'its own planner refused the signature');
    expect((refusal as PlannerNotProven).failure, PlannerProofFailure.signatureRefused, reason: 'the log says so');
  });

  // Harm: the window's own calls send an idea to a stranger, or take a stranger's answer for the planner's.
  test('the window\'s call proves, signs and checks the answer, and sends nothing to a stranger', () async {
    final planner = await _listening(_token);
    final answer = await callPlanner(
      'POST',
      const ['projects', 'demo', 'ideas'],
      json: {'offer': 'o', 'idea': 'a private idea'},
      address: () async => planner.address,
    );
    expect(answer.status, 200, reason: 'the planner answered');
    expect(answer.json?['id'], 'ST7', reason: 'its answer is read');
    expect(planner.bodies.single, contains('a private idea'), reason: 'the idea reached the planner');

    final stranger = await _listening(_token, stranger: true);
    final refusal = await _refusal(
      callPlanner(
        'POST',
        const ['projects', 'demo', 'ideas'],
        json: {'offer': 'o', 'idea': 'a private idea'},
        address: () async => stranger.address,
      ),
    );
    expect(refusal, isA<PlannerNotProven>(), reason: 'the stranger is named');
    expect(stranger.requests, isEmpty, reason: 'the idea never reached the stranger');
  });

  // Harm: a proof spent on a pooled connection lets a later new connection reach a stranger on the freed port unproven.
  test('a proof used up by a reused connection is asked again before the next new connection', () async {
    final planner = await _listening(_token);
    final address = planner.address;
    final link = PlannerLink(
      () async => address,
      timeout: const Duration(seconds: 5),
      proofTimeout: const Duration(seconds: 5),
    );
    addTearDown(link.close);
    Future<void> get() async {
      final signed = await link.open(await link.proven(), 'GET', Uri(path: '/projects/demo/stories'));
      await (await signed.request.close()).drain<void>();
    }

    await get();
    // Proven again while the first connection still waits in the pool, which the next request then reuses.
    link.forget();
    await get();
    expect(planner.signing.proofs, 2, reason: 'each proof was asked of the account\'s planner');

    await Future<void>.delayed(const Duration(milliseconds: 300));
    final port = planner.port;
    await planner.stop(force: false);
    final stranger = await _listening(_token, stranger: true, port: port);
    await get().then<void>((_) {}, onError: (Object _) {});

    expect(stranger.signing.proofs, 1, reason: 'the new connection was preceded by a proof');
    expect(stranger.requests, isEmpty, reason: 'nothing of the person\'s reached the stranger');
  });
}
