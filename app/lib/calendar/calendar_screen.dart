import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../book/book_providers.dart';
import '../shelf/records_provider.dart';
import '../theme/app_theme.dart';
import '../widgets/cover.dart';
import '../widgets/page_heading.dart';
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
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: backButton(context),
      ),
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
    final monthCount = calendar.countInMonth(_month.year, _month.month);
    final yearCount = calendar.countInYear(_month.year);
    final busiest = calendar.busiestMonth(_month.year);
    final yearLabel = _month.year == today.year ? '올해' : '${_month.year}년';

    return ListView(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 40),
      children: [
        PageHeading(
          title: '완독 달력',
          subtitle: '${_month.year}년 ${_month.month}월',
          trailing: Semantics(
            container: true,
            label: '이번 달 $monthCount권',
            excludeSemantics: true,
            child: BigNumber(
              '$monthCount',
              unit: '권',
              size: 64,
              unitStyle: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                letterSpacing: 0,
              ),
            ),
          ),
        ),
        Row(
          children: [
            IconButton(
              tooltip: '이전 달',
              icon: const Icon(Icons.chevron_left),
              onPressed: () => _shift(-1),
            ),
            Expanded(
              child: _MonthPills(
                month: _month,
                today: today,
                onPick: (month) => setState(() => _month = month),
              ),
            ),
            IconButton(
              tooltip: '다음 달',
              icon: const Icon(Icons.chevron_right),
              onPressed: atCurrentMonth ? null : () => _shift(1),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 14, 10, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _MonthGrid(month: _month, today: today, calendar: calendar),
                const Padding(
                  padding: EdgeInsets.fromLTRB(4, 12, 4, 0),
                  child: Wrap(
                    spacing: 14,
                    runSpacing: 6,
                    children: [
                      _Legend('다 읽은 날', color: AppColors.taupe),
                      _Legend('기록 없음', color: _plainCell),
                      _Legend('오늘', outlined: true),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            spacing: 8,
            children: [
              Expanded(
                child: _StatCard(
                  color: AppColors.lavender,
                  label: '$yearLabel 다 읽음',
                  value: '$yearCount',
                  unit: '권',
                ),
              ),
              Expanded(
                child: busiest == null
                    ? const SizedBox.shrink()
                    : _StatCard(
                        color: AppColors.tomato,
                        label: '가장 많이 읽은 달',
                        value: '$busiest',
                        unit: '월',
                      ),
              ),
            ],
          ),
        ),
        if (calendar.undatedDoneCount > 0)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
            child: Text(
              '다 읽은 날이 없는 책 ${calendar.undatedDoneCount}권은 달력에 안 보여요. '
              '기록에서 날짜를 넣어 주세요.',
              style: const TextStyle(fontSize: 13, color: AppColors.bodyMuted),
            ),
          ),
      ],
    );
  }
}

const _plainCell = Color(0xFFF4F4F2);
const _sunday = Color(0xFFC24A28);

/// 보고 있는 해의 열두 달. 오늘이 속한 달 뒤는 고를 수 없다.
class _MonthPills extends StatefulWidget {
  const _MonthPills({
    required this.month,
    required this.today,
    required this.onPick,
  });

  final DateTime month;
  final Day today;
  final ValueChanged<DateTime> onPick;

  @override
  State<_MonthPills> createState() => _MonthPillsState();
}

class _MonthPillsState extends State<_MonthPills> {
  final _selected = GlobalKey();

  @override
  void initState() {
    super.initState();
    _reveal();
  }

  @override
  void didUpdateWidget(_MonthPills old) {
    super.didUpdateWidget(old);
    if (old.month != widget.month) _reveal();
  }

  void _reveal() => WidgetsBinding.instance.addPostFrameCallback((_) {
    final context = _selected.currentContext;
    if (context != null) Scrollable.ensureVisible(context, alignment: 0.5);
  });

  @override
  Widget build(BuildContext context) {
    final year = widget.month.year;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        spacing: 6,
        children: [
          for (var m = 1; m <= 12; m++)
            _MonthPill(
              key: m == widget.month.month ? _selected : null,
              label: '$m월',
              selected: m == widget.month.month,
              future:
                  year > widget.today.year ||
                  (year == widget.today.year && m > widget.today.month),
              onTap: () => widget.onPick(DateTime(year, m)),
            ),
        ],
      ),
    );
  }
}

