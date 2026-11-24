/// Tells a duration in seconds, keeping its fraction and omitting trailing zeros.
String secondsOf(Duration duration) {
  final micros = duration.inMicroseconds;
  final magnitude = micros.abs();
  final whole = magnitude ~/ Duration.microsecondsPerSecond;
  final remainder = magnitude % Duration.microsecondsPerSecond;
  final sign = micros < 0 ? '-' : '';
  if (remainder == 0) return '$sign$whole';
  final fraction = remainder.toString().padLeft(6, '0').replaceFirst(RegExp(r'0+$'), '');
  return '$sign$whole.$fraction';
}
