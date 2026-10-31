import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/api_error.dart';
import '../../api/models.dart';
import '../../api/providers.dart';
import '../../shelf/records_provider.dart';

class RecordBar extends ConsumerWidget {
  const RecordBar({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mine = switch (ref.watch(recordsProvider)) {
      AsyncData(:final value) =>
        value.where((record) => record.isbn13 == isbn).firstOrNull,
      _ => null,
    };
    if (mine != null) {
      final stars = mine.rating == null ? '' : ' ${'★' * mine.rating!}';
      return Row(
        children: [
          Expanded(child: Text('내 기록 · ${mine.status.label}$stars')),
          TextButton(
            onPressed: () => context.push('/record/${mine.id}'),
            child: const Text('고치기'),
          ),
        ],
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('내 서재에 담기', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              for (final status in ReadingStatus.values)
                FilledButton.tonal(
                  onPressed: () => _create(context, ref, status),
                  child: Text(status.label),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _create(
    BuildContext context,
    WidgetRef ref,
    ReadingStatus status,
  ) async {
    try {
      await ref.read(apiProvider).createRecord(isbn, status);
    } on ApiException catch (error) {
      if (error.code != 'CONFLICT' && context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.message)));
      }
    }
    ref.invalidate(recordsProvider);
  }
}
