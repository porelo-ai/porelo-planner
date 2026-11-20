import 'package:flutter/material.dart';

import 'client.dart';
import 'hooks.dart';
import 'planning_board.dart';
import 'ui/style.dart';

/// The offer, beside a board's `NoForgeAccess`, to have the window put the person's token for the forge into the
/// vault of the machine whose planner refused; shown only when the window can, by `PlanningForgeAccess`.
class ForgeAccessOffer extends StatefulWidget {
  /// Offers for [board]'s refusal through [host], naming [provider], the machine supplying the board.
  const ForgeAccessOffer({required this.board, required this.host, required this.provider, super.key});

  /// The board whose refusal is offered for.
  final PlanningBoard board;

  /// The window; it offers only when it is a [PlanningForgeAccess].
  final PlanningHost host;

  /// The name of the machine whose planner refused.
  final String provider;

  static final _right = RegExp(r'^[A-Za-z][A-Za-z0-9 _-]{0,63}$');

  /// The forge's address a `NoForgeAccess` refusal names, only when it is an https origin with no user, port, path,
  /// query or fragment, so a token is never handed on for an address a changed answer slipped in; else null.
  static String? addressOf(PlanningRefusal? refusal) {
    if (refusal == null || refusal.name != 'NoForgeAccess') return null;
    final address = refusal.parameters['address'];
    if (address is! String) return null;
    final uri = Uri.tryParse(address);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasPort ||
        uri.path.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        address != 'https://${uri.host}') {
      return null;
    }
    return address;
  }

  /// The right a `NoForgeAccess` refusal names, when it is a plain name; else null.
  static String? rightOf(PlanningRefusal refusal) {
    final right = refusal.parameters['right'];
    return right is String && _right.hasMatch(right) ? right : null;
  }

  @override
  State<ForgeAccessOffer> createState() => _ForgeAccessOfferState();
}

class _ForgeAccessOfferState extends State<ForgeAccessOffer> {
  bool _calling = false;
  String? _failed;

  /// The source and project the call or its failure is for; another one shown drops both.
  (PlanningSource, String)? _for;

  /// Counts the calls, so an answer to one left behind changes nothing.
  int _call = 0;

  VoidCallback? _unfollow;

  @override
  void initState() {
    super.initState();
    _unfollow = widget.board.follow(_boardChanged);
  }

  @override
  void didUpdateWidget(ForgeAccessOffer old) {
    super.didUpdateWidget(old);
    if (!identical(old.board, widget.board)) {
      _unfollow?.call();
      _unfollow = widget.board.follow(_boardChanged);
      _forget();
    }
  }

  @override
  void dispose() {
    _unfollow?.call();
    super.dispose();
  }

  static bool _shows(PlanningBoard board, (PlanningSource, String)? on) =>
      on != null && identical(on.$1, board.backend) && on.$2 == board.project;

  void _forget() {
    _call++;
    _calling = false;
    _failed = null;
    _for = null;
  }

  void _boardChanged() {
    // A call or failure for a project no longer shown must neither disable nor speak beside another's refusal.
    if (_for != null && !_shows(widget.board, _for) && mounted) setState(_forget);
  }

  Future<void> _handOn(PlanningForgeAccess access, String address) async {
    final board = widget.board;
    final on = (board.backend, board.project);
    final call = ++_call;
    setState(() {
      _calling = true;
      _failed = null;
      _for = on;
    });
    var handed = false;
    try {
      handed = await access.handOnForgeToken(provider: widget.provider, address: address);
    } catch (error) {
      if (mounted && call == _call) setState(() => _failed = planningFailureWords(error));
    } finally {
      if (mounted && call == _call) setState(() => _calling = false);
    }
    // Still the same board by its source and project, not by a generation a failed watch also moves.
    if (handed && !board.disposed && _shows(board, on)) await board.reloadPlanning();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.board,
    builder: (context, _) {
      final host = widget.host;
      final refusal = widget.board.refusal;
      final address = ForgeAccessOffer.addressOf(refusal);
      if (host is! PlanningForgeAccess || refusal == null || address == null || widget.provider.isEmpty) {
        return const SizedBox.shrink();
      }
      final access = host as PlanningForgeAccess;
      final right = ForgeAccessOffer.rightOf(refusal);
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: Space.small),
        child: Wrap(
          spacing: Space.normal,
          runSpacing: Space.small,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              'The planner on ${widget.provider} has no access to $address'
              '${right == null ? '' : ' with the right $right'}.',
            ),
            FilledButton.tonal(
              key: const Key('forge-access-offer'),
              onPressed: _calling ? null : () => _handOn(access, address),
              child: Text('Hand on your token for $address'),
            ),
            if (_failed case final failed?) Text(failed, key: const Key('forge-access-failed')),
          ],
        ),
      );
    },
  );
}
