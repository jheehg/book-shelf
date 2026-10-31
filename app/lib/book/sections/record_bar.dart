import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/api_error.dart';
import '../../api/models.dart';
import '../../api/providers.dart';
import '../../shelf/finish_date_sheet.dart';
import '../../shelf/records_provider.dart';
import '../../theme/app_theme.dart';
import '../../theme/reading_status_style.dart';
import '../book_providers.dart';

const recordBarBottom = 34.0;
const recordBarHeight = 56.0;

/// 화면 아래에 떠 있는 기록 버튼. 담은 책은 기록 고치기로, 아직 담지 않은 책은
/// 상태를 고르는 시트로 이어진다.
class RecordBar extends ConsumerWidget {
  const RecordBar({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mine = ref.watch(myRecordProvider(isbn));
    if (mine != null) {
      return _Pill(
        onPressed: () => context.push('/record/${mine.id}'),
        label: '기록 고치기',
      );
    }
    final knownBook = switch (ref.watch(bookDetailProvider(isbn))) {
      AsyncData(:final value) => value != null,
      _ => false,
    };
    return _Pill(
      onPressed: () async {
        final status = await _askStatus(context);
        if (status != null && context.mounted) {
          await _create(context, ref, status);
        }
      },
      icon: Icons.add,
      label: '내 서재에 담기',
      // 잉크색 책 덩어리 위에서는 잉크 버튼이 묻히므로 토마토로 띄운다.
      background: knownBook ? AppColors.tomato : AppColors.ink,
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

Future<ReadingStatus?> _askStatus(BuildContext context) =>
    showModalBottomSheet<ReadingStatus>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text(
                '내 서재에 담기',
                style: Theme.of(sheetContext).textTheme.titleMedium,
              ),
            ),
            for (final status in ReadingStatus.values)
              ListTile(
                leading: CircleAvatar(
                  backgroundColor: status.color,
                  foregroundColor: AppColors.ink,
                  child: Transform.rotate(
                    angle: status.arrowAngle,
                    child: const Icon(Icons.arrow_forward, size: 22),
                  ),
                ),
                title: Text(
                  status.label,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                onTap: () => Navigator.pop(sheetContext, status),
              ),
          ],
        ),
      ),
    );

class _Pill extends StatelessWidget {
  const _Pill({
    required this.onPressed,
    required this.label,
    this.icon,
    this.background = AppColors.ink,
  });

  final VoidCallback onPressed;
  final String label;
  final IconData? icon;
  final Color background;

  @override
  Widget build(BuildContext context) {
    final foreground = background == AppColors.ink
        ? AppColors.surface
        : AppColors.ink;
    final style = FilledButton.styleFrom(
      backgroundColor: background,
      foregroundColor: foreground,
      minimumSize: const Size(0, recordBarHeight),
      padding: const EdgeInsets.symmetric(horizontal: 30),
      elevation: 8,
      shadowColor: const Color(0x66000000),
    );
    final icon = this.icon;
    return icon == null
        ? FilledButton(style: style, onPressed: onPressed, child: Text(label))
        : FilledButton.icon(
            style: style,
            onPressed: onPressed,
            icon: Icon(icon, size: 20),
            label: Text(label),
          );
  }
}
