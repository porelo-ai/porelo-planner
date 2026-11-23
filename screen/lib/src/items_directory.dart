import 'package:flutter/material.dart';

import 'client.dart';
import 'models.dart';
import 'planning_board.dart';
import 'ui/style.dart';

/// The window's folder dialog, opened at [initial] where it is given; answers the directory chosen, or null or an
/// empty text when the person put the dialog away.
typedef PlanningChooseDirectory = Future<String?> Function(String? initial);

/// The name of the planner's refusal of a local project's directory.
const itemsDirectoryRefused = 'ItemsDirectoryRefused';

/// Where the planner keeps a local project's stories and issues, in the planning header, with a choice of another
/// directory; nothing for a project GitHub's tracker keeps.
///
/// A directory the planner refused at a read stands in its place with the planner's words, and the choice opens
/// there. A choice refused here is named with the planner's words beside the directory still in use, where the choice
/// opens. While another directory than the default is kept, the default is offered back. The folder dialog is the
/// window's [choose]; without one, a typed path is taken as typed, so the screen opens no dialog of its own.
class ItemsDirectoryHeader extends StatefulWidget {
  /// Shows [board]'s directory, choosing another through [choose] where the window supplies a folder dialog.
  const ItemsDirectoryHeader({required this.board, this.choose, super.key});

  /// The board whose project's directory is shown.
  final PlanningBoard board;

  /// The window's folder dialog, or null to take a typed path.
  final PlanningChooseDirectory? choose;

  /// The directory [refusal] names and the planner's words, where it is the refusal of a directory; the directory is
  /// null where the planner named none that [planningDirectory] takes. The words are the planner's phrase, the one a
  /// failed read on the stream names as its `failure` before the description built around it.
  static ({String? directory, String words})? refusedOf(PlanningRefusal? refusal) {
    if (refusal == null || refusal.name != itemsDirectoryRefused) return null;
    final directory = refusal.parameters['directory'];
    final failure = refusal.parameters['failure'];
    return (
      directory: directory is String && planningDirectory(directory) ? directory : null,
      words: failure is String && failure.isNotEmpty ? failure : refusal.description,
    );
  }

  @override
  State<ItemsDirectoryHeader> createState() => _ItemsDirectoryHeaderState();
}

class _ItemsDirectoryHeaderState extends State<ItemsDirectoryHeader> {
  bool _calling = false;
  bool _typing = false;
  final _typed = TextEditingController();

  /// The planner's refusal of the directory chosen here, until another is taken.
  PlanningRefusal? _refused;

  /// Why a choice failed otherwise, in words.
  String? _failed;

  /// The source and project the call or its answer is for; another one shown drops both.
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
  void didUpdateWidget(ItemsDirectoryHeader old) {
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
    _typed.dispose();
    super.dispose();
  }

  static bool _shows(PlanningBoard board, (PlanningSource, String)? on) =>
      on != null && identical(on.$1, board.backend) && on.$2 == board.project;

  void _forget() {
    _call++;
    _calling = false;
    _typing = false;
    _refused = null;
    _failed = null;
    _for = null;
  }

  void _boardChanged() {
    // An answer for a project no longer shown must neither hold the choice nor speak beside another's directory.
    if (_for != null && !_shows(widget.board, _for) && mounted) setState(_forget);
  }

  Future<void> _choose(String? initial) async {
    final choose = widget.choose;
    if (choose == null) {
      setState(() {
        _typing = true;
        _typed.text = initial ?? '';
      });
      return;
    }
    final board = widget.board;
    final on = (board.backend, board.project);
    final String? chosen;
    try {
      chosen = await choose(initial);
    } catch (error) {
      if (mounted && _shows(widget.board, on)) setState(() => _failed = planningFailureWords(error));
      return;
    }
    // An empty answer is a dialog put away, as some dialogs answer it.
    if (chosen != null && chosen.isNotEmpty && mounted && _shows(widget.board, on)) await _keep(chosen);
  }

