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

  // Harm: a folded line whose Drop all or Resend acts on a list the planner did not send whole, so older folded saves
  // stay held unseen, or a list that is not the line's is taken for it.
  test('a folded line names every save it folds, and a list it cannot read leaves it unreadable', () {
    expect(
      PlanHeldSave.from(
        _held({
          'saves': [_older, _save],
        }),
      ).saves,
      [_older, _save],
    );
    expect(
      PlanHeldSave.from(
        _held({
          'saves': [_save],
        }),
      ).savesUnreadable,
      isFalse,
      reason: 'a line of one save',
    );
    for (final malformed in <Object?>[
      null,
      <dynamic>[],
      [_save, _older],
      [_older, 7, _save],
      [_older, '', _save],
      'not a list',
    ]) {
      final save = PlanHeldSave.from(_held({'saves': malformed}));
      expect(save.savesUnreadable, isTrue, reason: '$malformed');
      expect(save.saves, isEmpty, reason: 'never [id] alone: $malformed');
    }
  });

  // Harm: a time without its zone read as the screen's own local time, so a value or a try looks hours newer or older
  // than it is.
  test('a time without its zone is no time', () {
    final save = PlanHeldSave.from(
      _held({
        'changes': [
          {'target': 'AA1', 'field': 'state', 'now': 'Ready', 'nowAt': '2026-05-06T07:08:09', 'saved': 'Building'},
          {'target': 'AA1', 'field': 'size', 'now': 'S', 'nowAt': '2026-05-06T09:08:09+02:00', 'saved': 'M'},
        ],
        'tries': [
          {'at': '2026-05-06T07:00:00', 'result': 'REFUSED', 'reason': 'x'},
          {'at': '2026-05-06T09:00:00+02:00', 'result': 'REFUSED', 'reason': 'x'},
        ],
      }),
    );

    expect(save.changes.map((c) => c.read), [PlanHeldValueRead.never, PlanHeldValueRead.value]);
    expect(save.changes.last.nowAt, DateTime.utc(2026, 5, 6, 7, 8, 9), reason: 'an offset is taken to UTC');
    expect(save.tries.map((t) => t.at), [null, DateTime.utc(2026, 5, 6, 7)]);
  });

  // Harm: GitHub's words lost because its status is missing or out of range.
  test('an answer without a status, or with one out of range, keeps its words as status 0', () {
    expect(
      [
        for (final answer in <Object?>[
          {'text': 'Bad credentials'},
          {'status': 1000, 'text': 'Odd'},
          {'status': -1, 'text': 'Odder'},
        ])
          (
            PlanHeldSave.from(_held({'answer': answer})).answer?.status,
            PlanHeldSave.from(_held({'answer': answer})).answer?.text,
          ),
      ],
      [(0, 'Bad credentials'), (0, 'Odd'), (0, 'Odder')],
    );
    expect(PlanHeldSave.from(_held({'answer': 'words'})).answer, isNull, reason: 'no answer of another shape');
  });

  // Harm: a malformed entry reads as a change or a try with made-up values, or an empty one as read empty.
  test('a change or a try of another shape is told as it stands, and an unset without a time is never read', () {
    final save = PlanHeldSave.from(
      _held({
        'changes': [
          'not a change',
          {'target': 'AA1', 'field': 'size', 'nowUnset': true, 'saved': 'M'},
          {'target': 'AA1', 'field': 'size', 'nowUnset': false, 'nowAt': '2026-05-06T07:08:09Z', 'saved': 'M'},
        ],
        'tries': [
          {'at': '2026-05-06T07:00:00Z', 'result': 'LOST', 'reason': 'x'},
        ],
      }),
    );

    expect(save.changes, hasLength(2), reason: 'an entry that is no map is left out');
    expect(save.changes.map((c) => c.read), [PlanHeldValueRead.never, PlanHeldValueRead.never]);
    expect(save.changes.map((c) => c.nowAt), [null, null]);
    expect(save.tries.single.result, 'LOST', reason: 'an unknown result kept as the planner named it');
  });

  // Harm: an empty answer is shown as GitHub answering "No status", which it never did.
  test('an answer with neither status, words nor request id is no answer', () {
    expect(PlanHeldSave.from(_held({'answer': <String, dynamic>{}})).answer, isNull);
    expect(
      PlanHeldSave.from(
        _held({
          'answer': {'status': 0, 'text': ''},
        }),
      ).answer,
      isNull,
    );
    expect(
      PlanHeldSave.from(
        _held({
          'answer': {'requestId': 'C0DE:1'},
        }),
      ).answer?.requestId,
      'C0DE:1',
    );
  });
}
