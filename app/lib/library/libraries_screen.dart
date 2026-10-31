import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../book/book_providers.dart';
import '../theme/app_theme.dart';
import '../widgets/page_heading.dart';
import 'library_providers.dart';

class LibrariesScreen extends ConsumerStatefulWidget {
  const LibrariesScreen({super.key});

  @override
  ConsumerState<LibrariesScreen> createState() => _LibrariesScreenState();
}

class _LibrariesScreenState extends ConsumerState<LibrariesScreen> {
  List<Library>? _selected;
  int? _region;
  String _query = '';
  bool _saving = false;

  void _toggle(Library library) {
    final selected = [...?_selected];
    final index = selected.indexWhere(
      (item) => item.libCode == library.libCode,
    );
    if (index >= 0) {
      selected.removeAt(index);
    } else if (selected.length >= maxLibraries) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('도서관은 3곳까지 등록할 수 있어요')));
      return;
    } else {
      selected.add(library);
    }
    setState(() => _selected = selected);
  }

  Future<void> _save() async {
    final selected = _selected;
    if (selected == null || _saving) return;
    setState(() => _saving = true);
    try {
      await ref.read(apiProvider).setMyLibraries([
        for (final library in selected) library.libCode,
      ]);
      ref.invalidate(myLibrariesProvider);
      ref.invalidate(holdingsProvider);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('저장했어요')));
        if (context.canPop()) {
          context.pop();
        } else {
          context.go('/');
        }
      }
    } on ApiException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.message)));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final mine = ref.watch(myLibrariesProvider);
    if (_selected == null && mine is AsyncData<List<Library>>) {
      _selected = [...mine.value];
    }
    final selected = _selected ?? const <Library>[];
    final unsaved =
        mine.hasValue &&
        !listEquals(
          [for (final library in selected) library.libCode],
          [for (final library in mine.value!) library.libCode],
        );
    final regions = ref.watch(regionsProvider);

    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: backButton(context),
        actions: [
          FilledButton(
            onPressed: _selected == null || _saving ? null : _save,
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, 44),
              padding: const EdgeInsets.symmetric(horizontal: 20),
              textStyle: const TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
              ),
            ),
            child: const Text('저장'),
          ),
          const SizedBox(width: 16),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            PageHeading(
              title: '내 도서관',
              subtitle: '$maxLibraries곳까지',
              trailing: BigNumber(
                '${selected.length}',
                unit: '/$maxLibraries',
                unitStyle: const TextStyle(
                  fontSize: 28,
                  color: AppColors.muted,
                ),
              ),
            ),
            if (selected.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
                child: Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final library in selected)
                      _SelectedChip(
                        library: library,
                        onRemove: () => _toggle(library),
                      ),
                  ],
                ),
              ),
            if (unsaved)
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 4, 16, 0),
                child: Text(
                  '저장은 하루 3번까지예요. 다 고른 뒤에 저장해 주세요.',
                  style: TextStyle(fontSize: 12, color: AppColors.bodyMuted),
                ),
              ),
            const SizedBox(height: 12),
            Row(
              spacing: 8,
              children: [
                regions.when(
                  loading: () => const _RegionPill(
                    value: SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                  error: (_, _) => const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 8),
                    child: Text('지역을 불러오지 못했어요'),
                  ),
                  data: (list) => PopupMenuButton<int>(
                    key: const Key('region'),
                    tooltip: '지역 고르기',
                    initialValue: _region,
                    onSelected: (value) => setState(() => _region = value),
                    itemBuilder: (_) => [
                      for (final region in list)
                        PopupMenuItem(
                          value: region.code,
                          child: Text(region.label),
                        ),
                    ],
                    child: _RegionPill(
                      value: Text(
                        list
                                .where((region) => region.code == _region)
                                .firstOrNull
                                ?.label ??
                            '고르기',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          color: _region == null
                              ? AppColors.muted
                              : AppColors.ink,
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: TextField(
                    key: const Key('library-query'),
                    textInputAction: TextInputAction.search,
                    style: const TextStyle(fontSize: 15),
                    decoration: const InputDecoration(
                      hintText: '이름이나 주소',
                      prefixIcon: Icon(Icons.search, size: 20),
                      contentPadding: EdgeInsets.symmetric(vertical: 16),
                    ),
                    onSubmitted: (value) =>
                        setState(() => _query = value.trim()),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Expanded(child: _results(selected)),
          ],
        ),
      ),
    );
  }

  Widget _results(List<Library> selected) {
    if (_region == null) {
      return const Center(child: Text('지역을 먼저 골라 주세요'));
    }
    return ref
        .watch(librarySearchProvider((_region!, _query)))
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => const Center(child: Text('도서관을 불러오지 못했어요')),
          data: (list) => list.isEmpty
              ? const Center(child: Text('찾는 도서관이 없어요'))
              : ListView(
                  padding: const EdgeInsets.only(bottom: 32),
                  children: [
                    Card(
                      key: const Key('library-results'),
                      clipBehavior: Clip.antiAlias,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        child: Column(
                          children: [
                            for (final library in list)
                              _LibraryRow(
                                library: library,
                                checked: selected.any(
                                  (item) => item.libCode == library.libCode,
                                ),
                                onTap: () => _toggle(library),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
        );
  }
}

class _SelectedChip extends StatelessWidget {
  const _SelectedChip({required this.library, required this.onRemove});

  final Library library;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) => Container(
    height: 44,
    padding: const EdgeInsets.only(left: 18, right: 6),
    decoration: const ShapeDecoration(
      color: AppColors.ink,
      shape: StadiumBorder(),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      spacing: 6,
      children: [
        Text(
          library.name,
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w700,
            color: AppColors.surface,
          ),
        ),
        Semantics(
          button: true,
          label: '${library.name} 빼기',
          excludeSemantics: true,
          child: Material(
            key: Key('remove-${library.libCode}'),
            color: const Color(0xFF3A3A38),
            shape: const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onRemove,
              child: const SizedBox.square(
                dimension: 32,
                child: Icon(Icons.close, size: 14, color: AppColors.surface),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

class _RegionPill extends StatelessWidget {
  const _RegionPill({required this.value});

  final Widget value;

  @override
  Widget build(BuildContext context) => Container(
    height: 52,
    padding: const EdgeInsets.only(left: 18, right: 14),
    decoration: const ShapeDecoration(
      color: AppColors.surface,
      shape: StadiumBorder(),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      spacing: 8,
      children: [
        const Text(
          '지역',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: AppColors.bodyMuted,
          ),
        ),
        value,
        const Icon(Icons.keyboard_arrow_down, size: 20),
      ],
    ),
  );
}

class _LibraryRow extends StatelessWidget {
  const _LibraryRow({
    required this.library,
    required this.checked,
    required this.onTap,
  });

  final Library library;
  final bool checked;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    checked: checked,
    child: InkWell(
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 68),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
          child: Row(
            spacing: 14,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 2,
                  children: [
                    Text(
                      library.name,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (library.address != null)
                      Text(
                        library.address!,
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.bodyMuted,
                        ),
                      ),
                  ],
                ),
              ),
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: checked ? AppColors.ink : null,
                  border: checked
                      ? null
                      : Border.all(color: const Color(0xFFB5B5B0), width: 2),
                ),
                child: checked
                    ? const Icon(
                        Icons.check,
                        size: 16,
                        color: AppColors.surface,
                      )
                    : null,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
