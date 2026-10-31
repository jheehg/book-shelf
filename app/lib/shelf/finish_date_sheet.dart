import 'package:flutter/material.dart';

sealed class FinishAnswer {
  const FinishAnswer();
}

final class FinishCancelled extends FinishAnswer {
  const FinishCancelled();
}

final class FinishedUndated extends FinishAnswer {
  const FinishedUndated();
}

final class FinishedOn extends FinishAnswer {
  const FinishedOn(this.day);

  /// 'YYYY-MM-DD'
  final String day;
}

enum _Choice { today, pick, unknown }

/// 다 읽은 날은 짐작하지 않고 묻는다. 예전에 읽은 책을 담을 때가 많아 오늘로 채우면
/// 달력의 오늘 칸에 옛 책이 쌓인다.
Future<FinishAnswer> askFinishDate(BuildContext context, DateTime now) async {
  final choice = await showModalBottomSheet<_Choice>(
    context: context,
    builder: (sheetContext) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            title: Text(
              '언제 다 읽었어요?',
              style: Theme.of(sheetContext).textTheme.titleMedium,
            ),
          ),
          ListTile(
            leading: const Icon(Icons.today),
            title: const Text('오늘'),
            onTap: () => Navigator.pop(sheetContext, _Choice.today),
          ),
          ListTile(
            leading: const Icon(Icons.calendar_month),
            title: const Text('날짜 고르기'),
            onTap: () => Navigator.pop(sheetContext, _Choice.pick),
          ),
          ListTile(
            leading: const Icon(Icons.help_outline),
            title: const Text('기억 안 나요'),
            onTap: () => Navigator.pop(sheetContext, _Choice.unknown),
          ),
        ],
      ),
    ),
  );
  switch (choice) {
    case null:
      return const FinishCancelled();
    case _Choice.today:
      return FinishedOn(dayString(now));
    case _Choice.unknown:
      return const FinishedUndated();
    case _Choice.pick:
      if (!context.mounted) return const FinishCancelled();
      final picked = await showDatePicker(
        context: context,
        initialDate: now,
        firstDate: DateTime(1900),
        lastDate: now,
      );
      return picked == null
          ? const FinishCancelled()
          : FinishedOn(dayString(picked));
  }
}

String dayString(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-'
    '${date.month.toString().padLeft(2, '0')}-'
    '${date.day.toString().padLeft(2, '0')}';
