import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../book/book_providers.dart';
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
        title: const Text('내 도서관'),
        actions: [
          TextButton(
            onPressed: _selected == null || _saving ? null : _save,
            child: const Text('저장'),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: Text(
              '내 도서관 ${selected.length}/$maxLibraries',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Wrap(
              spacing: 8,
              children: [
                for (final library in selected)
                  InputChip(
                    label: Text(library.name),
                    deleteButtonTooltipMessage: '빼기',
                    deleteIcon: Icon(
                      Icons.close,
                      key: Key('remove-${library.libCode}'),
                    ),
                    onDeleted: () => _toggle(library),
                  ),
              ],
            ),
          ),
          if (unsaved)
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 4, 16, 0),
              child: Text('저장은 하루 3번까지예요. 다 고른 뒤에 저장해 주세요.'),
            ),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                regions.when(
                  loading: () => const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  error: (_, _) => const Text('지역을 불러오지 못했어요'),
                  data: (list) => SizedBox(
                    width: 120,
                    child: DropdownButtonFormField<int>(
                      key: const Key('region'),
                      initialValue: _region,
                      decoration: const InputDecoration(
                        labelText: '지역',
                        isDense: true,
                      ),
                      items: [
                        for (final region in list)
                          DropdownMenuItem(
                            value: region.code,
                            child: Text(region.label),
                          ),
                      ],
                      onChanged: (value) => setState(() => _region = value),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: const Key('library-query'),
                    textInputAction: TextInputAction.search,
                    decoration: const InputDecoration(hintText: '이름이나 주소'),
                    onSubmitted: (value) =>
                        setState(() => _query = value.trim()),
                  ),
                ),
              ],
            ),
          ),
          Expanded(child: _results(selected)),
        ],
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
                  children: [
                    for (final library in list)
                      ListTile(
                        title: Text(library.name),
                        subtitle: library.address == null
                            ? null
                            : Text(library.address!),
                        trailing: Icon(
                          selected.any(
                                (item) => item.libCode == library.libCode,
                              )
                              ? Icons.check_circle
                              : Icons.radio_button_unchecked,
                        ),
                        onTap: () => _toggle(library),
                      ),
                  ],
                ),
        );
  }
}
