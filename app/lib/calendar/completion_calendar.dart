import '../api/models.dart';

/// 서버 LocalDate와 같은 날짜 값. 시간대 변환이 끼어들지 않게 DateTime을 쓰지 않는다.
typedef Day = ({int year, int month, int day});

Day dayOf(DateTime time) => (year: time.year, month: time.month, day: time.day);

final _isoDate = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$');

Day? parseDay(String? value) {
  final match = value == null ? null : _isoDate.firstMatch(value);
  if (match == null) return null;
  final day = (
    year: int.parse(match[1]!),
    month: int.parse(match[2]!),
    day: int.parse(match[3]!),
  );
  final normalized = DateTime(day.year, day.month, day.day);
  return dayOf(normalized) == day ? day : null;
}

class CompletionCalendar {
  CompletionCalendar._(this._byDay, this.undatedDoneCount);

  /// 날짜가 없거나 읽을 수 없는 완독 기록은 달력에 올리지 못하므로 undated로 센다.
  factory CompletionCalendar.fromRecords(List<RecordItem> records) {
    final byDay = <Day, List<RecordItem>>{};
    var undated = 0;
    for (final record in records) {
      if (record.status != ReadingStatus.done) continue;
      final day = parseDay(record.finishedAt);
      if (day == null) {
        undated++;
      } else {
        byDay.putIfAbsent(day, () => []).add(record);
      }
    }
    return CompletionCalendar._(byDay, undated);
  }

  final Map<Day, List<RecordItem>> _byDay;
  final int undatedDoneCount;

  List<RecordItem> finishedOn(Day day) => _byDay[day] ?? const [];

  int countInMonth(int year, int month) => _byDay.entries
      .where((e) => e.key.year == year && e.key.month == month)
      .fold(0, (sum, e) => sum + e.value.length);

  int countInYear(int year) => _byDay.entries
      .where((e) => e.key.year == year)
      .fold(0, (sum, e) => sum + e.value.length);

  /// 동률이면 앞선 달을 고른다.
  int? busiestMonth(int year) {
    int? best;
    var bestCount = 0;
    for (var month = 1; month <= 12; month++) {
      final count = countInMonth(year, month);
      if (count > bestCount) {
        best = month;
        bestCount = count;
      }
    }
    return best;
  }
}
