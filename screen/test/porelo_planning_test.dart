import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart' as planning;

void main() {
  // A package the window cannot import breaks the window's build.
  test('the package can be imported', () {
    expect(planning.PlanningPackage.name, 'porelo_planning');
  });
}
