import 'package:flutter/material.dart';

/// The planning screen's spacing, on the window's own scale.
abstract final class Space {
  /// Hairline separation, inside a row.
  static const double tight = 4;

  /// Between a mark and the words beside it.
  static const double small = 8;

  /// Between rows, and inside a dense control.
  static const double normal = 12;

  /// Inside a pane, around its content.
  static const double wide = 16;
}

/// Planning board dimensions and the design's priority colors, derived from the window's color scheme.
abstract final class PlanningStyle {
  /// The width of a phase column.
  static const double column = 280;

  /// The width of a phase folded to its summary.
  static const double foldedColumn = 136;

  /// The miniature progress indicator's width.
  static const double progress = 56;

  /// The width of a filter above the board.
  static const double filter = 200;

  /// A mark beside a line of text.
  static const double mark = 16;

  /// An icon in a row.
  static const double rowIcon = 18;

  /// The width of the Start work dialog.
  static const double dialog = 620;

  /// The corner of a state.
  static const double radiusSmall = 8;

  /// The corner of a phase.
  static const double radiusMedium = 12;

  /// A card being dragged, left where it was.
  static const double stale = 0.5;

  /// Below this window width the start's ways of working stand one under the other.
  static const double compactWidth = 600;

  /// Light surfaces follow the board design; dark surfaces follow the window.
  static Color phase(ColorScheme scheme) =>
      scheme.brightness == Brightness.light ? const Color(0xFFECEEF1) : scheme.surfaceContainer;

  /// A working state's quiet tint.
  static Color working(ColorScheme scheme) =>
      scheme.brightness == Brightness.light ? const Color(0xFFFBF6EA) : scheme.tertiaryContainer;

  /// Background and foreground for priority chips, including an unknown priority.
  static ({Color background, Color foreground}) priority(String priority, ColorScheme scheme) {
    if (scheme.brightness == Brightness.dark) {
      return (
        background: scheme.surfaceContainerHigh,
        foreground: switch (priority) {
          'Highest' => const Color(0xFFFF8FA2),
          'High' => const Color(0xFFFFCB6B),
          'Medium' => const Color(0xFF9DB0FF),
          _ => scheme.onSurface,
        },
      );
    }
    return switch (priority) {
      'Highest' => (background: const Color(0xFFFDE8E8), foreground: const Color(0xFF9B1C1C)),
      'High' => (background: const Color(0xFFFDEEDC), foreground: const Color(0xFF8A4B0F)),
      'Medium' => (background: const Color(0xFFE6EEFB), foreground: const Color(0xFF1E4E8C)),
      _ => (background: const Color(0xFFECEFF2), foreground: const Color(0xFF4A535D)),
    };
  }
}
