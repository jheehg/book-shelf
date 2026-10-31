import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../settings/settings_providers.dart';
import '../theme/app_theme.dart';
import '../theme/reading_status_style.dart';
import '../widgets/cover.dart';
import 'bookshelf_view.dart';
import 'records_provider.dart';
import 'shelf_layout.dart';
import 'shelf_menu.dart';

const _barBottom = 28.0;
const _barHeight = 64.0;
const _cardOverlap = 18.0;

class ShelfScreen extends ConsumerWidget {
  const ShelfScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final records = ref.watch(recordsProvider);
    final layout = ref.watch(shelfLayoutProvider);
    final name = ref.watch(meProvider).value?.displayName;
    final listPadding = EdgeInsets.only(
      top: _cardOverlap,
      bottom:
          _barBottom + _barHeight + 24 + MediaQuery.paddingOf(context).bottom,
    );

    return Scaffold(
      body: Stack(
        children: [
          SafeArea(
            bottom: false,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _Header(
                  records: records.value,
                  name: name,
                  onMenu: () => showShelfMenu(
                    context,
                    records: records.value,
                    avatar: _Avatar(name: name, size: 24),
                  ),
                ),
                Expanded(
                  child: records.when(
                    loading: () =>
                        const Center(child: CircularProgressIndicator()),
                    error: (_, _) => Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Text('서재를 불러오지 못했어요'),
                          TextButton(
                            onPressed: () => ref.invalidate(recordsProvider),
                            child: const Text('다시 시도'),
                          ),
                        ],
                      ),
                    ),
                    data: (all) => RefreshIndicator(
                      onRefresh: () => ref.refresh(recordsProvider.future),
                      child: switch ((all.isEmpty, layout.view)) {
                        (true, _) => ListView(
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: listPadding,
                          children: const [_FirstBookGuide()],
                        ),
                        (false, ShelfView.cards) => _RecordCards(
                          records: layout.filter == null
                              ? all
                              : all
                                    .where((r) => r.status == layout.filter)
                                    .toList(),
                          padding: listPadding,
                        ),
                        (false, ShelfView.shelves) => Bookshelf(
                          records: all,
                          statuses: layout.filter == null
                              ? shelfOrder
                              : [layout.filter!],
                          padding: listPadding.copyWith(top: 8),
                        ),
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
          const Positioned(
            left: 0,
            right: 0,
            bottom: _barBottom,
            child: Center(child: _ActionBar()),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({
    required this.records,
    required this.name,
    required this.onMenu,
  });

  final List<RecordItem>? records;
  final String? name;
  final VoidCallback onMenu;

  @override
  Widget build(BuildContext context) {
    const big = TextStyle(
      fontSize: 30,
      height: 1.12,
      fontWeight: FontWeight.w900,
      letterSpacing: -0.6,
    );
    final reading = records
        ?.where((r) => r.status == ReadingStatus.reading)
        .length;
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 20, 16, 20),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('내 서재', style: big),
                if (records != null)
                  Text(
                    [
                      '${records!.length}권',
                      if (reading! > 0) '읽는 중 $reading',
                    ].join(' · '),
                    style: big.copyWith(color: AppColors.muted),
                  ),
              ],
            ),
          ),
          IconButton(
            tooltip: '설정',
            onPressed: () => context.push('/settings'),
            icon: _Avatar(name: name, size: 34),
          ),
          IconButton(
            tooltip: '서재 메뉴',
            onPressed: onMenu,
            icon: const Icon(Icons.keyboard_arrow_down, size: 28),
          ),
        ],
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.name, required this.size});

  final String? name;
  final double size;

  @override
  Widget build(BuildContext context) {
    final initial = name?.trim().characters.firstOrNull;
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        color: AppColors.ink,
        shape: BoxShape.circle,
      ),
      child: initial == null
          ? Icon(Icons.person, size: size * 0.55, color: AppColors.surface)
          : Text(
              initial,
              style: TextStyle(
                color: AppColors.surface,
                fontSize: size * 0.44,
                fontWeight: FontWeight.w700,
              ),
            ),
    );
  }
}

class _RecordCards extends StatelessWidget {
  const _RecordCards({required this.records, required this.padding});

  final List<RecordItem> records;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => ListView.builder(
    physics: const AlwaysScrollableScrollPhysics(),
    padding: padding.copyWith(left: 8, right: 8),
    itemCount: records.isEmpty ? 1 : records.length,
    itemBuilder: (_, i) => records.isEmpty
        ? const Padding(
            padding: EdgeInsets.all(32),
            child: Center(child: Text('이 상태의 기록이 없어요.')),
          )
        : _RecordCard(records[i]),
  );
}

/// 카드 바탕은 다음 카드 밑으로 _cardOverlap만큼 내려가 겹쳐 쌓인 모양이 된다.
/// 리스트는 뒤 항목을 나중에 그리므로 다음 카드가 그 부분을 덮는다.
class _RecordCard extends StatelessWidget {
  const _RecordCard(this.record);

  final RecordItem record;

