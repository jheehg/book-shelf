import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../api/api_error.dart';
import '../api/models.dart';
import '../api/providers.dart';
import '../book/book_providers.dart';
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

  String? get _problem {
    if (reviewLength(_review.text) > reviewMax) {
      return '한줄평은 $reviewMax자까지예요';
    }
    return validateReadDates(_startedAt, _finishedAt);
  }

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
        title: const Text('기록 고치기'),
        actions: [
          IconButton(
            key: const Key('delete'),
            tooltip: '기록 삭제',
            icon: const Icon(Icons.delete_outline),
            onPressed: _deleting || _saving || found == null ? null : _delete,
          ),
          TextButton(
            onPressed:
                _loaded == null || _problem != null || _saving || _deleting
                ? null
                : _save,
            child: const Text('저장'),
          ),
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
    final problem = _problem;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          record.book?.title ?? 'ISBN ${record.isbn13}',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        SegmentedButton<ReadingStatus>(
          segments: [
            for (final status in ReadingStatus.values)
              ButtonSegment(value: status, label: Text(status.label)),
          ],
          selected: {_status},
          onSelectionChanged: (selected) => _changeStatus(selected.first),
        ),
        const SizedBox(height: 16),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var star = 1; star <= 5; star++)
              IconButton(
                key: Key('star-$star'),
                tooltip: '$star점',
                icon: Icon(
                  _rating != null && star <= _rating!
                      ? Icons.star
                      : Icons.star_border,
                ),
                onPressed: () =>
                    setState(() => _rating = _rating == star ? null : star),
              ),
          ],
        ),
        TextField(
          key: const Key('review'),
          controller: _review,
          onChanged: (_) => setState(() {}),
          maxLines: 3,
          decoration: InputDecoration(
            labelText: '한줄평',
            counterText: '$length/$reviewMax',
            counterStyle: TextStyle(
              color: length > reviewMax
                  ? Theme.of(context).colorScheme.error
                  : null,
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text('공개 범위', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        SegmentedButton<RecordVisibility>(
          segments: [
            for (final visibility in RecordVisibility.values)
              ButtonSegment(value: visibility, label: Text(visibility.label)),
          ],
          selected: {_visibility},
          onSelectionChanged: (selected) =>
              setState(() => _visibility = selected.first),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(_visibility.description),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => _pickDate(started: true),
                child: Text(_startedAt ?? '시작한 날'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton(
                onPressed: () => _pickDate(started: false),
                child: Text(_finishedAt ?? '다 읽은 날'),
              ),
            ),
          ],
        ),
        if (_startedAt != null || _finishedAt != null)
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () => setState(() {
                _startedAt = null;
                _finishedAt = null;
              }),
              child: const Text('날짜 지우기'),
            ),
          ),
        if (problem != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              problem,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
      ],
    );
  }
}
