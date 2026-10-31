import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/api_error.dart';
import '../../api/models.dart';
import '../../api/providers.dart';
import '../../shelf/finish_date_sheet.dart';
import '../../shelf/records_provider.dart';
import '../book_providers.dart';

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
    String? finishedAt;
    if (status == ReadingStatus.done) {
      final answer = await askFinishDate(context, ref.read(nowProvider)());
      switch (answer) {
        case FinishCancelled():
          return;
        case FinishedUndated():
          break;
        case FinishedOn(:final day):
          finishedAt = day;
      }
    }
    try {
      final created = await ref.read(apiProvider).createRecord(isbn, status);
      if (finishedAt != null &&
          !await _addFinishedAt(ref, created, finishedAt) &&
          context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('기록은 담았어요. 다 읽은 날은 기록 고치기에서 넣어 주세요.')),
        );
      }
    } on ApiException catch (error) {
      if (error.code != 'CONFLICT' && context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.message)));
      }
    }
    ref.invalidate(recordsProvider);
  }

  /// 만들기 요청은 날짜를 받지 않아 만든 기록을 곧바로 고친다.
  Future<bool> _addFinishedAt(
    WidgetRef ref,
    RecordItem created,
    String finishedAt,
  ) async {
    try {
      await ref
          .read(apiProvider)
          .updateRecord(
            created.id,
            RecordUpdate(
              status: created.status,
              visibility: created.visibility,
              rating: created.rating,
              review: created.review,
              startedAt: created.startedAt,
              finishedAt: finishedAt,
            ),
          );
      return true;
    } on ApiException {
      return false;
    }
  }
}
