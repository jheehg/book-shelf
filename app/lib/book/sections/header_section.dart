import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../widgets/cover.dart';
import '../book_providers.dart';

class HeaderSection extends ConsumerWidget {
  const HeaderSection({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) => ref
      .watch(bookDetailProvider(isbn))
      .when(
        loading: () => const SizedBox(
          height: 120,
          child: Center(child: CircularProgressIndicator()),
        ),
        error: (error, _) => Row(
          children: [
            Expanded(child: Text('책 정보를 불러오지 못했어요 (ISBN $isbn)')),
            TextButton(
              onPressed: () => ref.invalidate(bookDetailProvider(isbn)),
              child: const Text('다시 시도'),
            ),
          ],
        ),
        data: (detail) {
          if (detail == null) {
            return Text(
              '책 정보를 찾지 못했어요 (ISBN $isbn)\n그래도 기록해 두면 책 정보는 나중에 채워져요.',
            );
          }
          final book = detail.book;
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Cover(url: book.coverUrl, isbn: book.isbn13, width: 80),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      book.title,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    if (book.author != null) Text(book.author!),
                    if (book.publisher != null)
                      Text(
                        book.publisher!,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ],
          );
        },
      );
}
