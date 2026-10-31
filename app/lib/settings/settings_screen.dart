import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_error.dart';
import '../api/book_shelf_api.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../auth/auth_controller.dart';
import '../library/library_providers.dart';
import '../shelf/records_provider.dart';
import '../theme/app_theme.dart';
import '../widgets/page_heading.dart';
import '../widgets/visibility_picker.dart';
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
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: backButton(context),
      ),
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

  Widget _settings(Me me, Shelf shelf) {
    final libraries = ref.watch(myLibrariesProvider);
    return ListView(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 40),
      children: [
        PageHeading(title: '설정', subtitle: '@${me.slug}'),
        _Section(
          color: AppColors.lavender,
          spacing: 18,
          children: [
            Row(
              spacing: 14,
              children: [
                ExcludeSemantics(
                  child: Container(
                    width: 56,
                    height: 56,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(
                      color: AppColors.ink,
                      shape: BoxShape.circle,
                    ),
                    child: Text(
                      me.displayName.characters.firstOrNull ?? '',
                      style: const TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w700,
                        color: AppColors.surface,
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 2,
                    children: [
                      Text(
                        me.displayName,
                        style: const TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w900,
                          letterSpacing: -0.44,
                        ),
                      ),
                      Text(
                        shelf.url.replaceFirst(RegExp('^https?://'), ''),
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            Material(
              color: const Color(0x99FFFFFF),
              borderRadius: BorderRadius.circular(18),
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  _ProfileRow(
                    label: '이름',
                    value: me.displayName,
                    onTap: _busy ? null : () => _editName(me),
                  ),
                  _ProfileRow(
                    label: '서재 주소',
                    value: me.slug,
                    onTap: _busy ? null : () => _editSlug(me),
                  ),
                ],
              ),
            ),
            Row(
              spacing: 8,
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => _copyUrl(shelf.url),
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(44),
                    ),
                    child: const Text('주소 복사'),
                  ),
                ),
                Expanded(
                  child: FilledButton(
                    onPressed: () => SharePlus.instance.share(
                      ShareParams(uri: Uri.parse(shelf.url)),
                    ),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(44),
                      textStyle: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    child: const Text('공유'),
                  ),
                ),
              ],
            ),
          ],
        ),
        const SizedBox(height: 8),
        _Section(
          children: [
            MergeSemantics(
              child: Row(
                children: [
                  const Expanded(child: Text('서재 공개', style: _cardTitle)),
                  Switch(
                    value: shelf.shelfPublic,
                    onChanged: _busy
                        ? null
                        : (open) => open ? _publish(shelf) : _close(),
                  ),
                ],
              ),
            ),
            Text(
              shelf.shelfPublic ? '주소를 아는 누구나 내 서재를 볼 수 있어요' : '나만 볼 수 있어요',
              style: _note,
            ),
            if (shelf.shelfPublic)
              Align(
                alignment: Alignment.centerLeft,
                child: InkWell(
                  onTap: () => launchUrl(
                    Uri.parse(shelf.url),
                    mode: LaunchMode.externalApplication,
                  ),
                  child: const SizedBox(
                    height: 44,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      spacing: 6,
                      children: [
                        Text(
                          '내 공개 서재 보기',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                            decoration: TextDecoration.underline,
                          ),
                        ),
                        Icon(Icons.north_east, size: 16),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),
        _Section(
          spacing: 12,
          children: [
            const Text('새 기록의 공개 범위', style: _cardTitle),
            VisibilityPicker(
              selected: me.defaultVisibility,
              onChanged: _busy ? null : _setDefaultVisibility,
            ),
            Text(me.defaultVisibility.description, style: _note),
          ],
        ),
        const SizedBox(height: 8),
        Material(
          color: AppColors.tomato,
          borderRadius: BorderRadius.circular(24),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => context.push('/libraries'),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Row(
                spacing: 8,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 4,
                      children: [
                        const Text('내 도서관', style: _cardTitle),
                        Text(
                          libraries.when(
                            loading: () => '',
                            error: (_, _) => '불러오지 못했어요',
                            data: _librariesLabel,
                          ),
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (libraries.value case final list?)
                    BigNumber(
                      '${list.length}',
                      unit: '/$maxLibraries',
                      size: 40,
                      unitStyle: const TextStyle(fontSize: 20),
                    ),
                  const Icon(Icons.chevron_right),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        _Section(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
          spacing: 4,
          children: [
            const Text('앱 정보', style: _cardTitle),
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 2,
                children: [
                  Text('데이터 출처', style: TextStyle(fontSize: 14)),
                  Text(
                    '책 정보 카카오 · 도서관 정보나루 · 전자책 Google Books',
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.6,
                      color: AppColors.bodyMuted,
                    ),
                  ),
                ],
              ),
            ),
            InkWell(
              onTap: () =>
                  showLicensePage(context: context, applicationName: '책장'),
              child: const SizedBox(
                height: 44,
                child: Row(
                  children: [
                    Expanded(
                      child: Text('오픈소스 라이선스', style: TextStyle(fontSize: 14)),
                    ),
                    Icon(Icons.chevron_right, size: 20),
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _logout,
          child: const Text('로그아웃'),
        ),
        TextButton(
          onPressed: _busy ? null : _deleteAccount,
          style: TextButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
            foregroundColor: _danger,
            textStyle: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              decoration: TextDecoration.underline,
              decorationColor: _danger,
            ),
          ),
          child: const Text('계정 삭제'),
        ),
      ],
    );
  }
}

const _cardTitle = TextStyle(fontSize: 17, fontWeight: FontWeight.w900);
const _note = TextStyle(fontSize: 13, height: 1.5, color: AppColors.bodyMuted);
const _danger = Color(0xFFB23D1C);

String _librariesLabel(List<Library> libraries) {
  if (libraries.isEmpty) return '아직 없어요';
  final others = libraries.length > 1 ? ' 외 ${libraries.length - 1}곳' : '';
  return '${libraries.first.name}$others';
}

class _Section extends StatelessWidget {
  const _Section({
    required this.children,
    this.color = AppColors.surface,
    this.spacing = 10,
    this.padding = const EdgeInsets.all(20),
  });

  final List<Widget> children;
  final Color color;
  final double spacing;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Container(
    padding: padding,
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(24),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: spacing,
      children: children,
    ),
  );
}

class _ProfileRow extends StatelessWidget {
  const _ProfileRow({
    required this.label,
    required this.value,
    required this.onTap,
  });

  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: SizedBox(
      height: 52,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Row(
          spacing: 4,
          children: [
            Expanded(
              child: Text(
                label,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            Text(
              value,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
            ),
            const Icon(Icons.chevron_right, size: 18),
          ],
        ),
      ),
    ),
  );
}

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
