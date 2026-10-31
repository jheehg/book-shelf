import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/models.dart';
import '../../theme/app_theme.dart';
import '../book_detail_screen.dart';
import '../book_providers.dart';

class BorrowSection extends ConsumerWidget {
  const BorrowSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final holdings = ref.watch(holdingsProvider(isbn));
    final list = holdings.value;
    final hasRows = list != null && list.isNotEmpty;
    return KeyedSubtree(
      key: const Key('section-빌리기'),
      child: DetailSection(
        title: hasRows ? '내 도서관' : '빌리기',
        trailing: hasRows ? const Text('전날 기준', style: detailNoteStyle) : null,
        spacing: hasRows ? 4 : 10,
        children: [
          switch (holdings) {
            AsyncError() => const Unavailable('확인 불가'),
            AsyncData(value: []) => const _RegisterLibraries(),
            AsyncData(:final value) => Column(
              children: [for (final holding in value) _HoldingRow(holding)],
            ),
            _ => const LinearProgressIndicator(),
          },
        ],
      ),
    );
  }
}

class _RegisterLibraries extends StatelessWidget {
  const _RegisterLibraries();

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    spacing: 10,
    children: [
      const Text(
        '내 도서관을 등록하면 대출할 수 있는지 바로 보여요.',
        style: TextStyle(fontSize: 14, height: 1.5, color: AppColors.bodyMuted),
      ),
      OutlinedButton(
        onPressed: () => context.push('/libraries'),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(0, 44),
          padding: const EdgeInsets.fromLTRB(18, 0, 14, 0),
        ),
        child: const Row(
          mainAxisSize: MainAxisSize.min,
          spacing: 6,
          children: [Text('도서관 등록'), Icon(Icons.chevron_right, size: 18)],
        ),
      ),
    ],
  );
}

class _HoldingRow extends StatelessWidget {
  const _HoldingRow(this.holding);

  final Holding holding;

  @override
  Widget build(BuildContext context) {
    final available = holding.state == HoldingState.available;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 52),
      child: Row(
        spacing: 12,
        children: [
          CircleAvatar(
            radius: 18,
            backgroundColor: available ? AppColors.lavender : AppColors.ground,
            foregroundColor: AppColors.ink,
            child: Text(
              holding.name.characters.firstOrNull ?? '',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
            ),
          ),
          Expanded(
            child: Text(
              holding.name,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
          ),
          _StatePill(holding.state),
        ],
      ),
    );
  }
}

class _StatePill extends StatelessWidget {
  const _StatePill(this.state);

  final HoldingState state;

  @override
  Widget build(BuildContext context) {
    const bold = TextStyle(fontSize: 13, fontWeight: FontWeight.w700);
    return switch (state) {
      HoldingState.available => Container(
        height: 30,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        alignment: Alignment.center,
        decoration: const ShapeDecoration(
          color: AppColors.ink,
          shape: StadiumBorder(),
        ),
        child: Text(
          state.label,
          style: bold.copyWith(color: AppColors.surface),
        ),
      ),
      HoldingState.onLoan => Container(
        height: 30,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        alignment: Alignment.center,
        decoration: const ShapeDecoration(
          shape: StadiumBorder(
            side: BorderSide(color: AppColors.ink, width: 1.5),
          ),
        ),
        child: Text(state.label, style: bold),
      ),
      HoldingState.notHeld || HoldingState.unknown => Text(
        state.label,
        style: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w500,
          color: AppColors.bodyMuted,
        ),
      ),
    };
  }
}
