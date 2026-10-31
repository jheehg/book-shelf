import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../theme/app_theme.dart';
import '../theme/reading_status_style.dart';
import 'shelf_layout.dart';

/// 보기·거르기를 고르면 닫히고, 완독 달력·설정은 닫은 뒤 그 화면으로 간다.
Future<void> showShelfMenu(
  BuildContext context, {
  required List<RecordItem>? records,
  required Widget avatar,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  builder: (sheetContext) => _ShelfMenu(
    records: records,
    avatar: avatar,
    go: (location) {
      Navigator.pop(sheetContext);
      context.push(location);
    },
  ),
);

class _ShelfMenu extends ConsumerWidget {
  const _ShelfMenu({
    required this.records,
    required this.avatar,
    required this.go,
  });

  final List<RecordItem>? records;
  final Widget avatar;
  final void Function(String location) go;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final layout = ref.watch(shelfLayoutProvider);
    final notifier = ref.read(shelfLayoutProvider.notifier);
    void close() => Navigator.pop(context);
    int? countOf(ReadingStatus? filter) =>
        records?.where((r) => filter == null || r.status == filter).length;

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(8, 0, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 8,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 0, 16, 4),
              child: Text(
                '내 서재',
                style: TextStyle(
                  fontSize: 26,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -0.5,
                ),
              ),
            ),
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 8),
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(26),
              ),
              child: Row(
                spacing: 6,
                children: [
                  for (final (view, label) in const [
                    (ShelfView.cards, '카드로 보기'),
                    (ShelfView.shelves, '책장으로 보기'),
                  ])
                    Expanded(
                      child: _Segment(
                        label: label,
                        selected: layout.view == view,
                        onTap: () {
                          notifier.show(view);
                          close();
                        },
                      ),
                    ),
                ],
              ),
            ),
            _Panel(
              color: AppColors.surface,
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Column(
                children: [
                  for (final filter in <ReadingStatus?>[null, ...shelfOrder])
                    _FilterRow(
                      label: filter?.label ?? '전체',
                      color: filter?.color ?? AppColors.ink,
                      count: countOf(filter),
                      selected: layout.filter == filter,
                      onTap: () {
                        notifier.filterBy(filter);
                        close();
                      },
                    ),
                ],
              ),
            ),
            _LinkRow(
              color: AppColors.taupe,
              leading: const Icon(Icons.calendar_month_outlined, size: 20),
              label: '완독 달력',
              trailing: Icons.arrow_forward,
              onTap: () => go('/calendar'),
            ),
            _LinkRow(
              color: AppColors.surface,
              leading: avatar,
              label: '설정',
              trailing: Icons.chevron_right,
              onTap: () => go('/settings'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Panel extends StatelessWidget {
  const _Panel({required this.color, required this.child, this.padding});

  final Color color;
  final Widget child;
  final EdgeInsets? padding;

  @override
  Widget build(BuildContext context) => Material(
    color: color,
    clipBehavior: Clip.antiAlias,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    child: Padding(padding: padding ?? EdgeInsets.zero, child: child),
  );
}

class _Segment extends StatelessWidget {
  const _Segment({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    inMutuallyExclusiveGroup: true,
    checked: selected,
    child: Material(
      color: selected ? AppColors.ink : Colors.transparent,
      shape: const StadiumBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: 44,
          child: Center(
            child: Text(
              label,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: selected ? AppColors.surface : AppColors.ink,
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _FilterRow extends StatelessWidget {
  const _FilterRow({
    required this.label,
    required this.color,
    required this.count,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final Color color;
  final int? count;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    inMutuallyExclusiveGroup: true,
    checked: selected,
    child: InkWell(
      onTap: onTap,
      child: SizedBox(
        height: 60,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            spacing: 14,
            children: [
              Container(
                width: 14,
                height: 14,
                decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              ),
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (count != null)
                Text(
                  '$count',
                  style: const TextStyle(
                    fontSize: 28,
                    height: 1,
                    letterSpacing: -0.8,
                  ),
                ),
              SizedBox.square(
                dimension: 24,
                child: selected
                    ? const DecoratedBox(
                        decoration: BoxDecoration(
                          color: AppColors.ink,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.check,
                          size: 14,
                          color: AppColors.surface,
                        ),
                      )
                    : null,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _LinkRow extends StatelessWidget {
  const _LinkRow({
    required this.color,
    required this.leading,
    required this.label,
    required this.trailing,
    required this.onTap,
  });

  final Color color;
  final Widget leading;
  final String label;
  final IconData trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => _Panel(
    color: color,
    child: InkWell(
      onTap: onTap,
      child: SizedBox(
        height: 60,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            spacing: 14,
            children: [
              SizedBox.square(dimension: 24, child: Center(child: leading)),
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Icon(trailing, size: 20),
            ],
          ),
        ),
      ),
    ),
  );
}
