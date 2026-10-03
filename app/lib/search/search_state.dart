import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';

final _jamoOnly = RegExp(r'^[ㄱ-ㆎ\s]+$');

bool isSearchable(String query) {
  final normalized = query.trim();
  return normalized.runes.length >= 2 && !_jamoOnly.hasMatch(normalized);
}

const int maxSearchPage = 10;

class SearchState {
  const SearchState({
    this.query = '',
    this.items = const [],
    this.isEnd = true,
    this.loading = false,
    this.error,
    this.page = 0,
  });

  final String query;
  final List<SearchItem> items;
  final bool isEnd;
  final bool loading;
  final String? error;
  final int page;

  SearchState copyWith({bool? loading, String? error}) => SearchState(
    query: query,
    items: items,
    isEnd: isEnd,
    loading: loading ?? this.loading,
    error: error,
    page: page,
  );
}

class SearchNotifier extends Notifier<SearchState> {
  int _generation = 0;

  @override
  SearchState build() => const SearchState();

  Future<void> search(String raw) async {
    final query = raw.trim();
    final generation = ++_generation;
    if (!isSearchable(query)) {
      state = SearchState(query: query);
      return;
    }
    state = SearchState(query: query, isEnd: false, loading: true);
    await _fetch(generation, query, 1, const []);
  }

  Future<void> loadMore() async {
    if (state.loading ||
        state.isEnd ||
        state.items.isEmpty ||
        state.page >= maxSearchPage) {
      return;
    }
    state = state.copyWith(loading: true);
    await _fetch(_generation, state.query, state.page + 1, state.items);
  }

  Future<void> _fetch(
    int generation,
    String query,
    int page,
    List<SearchItem> before,
  ) async {
    try {
      final result = await ref.read(apiProvider).search(query, page);
      if (generation != _generation) return;
      state = SearchState(
        query: query,
        items: [...before, ...result.items],
        isEnd: result.isEnd || page >= maxSearchPage,
        page: page,
      );
    } on ApiException catch (error) {
      if (generation != _generation) return;
      state = SearchState(
        query: query,
        items: before,
        isEnd: true,
        error: error.message,
        page: page - 1,
      );
    }
  }
}

final searchProvider =
    NotifierProvider.autoDispose<SearchNotifier, SearchState>(
      SearchNotifier.new,
    );
