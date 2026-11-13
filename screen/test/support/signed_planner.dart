import 'dart:convert';
import 'dart:io';

import 'package:porelo_planning/src/http/planner_link.dart';

/// What a fake planner does to be taken for the account's planner: it answers the proof, checks each request's
/// signature and proves each answer, as the planner's access filter does.
class SignedPlanner {
  /// Signs and checks under [token].
  SignedPlanner(this.token);

  /// The token it proves and checks under.
  String token;

  /// Whether it answers the proof as a stranger would: a proof under another token.
  bool stranger = false;

  /// Whether its answers carry their proof.
  bool proveAnswers = true;

  /// The proofs it was asked for.
  int proofs = 0;

  /// The bodies of the proofs asked, as sent.
  final proofBodies = <String>[];

  /// Answers [request] when it asks the proof, and says so.
  Future<bool> proof(HttpRequest request, List<int> body) async {
    if (request.method != 'POST' || request.uri.path != '/proof') return false;
    proofs++;
    final text = utf8.decode(body);
    proofBodies.add(text);
    final challenge = base64Url.decode(base64Url.normalize((jsonDecode(text) as Map)['challenge'] as String));
    final port = request.connectionInfo!.localPort;
    request.response.headers.contentType = ContentType.json;
    request.response.write(
      jsonEncode({'proof': PlannerSignature.proof(stranger ? 'x$token' : token, port, challenge)}),
    );
    await request.response.close();
    return true;
  }

  /// The nonce of [request] when its signature holds for it and [body] under the token, else null.
  List<int>? verified(HttpRequest request, List<int> body) {
    final header = request.headers[PlannerSignature.header];
    if (header == null || header.length != 1 || request.headers.value(HttpHeaders.authorizationHeader) != null) {
      return null;
    }
    final parts = RegExp(r'^porelo-planner-request-1;t=([0-9]+);n=([A-Za-z0-9_-]{22});s=[A-Za-z0-9_-]{43}$')
        .firstMatch(header.single);
    if (parts == null) return null;
    final nonce = base64Url.decode(base64Url.normalize(parts.group(2)!));
    final expected = PlannerSignature.request(
      token,
      method: request.method,
      target: request.uri.toString(),
      host: request.headers.value(HttpHeaders.hostHeader) ?? '',
      contentType: request.headers.value(HttpHeaders.contentTypeHeader) ?? '',
      body: body,
      time: int.parse(parts.group(1)!),
      nonce: nonce,
    );
    return expected == header.single ? nonce : null;
  }

  /// Sets the proof of the answer to the request of [nonce] on [response]; [body] null for a stream's opening.
  void prove(HttpResponse response, List<int> nonce, List<int>? body) {
    if (!proveAnswers) return;
    response.headers.set(
      PlannerSignature.answerHeader,
      PlannerSignature.answer(token, nonce, response.statusCode, body),
    );
  }
}
