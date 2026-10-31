import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

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
    isScrollControlled: true,
    builder: (sheetContext) => SafeArea(
      minimum: const EdgeInsets.fromLTRB(20, 0, 20, 40),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: 8,
        children: [
          const Padding(
            padding: EdgeInsets.only(bottom: 10),
            child: Text(
              '언제 다 읽었어요?',
              style: TextStyle(
                fontSize: 26,
                height: 1.15,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.52,
              ),
            ),
          ),
          _ChoiceRow(
            icon: Icons.today,
            label: '오늘',
            trailing: dayLabel(dayString(now)),
            onTap: () => Navigator.pop(sheetContext, _Choice.today),
          ),
          _ChoiceRow(
            icon: Icons.calendar_month,
            label: '날짜 고르기',
            onTap: () => Navigator.pop(sheetContext, _Choice.pick),
          ),
          _ChoiceRow(
            icon: Icons.help_outline,
            label: '기억 안 나요',
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

/// 'YYYY-MM-DD'를 '2026. 9. 1'처럼 읽기 쉽게 바꾼다.
String dayLabel(String day) {
  final date = DateTime.parse(day);
  return '${date.year}. ${date.month}. ${date.day}';
}

class _ChoiceRow extends StatelessWidget {
  const _ChoiceRow({
    required this.icon,
    required this.label,
    required this.onTap,
    this.trailing,
  });

  final IconData icon;
  final String label;
  final String? trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: AppColors.surface,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: SizedBox(
        height: 64,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            spacing: 14,
            children: [
              Icon(icon, size: 24, color: AppColors.ink),
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (trailing != null)
                Text(
                  trailing!,
                  style: const TextStyle(fontSize: 15, color: AppColors.muted),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
