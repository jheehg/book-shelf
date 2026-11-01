import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../book/book_providers.dart';
import '../theme/app_theme.dart';
import '../theme/reading_status_style.dart';
import '../widgets/cover.dart';
import '../widgets/page_heading.dart';
import '../widgets/visibility_picker.dart';
import 'finish_date_sheet.dart';
import 'records_provider.dart';
import 'review_length.dart';

class RecordEditScreen extends ConsumerStatefulWidget {
  const RecordEditScreen({super.key, required this.id});

  final int id;

  @override
  ConsumerState<RecordEditScreen> createState() => _RecordEditScreenState();
}

class _RecordEditScreenState extends ConsumerState<RecordEditScreen> {
  final _review = TextEditingController();
  RecordItem? _loaded;
  late ReadingStatus _status;
  late RecordVisibility _visibility;
  int? _rating;
  String? _startedAt;
  String? _finishedAt;
  bool _saving = false;
  bool _deleting = false;

  @override
  void dispose() {
    _review.dispose();
    super.dispose();
  }

  void _load(RecordItem record) {
    _loaded = record;
    _status = record.status;
    _visibility = record.visibility;
    _rating = record.rating;
    _startedAt = record.startedAt;
    _finishedAt = record.finishedAt;
    _review.text = record.review ?? '';
  }

  String? get _reviewProblem =>
      reviewLength(_review.text) > reviewMax ? '한줄평은 $reviewMax자까지예요' : null;

  String? get _dateProblem => validateReadDates(_startedAt, _finishedAt);

  String? get _problem => _reviewProblem ?? _dateProblem;

