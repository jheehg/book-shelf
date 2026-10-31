import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/calendar/completion_calendar.dart';
import 'package:flutter_test/flutter_test.dart';

RecordItem done(int id, String? finishedAt) => RecordItem(
  id: id,
  isbn13: '97900000000$id',
  status: ReadingStatus.done,
  visibility: RecordVisibility.private,
  finishedAt: finishedAt,
);

void main() {
  test('다 읽은 날로 묶고 다른 상태의 기록은 뺀다', () {
    final calendar = CompletionCalendar.fromRecords([
      done(1, '2026-10-12'),
      done(2, '2026-10-12'),
      done(3, '2026-10-13'),
      RecordItem(
        id: 4,
        isbn13: '9790000000004',
        status: ReadingStatus.reading,
        visibility: RecordVisibility.private,
        finishedAt: '2026-10-12',
      ),
    ]);

    expect(
      calendar.finishedOn((year: 2026, month: 10, day: 12)).map((r) => r.id),
      [1, 2],
    );
    expect(calendar.finishedOn((year: 2026, month: 10, day: 14)), isEmpty);
  });

  test('날짜가 없거나 잘못된 완독 기록은 달력에 없고 따로 센다', () {
    final calendar = CompletionCalendar.fromRecords([
      done(1, null),
      done(2, '2026-02-30'),
      done(3, 'yesterday'),
      done(4, '2026-02-28'),
    ]);

    expect(calendar.undatedDoneCount, 3);
    expect(calendar.countInYear(2026), 1);
    expect(calendar.finishedOn((year: 2026, month: 3, day: 2)), isEmpty);
  });

  test('달과 해의 권수를 센다', () {
    final calendar = CompletionCalendar.fromRecords([
      done(1, '2026-10-01'),
      done(2, '2026-10-31'),
      done(3, '2026-03-05'),
      done(4, '2025-10-10'),
    ]);

    expect(calendar.countInMonth(2026, 10), 2);
    expect(calendar.countInMonth(2026, 9), 0);
    expect(calendar.countInYear(2026), 3);
    expect(calendar.countInYear(2025), 1);
  });

  test('가장 많이 읽은 달은 동률이면 앞선 달이고 없으면 null이다', () {
    final calendar = CompletionCalendar.fromRecords([
      done(1, '2026-03-01'),
      done(2, '2026-03-02'),
      done(3, '2026-07-01'),
      done(4, '2026-07-09'),
      done(5, '2026-01-09'),
    ]);

    expect(calendar.busiestMonth(2026), 3);
    expect(calendar.busiestMonth(2024), isNull);
  });

  test('parseDay는 자릿수가 맞는 실제 날짜만 받는다', () {
    expect(parseDay('2026-10-05'), (year: 2026, month: 10, day: 5));
    expect(parseDay('2026-10-5'), isNull);
    expect(parseDay('2026-13-01'), isNull);
    expect(parseDay(''), isNull);
  });
}
