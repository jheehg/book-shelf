import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/models.dart';
import '../auth/auth_controller.dart';
import '../widgets/cover.dart';
import 'records_provider.dart';

class ShelfScreen extends ConsumerStatefulWidget {
  const ShelfScreen({super.key});

  @override
  ConsumerState<ShelfScreen> createState() => _ShelfScreenState();
}

class _ShelfScreenState extends ConsumerState<ShelfScreen> {
  ReadingStatus? _filter;

  @override
  Widget build(BuildContext context) {
    final records = ref.watch(recordsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('내 서재'),
        actions: [
          IconButton(
            key: const Key('logout'),
            tooltip: '로그아웃',
            icon: const Icon(Icons.logout),
            onPressed: () => ref.read(authProvider.notifier).logout(),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Wrap(
              spacing: 8,
              children: [
                for (final filter in <ReadingStatus?>[
                  null,
                  ...ReadingStatus.values,
                ])
                  ChoiceChip(
                    label: Text(filter?.label ?? '전체'),
                    selected: _filter == filter,
                    onSelected: (_) => setState(() => _filter = filter),
                  ),
              ],
            ),
          ),
          Expanded(
            child: records.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('서재를 불러오지 못했어요'),
                    TextButton(
                      onPressed: () => ref.invalidate(recordsProvider),
                      child: const Text('다시 시도'),
                    ),
                  ],
                ),
              ),
              data: (all) {
                final shown = _filter == null
                    ? all
                    : all.where((r) => r.status == _filter).toList();
                final message = all.isEmpty ? '아직 기록이 없어요.' : '이 상태의 기록이 없어요.';
                return RefreshIndicator(
                  onRefresh: () => ref.refresh(recordsProvider.future),
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: shown.isEmpty ? 1 : shown.length,
                    itemBuilder: (_, i) => shown.isEmpty
                        ? Padding(
                            padding: const EdgeInsets.all(32),
                            child: Center(child: Text(message)),
                          )
                        : _RecordTile(shown[i]),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _RecordTile extends StatelessWidget {
  const _RecordTile(this.record);

  final RecordItem record;

  @override
  Widget build(BuildContext context) {
    final rating = record.rating == null ? '' : ' · ${'★' * record.rating!}';
    return ListTile(
      leading: Cover(url: record.book?.coverUrl, isbn: record.isbn13),
      title: Text(record.book?.title ?? 'ISBN ${record.isbn13}'),
      subtitle: Text('${record.status.label}$rating'),
      onTap: () => context.push('/record/${record.id}'),
    );
  }
}
