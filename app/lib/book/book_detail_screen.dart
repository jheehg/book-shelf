import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../theme/app_theme.dart';
import '../widgets/page_heading.dart';
import 'book_providers.dart';
import 'sections/borrow_section.dart';
import 'sections/buy_sections.dart';
import 'sections/header_section.dart';
import 'sections/record_bar.dart';

class BookDetailScreen extends ConsumerWidget {
  const BookDetailScreen({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(
      automaticallyImplyLeading: false,
      leading: context.canPop()
          ? IconButton(
              tooltip: '뒤로',
              onPressed: context.pop,
              icon: const Icon(Icons.chevron_left, size: 32),
            )
          : null,
      actions: [?homeButton(context), const SizedBox(width: 8)],
    ),
    body: Stack(
      children: [
        RefreshIndicator(
          onRefresh: () async {
            ref.invalidate(bookDetailProvider(isbn));
            ref.invalidate(holdingsProvider(isbn));
            ref.invalidate(linksProvider(isbn));
            ref.invalidate(ebookProvider(isbn));
          },
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(
              8,
              0,
              8,
              recordBarBottom +
                  recordBarHeight +
                  24 +
                  MediaQuery.paddingOf(context).bottom,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              spacing: 8,
              children: [
                HeaderSection(isbn: isbn),
                NewBookSection(isbn: isbn),
                UsedSection(isbn: isbn),
                BorrowSection(isbn: isbn),
                EbookSection(isbn: isbn),
              ],
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: recordBarBottom,
          child: Center(child: RecordBar(isbn: isbn)),
        ),
      ],
    ),
  );
}

class DetailSection extends StatelessWidget {
  const DetailSection({
    super.key,
    required this.title,
    this.subtitle,
    this.trailing,
    this.spacing = 14,
    required this.children,
  });

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final double spacing;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    key: Key('section-$title'),
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: spacing,
        children: [
          Row(
            crossAxisAlignment: subtitle == null
                ? CrossAxisAlignment.baseline
                : CrossAxisAlignment.center,
            textBaseline: TextBaseline.alphabetic,
            spacing: 12,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 4,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (subtitle case final subtitle?)
                      Text(subtitle, style: detailNoteStyle),
                  ],
                ),
              ),
              ?trailing,
            ],
          ),
          ...children,
        ],
      ),
    ),
  );
}

const detailNoteStyle = TextStyle(fontSize: 12, color: AppColors.bodyMuted);

/// 값을 확인하지 못한 칸. 당기면 다시 불러온다는 것을 함께 알린다.
class Unavailable extends StatelessWidget {
  const Unavailable(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    spacing: 10,
    children: [
      Text(
        label,
        style: const TextStyle(
          fontSize: 26,
          height: 1,
          letterSpacing: -0.8,
          color: AppColors.bodyMuted,
        ),
      ),
      const Row(
        mainAxisSize: MainAxisSize.min,
        spacing: 6,
        children: [
          Icon(Icons.arrow_downward, size: 14, color: AppColors.bodyMuted),
          Text(
            '당겨서 다시 시도',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.bodyMuted,
            ),
          ),
        ],
      ),
    ],
  );
}

/// 가볍고 큰 숫자 뒤에 작은 '원'을 붙인다.
class PriceText extends StatelessWidget {
  const PriceText(this.digits, {super.key, required this.size, this.color});

  final String digits;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) => Text.rich(
    TextSpan(
      text: digits,
      children: [
        TextSpan(
          text: '원',
          style: TextStyle(fontSize: size * 0.46, fontWeight: FontWeight.w500),
        ),
      ],
    ),
    style: TextStyle(
      fontSize: size,
      height: 1,
      letterSpacing: size * -0.03,
      fontWeight: FontWeight.w400,
      color: color,
    ),
  );
}
