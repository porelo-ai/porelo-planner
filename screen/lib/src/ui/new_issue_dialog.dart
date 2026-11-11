import 'package:flutter/material.dart';

import '../planning_board.dart';
import 'style.dart';

/// The form for an idea added as a new issue: its repository, title, what must be true and, if known, its size.
///
/// It answers the new issue's id once the planner has written it; a refusal stays in the form, in the planner's words,
/// so nothing written is lost.
class NewIssueDialog extends StatefulWidget {
  /// Takes the board the issue is added against.
  const NewIssueDialog({required this.board, super.key});

  /// The board whose revision and repositories the issue is added against.
  final PlanningBoard board;

  @override
  State<NewIssueDialog> createState() => _NewIssueDialogState();
}

class _NewIssueDialogState extends State<NewIssueDialog> {
  static const _sizes = ['XS', 'S', 'M', 'L', 'XL'];

  final _title = TextEditingController();
  final _mustBeTrue = TextEditingController();
  late String? _repository = widget.board.repositories.firstOrNull;
  String? _size;
  String? _problem;
  bool _sending = false;

  @override
  void dispose() {
    _title.dispose();
    _mustBeTrue.dispose();
    super.dispose();
  }

  bool get _complete => _repository != null && _title.text.trim().isNotEmpty && _mustBeTrue.text.trim().isNotEmpty;

  Future<void> _create() async {
    final repository = _repository;
    if (repository == null || _sending) return;
    setState(() {
      _sending = true;
      _problem = null;
    });
    final id = await widget.board.createIssue(repository, _title.text, _mustBeTrue.text, _size);
    if (!mounted) return;
    if (id != null) {
      Navigator.pop(context, id);
      return;
    }
    setState(() {
      _sending = false;
      _problem = widget.board.actionProblem ?? 'The planner did not add the issue.';
    });
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('New issue'),
    content: SizedBox(
      width: PlanningStyle.dialog,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('The issue is written in state Idea and pushed at once, as a move is saved.'),
            const SizedBox(height: Space.normal),
            DropdownButtonFormField<String>(
              key: const Key('new-issue-repository'),
              initialValue: _repository,
              decoration: const InputDecoration(labelText: 'Repository'),
              isExpanded: true,
              items: [for (final name in widget.board.repositories) DropdownMenuItem(value: name, child: Text(name))],
              onChanged: _sending ? null : (name) => setState(() => _repository = name),
            ),
            TextField(
              key: const Key('new-issue-title'),
              controller: _title,
              enabled: !_sending,
              maxLength: 120,
              decoration: const InputDecoration(labelText: 'Title'),
              onChanged: (_) => setState(() {}),
            ),
            TextField(
              key: const Key('new-issue-must-be-true'),
              controller: _mustBeTrue,
              enabled: !_sending,
              maxLength: 4000,
              minLines: 3,
              maxLines: 8,
              decoration: const InputDecoration(labelText: 'What must be true'),
              onChanged: (_) => setState(() {}),
            ),
            DropdownButtonFormField<String?>(
              key: const Key('new-issue-size'),
              initialValue: _size,
              decoration: const InputDecoration(labelText: 'Size, if known'),
              isExpanded: true,
              items: [
                const DropdownMenuItem<String?>(child: Text('Not known yet')),
                for (final size in _sizes) DropdownMenuItem<String?>(value: size, child: Text(size)),
              ],
              onChanged: _sending ? null : (size) => setState(() => _size = size),
            ),
            if (_problem case final problem?)
              Padding(
                padding: const EdgeInsets.only(top: Space.small),
                child: Text(
                  problem,
                  key: const Key('new-issue-error'),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        key: const Key('new-issue-cancel'),
        onPressed: _sending ? null : () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        key: const Key('new-issue-create'),
        onPressed: _sending || !_complete ? null : _create,
        child: Text(_sending ? 'Adding…' : 'Add issue'),
      ),
    ],
  );
}
