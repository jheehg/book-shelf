import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../shelf/records_provider.dart';

final nowProvider = Provider<DateTime Function()>((ref) => DateTime.now);

final bookDetailProvider = FutureProvider.autoDispose
    .family<BookDetail?, String>((ref, isbn) async {
      try {
        return await ref.watch(apiProvider).book(isbn);
      } on ApiException catch (error) {
        if (error.notFound) return null;
        rethrow;
      }
    });

final linksProvider = FutureProvider.autoDispose.family<StoreLinks?, String>((
  ref,
  isbn,
) async {
  final api = ref.watch(apiProvider);
  final detail = await ref.watch(bookDetailProvider(isbn).future);
  if (detail == null) return null;
  return api.links(isbn);
});

final holdingsProvider = FutureProvider.autoDispose
    .family<List<Holding>, String>(
      (ref, isbn) => ref.watch(apiProvider).holdings(isbn),
    );

final ebookProvider = FutureProvider.autoDispose.family<Ebook?, String>((
  ref,
  isbn,
) async {
  final api = ref.watch(apiProvider);
  final detail = await ref.watch(bookDetailProvider(isbn).future);
  if (detail == null) return null;
  return api.ebook(isbn);
});

final myRecordProvider = Provider.autoDispose.family<RecordItem?, String>(
  (ref, isbn) => switch (ref.watch(recordsProvider)) {
    AsyncData(:final value) =>
      value.where((record) => record.isbn13 == isbn).firstOrNull,
    _ => null,
  },
);
