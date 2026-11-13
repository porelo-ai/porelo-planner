import 'package:flutter/material.dart';

import 'src/app.dart';

/// Runs the planning screen alone, against a mock, for whoever tests the screen.
void main() => runApp(PlanningTestApp(window: WindowStandIn()));
