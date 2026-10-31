import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../book/book_providers.dart';
import '../shelf/records_provider.dart';
import '../widgets/cover.dart';
import 'completion_calendar.dart';

const _weekdays = ['일', '월', '화', '수', '목', '금', '토'];

class CalendarScreen extends ConsumerStatefulWidget {
  const CalendarScreen({super.key});

  @override
  ConsumerState<CalendarScreen> createState() => _CalendarScreenState();
}

class _CalendarScreenState extends ConsumerState<CalendarScreen> {
  late DateTime _month;

  @override
  void initState() {
    super.initState();
    final now = ref.read(nowProvider)();
    _month = DateTime(now.year, now.month);
  }

  void _shift(int months) =>
      setState(() => _month = DateTime(_month.year, _month.month + months));

  @override
  Widget build(BuildContext context) {
    final records = ref.watch(recordsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('완독 달력')),
      body: records.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('기록을 불러오지 못했어요'),
              TextButton(
                onPressed: () => ref.invalidate(recordsProvider),
                child: const Text('다시 시도'),
              ),
            ],
          ),
        ),
        data: (all) => _body(CompletionCalendar.fromRecords(all)),
      ),
    );
  }

  Widget _body(CompletionCalendar calendar) {
    final today = dayOf(ref.watch(nowProvider)());
    final atCurrentMonth =
        _month.year == today.year && _month.month == today.month;
    final yearCount = calendar.countInYear(_month.year);
    final busiest = calendar.busiestMonth(_month.year);
    final yearLabel = _month.year == today.year ? '올해' : '${_month.year}년';
    final textTheme = Theme.of(context).textTheme;

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Row(
          children: [
            IconButton(
              tooltip: '이전 달',
              icon: const Icon(Icons.chevron_left),
              onPressed: () => _shift(-1),
            ),
            Expanded(
              child: Column(
                children: [
                  Text(
                    '${_month.year}년 ${_month.month}월',
                    style: textTheme.titleMedium,
                  ),
                  Text(
                    '이번 달 ${calendar.countInMonth(_month.year, _month.month)}권',
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: '다음 달',
              icon: const Icon(Icons.chevron_right),
              onPressed: atCurrentMonth ? null : () => _shift(1),
            ),
          ],
        ),
        const SizedBox(height: 8),
        _MonthGrid(month: _month, today: today, calendar: calendar),
        const SizedBox(height: 16),
        Text('$yearLabel 다 읽음 $yearCount권', style: textTheme.titleSmall),
        if (busiest != null) Text('가장 많이 읽은 달 $busiest월'),
        if (calendar.undatedDoneCount > 0)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              '다 읽은 날이 없는 책 ${calendar.undatedDoneCount}권은 달력에 안 보여요. '
              '기록에서 날짜를 넣어 주세요.',
            ),
          ),
      ],
    );
  }
}

class _MonthGrid extends StatelessWidget {
  const _MonthGrid({
    required this.month,
    required this.today,
    required this.calendar,
  });

  final DateTime month;
  final Day today;
  final CompletionCalendar calendar;

  @override
  Widget build(BuildContext context) {
    final leading = DateTime(month.year, month.month, 1).weekday % 7;
    final days = DateTime(month.year, month.month + 1, 0).day;
    return Column(
      children: [
        Row(
          children: [
            for (final weekday in _weekdays)
              Expanded(child: Center(child: Text(weekday))),
          ],
        ),
        const SizedBox(height: 4),
        GridView(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 7,
            mainAxisExtent: 72,
          ),
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          children: [
            for (var i = 0; i < leading; i++) const SizedBox.shrink(),
            for (var d = 1; d <= days; d++)
              _DayCell(
                day: (year: month.year, month: month.month, day: d),
                isToday:
                    today == (year: month.year, month: month.month, day: d),
                books: calendar.finishedOn((
                  year: month.year,
                  month: month.month,
                  day: d,
                )),
              ),
          ],
        ),
      ],
    );
  }
}

class _DayCell extends StatelessWidget {
  const _DayCell({
    required this.day,
    required this.isToday,
    required this.books,
  });

  final Day day;
  final bool isToday;
  final List<RecordItem> books;

  @override
  Widget build(BuildContext context) {
    final cell = Container(
      margin: const EdgeInsets.all(1),
      decoration: isToday
          ? BoxDecoration(
              border: Border.all(color: Theme.of(context).colorScheme.primary),
              borderRadius: BorderRadius.circular(4),
            )
          : null,
      child: Column(
        children: [
          Text('${day.day}'),
          if (books.isNotEmpty)
            Badge(
              isLabelVisible: books.length > 1,
              label: Text('+${books.length - 1}'),
              child: Cover(
                url: books.first.book?.coverUrl,
                isbn: books.first.isbn13,
                width: 30,
              ),
            ),
        ],
      ),
    );
    if (books.isEmpty) return cell;

    final first = _title(books.first);
    final who = books.length > 1 ? '$first 외 ${books.length - 1}권' : first;
    return Semantics(
      button: true,
      label: '${day.month}월 ${day.day}일, $who 다 읽음',
      excludeSemantics: true,
      child: InkWell(onTap: () => _showBooks(context), child: cell),
    );
  }

  void _showBooks(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            ListTile(title: Text('${day.month}월 ${day.day}일에 다 읽은 책')),
            for (final record in books)
              ListTile(
                leading: Cover(url: record.book?.coverUrl, isbn: record.isbn13),
                title: Text(_title(record)),
                subtitle: record.book?.author == null
                    ? null
                    : Text(record.book!.author!),
                onTap: () {
                  Navigator.pop(sheetContext);
                  context.push('/book/${record.isbn13}');
                },
              ),
          ],
        ),
      ),
    );
  }
}

String _title(RecordItem record) =>
    record.book?.title ?? 'ISBN ${record.isbn13}';
