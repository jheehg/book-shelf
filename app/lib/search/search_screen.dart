import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../book/book_detail_screen.dart';
import '../book/labels.dart';
import '../theme/app_theme.dart';
import '../widgets/cover.dart';
import 'debouncer.dart';
import 'search_state.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key});

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _debouncer = Debouncer();
  final _query = TextEditingController();
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_loadWhenNearEnd);
  }

  void _loadWhenNearEnd() {
    if (_scroll.hasClients &&
        _scroll.position.pixels > _scroll.position.maxScrollExtent - 300) {
      ref.read(searchProvider.notifier).loadMore();
    }
  }

  @override
  void dispose() {
    _debouncer.dispose();
    _query.dispose();
    _scroll
      ..removeListener(_loadWhenNearEnd)
      ..dispose();
    super.dispose();
  }

  void _clear() {
    _debouncer.cancel();
    _query.clear();
    ref.read(searchProvider.notifier).search('');
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(searchProvider);
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 8, 16, 0),
              child: Row(
                spacing: 4,
                children: [
                  IconButton(
                    tooltip: '뒤로',
                    icon: const Icon(Icons.chevron_left, size: 32),
                    onPressed: () => Navigator.maybePop(context),
                  ),
                  Expanded(child: _field()),
                ],
              ),
            ),
            if (state.items.isNotEmpty) _Heading(state.query, state.total),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(state.error!),
              ),
            Expanded(
              child: ListView.separated(
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 24),
                itemCount: state.items.length + (state.loading ? 1 : 0),
                separatorBuilder: (_, _) => const SizedBox(height: 8),
                itemBuilder: (context, index) {
                  if (index == state.items.length) {
                    return const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(child: CircularProgressIndicator()),
                    );
                  }
                  return _ResultCard(state.items[index]);
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field() => ListenableBuilder(
    listenable: _query,
    builder: (context, _) => TextField(
      controller: _query,
      autofocus: true,
      style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
      decoration: InputDecoration(
        hintText: '제목이나 저자',
        hintStyle: const TextStyle(
          color: AppColors.muted,
          fontWeight: FontWeight.w500,
        ),
        prefixIcon: const Padding(
          padding: EdgeInsets.only(left: 18, right: 10),
          child: Icon(Icons.search, size: 22, color: AppColors.ink),
        ),
        prefixIconConstraints: const BoxConstraints(),
        suffixIcon: _query.text.isEmpty
            ? null
            : Padding(
                padding: const EdgeInsets.only(right: 6),
                child: IconButton(
                  tooltip: '지우기',
                  style: IconButton.styleFrom(
                    backgroundColor: AppColors.ground,
                    fixedSize: const Size(40, 40),
                    minimumSize: const Size(40, 40),
                  ),
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: _clear,
                ),
              ),
        suffixIconConstraints: const BoxConstraints(),
        contentPadding: const EdgeInsets.symmetric(vertical: 15),
      ),
      onChanged: (value) =>
          _debouncer(() => ref.read(searchProvider.notifier).search(value)),
    ),
  );
}

class _Heading extends StatelessWidget {
  const _Heading(this.query, this.total);

  final String query;
  final int total;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 18, 16, 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      spacing: 12,
      children: [
        Expanded(
          child: Text(
            query,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.w900,
              letterSpacing: -0.52,
            ),
          ),
        ),
        Text.rich(
          TextSpan(
            text: '$total',
            children: const [
              TextSpan(
                text: '권',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w500),
              ),
            ],
          ),
          style: const TextStyle(
            fontSize: 36,
            height: 1,
            fontWeight: FontWeight.w400,
            letterSpacing: -1.08,
          ),
        ),
      ],
    ),
  );
}

class _ResultCard extends StatelessWidget {
  const _ResultCard(this.item);

  final SearchItem item;

  static Color _tagColor(Sale sale) => switch (sale) {
    Sale(fetchedAt: null) => AppColors.ground,
    Sale(status: SaleStatus.onSale) => AppColors.lavender,
    Sale(status: SaleStatus.order) => AppColors.taupe,
    _ => AppColors.ground,
  };

  @override
  Widget build(BuildContext context) {
    final book = item.book;
    final price = priceDigits(item.sale.salePrice);
    final byline = [
      book.author,
      book.publisher,
    ].whereType<String>().join(' · ');
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(22),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => context.push('/book/${book.isbn13}'),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 14, 18, 14),
          child: Row(
            spacing: 14,
            children: [
              DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(4),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x26000000),
                      blurRadius: 8,
                      offset: Offset(0, 2),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: Cover(
                    url: book.coverUrl,
                    isbn: book.isbn13,
                    width: 52,
                  ),
                ),
              ),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 3,
                  children: [
                    Text(
                      book.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 17,
                        height: 1.25,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -0.34,
                      ),
                    ),
                    if (byline.isNotEmpty)
                      Text(
                        byline,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: AppColors.bodyMuted,
                        ),
                      ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                spacing: 8,
                children: [
                  price == null
                      ? const Text(
                          '—',
                          style: TextStyle(fontSize: 22, height: 1),
                        )
                      : PriceText(price, size: 22),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: _tagColor(item.sale),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      saleStatusLabel(item.sale),
                      style: const TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
