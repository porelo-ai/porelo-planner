import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../client.dart';
import '../models.dart';

/// Where a project's planning is kept, as the planner is told it before its first read.
class PlannerProject {
  /// Takes the project's own repository's clone URL and the branch to read, the default branch when null.
  const PlannerProject(this.repository, [this.branch]);

  /// The clone URL of the project's own repository.
  final String repository;

  /// The branch to read and write, or null for the repository's default branch.
  final String? branch;

  Map<String, dynamic> _toJson() => {'repository': repository, 'branch': ?branch};

  @override
  bool operator ==(Object other) => other is PlannerProject && other.repository == repository && other.branch == branch;

  @override
  int get hashCode => Object.hash(repository, branch);
}

/// Answers where [project]'s planning is kept; it may throw when the window knows no repository for it.
typedef PlannerProjects = FutureOr<PlannerProject> Function(String project);

/// The planner's address as the planner writes it in `porelo-planner/planner.json` under the runtime directory: a
/// port on the loopback address and the token every call carries.
class PlannerAddress {
  /// Takes a port and a token, refusing what the planner would never have written.
  PlannerAddress(this.port, this.token) {
    if (port < 1 || port > 65535) {
      throw const PlanningRefusal('BadAddress', {}, 'the planner port is out of range');
    }
    if (!_token.hasMatch(token)) {
      throw const PlanningRefusal('BadAddress', {}, 'the planner token is empty or malformed');
    }
  }

  /// Reads the address file's text: `{"port": <int>, "token": "<text>"}`.
  factory PlannerAddress.parse(String text) {
    final Object? json;
    try {
      json = jsonDecode(text);
    } on FormatException {
      throw const PlanningRefusal('BadAddress', {}, 'the planner address file is not JSON');
    }
    if (json is! Map<String, dynamic> || json['port'] is! int || json['token'] is! String) {
      throw const PlanningRefusal('BadAddress', {}, 'the planner address file lacks a port or a token');
    }
    return PlannerAddress(json['port'] as int, json['token'] as String);
  }

  /// Reads the address file the planner of this account wrote under [runtimeDirectory], `XDG_RUNTIME_DIR` when
  /// null; when that is blank, under `<runUser>/<uid>` as the planner falls back, with the uid [uid] answers.
  static Future<PlannerAddress> read({
    String? runtimeDirectory,
    String runUser = '/run/user',
    Future<int?> Function() uid = _ownUid,
  }) async {
    var runtime = runtimeDirectory ?? Platform.environment['XDG_RUNTIME_DIR'] ?? '';
    if (runtime.trim().isEmpty) {
      final own = await uid();
      if (own == null) {
        throw const PlanningRefusal('NoAddress', {}, 'XDG_RUNTIME_DIR is not set and this account\'s uid is unknown');
      }
      runtime = '$runUser/$own';
    }
    if (!runtime.startsWith('/')) {
      throw const PlanningRefusal('NoAddress', {}, 'XDG_RUNTIME_DIR is not an absolute path');
    }
    final path = '$runtime/$directory/$file';
    RandomAccessFile? opened;
    try {
      if (await FileSystemEntity.type(path, followLinks: false) != FileSystemEntityType.file) {
        throw PlanningRefusal('NoAddress', const {}, 'no planner runs: $path is not a file');
      }
      // The planner writes it owner-only; a file others may read has leaked its token or was not written by it.
      if ((await File(path).stat()).mode & 0x3f != 0) {
        throw PlanningRefusal('BadAddress', const {}, '$path may be read or written by other accounts');
      }
      opened = await File(path).open();
      final bytes = await opened.read(_addressBytes + 1);
      if (bytes.length > _addressBytes) {
        throw PlanningRefusal('BadAddress', const {}, '$path is too large');
      }
      return PlannerAddress.parse(utf8.decode(bytes, allowMalformed: true));
    } on FileSystemException {
      // Gone or swapped while it was read, as when the planner stops.
      throw PlanningRefusal('NoAddress', const {}, 'no planner runs: $path cannot be read');
    } finally {
      await opened?.close();
    }
  }

  /// This account's uid from the `Uid:` line of `/proc/self/status`, or null when it cannot be read.
  static Future<int?> _ownUid() async {
    try {
      final status = await File('/proc/self/status').readAsLines();
      final line = status.firstWhere((each) => each.startsWith('Uid:'), orElse: () => '');
      return int.tryParse(line.substring(line.isEmpty ? 0 : 4).trim().split(RegExp(r'\s+')).first);
    } on FileSystemException {
      return null;
    }
  }