  @override
  Widget build(BuildContext context) {
    final status = record.status;
    final rating = record.rating;
    return Semantics(
      button: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => context.push('/record/${record.id}'),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: 0,
              right: 0,
              top: 0,
              bottom: -_cardOverlap,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: status.color,
                  borderRadius: BorderRadius.circular(22),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                spacing: 14,
                children: [
                  Row(
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: Cover(
                          url: record.book?.coverUrl,
                          isbn: record.isbn13,
                          width: 44,
                        ),
                      ),
                      const Spacer(),
                      Text(
                        status.label,
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(width: 6),
                      Transform.rotate(
                        angle: status.arrowAngle,
                        child: const Icon(Icons.arrow_forward, size: 26),
                      ),
                    ],
                  ),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    spacing: 12,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          spacing: 4,
                          children: [
                            Text(
                              record.book?.title ?? 'ISBN ${record.isbn13}',
                              style: const TextStyle(
                                fontSize: 24,
                                height: 1.15,
                                fontWeight: FontWeight.w900,
                                letterSpacing: -0.5,
                              ),
                            ),
                            if (record.book?.author case final author?)
                              Text(
                                author,
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                          ],
                        ),
                      ),
                      if (rating != null)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text('★', style: TextStyle(fontSize: 18)),
                            Text(
                              '$rating',
                              style: const TextStyle(
                                fontSize: 48,
                                height: 1,
                                letterSpacing: -1.4,
                              ),
                            ),
                          ],
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FirstBookGuide extends StatelessWidget {
  const _FirstBookGuide();

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(24, 52, 24, 0),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CustomPaint(
          painter: _DashedSpinePainter(),
          child: const SizedBox(
            width: 40,
            height: 140,
            child: Padding(
              padding: EdgeInsets.only(top: 12),
              child: Align(
                alignment: Alignment.topCenter,
                child: VerticalText(
                  '첫 책',
                  style: TextStyle(fontSize: 12, color: AppColors.bodyMuted),
                ),
              ),
            ),
          ),
        ),
        const ShelfLine(margin: 0),
        const SizedBox(height: 32),
        const Text(
          '아직 기록이 없어요.',
          style: TextStyle(
            fontSize: 28,
            height: 1.2,
            fontWeight: FontWeight.w900,
            letterSpacing: -0.5,
          ),
        ),
        const SizedBox(height: 10),
        const Text(
          '책 뒷면 바코드를 찍어 첫 책을 담아 보세요.',
          style: TextStyle(
            fontSize: 15,
            height: 1.5,
            fontWeight: FontWeight.w500,
            color: AppColors.bodyMuted,
          ),
        ),
      ],
    ),
  );
}

class _DashedSpinePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFF8A8A85)
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    final outline = Path()
      ..moveTo(1, size.height)
      ..lineTo(1, 4)
      ..arcToPoint(const Offset(4, 1), radius: const Radius.circular(3))
      ..lineTo(size.width - 4, 1)
      ..arcToPoint(Offset(size.width - 1, 4), radius: const Radius.circular(3))
      ..lineTo(size.width - 1, size.height);
    for (final metric in outline.computeMetrics()) {
      for (var d = 0.0; d < metric.length; d += 10) {
        canvas.drawPath(metric.extractPath(d, d + 6), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DashedSpinePainter oldDelegate) => false;
}

class _ActionBar extends StatelessWidget {
  const _ActionBar();

  @override
  Widget build(BuildContext context) => Container(
    height: _barHeight,
    padding: const EdgeInsets.symmetric(horizontal: 8),
    decoration: BoxDecoration(
      color: AppColors.raised,
      borderRadius: BorderRadius.circular(_barHeight / 2),
      boxShadow: const [
        BoxShadow(
          color: Color(0x29000000),
          blurRadius: 24,
          offset: Offset(0, 6),
        ),
      ],
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      spacing: 6,
      children: [
        _RoundButton(
          tooltip: '책 검색',
          color: AppColors.lavender,
          icon: Icons.search,
          onPressed: () => context.push('/search'),
        ),
        FilledButton.icon(
          style: FilledButton.styleFrom(
            minimumSize: const Size(0, 48),
            padding: const EdgeInsets.fromLTRB(16, 0, 20, 0),
            textStyle: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
            ),
          ),
          onPressed: () => context.push('/scan'),
          icon: const CustomPaint(
            size: Size.square(22),
            painter: _ScanCornersPainter(),
          ),
          label: const Text('바코드로 담기'),
        ),
        _RoundButton(
          tooltip: '내 도서관',
          color: AppColors.tomato,
          icon: Icons.local_library_outlined,
          onPressed: () => context.push('/libraries'),
        ),
      ],
    ),
  );
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.tooltip,
    required this.color,
    required this.icon,
    required this.onPressed,
  });

  final String tooltip;
  final Color color;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: tooltip,
    onPressed: onPressed,
    style: IconButton.styleFrom(
      backgroundColor: color,
      foregroundColor: AppColors.ink,
      fixedSize: const Size.square(48),
    ),
    icon: Icon(icon, size: 22),
  );
}

/// 캔버스의 바코드 아이콘: 네 모서리와 가운데 가로줄.
class _ScanCornersPainter extends CustomPainter {
  const _ScanCornersPainter();

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 24);
    final paint = Paint()
      ..color = AppColors.surface
      ..strokeWidth = 2.2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;
    final path = Path()
      ..moveTo(4, 8)
      ..lineTo(4, 4)
      ..lineTo(8, 4)
      ..moveTo(16, 4)
      ..lineTo(20, 4)
      ..lineTo(20, 8)
      ..moveTo(20, 16)
      ..lineTo(20, 20)
      ..lineTo(16, 20)
      ..moveTo(8, 20)
      ..lineTo(4, 20)
      ..lineTo(4, 16)
      ..moveTo(7, 12)
      ..lineTo(17, 12);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_ScanCornersPainter oldDelegate) => false;
}
