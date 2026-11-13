@TestOn('windows')
@Tags(['windows'])
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

const _token = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ';

/// Runs [script] in Windows PowerShell of the system's own directory, with [environment], and fails on any error.
Future<void> _powershell(String script, Map<String, String> environment) async {
  final root = Platform.environment['SystemRoot']!;
  final result = await Process.run('$root\\System32\\WindowsPowerShell\\v1.0\\powershell.exe', [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-EncodedCommand',
    base64.encode([
      for (final unit in "\$ErrorActionPreference = 'Stop'\n$script".codeUnits) ...[unit & 0xff, unit >> 8],
    ]),
  ], environment: environment);
  expect(result.exitCode, 0, reason: 'PowerShell: ${result.stdout} ${result.stderr}');
}

/// The list the planner gives each directory it makes: the account alone, full control, passed on to all below.
const _protect = r'''
$self = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetOwner($self)
$acl.SetAccessRuleProtection($true, $false)
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule($self, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $env:PORELO_PATH -AclObject $acl
''';

void main() {
  // Harm: a screen that cannot read the planner's real file on Windows finds no planner there; one that reads it after
  // another account was let in hands that account the token.
  test('the planner\'s own file is read on Windows, and refused once Everyone may read it', () async {
    // Resolved, as a runner's TEMP may name its account by an 8.3 short name the checks refuse.
    final temp = await Directory.systemTemp.createTemp('porelo-address');
    addTearDown(() => temp.delete(recursive: true));
    final base = await temp.resolveSymbolicLinks();
    final own = Directory('$base\\${PlannerAddress.directory}');
    await own.create();
    for (final path in [base, own.path]) {
      await _powershell(_protect, {'PORELO_PATH': path});
    }
    final file = File('${own.path}\\${PlannerAddress.file}');
    await file.writeAsString('{"port": 4711, "token": "$_token"}\n');

    final address = await PlannerAddress.read(runtimeDirectory: base);
    expect(address.port, 4711, reason: 'the lists the planner makes, read through the real PowerShell');

    await _powershell(
      r'''
$acl = Get-Acl -LiteralPath $env:PORELO_PATH
$everyone = New-Object System.Security.Principal.SecurityIdentifier('S-1-1-0')
$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($everyone, 'Read', 'Allow')))
Set-Acl -LiteralPath $env:PORELO_PATH -AclObject $acl
''',
      {'PORELO_PATH': file.path},
    );
    final refusal = await PlannerAddress.read(runtimeDirectory: base)
        .then<Object>((_) => 'read', onError: (Object e) => e);
    expect(refusal, isA<PlanningRefusal>().having((r) => r.name, 'name', 'BadAddress'), reason: 'Everyone may read it');
  });
}
