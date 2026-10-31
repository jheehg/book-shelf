import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_error.dart';
import '../api/book_shelf_api.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../auth/auth_controller.dart';
import '../library/library_providers.dart';
import '../shelf/records_provider.dart';
import 'settings_providers.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  bool _busy = false;

  void _snack(String message) => ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));

  Future<void> _mutate(Future<void> Function(BookShelfApi api) action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await action(ref.read(apiProvider));
    } on ApiException catch (error) {
      if (mounted) _snack(error.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _confirm({
    required String title,
    String? content,
    required String action,
    bool destructive = false,
  }) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: content == null ? null : Text(content),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            style: destructive
                ? TextButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  )
                : null,
            onPressed: () => Navigator.pop(context, true),
            child: Text(action),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  Future<void> _editName(Me me) async {
    final name = await showDialog<String>(
      context: context,
      builder: (_) => _TextDialog(
        title: '이름',
        initial: me.displayName,
        maxLength: 30,
        validate: (value) => value.isEmpty ? '이름을 적어 주세요' : null,
      ),
    );
    if (name == null || name == me.displayName) return;
    await _mutate((api) async {
      await api.updateMe(displayName: name);
      ref.invalidate(meProvider);
    });
  }

  Future<void> _editSlug(Me me) async {
    final slug = await showDialog<String>(
      context: context,
      builder: (_) => _TextDialog(
        title: '서재 주소',
        initial: me.slug,
        helper: slugRule,
        validate: (value) => slugPattern.hasMatch(value) ? null : slugRule,
      ),
    );
    if (slug == null || slug == me.slug) return;
    await _mutate((api) async {
      await api.updateMe(slug: slug);
      ref.invalidate(meProvider);
      ref.invalidate(shelfProvider);
    });
  }

  Future<void> _copyUrl(String url) async {
    await Clipboard.setData(ClipboardData(text: url));
    if (mounted) _snack('주소를 복사했어요');
  }

  Future<void> _publish(Shelf shelf) async {
    final mode = await showDialog<RecordVisibility>(
      context: context,
      builder: (_) => _PublishDialog(doneCount: shelf.doneCount),
    );
    if (mode == null) return;
    await _mutate((api) async {
      await api.publishShelf(mode);
      ref.invalidate(shelfProvider);
      ref.invalidate(meProvider);
      ref.invalidate(recordsProvider);
    });
  }

  Future<void> _close() => _mutate((api) async {
    await api.closeShelf();
    ref.invalidate(shelfProvider);
    ref.invalidate(meProvider);
  });

  Future<void> _setDefaultVisibility(RecordVisibility visibility) =>
      _mutate((api) async {
        await api.updateMe(defaultVisibility: visibility);
        ref.invalidate(meProvider);
      });

  Future<void> _logout() async {
    if (!await _confirm(title: '로그아웃할까요?', action: '로그아웃')) return;
    await ref.read(authProvider.notifier).logout();
  }

  Future<void> _deleteAccount() async {
    final confirmed = await _confirm(
      title: '계정을 지울까요?',
      content: '기록과 서재가 모두 지워지고 되돌릴 수 없어요.',
      action: '지우기',
      destructive: true,
    );
    if (!confirmed) return;
    await _mutate((api) async {
      await api.deleteAccount();
      await ref.read(authProvider.notifier).signOutLocally();
    });
  }

  @override
  Widget build(BuildContext context) {
    final me = ref.watch(meProvider);
    final shelf = ref.watch(shelfProvider);
    final meValue = me.value;
    final shelfValue = shelf.value;
    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: meValue != null && shelfValue != null
          ? _settings(meValue, shelfValue)
          : me.hasError || shelf.hasError
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('설정을 불러오지 못했어요'),
                  TextButton(
                    onPressed: () {
                      ref.invalidate(meProvider);
                      ref.invalidate(shelfProvider);
                    },
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            )
          : const Center(child: CircularProgressIndicator()),
    );
  }

  Widget _settings(Me me, Shelf shelf) => ListView(
    children: [
      const _Header('프로필'),
      ListTile(
        title: const Text('이름'),
        subtitle: Text(me.displayName),
        onTap: _busy ? null : () => _editName(me),
      ),
      ListTile(
        title: const Text('서재 주소'),
        subtitle: Text(me.slug),
        onTap: _busy ? null : () => _editSlug(me),
      ),
      ListTile(
        title: const Text('서재 링크'),
        subtitle: Text(shelf.url),
        trailing: IconButton(
          tooltip: '주소 복사',
          icon: const Icon(Icons.copy),
          onPressed: () => _copyUrl(shelf.url),
        ),
      ),
      const _Header('서재 공개'),
      SwitchListTile(
        title: const Text('서재 공개'),
        subtitle: Text(
          shelf.shelfPublic ? '주소를 아는 누구나 내 서재를 볼 수 있어요' : '나만 볼 수 있어요',
        ),
        value: shelf.shelfPublic,
        onChanged: _busy ? null : (open) => open ? _publish(shelf) : _close(),
      ),
      if (shelf.shelfPublic)
        ListTile(
          title: const Text('내 공개 서재 보기'),
          trailing: const Icon(Icons.open_in_new),
          onTap: () => launchUrl(
            Uri.parse(shelf.url),
            mode: LaunchMode.externalApplication,
          ),
        ),
      const _Header('새 기록의 공개 범위'),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SegmentedButton<RecordVisibility>(
              segments: [
                for (final visibility in RecordVisibility.values)
                  ButtonSegment(
                    value: visibility,
                    label: Text(visibility.label),
                  ),
              ],
              selected: {me.defaultVisibility},
              onSelectionChanged: _busy
                  ? null
                  : (selected) => _setDefaultVisibility(selected.first),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(me.defaultVisibility.description),
            ),
          ],
        ),
      ),
      const _Header('내 도서관'),
      ListTile(
        title: const Text('내 도서관'),
        subtitle: Text(
          ref
              .watch(myLibrariesProvider)
              .when(
                loading: () => '',
                error: (_, _) => '불러오지 못했어요',
                data: _librariesLabel,
              ),
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => context.push('/libraries'),
      ),
      const _Header('계정'),
      ListTile(title: const Text('로그아웃'), onTap: _busy ? null : _logout),
      ListTile(
        title: const Text('계정 삭제'),
        onTap: _busy ? null : _deleteAccount,
      ),
      const _Header('앱 정보'),
      const ListTile(
        title: Text('데이터 출처'),
        subtitle: Text('책 정보 카카오 · 도서관 정보나루 · 전자책 Google Books'),
      ),
      ListTile(
        title: const Text('오픈소스 라이선스'),
        onTap: () => showLicensePage(context: context, applicationName: '책장'),
      ),
    ],
  );
}

