import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/models.dart';
import '../book_detail_screen.dart';
import '../book_providers.dart';

class BorrowSection extends ConsumerWidget {
  const BorrowSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final holdings = ref.watch(holdingsProvider(isbn));
    return DetailSection(
      title: '빌리기',
      basis: holdings.hasValue && holdings.value!.isNotEmpty ? '전날 기준' : null,
      children: [
        holdings.when(
          loading: () => const LinearProgressIndicator(),
          error: (_, _) => const Text('확인 불가'),
          data: (list) => list.isEmpty
              ? Row(
                  children: [
                    const Expanded(child: Text('도서관을 등록하면 대출 가능 여부가 보여요')),
                    TextButton(
                      onPressed: () => context.push('/libraries'),
                      child: const Text('등록'),
                    ),
                  ],
                )
              : Column(
                  children: [
                    for (final holding in list)
                      Row(
                        children: [
                          Expanded(child: Text(holding.name)),
                          Text(
                            holding.state.label,
                            style: TextStyle(
                              color: holding.state == HoldingState.available
                                  ? Colors.green
                                  : null,
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
        ),
      ],
    );
  }
}