  /// The directory under the runtime directory.
  static const directory = 'porelo-planner';

  /// The file the planner writes there.
  static const file = 'planner.json';

  static const _addressBytes = 4096;

  // The token68 characters, so it cannot break out of the Authorization header.
  static final _token = RegExp(r'^[A-Za-z0-9._~+/=-]+$');

  /// The planner's port on the loopback address.
  final int port;

  /// The token every call carries, for the planner's caller alone.
  final String token;

  /// The planner's base URI.
  Uri get base => Uri(scheme: 'http', host: '127.0.0.1', port: port);

  /// Never the token, so a log line or an error cannot show it.
  @override
  String toString() => 'PlannerAddress($base)';
}

/// The planner's board reads over HTTP, as its REST description gives them.
///
/// Before its first read of a project it tells the planner the project's repository (`PUT /projects/{project}`),
/// and again once when a read answers `NoSuchProject`, such as after the planner restarted. An
/// `application/problem+json` answer arrives as a [PlanningRefusal] named after `urn:porelo-planner:`; what the
/// planner does not offer yet - moves, work, reading rounds and an event stream - as [PlanningUnsupported].
class HttpPlanningSource implements PlanningSource {
  /// Calls the planner at [base], a loopback `http` address, with [token]; [projects] names each project's repository.
  HttpPlanningSource(
    this.base,
    String token,
    this.projects, {
    this.label = 'planner',
    this.timeout = const Duration(seconds: 30),
    this.maxBody = 16 * 1024 * 1024,
  }) : _authorization = 'Bearer $token' {
    final host = InternetAddress.tryParse(base.host);
    final bare = base.userInfo.isEmpty && !base.hasQuery && !base.hasFragment && (base.path == '' || base.path == '/');
    if (base.scheme != 'http' || host == null || !host.isLoopback || !base.hasPort || !bare) {
      throw ArgumentError.value(base.toString(), 'base', 'not an http address on the loopback interface');
    }
    if (!PlannerAddress._token.hasMatch(token)) {
      throw ArgumentError('the planner token is empty or malformed', 'token');
    }
    _client
      ..connectionTimeout = timeout
      // Inflating a compressed answer would pass the bound on the bytes received.
      ..autoUncompress = false
      // Straight to the loopback address, never through a proxy that would see the token.
      ..findProxy = ((_) => 'DIRECT');
  }

  /// Calls the planner whose address [address] gives.
  factory HttpPlanningSource.at(PlannerAddress address, PlannerProjects projects, {String label = 'planner'}) =>
      HttpPlanningSource(address.base, address.token, projects, label: label);

  /// Calls the planner this account runs, read from its address file under [runtimeDirectory].
  static Future<HttpPlanningSource> fromAddressFile(
    PlannerProjects projects, {
    String? runtimeDirectory,
    String label = 'planner',
  }) async =>
      HttpPlanningSource.at(await PlannerAddress.read(runtimeDirectory: runtimeDirectory), projects, label: label);

  /// The planner's base URI.
  final Uri base;

  /// Where each project's planning is kept.
  final PlannerProjects projects;

  @override
  final String label;

  /// How long one HTTP request may take, from connecting to the last byte of its answer; a source call that makes
  /// several requests, such as a snapshot or a retry after `NoSuchProject`, may take several times as long.
  final Duration timeout;

  /// The most bytes one answer may have.
  final int maxBody;

  final String _authorization;
  final HttpClient _client = HttpClient();
  final Map<String, ({PlannerProject source, Future<void> done})> _given = {};

  static const _prefix = 'urn:porelo-planner:';
  static final _refusalName = RegExp(r'^[A-Z][A-Za-z]+$');
  static const _echo = 200;
  static const _parameters = 16;
  static const _key = 64;
  static final _name = RegExp(r'^[a-z0-9][a-z0-9._-]{0,99}$');
  static final _storyId = RegExp(r'^ST[0-9]{1,5}$');
  static final _issueId = RegExp(r'^[A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?$');

  /// Closes the connections it holds; calls after it fail.
  void close() => _client.close(force: true);

  /// Never the token, so a log line or an error cannot show it.
  @override
  String toString() => 'HttpPlanningSource($label, $base)';

