import 'dart:async';

import 'package:flutter/material.dart';

import '../models.dart';
import '../planning_board.dart';
import 'style.dart';

/// The saves the planner keeps for the person on one board, as the "Held saves" section of Needs you: per save each
/// command's target with its state and reason, Resend where the planner resends the save, and Drop. It shows nothing
/// while the board holds none.
class PlanningHeldSavesView extends StatelessWidget {
  /// Shows the held saves of [board], held by the planner on [machine] when one is named.
  const PlanningHeldSavesView({required this.board, this.machine, super.key});

  /// The board whose held saves are shown and resent or dropped.
  final PlanningBoard board;

  /// The machine whose planner holds the saves, or null when the window shows only its own.
  final String? machine;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: board,
    builder: (context, _) {
      final saves = board.heldSaves;
      if (saves.isEmpty) return const SizedBox.shrink();
      return Card(
        key: Key('held-saves ${board.project}'),
        child: Padding(
          padding: const EdgeInsets.all(Space.normal),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Two machines may hold the same project; Resend and Drop act on this machine's planner only.
              Text(
                'Held saves · ${planningShown(board.project, multiline: false)}'
                '${machine == null ? '' : ' on ${planningShown(machine!, multiline: false)}'}',
              ),
              for (final save in saves) _save(save),
              if (board.actionProblem case final problem?)
                Text(problem, key: Key('held-saves-problem ${board.project}')),
            ],
          ),
        ),
      );
    },
  );

  Widget _save(PlanHeldSave save) {
    final unavailable = board.heldSaveUnavailable(save.id);
    return Padding(
      key: Key('held-save ${save.id}'),
      padding: const EdgeInsets.only(top: Space.small),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_source(save)),
          for (final command in save.commands) Text(_command(command), key: Key('held-save-command ${command.id}')),
          Wrap(
            spacing: Space.small,
            children: [
              // The planner judges whether a save can be sent again; a run's halted save or one from elsewhere cannot.
              if (save.resendable)
                TextButton(
                  key: Key('held-save-resend ${save.id}'),
                  onPressed: unavailable == null ? () => unawaited(board.resendSave(save.id)) : null,
                  child: const Text('Resend'),
                ),
              TextButton(
                key: Key('held-save-drop ${save.id}'),
                onPressed: unavailable == null ? () => unawaited(board.dropSave(save.id)) : null,
                child: const Text('Drop'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  static String _source(PlanHeldSave save) {
    if (save.earlier) {
      final where = save.repository.isEmpty ? 'another repository' : save.repository;
      return planningShown(
        'Kept from $where${save.branch.isEmpty ? '' : ' on ${save.branch}'}; it is never written here.',
        multiline: false,
      );
    }
    return save.run ? 'A save of a run.' : 'A save made on the board.';
  }

  static String _command(PlanHeldCommand command) {
    final state = switch (command.state) {
      'QUEUED' => 'waiting',
      'DONE' => 'saved',
      'REFUSED' => 'refused',
      'HELD' => 'held',
      _ => 'not known',
    };
    final reason = command.reason.isEmpty || command.reason == 'NONE'
        ? ''
        : ' · ${command.reason}${command.subject.isEmpty ? '' : ': ${command.subject}'}';
    return planningShown('${command.target} · $state$reason', multiline: false);
  }
}
