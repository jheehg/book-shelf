import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/models.dart';
import '../../theme/app_theme.dart';
import '../book_detail_screen.dart';
import '../book_providers.dart';
import '../labels.dart';

class _Links extends StatelessWidget {
  const _Links(this.links);

  final List<StoreLink> links;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final link in links)
        OutlinedButton(
          onPressed: () => launchUrl(
            Uri.parse(link.url),
            mode: LaunchMode.externalApplication,
          ),
          style: OutlinedButton.styleFrom(
            minimumSize: const Size(0, 44),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            textStyle: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
            ),
          ),
          child: Text(link.name),
        ),
    ],
  );
}

Widget _linksOf(
  WidgetRef ref,
  String isbn,
  List<StoreLink> Function(StoreLinks) select,
) => ref
    .watch(linksProvider(isbn))
    .when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const Text('서점 링크를 불러오지 못했어요'),
      data: (links) => links == null
          ? const Text(
              '책 정보가 없어 서점 링크를 만들 수 없어요',
              style: TextStyle(
                fontSize: 14,
                height: 1.5,
                color: AppColors.bodyMuted,
              ),
            )
          : _Links(select(links)),
    );

/// 새 책 칸이 서점 링크를 만들 수 없다고 한 번 알리므로 나머지 구매 칸은 숨긴다.
bool _noBookInfo(WidgetRef ref, String isbn) =>
    switch (ref.watch(linksProvider(isbn))) {
      AsyncData(value: null) => true,
      _ => false,
    };

class NewBookSection extends ConsumerWidget {
  const NewBookSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(bookDetailProvider(isbn));
    final sale = detail.value?.sale;
    final now = ref.watch(nowProvider)();
    final fetchedAt = sale?.fetchedAt;
    final price = priceDigits(sale?.salePrice);
    final list = priceLabel(sale?.listPrice);
    return DetailSection(
      title: '새 책',
      trailing: fetchedAt != null && price != null && list != null
          ? Text(
              list,
              style: detailNoteStyle.copyWith(
                fontSize: 13,
                decoration: TextDecoration.lineThrough,
              ),
            )
          : null,
      children: [
        if (detail.hasError)
          const Unavailable('확인 불가')
        else if (sale != null && fetchedAt == null)
          Unavailable(saleStatusLabel(sale))
        else if (sale != null && fetchedAt != null) ...[
          if (price != null) PriceText(price, size: 44),
          Text(
            '${saleStatusLabel(sale)} · ${basisLabel(fetchedAt, now)}',
            style: detailNoteStyle,
          ),
        ],
        _linksOf(ref, isbn, (links) => links.newBooks),
      ],
    );
  }
}

class UsedSection extends ConsumerWidget {
  const UsedSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (_noBookInfo(ref, isbn)) return const SizedBox.shrink();
    return DetailSection(
      title: '중고',
      children: [_linksOf(ref, isbn, (links) => links.used)],
    );
  }
}

class EbookSection extends ConsumerWidget {
  const EbookSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (_noBookInfo(ref, isbn)) return const SizedBox.shrink();
    final ebook = ref.watch(ebookProvider(isbn));
    final now = ref.watch(nowProvider)();
    final offer = ebook.value;
    final price = offer != null && offer.matched
        ? priceDigits(offer.price)
        : null;
    final unavailable =
        ebook.hasError || (offer?.status == 'UNKNOWN' && price == null);
    return DetailSection(
      title: '전자책',
      subtitle: price == null
          ? null
          : [
              'Google Play · ${offer!.attribution ?? 'powered by Google'}',
              if (offer.fetchedAt != null) basisLabel(offer.fetchedAt!, now),
            ].join('\n'),
      trailing: ebook.isLoading
          ? const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : unavailable
          ? const Text(
              '확인 불가',
              style: TextStyle(fontSize: 18, color: AppColors.bodyMuted),
            )
          : price == null
          ? null
          : InkWell(
              onTap: offer?.link == null
                  ? null
                  : () => launchUrl(
                      Uri.parse(offer!.link!),
                      mode: LaunchMode.externalApplication,
                    ),
              child: PriceText(price, size: 30),
            ),
      children: [_linksOf(ref, isbn, (links) => links.ebook)],
    );
  }
}
