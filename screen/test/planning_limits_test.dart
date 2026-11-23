import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';
import 'package:porelo_planning/src/http/windows_access.dart';

void main() {
  // Harm: a factor of the window's limits line giving a limit a millisecond off, or a retry count of none.
  test('each limit is its default times its group\'s factor, rounded up to a whole millisecond', () {
    final limits = PlanningLimits(services: 1.1, machines: 0.1, polling: 2.5, retries: 0.25);

    expect(limits.servicesOf(const Duration(seconds: 30)), const Duration(seconds: 33));
    expect(limits.machinesOf(const Duration(milliseconds: 15)), const Duration(milliseconds: 2));
    expect(limits.pollingOf(const Duration(minutes: 1)), const Duration(seconds: 150));
    expect(limits.retryPauseOf(const Duration(seconds: 1)), const Duration(milliseconds: 250));
    expect(limits.triesOf(3), 1, reason: 'a quarter of three tries is still one');
    expect(PlanningLimits(retries: 2.5).triesOf(3), 8, reason: 'seven and a half tries round up');
    expect(PlanningLimits.ones, PlanningLimits(services: 1, machines: 1, polling: 1, retries: 1));
    expect(PlanningLimits(), PlanningLimits.ones, reason: 'each factor 1.0 unless given');
  });

  // Harm: a window that hands the screen a factor no limits line allows learning of it only when a limit is first
  // asked, deep inside a board's read, where it is shown as a failed read instead of the window's own fault.
  test('a factor outside the line\'s range, or no number, is refused when the factors are made', () {
    expect(() => PlanningLimits(services: 0), throwsArgumentError, reason: 'below the line\'s range');
    expect(() => PlanningLimits(machines: 100.5), throwsArgumentError, reason: 'above the line\'s range');
    expect(() => PlanningLimits(polling: double.nan), throwsArgumentError, reason: 'no number');
    expect(() => PlanningLimits(retries: double.infinity), throwsArgumentError, reason: 'no finite number');
    expect(PlanningLimits(services: 0.1, machines: 100).services, 0.1, reason: 'the range\'s ends are allowed');
  });

  // Harm: a PowerShell call of the screen ended at the default limit on a slow machine whose machines factor allows
  // it more, or a hanging one held past the limit the factor sets.
  test('a PowerShell call is given as long as the machines factor says', () async {
    final root = await Directory.systemTemp.createTemp('planning-limits-');
    addTearDown(() => root.delete(recursive: true));
    // The program's path as the screen builds it from the system root, its backslashes part of one file name here.
    final program = File('${root.path}/root\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
    await program.writeAsString('#!/bin/sh\nsleep 10\necho late\n');
    await Process.run('chmod', ['700', program.path]);

    final started = DateTime.now();
    final answer = await WindowsAccess.ask(
      ['C:\\Users\\person'],
      systemRoot: '${root.path}/root',
      limits: PlanningLimits(machines: 0.1),
    );

    expect(answer, isNull, reason: 'the call ended at its limit');
    expect(
      DateTime.now().difference(started),
      lessThan(const Duration(seconds: 8)),
      reason: 'a tenth of the 30 s default, not the program\'s 10 s',
    );
  }, skip: Platform.isWindows ? 'the stand-in program is a shell script' : false);
}
