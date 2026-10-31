import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../theme/app_theme.dart';
import 'shelf_layout.dart';

const _spineColors = [
  Color(0xFFF2643C),
  Color(0xFFAB9DFF),
  Color(0xFFD6CABB),
  Color(0xFF1FA35B),
  Color(0xFFF3EE7A),
  Color(0xFFE9C4DD),
  Color(0xFFC98D2A),
  Color(0xFFF0607A),
  Color(0xFF9FC6F5),
];
const _spineHeights = <double>[128.0, 146, 116, 138, 152, 122, 142, 112, 134];
const _spineWidths = <double>[30.0, 38, 26, 42, 32, 28, 36, 24, 34];

/// 상태별 책장을 위아래로 쌓는다. statuses가 하나면 그 칸만 보인다.
class Bookshelf extends StatelessWidget {
  const Bookshelf({
    super.key,
    required this.records,
    required this.statuses,
    required this.padding,
  });

  final List<RecordItem> records;
  final List<ReadingStatus> statuses;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => ListView.separated(
    physics: const AlwaysScrollableScrollPhysics(),
    padding: padding,
    itemCount: statuses.length,
    separatorBuilder: (_, _) => const SizedBox(height: 26),
    itemBuilder: (_, i) => _ShelfSection(
      status: statuses[i],
      count: records.where((r) => r.status == statuses[i]).length,
      slots: shelfSlots(statuses[i], records),
    ),
  );
}

class _ShelfSection extends StatelessWidget {
  const _ShelfSection({
    required this.status,
    required this.count,
    required this.slots,
  });

  final ReadingStatus status;
  final int count;
  final List<ShelfSlot> slots;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.baseline,
          textBaseline: TextBaseline.alphabetic,
          children: [
            Expanded(
              child: Text(
                status.label,
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
            Text(
              '$count',
              style: const TextStyle(
                fontSize: 36,
                height: 1,
                letterSpacing: -1,
              ),
            ),
          ],
        ),
      ),
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 24),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          spacing: 3,
          children: [
            for (final slot in slots)
              switch (slot) {
                SpineSlot(:final record) => _Spine(record),
                BookendSlot(:final label) => _Bookend(label),
              },
          ],
        ),
      ),
      const ShelfLine(),
    ],
  );
}

class ShelfLine extends StatelessWidget {
  const ShelfLine({super.key, this.margin = 16});

  final double margin;

  @override
  Widget build(BuildContext context) => Container(
    height: 4,
    margin: EdgeInsets.symmetric(horizontal: margin),
    decoration: BoxDecoration(
      color: AppColors.ink,
      borderRadius: BorderRadius.circular(2),
    ),
  );
}

class _Spine extends StatelessWidget {
  const _Spine(this.record);

  final RecordItem record;

  @override
  Widget build(BuildContext context) {
    final book = record.book;
    final seed = _seed(record.isbn13);
    final title = book?.title ?? '책 정보 없음';
    return Semantics(
      button: true,
      label: book?.title ?? 'ISBN ${record.isbn13}',
      excludeSemantics: true,
      child: GestureDetector(
        onTap: () => context.push('/record/${record.id}'),
        child: Container(
          width: book == null ? 30 : _spineWidths[seed % 9],
          height: book == null ? 120 : _spineHeights[seed ~/ 9 % 9],
          padding: const EdgeInsets.only(top: 10),
          clipBehavior: Clip.hardEdge,
          decoration: BoxDecoration(
            color: book == null
                ? AppColors.unknown
                : _spineColors[seed ~/ 81 % 9],
            borderRadius: const BorderRadius.vertical(top: Radius.circular(3)),
          ),
          alignment: Alignment.topCenter,
          child: VerticalText(title, style: const TextStyle(fontSize: 12)),
        ),
      ),
    );
  }

  /// String.hashCode는 실행마다 같다는 보장이 없어 책등 모양이 바뀔 수 있다.
  static int _seed(String isbn) =>
      isbn.codeUnits.fold(7, (hash, unit) => (hash * 31 + unit) & 0x7fffffff);
}

class _Bookend extends StatelessWidget {
  const _Bookend(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Semantics(
    label: label,
    excludeSemantics: true,
    child: Container(
      width: 22,
      constraints: const BoxConstraints(minHeight: 64),
      margin: const EdgeInsets.symmetric(horizontal: 6),
      padding: const EdgeInsets.only(top: 8, bottom: 6),
      alignment: Alignment.topCenter,
      decoration: const BoxDecoration(
        color: AppColors.ink,
        borderRadius: BorderRadius.vertical(top: Radius.circular(3)),
      ),
      child: VerticalText(
        label,
        style: const TextStyle(fontSize: 11, color: AppColors.surface),
      ),
    ),
  );
}

/// 한글은 세로쓰기에서 글자를 눕히지 않으므로 회전 대신 한 글자씩 쌓는다.
class VerticalText extends StatelessWidget {
  const VerticalText(this.text, {super.key, required this.style});

  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    final gap = TextStyle(fontSize: (style.fontSize ?? 12) / 3);
    final glyphs = text.trim().characters.toList();
    return Text.rich(
      TextSpan(
        children: [
          for (final (i, glyph) in glyphs.indexed)
            // 띄어쓰기를 글자 한 칸 높이로 두면 낱말 사이가 너무 벌어진다.
            if (glyph == ' ')
              TextSpan(text: '\n', style: gap)
            else
              TextSpan(text: i == glyphs.length - 1 ? glyph : '$glyph\n'),
        ],
      ),
      textAlign: TextAlign.center,
      softWrap: false,
      overflow: TextOverflow.clip,
      style: style.copyWith(fontWeight: FontWeight.w700, height: 1.05),
    );
  }
}
