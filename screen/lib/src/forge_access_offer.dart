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

  Future<void> _handOn(PlanningForgeAccess access, String address) async {
    final board = widget.board;
    final generation = board.generation;
    setState(() {
      _calling = true;
      _failed = null;
    });
    var handed = false;
    try {
      handed = await access.handOnForgeToken(provider: widget.provider, address: address);
    } catch (error) {
      if (mounted) setState(() => _failed = planningFailureWords(error));
    } finally {
      if (mounted) setState(() => _calling = false);
    }
    // A board switched or closed meanwhile is not read again for a token handed on for another.
    if (handed && !board.disposed && board.generation == generation) await board.reloadPlanning();
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
