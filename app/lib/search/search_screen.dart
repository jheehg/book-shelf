import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../book/labels.dart';
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
      appBar: AppBar(
        title: ListenableBuilder(
          listenable: _query,
          builder: (context, _) => TextField(
            controller: _query,
            autofocus: true,
            decoration: InputDecoration(
              hintText: '제목이나 저자',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: _query.text.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '지우기',
                      icon: const Icon(Icons.close),
                      onPressed: _clear,
                    ),
              filled: true,
              fillColor: Theme.of(context).colorScheme.surfaceContainerHighest,
              isDense: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(28),
                borderSide: BorderSide.none,
              ),
            ),
            onChanged: (value) => _debouncer(
              () => ref.read(searchProvider.notifier).search(value),
            ),
          ),
        ),
      ),
      body: Column(
        children: [
          if (state.error != null)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(state.error!),
            ),
          Expanded(
            child: ListView.builder(
              controller: _scroll,
              itemCount: state.items.length + (state.loading ? 1 : 0),
              itemBuilder: (context, index) {
                if (index == state.items.length) {
                  return const Padding(
                    padding: EdgeInsets.all(16),
                    child: Center(child: CircularProgressIndicator()),
                  );
                }
                final item = state.items[index];
                final price = priceLabel(item.sale.salePrice);
                final subtitle = [
                  item.book.author,
                  saleStatusLabel(item.sale),
                  price,
                ].whereType<String>().join(' · ');
                return ListTile(
                  leading: Cover(
                    url: item.book.coverUrl,
                    isbn: item.book.isbn13,
                  ),
                  title: Text(item.book.title),
                  subtitle: Text(subtitle),
                  onTap: () => context.push('/book/${item.book.isbn13}'),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
