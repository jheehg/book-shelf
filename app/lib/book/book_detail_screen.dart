import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'book_providers.dart';
import 'sections/borrow_section.dart';
import 'sections/buy_sections.dart';
import 'sections/header_section.dart';
import 'sections/record_bar.dart';

class BookDetailScreen extends ConsumerWidget {
  const BookDetailScreen({super.key, required this.isbn});

  final String isbn;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(),
    body: RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(bookDetailProvider(isbn));
        ref.invalidate(holdingsProvider(isbn));
        ref.invalidate(linksProvider(isbn));
        ref.invalidate(ebookProvider(isbn));
      },
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              HeaderSection(isbn: isbn),
              RecordBar(isbn: isbn),
              const Divider(height: 32),
              BorrowSection(isbn: isbn),
              NewBookSection(isbn: isbn),
              UsedSection(isbn: isbn),
              EbookSection(isbn: isbn),
            ],
          ),
        ),
      ),
    ),
  );
}

class DetailSection extends StatelessWidget {
  const DetailSection({
    super.key,
    required this.title,
    this.basis,
    required this.children,
  });

  final String title;
  final String? basis;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    key: Key('section-$title'),
    padding: const EdgeInsets.only(bottom: 20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const Spacer(),
            if (basis != null)
              Text(basis!, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
        const SizedBox(height: 8),
        ...children,
      ],
    ),
  );
}