String _librariesLabel(List<Library> libraries) {
  if (libraries.isEmpty) return '아직 없어요';
  final others = libraries.length > 1 ? ' 외 ${libraries.length - 1}곳' : '';
  return '${libraries.first.name}$others · ${libraries.length}/$maxLibraries';
}

class _Header extends StatelessWidget {
  const _Header(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
    child: Text(text, style: Theme.of(context).textTheme.titleSmall),
  );
}

/// 확인을 누르면 앞뒤 공백을 지운 값을 돌려준다.
class _TextDialog extends StatefulWidget {
  const _TextDialog({
    required this.title,
    required this.initial,
    required this.validate,
    this.helper,
    this.maxLength,
  });

  final String title;
  final String initial;
  final String? Function(String value) validate;
  final String? helper;
  final int? maxLength;

  @override
  State<_TextDialog> createState() => _TextDialogState();
}

class _TextDialogState extends State<_TextDialog> {
  late final _controller = TextEditingController(text: widget.initial);
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final value = _controller.text.trim();
    final error = widget.validate(value);
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    Navigator.pop(context, value);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: TextField(
      controller: _controller,
      autofocus: true,
      autocorrect: false,
      maxLength: widget.maxLength,
      onSubmitted: (_) => _submit(),
      decoration: InputDecoration(helperText: widget.helper, errorText: _error),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('취소'),
      ),
      TextButton(onPressed: _submit, child: const Text('저장')),
    ],
  );
}

class _PublishDialog extends StatefulWidget {
  const _PublishDialog({required this.doneCount});

  final int doneCount;

  @override
  State<_PublishDialog> createState() => _PublishDialogState();
}

class _PublishDialogState extends State<_PublishDialog> {
  RecordVisibility _mode = RecordVisibility.ratingOnly;

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('서재를 공개할까요?'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('다 읽은 기록 ${widget.doneCount}권의 공개 범위가 아래로 바뀌어요'),
        const SizedBox(height: 8),
        RadioGroup<RecordVisibility>(
          groupValue: _mode,
          onChanged: (mode) => setState(() => _mode = mode ?? _mode),
          child: Column(
            children: [
              for (final mode in [
                RecordVisibility.public,
                RecordVisibility.ratingOnly,
              ])
                RadioListTile<RecordVisibility>(
                  contentPadding: EdgeInsets.zero,
                  value: mode,
                  title: Text(mode.label),
                  subtitle: Text(mode.description),
                ),
            ],
          ),
        ),
      ],
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('취소'),
      ),
      TextButton(
        onPressed: () => Navigator.pop(context, _mode),
        child: const Text('공개하기'),
      ),
    ],
  );
}
