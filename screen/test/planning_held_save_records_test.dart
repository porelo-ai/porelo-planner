import 'package:flutter_test/flutter_test.dart';
import 'package:porelo_planning/porelo_planning.dart';

const _save = '11111111-1111-4111-8111-111111111111';
const _older = '22222222-2222-4222-8222-222222222222';

Map<String, dynamic> _held(Map<String, dynamic> more) => {
  'id': _save,
  'run': false,
  'earlier': false,
  'resendable': true,
  'reason': 'AA1 was changed elsewhere since it was read.',
  'changes': <dynamic>[],
  'tries': <dynamic>[],
  'saves': [_save],
  'commands': [
    {'id': '$_save/0', 'kind': 'WRITE', 'target': 'AA1', 'state': 'HELD', 'reason': 'Changed', 'subject': 'AA1'},
  ],
  ...more,
};

void main() {
  // Harm: a value the planner never read shown as GitHub's, one read empty shown as unknown, or the time of one
  // field's read given to another, so the person resends over a value they think is current.
  test('a change tells a value never read, read empty and read, each at its own time', () {
    final save = PlanHeldSave.from(
      _held({
        'changes': [
          {'target': 'AA1', 'field': 'state', 'now': 'Ready', 'nowAt': '2026-05-06T07:08:09Z', 'saved': 'Building'},
          {'target': 'AA1', 'field': 'size', 'nowUnset': true, 'nowAt': '2026-05-06T09:00:00Z', 'saved': 'M'},
          {'target': 'AA1', 'field': 'plan', 'saved': 'The plan.'},
          {'target': 'AA1', 'field': 'worker', 'now': 'AGENT', 'saved': 'MANUAL'},
          {'target': 'AA1', 'field': 'title', 'now': 'Old', 'nowAt': 'yesterday', 'saved': 'New'},
          {'target': 'AA1', 'field': 'priority', 'now': 'High', 'nowAt': '2026-05-06T07:08:09Z'},
        ],
      }),
    );

    final changes = save.changes;
    expect(changes.map((c) => c.read), [
      PlanHeldValueRead.value,
      PlanHeldValueRead.empty,
      PlanHeldValueRead.never,
      PlanHeldValueRead.never,
      PlanHeldValueRead.never,
      PlanHeldValueRead.value,
    ], reason: 'a value without its time, or with no time, is never read');
    expect(changes.map((c) => c.now), ['Ready', null, null, null, null, 'High']);
    expect(changes.map((c) => c.nowAt), [
      DateTime.utc(2026, 5, 6, 7, 8, 9),
      DateTime.utc(2026, 5, 6, 9),
      null,
      null,
      null,
      DateTime.utc(2026, 5, 6, 7, 8, 9),
    ], reason: 'each field at the time of its own read');
    expect(changes.map((c) => c.saved), ['Building', 'M', 'The plan.', 'MANUAL', 'New', null]);
    expect(changes.first.target, 'AA1');
    expect(changes.first.field, 'state');
  });

  // Harm: the person sees a held save without why, without GitHub's own answer and its request id, or without when
  // it was tried and how each try ended, so they cannot tell a passing fault from a lasting refusal.
  test('a held save reads its reason, its answer and every try', () {
    final save = PlanHeldSave.from(
      _held({
        'answer': {'status': 502, 'text': 'Server Error', 'requestId': 'C0DE:1234'},
        'tries': [
          {'at': '2026-05-06T07:00:00Z', 'result': 'REFUSED', 'reason': 'The server could not be reached.'},
          {
            'at': '2026-05-06T07:05:00Z',
            'result': 'REFUSED',
            'reason': 'Your login was not accepted.',
            'answer': {'status': 401, 'text': 'Bad credentials'},
          },
          {
            'at': '2026-05-06T07:10:00Z',
            'result': 'HELD',
            'reason': 'The server could not be reached.',
            'answer': {'status': 502, 'text': 'Server Error', 'requestId': 'C0DE:1234'},
          },
        ],
      }),
    );

    expect(save.reason, 'AA1 was changed elsewhere since it was read.');
    expect([save.answer?.status, save.answer?.text, save.answer?.requestId], [502, 'Server Error', 'C0DE:1234']);
    expect(save.tries.map((t) => t.at), [
      DateTime.utc(2026, 5, 6, 7),
      DateTime.utc(2026, 5, 6, 7, 5),
      DateTime.utc(2026, 5, 6, 7, 10),
    ]);
    expect(save.tries.map((t) => t.result), ['REFUSED', 'REFUSED', 'HELD']);
    expect(save.tries[1].reason, 'Your login was not accepted.');
    expect(save.tries.first.answer, isNull, reason: 'a try without an answer has none');
    expect(save.tries[1].answer?.requestId, isNull, reason: 'an answer without a request id names none');
    expect(save.tries[1].answer?.text, 'Bad credentials');
    expect(PlanHeldSave.from(_held({})).answer, isNull, reason: 'a save whose last try kept no answer');
  });

  // Harm: a folded line whose drop removes only its newest save, so the others come back, or a list the line does
  // not end with acted on as if it were the line's.
  test('a folded line names every save it folds, its own id last', () {
    expect(
      PlanHeldSave.from(
        _held({
          'saves': [_older, _save],
        }),
      ).saves,
      [_older, _save],
    );
    expect(PlanHeldSave.from(_held({'saves': null})).saves, [_save], reason: 'none named: the line alone');
    expect(
      PlanHeldSave.from(
        _held({
          'saves': [_save, _older],
        }),
      ).saves,
      [_save],
      reason: 'a list that does not end with the line is not taken',
    );
  });
}