  /// Keeps [directory], or the default where it is null; a path outside the planner's rule is refused here in the
  /// planner's words, never trimmed into another.
  Future<void> _keep(String? directory) async {
    final board = widget.board;
    final on = (board.backend, board.project);
    final call = ++_call;
    if (directory != null) {
      if (planningDirectoryFault(directory) case final fault?) {
        setState(() {
          _failed = null;
          _for = on;
          _refused = PlanningRefusal(itemsDirectoryRefused, const {}, fault);
        });
        return;
      }
    }
    setState(() {
      _calling = true;
      _failed = null;
      _for = on;
    });
    try {
      await board.backend.setItemsDirectory(board.project, directory, const {});
      if (!mounted || call != _call) return;
      setState(() {
        _refused = null;
        _typing = false;
      });
    } on PlanningRefusal catch (refusal) {
      if (mounted && call == _call) {
        setState(() {
          if (refusal.name == itemsDirectoryRefused) {
            _refused = refusal;
          } else {
            _failed = planningFailureWords(refusal);
          }
        });
      }
      return;
    } catch (error) {
      if (mounted && call == _call) setState(() => _failed = planningFailureWords(error));
      return;
    } finally {
      if (mounted && call == _call) setState(() => _calling = false);
    }
    // Still the same board by its source and project, not by a generation a failed watch also moves. The store's own
    // read, after a save under way: the planner fetches nothing for a directory changed.
    if (!board.disposed && _shows(board, on)) await board.readAgain();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.board,
    builder: (context, _) {
      final board = widget.board;
      final refused =
          ItemsDirectoryHeader.refusedOf(_refused) ??
          ItemsDirectoryHeader.refusedOf(board.refusal) ??
          ItemsDirectoryHeader.refusedOf(board.readRefusal);
      final tracker = board.snapshot?.tracker;
      if (refused == null && tracker?.local != true) return const SizedBox.shrink();
      // A choice refused here leaves the directory in use as it was: it is named, and the choice opens there.
      final inUse = _refused != null && tracker?.local == true ? tracker!.items : null;
      final initial = refused == null ? tracker?.items : inUse ?? refused.directory;
      final words = refused == null
          ? 'Planning kept in ${tracker!.items}'
          : [
              [
                refused.directory == null
                    ? 'The directory for this project’s planning was refused'
                    : 'The planner cannot keep planning in ${refused.directory}',
                if (refused.words.isNotEmpty) refused.words,
              ].join(': '),
              if (inUse != null) 'Planning stays in $inUse.',
            ].join(' ');
      final offerDefault = tracker?.awayFromDefault == true;
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: Space.small),
        child: Wrap(
          spacing: Space.normal,
          runSpacing: Space.small,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(words, key: Key(refused == null ? 'items-directory' : 'items-directory-refused')),
            TextButton(
              key: const Key('items-directory-choose'),
              onPressed: _calling ? null : () => _choose(initial),
              child: const Text('Choose…'),
            ),
            if (offerDefault)
              TextButton(
                key: const Key('items-directory-default'),
                onPressed: _calling ? null : () => _keep(null),
                child: const Text('Use the default'),
              ),
            if (_typing) ...[
              SizedBox(
                width: PlanningStyle.filter * 2,
                child: TextField(
                  key: const Key('items-directory-typed'),
                  controller: _typed,
                  decoration: const InputDecoration(labelText: 'Directory'),
                  onSubmitted: _calling ? null : _keep,
                ),
              ),
              FilledButton.tonal(
                key: const Key('items-directory-use'),
                onPressed: _calling ? null : () => _keep(_typed.text),
                child: const Text('Use'),
              ),
            ],
            if (_failed case final failed?) Text(failed, key: const Key('items-directory-failed')),
          ],
        ),
      );
    },
  );
}
