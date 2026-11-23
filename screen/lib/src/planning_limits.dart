/// The four factors every time limit of the planning screen follows, as Porelo's limits line names them.
library;

import 'package:flutter/foundation.dart' show immutable;

/// The four factors the window reads from Porelo's `Limits()` and hands to the screen, so a slow network or machine
/// is met by one setting rather than a new build; each is 1.0 by default.
///
/// Each of the screen's time limits belongs to one group and is its default times that group's factor: [services]
/// for an answer from the planner, [machines] for a program the screen runs, [polling] for how often something is
/// looked at again, and [retries] for how often, and after what pause, a failed step is tried again. A factor lies
/// from 0.1 to 100 with at most two digits after its dot, as the limits line allows, and is taken to two digits; one
/// outside that range, or one that is no number, is refused with an [ArgumentError] when the factors are made.
/// A planning source that carries the factors its window read for it, so each board made from it follows them.
///
/// A board made from a source that is none follows 1.0 for every factor.
abstract interface class PlanningLimited {
  /// The factors the window read from Porelo's `Limits()` for this source's computer.
  PlanningLimits get limits;
}

@immutable
class PlanningLimits {
  /// Takes the four factors, each 1.0 unless given; throws an [ArgumentError] for one outside [least] to [most] or
  /// one that is no number.
  PlanningLimits({double services = 1.0, double machines = 1.0, double polling = 1.0, double retries = 1.0})
    : services = _checked(services, 'services'),
      machines = _checked(machines, 'machines'),
      polling = _checked(polling, 'polling'),
      retries = _checked(retries, 'retries');

  const PlanningLimits._ones() : services = 1.0, machines = 1.0, polling = 1.0, retries = 1.0;

  /// Every factor 1.0, as a source without factors of its own follows.
  static const ones = PlanningLimits._ones();

  /// The factor for an answer from the planner.
  final double services;

  /// The factor for a program the screen runs, such as Windows PowerShell.
  final double machines;

  /// The factor for how long until something is looked at again.
  final double polling;

  /// The factor for how often, and after what pause, a failed step is tried again.
  final double retries;

  /// The least factor the limits line allows.
  static const least = 0.1;

  /// The greatest factor the limits line allows.
  static const most = 100.0;

  /// [limit] times [services], rounded up to a whole millisecond.
  Duration servicesOf(Duration limit) => _times(limit, services);

  /// [limit] times [machines], rounded up to a whole millisecond.
  Duration machinesOf(Duration limit) => _times(limit, machines);

  /// [interval] times [polling], rounded up to a whole millisecond: 2.0 looks half as often.
  Duration pollingOf(Duration interval) => _times(interval, polling);

  /// The pause [pause] before a step is tried again, times [retries], rounded up to a whole millisecond.
  Duration retryPauseOf(Duration pause) => _times(pause, retries);

  /// How often a step tried [tries] times by default is tried: times [retries], rounded up, and never less than one.
  int triesOf(int tries) {
    if (tries < 1) throw ArgumentError.value(tries, 'tries', 'a step is tried at least once');
    final scaled = _ceilDiv(tries * _hundredths(retries), 100);
    return scaled < 1 ? 1 : scaled;
  }

  static Duration _times(Duration limit, double factor) {
    // In whole numbers, so 1.1 times 30 s is 33 s and not a millisecond more; at most 100 times a limit of days,
    // far inside what an int and a Duration hold.
    final millis = _ceilDiv(limit.inMicroseconds * _hundredths(factor), 100 * Duration.microsecondsPerMillisecond);
    return Duration(milliseconds: millis);
  }

  /// [factor], refused when it lies outside [least] to [most] or is no number.
  static double _checked(double factor, String name) {
    if (factor.isNaN || factor < least || factor > most) {
      throw ArgumentError.value(factor, name, 'a factor lies from $least to $most');
    }
    return factor;
  }

  /// [factor] in hundredths, as the limits line writes it.
  static int _hundredths(double factor) => (factor * 100).round();

  static int _ceilDiv(int dividend, int divisor) => dividend <= 0 ? 0 : (dividend + divisor - 1) ~/ divisor;

  @override
  bool operator ==(Object other) =>
      other is PlanningLimits &&
      other.services == services &&
      other.machines == machines &&
      other.polling == polling &&
      other.retries == retries;

  @override
  int get hashCode => Object.hash(services, machines, polling, retries);

  @override
  String toString() => 'PlanningLimits(services: $services, machines: $machines, polling: $polling, retries: $retries)';
}
