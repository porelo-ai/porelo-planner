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
  ///
  /// The file is trusted only when no other account can have placed or swapped it: every directory above the
  /// runtime directory belongs to root or this account, one others may write being sticky as `/tmp` is; the runtime
  /// directory, its `porelo-planner` directory and `planner.json` are this account's, no symbolic link, and closed to
  /// group and others. [stat] answers each path's owner and mode, the link itself for a link.
  static Future<PlannerAddress> read({
    String? runtimeDirectory,
    String runUser = '/run/user',
    Future<int?> Function() uid = _ownUid,
    Future<List<({int owner, int mode})>?> Function(List<String> paths) stat = _stat,
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
    final own = await uid();
    if (own == null) {
      throw const PlanningRefusal('NoAddress', {}, 'this account\'s uid is unknown');
    }
    final steps = runtime.split('/').where((step) => step.isNotEmpty).toList();
    // A `.` or `..`, or a trailing `/.`, would let a link stand where the checks below see a directory.
    if (steps.any((step) => step == '.' || step == '..')) {
      throw const PlanningRefusal('BadAddress', {}, 'XDG_RUNTIME_DIR names . or ..');
    }
    final base = '/${steps.join('/')}';
    final above = ['/', for (var count = 1; count < steps.length; count++) '/${steps.take(count).join('/')}'];
    final path = '$base/$directory/$file';
    final paths = [...above, base, '$base/$directory', path];
    final entries = await stat(paths);
    if (entries == null || entries.length != paths.length) {
      throw PlanningRefusal('NoAddress', const {}, 'no planner runs: $path cannot be read');
    }
    for (var index = 0; index < above.length; index++) {
      _above(above[index], entries[index], own);
    }
    _ownOnly(base, entries[above.length], own, _directoryType);
    _ownOnly('$base/$directory', entries[above.length + 1], own, _directoryType);
    _ownOnly(path, entries[above.length + 2], own, _fileType);
    RandomAccessFile? opened;
    try {
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

  /// Each path's owner and mode from stat(1), which reads a link itself, since Dart's FileStat has no owner; null
  /// when any cannot be read.
  static Future<List<({int owner, int mode})>?> _stat(List<String> paths) async {
    final ProcessResult result;
    try {
      result = await Process.run('/usr/bin/stat', ['--format=%u %f', '--', ...paths], environment: {'LC_ALL': 'C'});
    } on ProcessException {
      return null;
    }
    if (result.exitCode != 0) return null;
    final entries = <({int owner, int mode})>[];
    for (final line in const LineSplitter().convert(result.stdout as String)) {
      final fields = line.split(' ');
      final owner = fields.length == 2 ? int.tryParse(fields[0]) : null;
      final mode = fields.length == 2 ? int.tryParse(fields[1], radix: 16) : null;
      if (owner == null || mode == null) return null;
      entries.add((owner: owner, mode: mode));
    }
    return entries;
  }

  /// Refuses a directory above the runtime directory that another account could change: one not root's or this
  /// account's, or one others may write that is not sticky. A link of root or this account is followed.
  static void _above(String path, ({int owner, int mode}) entry, int own) {
    if (entry.owner != 0 && entry.owner != own) {
      throw PlanningRefusal('BadAddress', const {}, '$path belongs to another account');
    }
    final type = entry.mode & _typeBits;
    if (type == _linkType) return;
    if (type != _directoryType) {
      throw PlanningRefusal('BadAddress', const {}, '$path is not a directory');
    }
    if (entry.mode & _othersWrite != 0 && entry.mode & _sticky == 0) {
      throw PlanningRefusal('BadAddress', const {}, '$path may be changed by other accounts');
    }
  }

  /// Refuses [path] unless it is of [type], not a symbolic link, owned by [own] and with no bit for group or others.
  static void _ownOnly(String path, ({int owner, int mode}) entry, int own, int type) {
    final (:owner, :mode) = entry;
    if (mode & _typeBits != type) {
      throw PlanningRefusal('BadAddress', const {}, '$path is not a ${type == _fileType ? 'file' : 'directory'}');
    }
    if (owner != own) {
      throw PlanningRefusal('BadAddress', const {}, '$path belongs to another account');
    }
    // The planner makes them owner-only; one others may open has leaked its token or was not made by it.
    if (mode & 0x3f != 0) {
      throw PlanningRefusal('BadAddress', const {}, '$path may be read or written by other accounts');
    }
  }

  static const _typeBits = 0xf000;
  static const _directoryType = 0x4000;
  static const _fileType = 0x8000;
  static const _linkType = 0xa000;
  static const _othersWrite = 0x12;
  static const _sticky = 0x200;

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
    this.silence = const Duration(seconds: 45),
    this.backoff = const Duration(seconds: 1),
    this.backoffMost = const Duration(seconds: 30),
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

  /// The most bytes one answer may have, and one event of the stream.
  final int maxBody;

  /// How long the stream may stay without a line before its connection counts as lost; the planner sends one at
  /// least every 15 s.
  final Duration silence;

  /// The first wait before the stream is opened again; it doubles with each failure up to [backoffMost].
  final Duration backoff;

  /// The longest wait before the stream is opened again.
  final Duration backoffMost;

  final String _authorization;
  final HttpClient _client = HttpClient();
  final Map<String, _Given> _given = {};

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

  /// Answers the board at once and again on each new revision, from the planner's event stream, opened again after
  /// a lost connection until the watch is cancelled; a refusal, or a connection not restored within [silence], ends
  /// it with an error.
  @override
  Stream<PlanningSnapshot> watchStories(String project) => _Watch(this, project).replies.stream;

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
    final given = await _told(project);
    final uri = _uri(project, path);
    try {
      return await _call('GET', uri);
    } on PlanningRefusal catch (refusal) {
      if (refusal.name != 'NoSuchProject') rethrow;
      await _retell(project, given);
      return _call('GET', uri);
    }
  }

  /// Tells the planner the project unless it was told where the window keeps it now; answers what it was told.
  Future<_Given> _told(String project) async {
    _checked('project', project, _name);
    final wanted = await projects(project);
    var given = _given[project];
    if (given == null || given.source != wanted) given = _tell(project, wanted);
    await given.done;
    return given;
  }

  /// Tells the planner the project again after `NoSuchProject`; calls refused at the same time share one new PUT, as
  /// only the first to see the old one makes it.
  Future<void> _retell(String project, _Given given) async {
    final now = _given[project];
    await (now != null && !identical(now, given) ? now : _tell(project, given.source)).done;
  }

  /// Tells the planner where [project] is kept; reads at the same time wait on the same call.
  _Given _tell(String project, PlannerProject source) {
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

/// What the planner was told of a project, and the call that told it.
typedef _Given = ({PlannerProject source, Future<void> done});

/// A connection lost in a way that opening it again may mend: the failure, kept for when it is not mended.
class _Lost implements Exception {
  const _Lost(this.refusal);

  final PlanningRefusal refusal;
}

/// How one connection to the stream ended: whether it brought a board, whether it should start the waits over, and
/// what ended it.
typedef _Connection = ({bool restored, bool renewed, PlanningRefusal lost});

/// One watch of a project's board over the planner's event stream, opened again after a lost connection.
class _Watch {
  _Watch(this.source, this.project) {
    replies = StreamController<PlanningSnapshot>(onListen: () => unawaited(_run()), onCancel: _cancel);
  }

  final HttpPlanningSource source;
  final String project;
  late final StreamController<PlanningSnapshot> replies;

  // A revision travels back in the Last-Event-ID header, so it must be a short header value.
  static final _revision = RegExp(r'^[\x21-\x7e]{1,200}$');
  static const _idMost = 200;
  static const _newline = 10;
  static const _cancelled = PlanningRefusal('Cancelled', {}, 'the watch was cancelled');

  bool _ended = false;

  /// The revision answered last, null before the first board or after the project moved to another repository.
  String? _last;
  PlannerProject? _watched;
  HttpClientRequest? _request;
  StreamSubscription<List<int>>? _reading;

  /// Ends the connection being read now, if any.
  void Function()? _stop;
  Timer? _timer;
  Completer<void>? _waiting;

  void _cancel() {
    _ended = true;
    _request?.abort();
    unawaited(_reading?.cancel());
    _stop?.call();
    _timer?.cancel();
    if (_waiting case final waiting? when !waiting.isCompleted) waiting.complete();
  }

  Future<void> _run() async {
    var wait = source.backoff;
    DateTime? lost;
    while (!_ended) {
      _Connection connection;
      try {
        connection = await _connect();
      } on _Lost catch (error) {
        connection = (restored: false, renewed: false, lost: error.refusal);
      } on PlanningRefusal catch (refusal, stack) {
        return _end(refusal, stack);
      } catch (error, stack) {
        return _end(PlanningRefusal('BadAnswer', const {}, 'the planner\'s stream cannot be read: $error'), stack);
      }
      if (_ended) return;
      // Before any board, a failure is said at once, as a read's would be.
      if (_last == null) return _end(connection.lost);
      if (connection.renewed) wait = source.backoff;
      final now = DateTime.now();
      // A connection that brought a board was restored; the time allowed to restore the next starts at its end.
      if (connection.restored || lost == null) lost = now;
      final left = source.silence - now.difference(lost);
      if (left <= Duration.zero) return _end(connection.lost);
      await _sleep(wait < left ? wait : left);
      final doubled = wait * 2;
      wait = doubled > source.backoffMost ? source.backoffMost : doubled;
    }
  }

  void _end(PlanningRefusal failure, [StackTrace? stack]) {
    if (_ended) return;
    _ended = true;
    replies.addError(failure, stack);
    unawaited(replies.close());
  }

  Future<void> _sleep(Duration wait) {
    final waiting = _waiting = Completer<void>();
    _timer = Timer(wait, () {
      if (!waiting.isCompleted) waiting.complete();
    });
    return waiting.future;
  }

  /// Opens the stream once and follows it until it ends.
  Future<_Connection> _connect() async {
    final _Given given;
    try {
      given = await source._told(project);
    } on PlanningRefusal catch (refusal) {
      throw _transient(refusal) ? _Lost(refusal) : refusal;
    }
    if (_ended) throw const _Lost(_cancelled);
    if (_watched != given.source) {
      // Another repository or branch: its revisions say nothing of the last one.
      _watched = given.source;
      _last = null;
    }
    HttpClientResponse response;
    try {
      response = await _open();
    } on PlanningRefusal catch (refusal) {
      if (refusal.name != 'NoSuchProject') rethrow;
      try {
        await source._retell(project, given);
      } on PlanningRefusal catch (again) {
        throw _transient(again) ? _Lost(again) : again;
      }
      if (_ended) throw const _Lost(_cancelled);
      response = await _open();
    }
    return _follow(response);
  }

  static bool _transient(PlanningRefusal refusal) =>
      const {'PlannerUnreachable', 'TimedOut', 'Http503', 'Http504', 'Unreachable'}.contains(refusal.name);

  /// Opens the stream; a refusal is thrown as the reads throw it, a lost connection as [_Lost].
  Future<HttpClientResponse> _open() async {
    final HttpClientResponse response;
    try {
      response = await () async {
        final request = _request = await source._client.openUrl('GET', source._uri(project, const ['events']));
        if (_ended) {
          request.abort();
          throw const _Lost(_cancelled);
        }
        request
          ..followRedirects = false
          ..headers.set(HttpHeaders.authorizationHeader, source._authorization)
          ..headers.set(HttpHeaders.acceptHeader, 'text/event-stream, application/problem+json');
        if (_last case final last?) request.headers.set('last-event-id', last);
        return request.close();
      }().timeout(source.timeout);
    } on TimeoutException {
      _request?.abort();
      throw _Lost(
        PlanningRefusal('TimedOut', const {}, 'the planner did not answer within ${source.timeout.inSeconds} s'),
      );
    } on SocketException catch (error) {
      throw _Lost(
        PlanningRefusal(
          'PlannerUnreachable',
          const {},
          'the planner cannot be reached: ${error.osError?.message ?? error.message}',
        ),
      );
    } on HttpException catch (error) {
      throw _Lost(PlanningRefusal('PlannerUnreachable', const {}, 'the planner cannot be reached: ${error.message}'));
    }
    final status = response.statusCode;
    String? type;
    try {
      type = response.headers.contentType?.mimeType;
    } on HttpException {
      type = null;
    }
    if (status == HttpStatus.ok && type == 'text/event-stream') return response;
    final bytes = await _body(response);
    if (status >= 200 && status < 300) {
      throw PlanningRefusal('BadAnswer', const {}, 'the planner\'s stream is not text/event-stream (HTTP $status)');
    }
    try {
      source._answer('GET', response, bytes);
    } on PlanningRefusal catch (refusal) {
      // A planner stopping or a store not answering may mend; one too large to read was thrown before.
      if (status == HttpStatus.serviceUnavailable || status == HttpStatus.gatewayTimeout) throw _Lost(refusal);
      rethrow;
    }
    throw PlanningRefusal('Http$status', const {}, 'the planner refused the stream');
  }

  /// A refusal's body, within the bound and the time an answer may take; cancelling the watch ends it.
  Future<List<int>> _body(HttpClientResponse response) {
    final done = Completer<List<int>>();
    final bytes = BytesBuilder(copy: false);
    late final StreamSubscription<List<int>> reading;
    late final Timer deadline;
    void fail(Object failure) {
      deadline.cancel();
      _stop = null;
      unawaited(reading.cancel());
      if (!done.isCompleted) done.completeError(failure);
    }

    deadline = Timer(
      source.timeout,
      () => fail(
        _Lost(PlanningRefusal('TimedOut', const {}, 'the planner did not answer within ${source.timeout.inSeconds} s')),
      ),
    );
    reading = _reading = response.listen(
      (chunk) {
        bytes.add(chunk);
        if (bytes.length > source.maxBody) fail(source._tooLarge());
      },
      onError: (Object _) =>
          fail(const _Lost(PlanningRefusal('PlannerUnreachable', {}, 'the connection to the planner broke'))),
      onDone: () {
        deadline.cancel();
        _stop = null;
        if (!done.isCompleted) done.complete(bytes.takeBytes());
      },
      cancelOnError: true,
    );
    _stop = () => fail(const _Lost(_cancelled));
    if (response.contentLength > source.maxBody) fail(source._tooLarge());
    return done.future;
  }

  /// Reads the stream's events until it ends, falls silent or breaks a bound.
  Future<_Connection> _follow(HttpClientResponse response) {
    final done = Completer<_Connection>();
    final opened = DateTime.now();
    final line = BytesBuilder(copy: false);
    final data = <String>[];
    var eventBytes = 0;
    String? event;
    var renewed = false;
    var boards = 0;
    Timer? quiet;
    Timer? first;

    void close() {
      quiet?.cancel();
      first?.cancel();
      _stop = null;
      unawaited(_reading?.cancel());
    }

    void lose(PlanningRefusal why) {
      if (done.isCompleted) return;
      close();
      // A connection that stayed open for the longest wait with its board starts the waits over too.
      final stayed = boards > 0 && DateTime.now().difference(opened) >= source.backoffMost;
      done.complete((restored: boards > 0, renewed: renewed || stayed, lost: why));
    }

    void fail(Object failure) {
      if (done.isCompleted) return;
      close();
      done.completeError(failure);
    }

    void heard() {
      quiet?.cancel();
      quiet = Timer(
        source.silence,
        () => lose(
          PlanningRefusal('PlannerSilent', const {}, 'the planner sent no line for ${source.silence.inSeconds} s'),
        ),
      );
    }

    void dispatch() {
      eventBytes = 0;
      final kind = event ?? 'message';
      event = null;
      if (data.isEmpty) return;
      final text = data.join('\n');
      data.clear();
      if (kind != 'board') return;
      final Object? json;
      try {
        json = jsonDecode(text);
      } on FormatException {
        throw const PlanningRefusal('BadAnswer', {}, 'the planner streamed a board that is not JSON');
      }
      if (json is! Map<String, dynamic> ||
          json['revision'] is! String ||
          !_revision.hasMatch(json['revision'] as String)) {
        throw const PlanningRefusal('BadAnswer', {}, 'the planner streamed a board without a revision');
      }
      final PlanningSnapshot snapshot;
      try {
        snapshot = PlanningSnapshot.from(json);
      } on Object catch (error) {
        throw PlanningRefusal('BadAnswer', const {}, 'the planner streamed a board that cannot be read: $error');
      }
      if (!snapshot.complete) throw const PlanningRefusal('BadAnswer', {}, 'the planner streamed an incomplete board');
      boards++;
      first?.cancel();
      final revision = json['revision'] as String;
      if (revision == _last) return;
      _last = revision;
      renewed = true;
      replies.add(snapshot);
    }

    void take(List<int> bytes) {
      heard();
      var text = utf8.decode(bytes);
      if (text.endsWith('\r')) text = text.substring(0, text.length - 1);
      if (text.isEmpty) return dispatch();
      if (text.startsWith(':')) return;
      eventBytes += bytes.length + 1;
      if (eventBytes > source.maxBody) throw source._tooLarge();
      final colon = text.indexOf(':');
      final field = colon < 0 ? text : text.substring(0, colon);
      var value = colon < 0 ? '' : text.substring(colon + 1);
      if (value.startsWith(' ')) value = value.substring(1);
      switch (field) {
        case 'event':
          event = value;
        case 'data':
          data.add(value);
        case 'id':
          if (value.length > _idMost) {
            throw const PlanningRefusal('BadAnswer', {}, 'the planner streamed an id longer than 200 characters');
          }
      }
    }

    void chunk(List<int> bytes) {
      try {
        var start = 0;
        for (var at = 0; at < bytes.length; at++) {
          if (bytes[at] != _newline) continue;
          line.add(bytes.sublist(start, at));
          start = at + 1;
          take(line.takeBytes());
          if (done.isCompleted) return;
        }
        line.add(bytes.sublist(start));
        if (line.length + eventBytes > source.maxBody) throw source._tooLarge();
      } on PlanningRefusal catch (refusal) {
        fail(refusal);
      } on FormatException {
        fail(const PlanningRefusal('BadAnswer', {}, 'the planner streamed text that is not UTF-8'));
      }
    }

    first = Timer(
      source.timeout,
      () =>
          lose(PlanningRefusal('TimedOut', const {}, 'the planner sent no board within ${source.timeout.inSeconds} s')),
    );
    heard();
    _stop = () => lose(_cancelled);
    _reading = response.listen(
      chunk,
      onError: (Object error) => error is SocketException || error is HttpException
          ? lose(const PlanningRefusal('PlannerUnreachable', {}, 'the connection to the planner broke'))
          : fail(error),
      onDone: () => boards == 0
          ? fail(const PlanningRefusal('BadAnswer', {}, 'the planner ended the stream before its board'))
          : lose(const PlanningRefusal('PlannerUnreachable', {}, 'the planner ended the stream')),
      cancelOnError: true,
    );
    return done.future;
  }
}