  /// Answers the board's first snapshot and then ends, since the planner has no event stream yet; the screen takes
  /// that last snapshot as the whole answer.
  @override
  Stream<PlanningSnapshot> watchStories(String project) {
    final replies = StreamController<PlanningSnapshot>();
    replies.onListen = () async {
      try {
        replies.add(await _snapshot(project));
      } catch (error, stack) {
        replies.addError(error, stack);
      }
      await replies.close();
    };
    return replies.stream;
  }

  @override
  Future<PlanningSnapshot> planningWorkflow(String project) => _snapshot(project);

  @override
  Future<PlanningSnapshot> stories(String project) => _snapshot(project);

  @override
  Future<PlanStoryDetail> getStory(String project, String story) async =>
      PlanStoryDetail.from(await _read(project, ['stories', _checked('story', story, _storyId)]));

  @override
  Future<PlanIssueList> listStoryIssues(String project, String story) async =>
      PlanIssueList.from(await _read(project, ['stories', _checked('story', story, _storyId), 'issues']));

  @override
  Future<PlanIssueDetail> getIssue(String project, String issue) async =>
      PlanIssueDetail.from(await _read(project, ['issues', _checked('issue', issue, _issueId)]));

  @override
  Future<PlanningMoveResult> saveMoves(String project, List<PlanningMove> moves, String revision) =>
      _unsupported('saving moves');

  @override
  Future<StoryWorkProposal> proposeStoryWork(String project, String story, String to) =>
      _unsupported('proposing story work');

  @override
  Future<StoryWorkStatus> getStoryWork(String project, String story) => _unsupported('reading story work');

  @override
  Future<StoryWorkStatus> stopStoryWork(String project, String story) => _unsupported('stopping story work');

  @override
  Future<PlanningIssueDetail> getPlanningIssue(String project, String issue) =>
      _unsupported('reading an issue\'s findings');

  @override
  Future<ReadingResolution> resolveIssueReading(
    String project,
    String story,
    String issue,
    int round,
    String choice,
    String revision, {
    String? comment,
  }) => _unsupported('deciding a reading round');

  Future<T> _unsupported<T>(String what) async => throw PlanningUnsupported('the planner does not offer $what yet');

  /// The workflow and the stories read at one revision, read once more when the store moved between the two.
  Future<PlanningSnapshot> _snapshot(String project) async {
    var workflow = await _read(project, ['workflow']);
    var board = await _read(project, ['stories']);
    if (workflow['revision'] != board['revision']) {
      workflow = await _read(project, ['workflow']);
      board = await _read(project, ['stories']);
      if (workflow['revision'] != board['revision']) {
        throw const PlanningRefusal('RevisionMoved', {}, 'the board changed while it was read; read it again');
      }
    }
    return PlanningSnapshot.from({...workflow, 'stories': board['stories']});
  }

  /// Reads `projects/{project}/<path>`, telling the planner the project first, again whenever the window names
  /// another repository or branch for it, and again once on `NoSuchProject`.
  Future<Map<String, dynamic>> _read(String project, List<String> path) async {
    _checked('project', project, _name);
    final wanted = await projects(project);
    var given = _given[project];
    if (given == null || given.source != wanted) given = _tell(project, wanted);
    await given.done;
    final uri = _uri(project, path);
    try {
      return await _call('GET', uri);
    } on PlanningRefusal catch (refusal) {
      if (refusal.name != 'NoSuchProject') rethrow;
      // Reads refused at the same time share one new PUT: only the first to see the old one makes it.
      final now = _given[project];
      await (now != null && !identical(now, given) ? now : _tell(project, wanted)).done;
      return _call('GET', uri);
    }
  }

  /// Tells the planner where [project] is kept; reads at the same time wait on the same call.
  ({PlannerProject source, Future<void> done}) _tell(String project, PlannerProject source) {
    final told = (source: source, done: _call('PUT', _uri(project, const []), source._toJson()));
    _given[project] = told;
    told.done.then<void>(
      (_) {},
      onError: (Object _) {
        // The next read tells the planner again instead of trusting a call that failed.
        if (identical(_given[project], told)) _given.remove(project);
      },
    );
    return told;
  }

  static String _checked(String what, String value, RegExp pattern) {
    if (!pattern.hasMatch(value)) {
      throw PlanningRefusal('InvalidName', {what: value}, 'the $what name is not a valid name');
    }
    return value;
  }

  Uri _uri(String project, List<String> path) => base.replace(pathSegments: ['projects', project, ...path]);