  Future<void> _save() async {
    if (_saving || _problem != null) return;
    setState(() => _saving = true);
    final review = _review.text.trim();
    try {
      await ref
          .read(apiProvider)
          .updateRecord(
            widget.id,
            RecordUpdate(
              status: _status,
              visibility: _visibility,
              rating: _rating,
              review: review.isEmpty ? null : review,
              startedAt: _startedAt,
              finishedAt: _finishedAt,
            ),
          );
      ref.invalidate(recordsProvider);
      if (mounted) {
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

  Future<void> _delete() async {
    if (_deleting || _saving) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('이 기록을 지울까요?'),
        content: const Text('별점과 한줄평도 함께 지워져요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('지우기'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deleting = true);
    try {
      await ref.read(apiProvider).deleteRecord(widget.id);
      ref.invalidate(recordsProvider);
      if (mounted) context.go('/');
    } on ApiException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.message)));
      }
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  Future<void> _changeStatus(ReadingStatus status) async {
    if (status != ReadingStatus.done || _finishedAt != null) {
      setState(() => _status = status);
      return;
    }
    final answer = await askFinishDate(context, ref.read(nowProvider)());
    if (!mounted) return;
    switch (answer) {
      case FinishCancelled():
        return;
      case FinishedUndated():
        setState(() => _status = status);
      case FinishedOn(:final day):
        setState(() {
          _status = status;
          _finishedAt = day;
        });
    }
  }

  Future<void> _pickDate({required bool started}) async {
    final current = started ? _startedAt : _finishedAt;
    final now = ref.read(nowProvider)();
    final picked = await showDatePicker(
      context: context,
      initialDate: current == null ? now : DateTime.parse(current),
      firstDate: DateTime(1900),
      lastDate: now,
    );
    if (picked == null || !mounted) return;
    final value = dayString(picked);
    setState(() {
      if (started) {
        _startedAt = value;
      } else {
        _finishedAt = value;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final records = ref.watch(recordsProvider);
    final found = switch (records) {
      AsyncData(:final value) =>
        value.where((record) => record.id == widget.id).firstOrNull,
      _ => null,
    };
    if (found != null && _loaded == null) _load(found);

    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                tooltip: '뒤로',
                onPressed: context.pop,
                icon: const Icon(Icons.chevron_left, size: 32),
              )
            : null,
        actions: [
          ?homeButton(context),
          IconButton(
            key: const Key('delete'),
            tooltip: '기록 삭제',
            icon: const Icon(Icons.delete_outline, size: 24),
            onPressed: _deleting || _saving || found == null ? null : _delete,
          ),
          const SizedBox(width: 6),
          FilledButton(
            onPressed:
                _loaded == null || _problem != null || _saving || _deleting
                ? null
                : _save,
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
      body: records.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => const Center(child: Text('기록을 불러오지 못했어요')),
        data: (_) =>
            found == null ? const Center(child: Text('기록이 없어요')) : _form(found),
      ),
    );
  }

  Widget _form(RecordItem record) {
    final length = reviewLength(_review.text);
    final reviewProblem = _reviewProblem;
    final dateProblem = _dateProblem;
    return ListView(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 40),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            spacing: 14,
            children: [
              DecoratedBox(
                decoration: const BoxDecoration(
                  boxShadow: [
                    BoxShadow(
                      color: Color(0x26000000),
                      blurRadius: 8,
                      offset: Offset(0, 2),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: Cover(
                    url: record.book?.coverUrl,
                    isbn: record.isbn13,
                    width: 52,
                  ),
                ),
              ),
              Expanded(
                child: Text.rich(
                  TextSpan(
                    text: '${record.book?.title ?? 'ISBN ${record.isbn13}'}\n',
                    children: const [
                      TextSpan(
                        text: '기록 고치기',
                        style: TextStyle(color: AppColors.muted),
                      ),
                    ],
                  ),
                  style: const TextStyle(
                    fontSize: 30,
                    height: 1.12,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -0.6,
                  ),
                ),
              ),
            ],
          ),
        ),
        Row(
          spacing: 8,
          children: [
            for (final status in ReadingStatus.values)
              Expanded(
                child: _StatusTile(
                  status: status,
                  selected: status == _status,
                  onTap: () => _changeStatus(status),
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),
        _Panel(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 6,
                  children: [
                    const Text('별점', style: _label),
                    Transform.translate(
                      offset: const Offset(-10, 0),
                      child: Row(
                        children: [
                          for (var star = 1; star <= 5; star++)
                            IconButton(
                              key: Key('star-$star'),
                              tooltip: '$star점',
                              padding: EdgeInsets.zero,
                              constraints: const BoxConstraints.tightFor(
                                width: 44,
                                height: 44,
                              ),
                              icon: Icon(
                                Icons.star_rounded,
                                size: 32,
                                color: _rating != null && star <= _rating!
                                    ? AppColors.ink
                                    : AppColors.unknown,
                              ),
                              onPressed: () => setState(
                                () => _rating = _rating == star ? null : star,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              ExcludeSemantics(
                child: Text(
                  _rating?.toString() ?? '–',
                  style: TextStyle(
                    fontSize: 60,
                    height: 1,
                    letterSpacing: -1.8,
                    color: _rating == null ? AppColors.unknown : AppColors.ink,
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        _Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            spacing: 10,
            children: [
              const Text('한줄평', style: _label),
              TextField(
                key: const Key('review'),
                controller: _review,
                onChanged: (_) => setState(() {}),
                minLines: 3,
                maxLines: 3,
                style: const TextStyle(
                  fontSize: 18,
                  height: 1.5,
                  fontWeight: FontWeight.w500,
                ),
                decoration: const InputDecoration(
                  filled: false,
                  isCollapsed: true,
                  contentPadding: EdgeInsets.zero,
                  border: InputBorder.none,
                  hintText: '이 책을 한 줄로 남겨요',
                  hintStyle: TextStyle(color: AppColors.unknown),
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: reviewProblem == null
                        ? const SizedBox.shrink()
                        : _Problem(reviewProblem),
                  ),
                  Text(
                    '$length / $reviewMax',
                    style: TextStyle(
                      fontSize: 12,
                      color: length > reviewMax
                          ? Theme.of(context).colorScheme.error
                          : AppColors.bodyMuted,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        _Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            spacing: 12,
            children: [
              const Text('공개 범위', style: _label),
              VisibilityPicker(
                selected: _visibility,
                onChanged: (visibility) =>
                    setState(() => _visibility = visibility),
              ),
              Text(
                _visibility.description,
                style: const TextStyle(
                  fontSize: 13,
                  color: AppColors.bodyMuted,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        _Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            spacing: 12,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 14,
                      children: [
                        _DateField(
                          label: '시작한 날',
                          day: _startedAt,
                          onTap: () => _pickDate(started: true),
                        ),
                        _DateField(
                          label: '다 읽은 날',
                          day: _finishedAt,
                          onTap: () => _pickDate(started: false),
                        ),
                      ],
                    ),
                  ),
                  if (_startedAt != null || _finishedAt != null)
                    OutlinedButton(
                      onPressed: () => setState(() {
                        _startedAt = null;
                        _finishedAt = null;
                      }),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size(0, 44),
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        textStyle: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      child: const Text('날짜 지우기'),
                    ),
                ],
              ),
              if (dateProblem != null) _Problem(dateProblem),
            ],
          ),
        ),
      ],
    );
  }
}

const _label = TextStyle(fontSize: 15, fontWeight: FontWeight.w700);

class _Panel extends StatelessWidget {
  const _Panel({
    required this.child,
    this.padding = const EdgeInsets.fromLTRB(20, 18, 20, 18),
  });

  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(padding: padding, child: child),
  );
}

class _Problem extends StatelessWidget {
  const _Problem(this.message);

  final String message;

  @override
  Widget build(BuildContext context) => Text(
    message,
    style: TextStyle(
      fontSize: 13,
      fontWeight: FontWeight.w700,
      color: Theme.of(context).colorScheme.error,
    ),
  );
}

class _StatusTile extends StatelessWidget {
  const _StatusTile({
    required this.status,
    required this.selected,
    required this.onTap,
  });

  final ReadingStatus status;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    inMutuallyExclusiveGroup: true,
    checked: selected,
    child: Material(
      color: status.color,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: selected
            ? const BorderSide(color: AppColors.ink, width: 3)
            : BorderSide.none,
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: 104,
          child: Stack(
            children: [
              Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Transform.rotate(
                      angle: status.arrowAngle,
                      child: const Icon(
                        Icons.arrow_forward,
                        size: 24,
                        color: AppColors.ink,
                      ),
                    ),
                    Text(
                      status.label,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ],
                ),
              ),
              if (selected)
                Positioned(
                  top: 10,
                  right: 10,
                  child: Container(
                    width: 22,
                    height: 22,
                    decoration: const BoxDecoration(
                      color: AppColors.ink,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.check,
                      size: 14,
                      color: AppColors.surface,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _DateField extends StatelessWidget {
  const _DateField({
    required this.label,
    required this.day,
    required this.onTap,
  });

  final String label;
  final String? day;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    spacing: 4,
    children: [
      Text(label, style: _label),
      Semantics(
        button: true,
        label: label,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: Text(
            day == null ? '날짜 없음' : dayLabel(day!),
            style: TextStyle(
              fontSize: 26,
              letterSpacing: -0.52,
              color: day == null ? AppColors.unknown : AppColors.ink,
            ),
          ),
        ),
      ),
    ],
  );
}
