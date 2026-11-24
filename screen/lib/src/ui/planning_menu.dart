import 'package:flutter/material.dart';

/// A menu whose entries are built when it opens, so a board that changes while it stands open changes no entry: each
/// runs against what the person saw. A group of entries is a [SubmenuButton] among them; a list longer than the window
/// scrolls, as [MenuAnchor] keeps every menu inside the window and scrolls its panel by itself.
class PlanningMenu extends StatefulWidget {
  /// Takes the entries to build when it opens and the button that opens it.
  const PlanningMenu({required this.entries, required this.builder, this.live = false, super.key});

  /// The entries, built each time the menu opens.
  final List<Widget> Function(BuildContext context) entries;

  /// Whether the entries are built anew whenever the screen is, while the menu stands open, so each runs what its
  /// menu's owner holds now; for actions whose availability the board decides at the moment they run.
  final bool live;

  /// The button, given what opens the menu, or closes it while it stands open.
  final Widget Function(BuildContext context, VoidCallback toggle) builder;

  @override
  State<PlanningMenu> createState() => _PlanningMenuState();
}

class _PlanningMenuState extends State<PlanningMenu> {
  final _controller = MenuController();
  List<Widget> _entries = const [];

  void _toggle() {
    if (_controller.isOpen) {
      _controller.close();
      return;
    }
    setState(() => _entries = widget.entries(context));
    _controller.open();
  }

  @override
  void didUpdateWidget(PlanningMenu old) {
    super.didUpdateWidget(old);
    if (widget.live && _controller.isOpen) _entries = widget.entries(context);
  }

  @override
  Widget build(BuildContext context) => MenuAnchor(
    controller: _controller,
    // A tap beside the open menu only closes it, as a popup menu's did, so it never also lands on the board.
    consumeOutsideTap: true,
    menuChildren: _entries,
    builder: (context, _, _) => widget.builder(context, _toggle),
  );
}
