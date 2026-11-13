import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import '../client.dart';
import 'planner_source.dart';

/// Why a listener on the planner's port was not taken for this account's planner, for the log alone.
enum PlannerProofFailure {
  /// It answered no proof of the token, a wrong one, or an answer without its proof.
  proofFailed,

  /// It proved the token, yet refused a request signed under it, such as for a clock far off.
  signatureRefused,
}

/// The listener on the planner's port is not this account's planner, or an older one: nothing more is sent to it.
///
/// Never transient: a watch ends with it, and the screen calls the port again only after a back-off, or at once when
/// the address file names another port or token.
class PlannerNotProven extends PlanningRefusal {
  /// Takes why and the port of the address file the listener was found on.
  const PlannerNotProven(this.failure, this.port) : super('PlannerNotProven', const {}, words);

  /// The fixed words for the person.
  static const words =
      'The planner answering is not this account\'s planner, or it is an older one. Opening Planning again starts '
      'this account\'s own.';

  /// Why, for the log alone.
  final PlannerProofFailure failure;

  /// The address file's port.
  final int port;

  /// One line for the log: why and the port, never the token or a signature.
  String get logLine => switch (failure) {
    PlannerProofFailure.proofFailed => 'the listener on port $port did not prove the planner\'s token',
    PlannerProofFailure.signatureRefused => 'the planner on port $port refused a signed request',
  };
}

/// The forms the screen signs and checks under the planner's token, as the planner's REST description gives them.
abstract final class PlannerSignature {
  /// The header a request's signature travels in.
  static const header = 'porelo-signature';

  /// The header an answer's proof travels in.
  static const answerHeader = 'porelo-answer';

  static const _request = 'porelo-planner-request-1';
  static const _answer = 'porelo-planner-answer-1';
  static const _proof = 'porelo-planner-proof-1';

  /// The signature header of a request.
  static String request(
    String token, {
    required String method,
    required String target,
    required String host,
    required String contentType,
    required List<int> body,
    required int time,
    required List<int> nonce,
  }) {
    final form = _labeled(_request)
      ..add(_field(ascii.encode(method)))
      ..add(_field(utf8.encode(target)))
      ..add(_field(utf8.encode(host)))
      ..add(_field(utf8.encode(contentType)))
      ..add(_field(sha256.convert(body).bytes))
      ..add(_field(ascii.encode('$time')))
      ..add(_field(nonce));
    return '$_request;t=$time;n=${base64(nonce)};s=${base64(_mac(token, form.takeBytes()))}';
  }

  /// The proof header an answer must carry; [body] is null for the opening of the changes stream.
  static String answer(String token, List<int> nonce, int status, List<int>? body) {
    final form = _labeled(_answer)
      ..add(_field(nonce))
      ..add(_field(ascii.encode('$status')))
      ..add(_field(body == null ? const [] : sha256.convert(body).bytes));
    return '$_answer;s=${base64(_mac(token, form.takeBytes()))}';
  }

  /// The proof the planner on [port] must answer for [challenge].
  static String proof(String token, int port, List<int> challenge) {
    final form = _labeled(_proof)
      ..add([port >> 8 & 0xff, port & 0xff])
      ..add(challenge);
    return base64(_mac(token, form.takeBytes()));
  }

