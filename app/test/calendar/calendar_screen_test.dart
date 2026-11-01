import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/book/book_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

final today = DateTime(2026, 10, 31, 9);

RecordItem finished(int id, String isbn, String? finishedAt, {String? title}) =>
    RecordItem(
      id: id,
      isbn13: isbn,
      status: ReadingStatus.done,
      visibility: RecordVisibility.private,
      finishedAt: finishedAt,
      book: title == null ? null : Book(isbn13: isbn, title: title),
    );

Future<void> openCalendar(
  WidgetTester tester,
  FakeApi api, {
  String location = '/calendar',
}) {
  tester.view.physicalSize = const Size(1170, 2532);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  return pumpApp(
    tester,
    api,
    initialLocation: location,
    overrides: [nowProvider.overrideWithValue(() => today)],
  );
}

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..recordsBySlug = {
        'mine': [
          finished(1, '9788936434120', '2026-10-12', title: '소년이 온다'),
          finished(2, '9788954651134', '2026-10-12', title: '채식주의자'),
          finished(3, '9791100000007', '2026-09-03', title: '작별하지 않는다'),
          finished(4, '9791100000014', '2026-03-01', title: '흰'),
          finished(5, '9791100000021', '2026-03-20', title: '희랍어 시간'),
          finished(6, '9791100000038', '2025-12-24', title: '지난해 책'),
          record(7, '9791100000045', ReadingStatus.reading, title: '읽는 책'),
        ],
      };
  });

  testWidgets('다 읽은 날 칸에 그 책이 보이고 여러 권이면 더 있다고 알린다', (tester) async {
    await openCalendar(tester, api);

    expect(find.text('2026년 10월'), findsOneWidget);
    expect(find.bySemanticsLabel('이번 달 2권'), findsOneWidget);
    expect(find.bySemanticsLabel('10월 12일, 소년이 온다 외 1권 다 읽음'), findsOneWidget);
    expect(find.text('+1'), findsOneWidget);
  });

  testWidgets('이전 달로 가면 그 달의 책이 보이고 이번 달에서는 다음 달로 못 간다', (tester) async {
    await openCalendar(tester, api);

    final next = find.widgetWithIcon(IconButton, Icons.chevron_right);
    expect(tester.widget<IconButton>(next).onPressed, isNull);

    await tester.tap(find.byTooltip('이전 달'));
    await tester.pumpAndSettle();

    expect(find.text('2026년 9월'), findsOneWidget);
    expect(find.bySemanticsLabel('이번 달 1권'), findsOneWidget);
    expect(find.bySemanticsLabel('9월 3일, 작별하지 않는다 다 읽음'), findsOneWidget);
    expect(tester.widget<IconButton>(next).onPressed, isNotNull);

    await tester.tap(find.byTooltip('다음 달'));
    await tester.pumpAndSettle();
    expect(find.text('2026년 10월'), findsOneWidget);
  });

  testWidgets('달 이름을 눌러 그 달로 가고, 오지 않은 달은 누를 수 없다', (tester) async {
    await openCalendar(tester, api);

    await tester.tap(find.text('11월'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(find.text('2026년 10월'), findsOneWidget);

    final march = find.widgetWithText(InkWell, '3월');
    await tester.ensureVisible(march);
    await tester.pumpAndSettle();
    await tester.tap(march);
    await tester.pumpAndSettle();

    expect(find.text('2026년 3월'), findsOneWidget);
    expect(find.bySemanticsLabel('이번 달 2권'), findsOneWidget);
    expect(find.bySemanticsLabel('3월 1일, 흰 다 읽음'), findsOneWidget);
  });

  testWidgets('지난해를 보면 그해 권수로 보여 준다', (tester) async {
    await openCalendar(tester, api);

    for (var i = 0; i < 10; i++) {
      await tester.tap(find.byTooltip('이전 달'));
      await tester.pumpAndSettle();
    }

    expect(find.text('2025년 12월'), findsOneWidget);
    expect(find.bySemanticsLabel('2025년 다 읽음 1권'), findsOneWidget);
    expect(find.bySemanticsLabel('가장 많이 읽은 달 12월'), findsOneWidget);
  });

  testWidgets('날짜를 누르면 그날 읽은 책 목록에서 내 기록 화면으로 간다', (tester) async {
    await openCalendar(tester, api);

    await tester.tap(find.bySemanticsLabel('10월 12일, 소년이 온다 외 1권 다 읽음'));
    await tester.pumpAndSettle();

    expect(find.text('10월 12일에 다 읽은 책'), findsOneWidget);
    expect(find.text('채식주의자'), findsOneWidget);

    await tester.tap(find.text('소년이 온다'));
    await tester.pumpAndSettle();

    expect(find.byTooltip('기록 삭제'), findsOneWidget);
    expect(find.text('완독 달력'), findsNothing);
  });

  testWidgets('올해 권수와 가장 많이 읽은 달을 보여 준다', (tester) async {
    await openCalendar(tester, api);

    expect(find.bySemanticsLabel('올해 다 읽음 5권'), findsOneWidget);
    expect(find.bySemanticsLabel('가장 많이 읽은 달 3월'), findsOneWidget);
  });

  testWidgets('다 읽은 책이 없는 해에는 가장 많이 읽은 달을 숨긴다', (tester) async {
    api.recordsBySlug['mine'] = [];
    await openCalendar(tester, api);

    expect(find.bySemanticsLabel('올해 다 읽음 0권'), findsOneWidget);
    expect(find.textContaining('가장 많이 읽은 달'), findsNothing);
  });

  testWidgets('날짜 없는 완독 기록은 따로 안내한다', (tester) async {
    api.recordsBySlug['mine'] = [
      finished(1, '9788936434120', null, title: '소년이 온다'),
      finished(2, '9788954651134', null, title: '채식주의자'),
    ];
    await openCalendar(tester, api);

    expect(
      find.text('다 읽은 날이 없는 책 2권은 달력에 안 보여요. 기록에서 날짜를 넣어 주세요.'),
      findsOneWidget,
    );
  });

  testWidgets('서재 메뉴에서 완독 달력을 연다', (tester) async {
    await openCalendar(tester, api, location: '/');

    await tester.tap(find.byTooltip('서재 메뉴'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('완독 달력'));
    await tester.pumpAndSettle();

    expect(find.text('완독 달력'), findsOneWidget);
    expect(find.text('2026년 10월'), findsOneWidget);
  });
}