class _MonthPill extends StatelessWidget {
  const _MonthPill({
    super.key,
    required this.label,
    required this.selected,
    required this.future,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final bool future;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final text = Text(
      label,
      style: TextStyle(
        fontSize: 15,
        fontWeight: FontWeight.w700,
        color: selected
            ? AppColors.surface
            : future
            ? const Color(0xFF6B6B67)
            : AppColors.ink,
      ),
    );
    const padding = EdgeInsets.symmetric(horizontal: 18);
    if (future) {
      return CustomPaint(
        painter: const _DashedStadium(),
        child: Container(
          height: 44,
          padding: padding,
          alignment: Alignment.center,
          child: text,
        ),
      );
    }
    return Semantics(
      selected: selected,
      child: Material(
        color: selected ? AppColors.ink : AppColors.surface,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: selected ? null : onTap,
          child: Container(
            height: 44,
            padding: padding,
            alignment: Alignment.center,
            child: text,
          ),
        ),
      ),
    );
  }
}

class _DashedStadium extends CustomPainter {
  const _DashedStadium();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFFB5B5B0)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final outline = Path()
      ..addRRect(
        RRect.fromRectAndRadius(
          (Offset.zero & size).deflate(0.75),
          Radius.circular(size.height / 2),
        ),
      );
    for (final metric in outline.computeMetrics()) {
      for (var d = 0.0; d < metric.length; d += 7) {
        canvas.drawPath(metric.extractPath(d, d + 4), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DashedStadium old) => false;
}

class _Legend extends StatelessWidget {
  const _Legend(this.label, {this.color, this.outlined = false});

  final String label;
  final Color? color;
  final bool outlined;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    spacing: 6,
    children: [
      Container(
        width: 14,
        height: 14,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(4),
          border: outlined ? Border.all(color: AppColors.ink, width: 2) : null,
        ),
      ),
      Text(
        label,
        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
      ),
    ],
  );
}

class _StatCard extends StatelessWidget {
  const _StatCard({
    required this.color,
    required this.label,
    required this.value,
    required this.unit,
  });

  final Color color;
  final String label;
  final String value;
  final String unit;

  @override
  Widget build(BuildContext context) => Semantics(
    container: true,
    label: '$label $value$unit',
    excludeSemantics: true,
    child: Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 6,
        children: [
          Text(
            label,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
          ),
          BigNumber(
            value,
            unit: unit,
            size: 44,
            unitStyle: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              letterSpacing: 0,
            ),
          ),
        ],
      ),
    ),
  );
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
          spacing: 5,
          children: [
            for (final weekday in _weekdays)
              Expanded(
                child: Center(
                  child: Text(
                    weekday,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      color: weekday == '일' ? _sunday : AppColors.ink,
                    ),
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 10),
        GridView(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 7,
            mainAxisExtent: 70,
            mainAxisSpacing: 5,
            crossAxisSpacing: 5,
          ),
          shrinkWrap: true,
          padding: EdgeInsets.zero,
          clipBehavior: Clip.none,
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
    final finished = books.isNotEmpty;
    final cell = Container(
      padding: const EdgeInsets.fromLTRB(7, 6, 7, 6),
      decoration: BoxDecoration(
        color: finished ? AppColors.taupe : _plainCell,
        borderRadius: BorderRadius.circular(14),
      ),
      // 테두리를 레이아웃 밖에 그려야 오늘 칸의 표지가 넘치지 않는다.
      foregroundDecoration: isToday
          ? BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppColors.ink, width: 2),
            )
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${day.day}',
            style: TextStyle(
              fontSize: 11,
              height: 1.2,
              fontWeight: FontWeight.w700,
              color: finished ? AppColors.ink : AppColors.bodyMuted,
            ),
          ),
          if (finished)
            Center(
              child: Container(
                margin: const EdgeInsets.only(top: 1),
                decoration: const BoxDecoration(
                  boxShadow: [
                    BoxShadow(
                      color: Color(0x33000000),
                      blurRadius: 6,
                      offset: Offset(0, 2),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(3),
                  child: Cover(
                    url: books.first.book?.coverUrl,
                    isbn: books.first.isbn13,
                    width: 28,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
    if (!finished) return cell;

    final first = _title(books.first);
    final who = books.length > 1 ? '$first 외 ${books.length - 1}권' : first;
    return Semantics(
      button: true,
      label: '${day.month}월 ${day.day}일, $who 다 읽음',
      excludeSemantics: true,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: Material(
              type: MaterialType.transparency,
              child: InkWell(
                borderRadius: BorderRadius.circular(14),
                onTap: () => _showBooks(context),
                child: cell,
              ),
            ),
          ),
          if (books.length > 1)
            Positioned(
              right: -3,
              top: -3,
              child: Container(
                constraints: const BoxConstraints(minWidth: 20),
                height: 20,
                padding: const EdgeInsets.symmetric(horizontal: 5),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.ink,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '+${books.length - 1}',
                  style: const TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    color: AppColors.surface,
                  ),
                ),
              ),
            ),
        ],
      ),
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
                  context.push('/record/${record.id}');
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
