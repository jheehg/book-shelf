import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../theme/app_theme.dart';
import '../../theme/reading_status_style.dart';
import '../../widgets/cover.dart';
import '../../widgets/status_tag.dart';
import '../book_providers.dart';

const _titleStyle = TextStyle(
  fontSize: 36,
  height: 1.1,
  fontWeight: FontWeight.w900,
  letterSpacing: -1.08,
);
const _bodyStyle = TextStyle(
  fontSize: 15,
  height: 1.5,
  fontWeight: FontWeight.w500,
);

/// 책 정보와 내 기록을 한 덩어리로 보여준다. 바탕색이 곧 기록 상태다:
/// 담은 책은 상태 색, 아직 담지 않은 책은 잉크, 서버가 모르는 책은 회색.
class HeaderSection extends ConsumerWidget {
  const HeaderSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(bookDetailProvider(isbn));
    final mine = ref.watch(myRecordProvider(isbn));
    return switch (detail) {
      AsyncData(:final value) => _Hero(isbn: isbn, detail: value, mine: mine),
      AsyncError() => _Block(
        color: AppColors.unknown,
        children: [
          const Text('책 정보를\n불러오지 못했어요', style: _titleStyle),
          Text(
            'ISBN $isbn',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton(
              onPressed: () => ref.invalidate(bookDetailProvider(isbn)),
              child: const Text('다시 시도'),
            ),
          ),
        ],
      ),
      _ => const _Block(
        color: AppColors.surface,
        children: [
          SizedBox(
            height: 160,
            child: Center(child: CircularProgressIndicator()),
          ),
        ],
      ),
    };
  }
}

class _Hero extends StatelessWidget {
  const _Hero({required this.isbn, required this.detail, required this.mine});

  final String isbn;
  final BookDetail? detail;
  final RecordItem? mine;

  @override
  Widget build(BuildContext context) {
    final book = detail?.book;
    final mine = this.mine;
    final onInk = book != null && mine == null;
    final background =
        mine?.status.color ??
        (book == null ? AppColors.unknown : AppColors.ink);
    final ink = onInk ? AppColors.surface : AppColors.ink;
    final review = mine?.review;
    return DefaultTextStyle.merge(
      style: TextStyle(color: ink),
      child: IconTheme.merge(
        data: IconThemeData(color: ink),
        child: _Block(
          key: const Key('book-hero'),
          color: background,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (book == null)
                  const _UnknownCover()
                else
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: Cover(
                      url: book.coverUrl,
                      isbn: book.isbn13,
                      width: 52,
                    ),
                  ),
                const Spacer(),
                if (mine != null)
                  StatusTag(mine.status)
                else if (book != null)
                  const Text(
                    '아직 담지 않은 책',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: AppColors.unknown,
                    ),
                  ),
              ],
            ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              spacing: 12,
              children: [
                Expanded(
                  child: book == null
                      ? _UnknownTitle(isbn)
                      : _Title(book, subtle: onInk),
                ),
                if (mine?.rating case final rating?) _Rating(rating),
              ],
            ),
            if (review != null && review.isNotEmpty)
              Text('“$review”', style: _bodyStyle),
            if (book == null && mine == null)
              const Text('그래도 기록해 두면 책 정보는 나중에 채워져요.', style: _bodyStyle),
          ],
        ),
      ),
    );
  }
}

class _Block extends StatelessWidget {
  const _Block({super.key, required this.color, required this.children});

  final Color color;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(20, 20, 20, 26),
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(24),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 22,
      children: children,
    ),
  );
}

class _Title extends StatelessWidget {
  const _Title(this.book, {required this.subtle});

  final Book book;
  final bool subtle;

  @override
  Widget build(BuildContext context) {
    final byline = [?book.author, ?book.publisher].join(' · ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 6,
      children: [
        Text(book.title, style: _titleStyle),
        if (byline.isNotEmpty)
          Text(
            byline,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: subtle ? const Color(0xFFD8D8D4) : null,
            ),
          ),
      ],
    );
  }
}

class _UnknownTitle extends StatelessWidget {
  const _UnknownTitle(this.isbn);

  final String isbn;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    spacing: 8,
    children: [
      Text(
        '책 정보를\n찾지 못했어요',
        style: _titleStyle.copyWith(fontSize: 32, height: 1.12),
      ),
      Text(
        'ISBN $isbn',
        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
      ),
    ],
  );
}

class _UnknownCover extends StatelessWidget {
  const _UnknownCover();

  @override
  Widget build(BuildContext context) => Container(
    width: 52,
    height: 76,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(4),
    ),
    child: const Text(
      '?',
      style: TextStyle(
        fontSize: 24,
        fontWeight: FontWeight.w700,
        color: AppColors.ink,
      ),
    ),
  );
}

class _Rating extends StatelessWidget {
  const _Rating(this.rating);

  final int rating;

  @override
  Widget build(BuildContext context) => Semantics(
    label: '별점 $rating',
    child: ExcludeSemantics(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('★', style: TextStyle(fontSize: 22)),
          Text(
            '$rating',
            style: const TextStyle(
              fontSize: 60,
              height: 1,
              letterSpacing: -1.8,
            ),
          ),
        ],
      ),
    ),
  );
}
