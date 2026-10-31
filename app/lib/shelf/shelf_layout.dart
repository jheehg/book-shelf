import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';

enum ShelfView { cards, shelves }

/// 책장과 메뉴가 쓰는 상태 순서. enum 선언 순서와 다르다.
const shelfOrder = [
  ReadingStatus.reading,
  ReadingStatus.want,
  ReadingStatus.done,
];

@immutable
class ShelfLayout {
  const ShelfLayout({this.view = ShelfView.cards, this.filter});

  final ShelfView view;

  /// null이면 전체.
  final ReadingStatus? filter;
}

class ShelfLayoutNotifier extends Notifier<ShelfLayout> {
  @override
  ShelfLayout build() => const ShelfLayout();

  void show(ShelfView view) =>
      state = ShelfLayout(view: view, filter: state.filter);

  void filterBy(ReadingStatus? filter) =>
      state = ShelfLayout(view: state.view, filter: filter);
}

final shelfLayoutProvider = NotifierProvider<ShelfLayoutNotifier, ShelfLayout>(
  ShelfLayoutNotifier.new,
);

sealed class ShelfSlot {
  const ShelfSlot();
}

final class SpineSlot extends ShelfSlot {
  const SpineSlot(this.record);
  final RecordItem record;
}

final class BookendSlot extends ShelfSlot {
  const BookendSlot(this.label);
  final String label;
}

const undatedLabel = '날짜 없음';

/// 다 읽음 칸은 다 읽은 해마다 책꽂이 받침으로 나누고, 최근 해부터 놓는다.
List<ShelfSlot> shelfSlots(ReadingStatus status, List<RecordItem> records) {
  final mine = records.where((record) => record.status == status);
  if (status != ReadingStatus.done) {
    return [for (final record in mine) SpineSlot(record)];
  }
  final dated = mine.where((record) => record.finishedAt != null).toList()
    ..sort((a, b) => b.finishedAt!.compareTo(a.finishedAt!));
  final undated = mine.where((record) => record.finishedAt == null).toList();
  final slots = <ShelfSlot>[];
  String? year;
  for (final record in dated) {
    final recordYear = record.finishedAt!.substring(0, 4);
    if (recordYear != year) {
      slots.add(BookendSlot(recordYear));
      year = recordYear;
    }
    slots.add(SpineSlot(record));
  }
  if (undated.isNotEmpty) {
    slots
      ..add(const BookendSlot(undatedLabel))
      ..addAll(undated.map(SpineSlot.new));
  }
  return slots;
}
