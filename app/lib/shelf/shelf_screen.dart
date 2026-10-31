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

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('로그아웃할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('로그아웃'),
          ),
        ],
      ),
    );
    if (confirmed == true) await ref.read(authProvider.notifier).logout();
  }

  @override
  Widget build(BuildContext context) {
    final records = ref.watch(recordsProvider);
    final loaded = records.value;
    return Scaffold(
      appBar: AppBar(
        title: const Text('내 서재'),
        actions: [
          IconButton(
            tooltip: '책 검색',
            icon: const Icon(Icons.search),
            onPressed: () => context.push('/search'),
          ),
          IconButton(
            tooltip: '완독 달력',
            icon: const Icon(Icons.calendar_month_outlined),
            onPressed: () => context.push('/calendar'),
          ),
          IconButton(
            tooltip: '내 도서관',
            icon: const Icon(Icons.local_library_outlined),
            onPressed: () => context.push('/libraries'),
          ),
          IconButton(
            key: const Key('logout'),
            tooltip: '로그아웃',
            icon: const Icon(Icons.logout),
            onPressed: _confirmLogout,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/scan'),
        icon: const Icon(Icons.qr_code_scanner),
        label: const Text('바코드로 담기'),
      ),
      body: Column(
        children: [
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              spacing: 8,
              children: [
                for (final filter in <ReadingStatus?>[
                  null,
                  ...ReadingStatus.values,
                ])
                  ChoiceChip(
                    label: Text(
                      [
                        filter?.label ?? '전체',
                        if (loaded != null)
                          filter == null
                              ? loaded.length
                              : loaded.where((r) => r.status == filter).length,
                      ].join(' '),
                    ),
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
                return RefreshIndicator(
                  onRefresh: () => ref.refresh(recordsProvider.future),
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    // FAB 높이 56과 여백 16에 틈을 더해 마지막 줄이 가리지 않게 한다.
                    padding: EdgeInsets.only(
                      bottom: 88 + MediaQuery.paddingOf(context).bottom,
                    ),
                    itemCount: shown.isEmpty ? 1 : shown.length,
                    itemBuilder: (_, i) => shown.isEmpty
                        ? Padding(
                            padding: const EdgeInsets.all(32),
                            child: all.isEmpty
                                ? const _FirstBookGuide()
                                : const Center(child: Text('이 상태의 기록이 없어요.')),
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

class _FirstBookGuide extends StatelessWidget {
  const _FirstBookGuide();

  @override
  Widget build(BuildContext context) => const Column(
    children: [
      Text('아직 기록이 없어요.'),
      SizedBox(height: 4),
      Text('책 뒷면 바코드를 찍어 첫 책을 담아 보세요.'),
    ],
  );
}

class _RecordTile extends StatelessWidget {
  const _RecordTile(this.record);

  final RecordItem record;

  @override
  Widget build(BuildContext context) {
    final subtitle = [
      record.book?.author,
      record.status.label,
      if (record.rating != null) '★' * record.rating!,
    ].whereType<String>().join(' · ');
    return ListTile(
      leading: Cover(url: record.book?.coverUrl, isbn: record.isbn13),
      title: Text(record.book?.title ?? 'ISBN ${record.isbn13}'),
      subtitle: Text(subtitle),
      onTap: () => context.push('/record/${record.id}'),
    );
  }
}
