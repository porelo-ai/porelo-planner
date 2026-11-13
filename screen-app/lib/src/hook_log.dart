import 'dart:convert';

import 'package:flutter/foundation.dart';

/// One call between the screen and the window, as the app saw it.
@immutable
class HookCall {
  /// Takes when it came, what was called and its arguments.
  const HookCall(this.at, this.hook, this.arguments);

  /// When it came.
  final DateTime at;

  /// What was called, such as `host.go` or `startTask`.
  final String hook;

  /// Its arguments, by name, as plain values.
  final Map<String, Object?> arguments;

  /// The call as one line of the log.
  String get line => '${at.toIso8601String()} $hook ${jsonEncode(arguments)}';
}

/// Every call between the screen and the window, in order, to be read, copied and cleared.
class HookLog extends ChangeNotifier {
  /// Stamps each call with [clock], the wall clock by default.
  HookLog({DateTime Function()? clock}) : _clock = clock ?? DateTime.now;

  final DateTime Function() _clock;
  final List<HookCall> _calls = [];

  /// The calls so far, the oldest first.
  List<HookCall> get calls => List.unmodifiable(_calls);

  /// Writes a call of [hook] with its [arguments].
  void add(String hook, Map<String, Object?> arguments) {
    _calls.add(HookCall(_clock(), hook, arguments));
    notifyListeners();
  }

  /// Forgets every call.
  void clear() {
    _calls.clear();
    notifyListeners();
  }

  /// The whole log, one call a line, as it is copied.
  String get text => _calls.map((call) => call.line).join('\n');
}