  Future<Map<String, dynamic>> _call(String method, Uri uri, [Map<String, dynamic>? body]) async {
    HttpClientRequest? request;
    try {
      return await () async {
        request = await _client.openUrl(method, uri);
        final sent = request!
          ..followRedirects = false
          ..headers.set(HttpHeaders.authorizationHeader, _authorization)
          ..headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        if (body != null) {
          sent.headers.contentType = ContentType.json;
          sent.write(jsonEncode(body));
        }
        final response = await sent.close();
        final bytes = await _bytes(response);
        return _answer(method, response, bytes);
      }().timeout(timeout);
    } on TimeoutException {
      request?.abort();
      throw PlanningRefusal('TimedOut', const {}, 'the planner did not answer within ${timeout.inSeconds} s');
    } on SocketException catch (error) {
      throw PlanningRefusal(
        'PlannerUnreachable',
        const {},
        'the planner cannot be reached: ${error.osError?.message ?? error.message}',
      );
    } on HttpException catch (error) {
      throw PlanningRefusal('PlannerUnreachable', const {}, 'the planner cannot be reached: ${error.message}');
    }
  }

  Future<List<int>> _bytes(HttpClientResponse response) async {
    if (response.contentLength > maxBody) {
      // Cancelling the unread answer closes its connection; aborting the request no longer does once it answered.
      await response.listen(null).cancel();
      throw _tooLarge();
    }
    final bytes = BytesBuilder(copy: false);
    // Leaving the loop cancels the answer, which closes its connection.
    await for (final chunk in response) {
      bytes.add(chunk);
      if (bytes.length > maxBody) throw _tooLarge();
    }
    return bytes.takeBytes();
  }

  PlanningRefusal _tooLarge() =>
      PlanningRefusal('AnswerTooLarge', const {}, 'the planner answered more than $maxBody bytes');

  Map<String, dynamic> _answer(String method, HttpClientResponse response, List<int> bytes) {
    final status = response.statusCode;
    final String? type;
    try {
      type = response.headers.contentType?.mimeType;
    } on HttpException {
      throw PlanningRefusal('BadAnswer', const {}, 'the planner\'s answer has a malformed content type (HTTP $status)');
    }
    if (status >= 200 && status < 300) {
      if (method == 'PUT') {
        if (status == HttpStatus.noContent) return const {};
        throw PlanningRefusal('BadAnswer', const {}, 'the planner answered HTTP $status to naming a project, not 204');
      }
      final json = type == 'application/json' ? _json(bytes) : null;
      if (json == null || json['revision'] is! String) {
        throw PlanningRefusal(
          'BadAnswer',
          const {},
          'the planner\'s answer is not a JSON object with a revision (HTTP $status)',
        );
      }
      return json;
    }
    if (type == 'application/problem+json') {
      if (_problem(_json(bytes)) case final refusal?) throw refusal;
    }
    throw PlanningRefusal(
      'Http$status',
      const {},
      _bounded('the planner answered HTTP $status ${_text(bytes)}'.trim()),
    );
  }

  PlanningRefusal? _problem(Map<String, dynamic>? json) {
    final type = json?['type'];
    if (json == null || type is! String || !type.startsWith(_prefix)) {
      return null;
    }
    final name = type.substring(_prefix.length);
    if (!_refusalName.hasMatch(name)) return null;
    final parameters = json['parameters'] is Map<String, dynamic>
        ? {
            for (final entry
                in (json['parameters'] as Map<String, dynamic>).entries
                    .where((entry) => entry.key.length <= _key && entry.value is String)
                    .take(_parameters))
              entry.key: _bounded(entry.value as String),
          }
        : <String, dynamic>{};
    final detail = json['detail'] is String ? _bounded(json['detail'] as String) : '';
    return PlanningRefusal(name, parameters, detail);
  }

  static Map<String, dynamic>? _json(List<int> bytes) {
    try {
      final json = jsonDecode(utf8.decode(bytes));
      return json is Map<String, dynamic> ? json : null;
    } on FormatException {
      return null;
    }
  }

  static String _text(List<int> bytes) =>
      utf8.decode(bytes.take(_echo).toList(), allowMalformed: true).replaceAll(RegExp(r'[\x00-\x1f\x7f]'), ' ');

  static String _bounded(String text) => text.length <= 1000 ? text : '${text.substring(0, 1000)}...';
}
