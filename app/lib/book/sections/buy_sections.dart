import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/models.dart';
import '../book_detail_screen.dart';
import '../book_providers.dart';
import '../labels.dart';

class _Links extends StatelessWidget {
  const _Links(this.links);

  final List<StoreLink> links;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 8,
    runSpacing: 4,
    children: [
      for (final link in links)
        OutlinedButton.icon(
          onPressed: () => launchUrl(
            Uri.parse(link.url),
            mode: LaunchMode.externalApplication,
          ),
          style: OutlinedButton.styleFrom(
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            iconSize: 14,
            iconAlignment: IconAlignment.end,
          ),
          icon: const Icon(Icons.open_in_new),
          label: Text(link.name),
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
          ? const Text('책 정보가 없어 서점 링크를 만들 수 없어요')
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
    final price = sale == null ? null : priceLabel(sale.salePrice);
    return DetailSection(
      title: '새 책',
      basis: sale?.fetchedAt == null ? null : basisLabel(sale!.fetchedAt!, now),
      children: [
        if (sale != null)
          Row(
            children: [
              Text(saleStatusLabel(sale)),
              const SizedBox(width: 12),
              if (sale.fetchedAt != null && price != null) Text(price),
            ],
          ),
        if (detail.hasError) const Text('확인 불가 · 당겨서 다시 시도'),
        const SizedBox(height: 8),
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
    final value = ebook.value;
    final basis = value != null && value.matched && value.fetchedAt != null
        ? basisLabel(value.fetchedAt!, now)
        : null;
    return DetailSection(
      title: '전자책·구독',
      basis: basis,
      children: [
        ebook.when(
          loading: () => const Align(
            alignment: Alignment.centerLeft,
            child: SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
          error: (_, _) => const Text('가격 확인 불가 · 당겨서 다시 시도'),
          data: (offer) {
            if (offer == null || !offer.matched) {
              return offer?.status == 'UNKNOWN'
                  ? const Text('가격 확인 불가 · 당겨서 다시 시도')
                  : const SizedBox.shrink();
            }
            final price = priceLabel(offer.price);
            if (price == null) return const SizedBox.shrink();
            return InkWell(
              onTap: offer.link == null
                  ? null
                  : () => launchUrl(
                      Uri.parse(offer.link!),
                      mode: LaunchMode.externalApplication,
                    ),
              child: Row(
                children: [
                  const Text('Google Play 도서'),
                  const SizedBox(width: 12),
                  Text(price),
                  const Spacer(),
                  Text(
                    offer.attribution ?? 'powered by Google',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 8),
        _linksOf(ref, isbn, (links) => links.ebook),
      ],
    );
  }
}