  /// Base64url without padding.
  static String base64(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

  /// Whether two texts are equal, in a time that does not depend on where they differ.
  static bool same(String? given, String expected) {
    if (given == null) return false;
    final a = utf8.encode(given);
    final b = utf8.encode(expected);
    var differ = a.length ^ b.length;
    for (var at = 0; at < b.length; at++) {
      differ |= (at < a.length ? a[at] : 0) ^ b[at];
    }
    return differ == 0;
  }

  static List<int> _mac(String token, List<int> message) => Hmac(sha256, utf8.encode(token)).convert(message).bytes;

  static BytesBuilder _labeled(String label) => BytesBuilder(copy: false)
    ..add(ascii.encode(label))
    ..addByte(0);

  static List<int> _field(List<int> bytes) {
    final length = bytes.length;
    return [length >> 24 & 0xff, length >> 16 & 0xff, length >> 8 & 0xff, length & 0xff, ...bytes];
  }
}

/// The address a listener proved it holds the token of, and the base to call it at.
typedef PlannerProven = ({PlannerAddress address, Uri base});

/// A request signed under the proven planner's token, and the nonce its answer must be proven over.
typedef PlannerSigned = ({HttpClientRequest request, List<int> nonce, PlannerProven proven});

/// The screen's link to this account's planner: it reads the address, has the listener on its port prove the token
/// before anything else is sent, signs each request and checks each answer's proof.
///
/// It proves again after [forget] - a connection error, a timeout, a stream opened again or a 401 - and whenever a
/// read of the address finds another port or token. After a failed proof it sends nothing until a back-off, from
/// [backoff] doubling up to [backoffMost], has passed, or the address file names another port or token.
class PlannerLink {
  /// Reads the address with [read]; calls [host], a loopback address.
  PlannerLink(
    this._read, {
    this.host = '127.0.0.1',
    this.timeout = const Duration(seconds: 30),
    this.proofTimeout = const Duration(seconds: 60),
    this.backoff = const Duration(seconds: 1),
    this.backoffMost = const Duration(seconds: 30),
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now,
       _wait = backoff {
    for (final each in [client, _proofs]) {
      each
        ..connectionTimeout = timeout
        // Inflating a compressed answer would pass the bound on the bytes received.
        ..autoUncompress = false
        // Straight to the loopback address, never through a proxy.
        ..findProxy = ((_) => 'DIRECT');
    }
    client.connectionFactory = _connect;
  }

  final Future<PlannerAddress> Function() _read;

  /// The loopback address the planner is called at.
  final String host;

  /// How long one request may take.
  final Duration timeout;

  /// How long the proof may take, longer than a request, so a planner still starting is not taken for a stranger.
  final Duration proofTimeout;

  /// The first wait after a failed proof.
  final Duration backoff;

  /// The longest wait after failed proofs.
  final Duration backoffMost;

  final DateTime Function() _clock;

  /// The client every request goes through; each new connection it opens is preceded by a proof.
  final HttpClient client = HttpClient();

  /// The client the proofs go through, apart, so asking one opens no connection that needs one.
  final HttpClient _proofs = HttpClient();

  /// Whether a proof was answered and no request opened since, so the next new connection needs none of its own.
  bool _fresh = false;

  static const _proofMost = 1024;
  static final _random = Random.secure();

  PlannerProven? _proven;
  Future<PlannerProven>? _proving;
  PlannerNotProven? _failure;
  PlannerAddress? _failedAt;
  DateTime? _retry;
  Duration _wait;

  /// The port proven last or read last, for what the screen shows; null before the first read.
  int? get port => _proven?.address.port ?? _failedAt?.port;

  /// Closes the connections it holds.
  void close() {
    client.close(force: true);
    _proofs.close(force: true);
  }

  /// Opens a connection for a request only to a listener that proves the token right before: a planner that ended
  /// leaves its port free, and a connection opened after that reaches whoever bound it, with no error to say so.
  Future<ConnectionTask<Socket>> _connect(Uri uri, String? proxyHost, int? proxyPort) async {
    final proven = _proven;
    if (proven == null || proven.address.port != uri.port || proven.base.host != uri.host) {
      throw const SocketException('the planner was not proven for this connection');
    }
    if (!_fresh) {
      final bool proves;
      try {
        proves = await _asked(proven.address, proven.base);
      } on PlanningRefusal catch (refusal) {
        // Nothing listens: lost as a connection is, so the caller proves again and a watch opens again.
        throw SocketException(refusal.description);
      }
      if (!proves) throw _failed(proven.address, PlannerProofFailure.proofFailed);
    }
    _fresh = false;
    return Socket.startConnect(uri.host, uri.port);
  }

  /// Proves the planner again before the next request.
  void forget([PlannerProven? used]) {
    if (used == null || identical(_proven, used)) _proven = null;
  }

  /// The proven planner, proving it first when nothing is proven: the address read again, then the proof asked.
  ///
  /// Throws [PlannerNotProven] while a failed proof's back-off lasts and the address is the same, a refusal named
  /// `NoAddress` or `BadAddress` for an address that cannot be read, `PlannerUnreachable` when nothing listens.
  Future<PlannerProven> proven() {
    if (_proven case final proven?) return Future.value(proven);
    return _proving ??= _prove().whenComplete(() => _proving = null);
  }

  Future<PlannerProven> _prove() async {
    final address = await _read();
    if (_failure case final failure?) {
      if (_failedAt?.port != address.port || _failedAt?.token != address.token) {
        // Another planner wrote the file: it is proven at once, and the waits start over.
        _wait = backoff;
      } else if (_clock().isBefore(_retry!)) {
        throw failure;
      }
    }
    final base = Uri(scheme: 'http', host: host, port: address.port);
    if (!await _asked(address, base)) throw _failed(address, PlannerProofFailure.proofFailed);
    _failure = null;
    _wait = backoff;
    _fresh = true;
    return _proven = (address: address, base: base);
  }

  /// Records a failure and starts its back-off; nothing is sent to the port until it has passed.
  PlannerNotProven _failed(PlannerAddress address, PlannerProofFailure why) {
    final failure = PlannerNotProven(why, address.port);
    if (identical(_proven?.address, address)) _proven = null;
    _failure = failure;
    _failedAt = address;
    _retry = _clock().add(_wait);
    final doubled = _wait * 2;
    _wait = doubled > backoffMost ? backoffMost : doubled;
    return failure;
  }

  /// Fails the proven planner for an answer without its proof.
  PlannerNotProven unproven(PlannerProven proven) => _failed(proven.address, PlannerProofFailure.proofFailed);

  /// After a 401 to a request signed for [used]: proves the planner again, the address read again; answers whether
  /// another planner, by its port or token, is proven now, so the request may be sent once more, and throws
  /// [PlannerNotProven] when the same planner refused its own signature.
  Future<bool> refused(PlannerProven used) async {
    forget(used);
    final now = await proven();
    if (now.address.port != used.address.port || now.address.token != used.address.token) return true;
    throw _failed(now.address, PlannerProofFailure.signatureRefused);
  }

  /// Asks the listener at [base] for its proof of the token of [address]; false for anything but the one proof.
  Future<bool> _asked(PlannerAddress address, Uri base) async {
    final challenge = List<int>.generate(32, (_) => _random.nextInt(256));
    final body = utf8.encode(jsonEncode({'challenge': PlannerSignature.base64(challenge)}));
    HttpClientRequest? request;
    try {
      return await () async {
        request = await _proofs.openUrl('POST', base.replace(path: '/proof'));
        final sent = request!
          ..followRedirects = false
          // Closed once answered: a proof's connection serves nothing else.
          ..persistentConnection = false
          ..headers.set(HttpHeaders.contentTypeHeader, 'application/json')
          ..headers.set(HttpHeaders.acceptHeader, 'application/json')
          ..contentLength = body.length
          ..add(body);
        final response = await sent.close();
        final bytes = BytesBuilder(copy: false);
        if (response.contentLength > _proofMost) {
          await response.listen(null).cancel();
          return false;
        }
        await for (final chunk in response) {
          bytes.add(chunk);
          if (bytes.length > _proofMost) return false;
        }
        if (response.statusCode != HttpStatus.ok || _mime(response) != 'application/json') return false;
        final Object? json;
        try {
          json = jsonDecode(utf8.decode(bytes.takeBytes()));
        } on FormatException {
          return false;
        }
        if (json is! Map<String, dynamic> || json.length != 1 || json['proof'] is! String) return false;
        return PlannerSignature.same(
          json['proof'] as String,
          PlannerSignature.proof(address.token, address.port, challenge),
        );
      }().timeout(proofTimeout);
    } on TimeoutException {
      request?.abort();
      return false;
    } on SocketException catch (error) {
      if (request == null) {
        throw PlanningRefusal(
          'PlannerUnreachable',
          const {},
          'the planner cannot be reached: ${error.osError?.message ?? error.message}',
        );
      }
      return false;
    } on HttpException {
      if (request == null) {
        throw const PlanningRefusal('PlannerUnreachable', {}, 'the planner cannot be reached');
      }
      return false;
    }
  }

  static String? _mime(HttpClientResponse response) {
    try {
      return response.headers.contentType?.mimeType;
    } on HttpException {
      return null;
    }
  }

  /// Opens a request to [path], a URI whose path and query count, at the proven planner, with [headers], signed with
  /// its body and content type; the caller closes it and checks its answer with [proves].
  Future<PlannerSigned> open(
    PlannerProven proven,
    String method,
    Uri path, {
    List<int>? body,
    String? type,
    Map<String, String> headers = const {},
  }) async {
    final uri = proven.base.replace(pathSegments: path.pathSegments, query: path.hasQuery ? path.query : null);
    final request = await client.openUrl(method, uri);
    // A request on a pooled connection spends the proof too, or a later new connection would go out unproven.
    _fresh = false;
    request.followRedirects = false;
    headers.forEach(request.headers.set);
    if (type != null) request.headers.set(HttpHeaders.contentTypeHeader, type);
    final bytes = body ?? const <int>[];
    final nonce = List<int>.generate(16, (_) => _random.nextInt(256));
    final target = '${uri.path.isEmpty ? '/' : uri.path}${uri.hasQuery ? '?${uri.query}' : ''}';
    request.headers.set(
      PlannerSignature.header,
      PlannerSignature.request(
        proven.address.token,
        method: method,
        target: target,
        host: request.headers.value(HttpHeaders.hostHeader) ?? '',
        contentType: type ?? '',
        body: bytes,
        time: _clock().toUtc().millisecondsSinceEpoch ~/ 1000,
        nonce: nonce,
      ),
    );
    if (body != null) {
      request
        ..contentLength = bytes.length
        ..add(bytes);
    }
    return (request: request, nonce: nonce, proven: proven);
  }

  /// Whether [response], with [body] as read or null for the opening of the changes stream, carries the proof of
  /// the planner [signed] went to.
  static bool proves(PlannerSigned signed, HttpClientResponse response, List<int>? body) => PlannerSignature.same(
    response.headers.value(PlannerSignature.answerHeader),
    PlannerSignature.answer(signed.proven.address.token, signed.nonce, response.statusCode, body),
  );
}
