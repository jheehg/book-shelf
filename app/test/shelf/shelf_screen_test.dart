import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/api/providers.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:bookshelf/scan/scan_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..recordsBySlug = {
        'mine': [
          record(
            1,
            '9788936434120',
            ReadingStatus.done,
            title: '소년이 온다',
            rating: 5,
          ),
          record(2, '9791100000007', ReadingStatus.want),
        ],
        'dev-other': [
          record(3, '9788954651134', ReadingStatus.reading, title: '다른 사람의 책'),
        ],
      };
  });

  Future<void> confirmLogout(WidgetTester tester) async {
    await tester.tap(find.byTooltip('설정'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('로그아웃'), 200);
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '로그아웃'));
    await tester.pumpAndSettle();
  }

  testWidgets('토큰이 없으면 로그인 화면이고 개발 로그인하면 서재로 간다', (tester) async {
    await pumpApp(tester, api, store: MemoryTokenStore());
    expect(find.text('개발 로그인'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'mine');
    await tester.tap(find.text('개발 로그인'));
    await tester.pumpAndSettle();

    expect(find.text('내 서재'), findsOneWidget);
    expect(find.text('소년이 온다'), findsOneWidget);
    expect(api.calls, contains('devLogin:mine'));
  });

  testWidgets('slug 규칙에 맞지 않으면 서버를 부르지 않는다', (tester) async {
    await pumpApp(tester, api, store: MemoryTokenStore());
    await tester.enterText(find.byType(TextField), 'Me');
    await tester.tap(find.text('개발 로그인'));
    await tester.pumpAndSettle();

    expect(find.textContaining('소문자·숫자·하이픈'), findsOneWidget);
    expect(api.calls.where((call) => call.startsWith('devLogin')), isEmpty);
  });

  testWidgets('책 정보가 없는 기록은 ISBN과 대체 표지로 보인다', (tester) async {
    await pumpApp(tester, api);
    expect(find.text('ISBN 9791100000007'), findsOneWidget);
    expect(
      find.byKey(const Key('cover-placeholder-9791100000007')),
      findsOneWidget,
    );
  });

  Future<void> openMenu(WidgetTester tester) async {
    await tester.tap(find.byTooltip('서재 메뉴'));
    await tester.pumpAndSettle();
  }

  Finder inMenu(Finder finder) =>
      find.descendant(of: find.byType(BottomSheet), matching: finder);

  Future<void> chooseInMenu(WidgetTester tester, String label) async {
    await openMenu(tester);
    await tester.tap(inMenu(find.text(label)));
    await tester.pumpAndSettle();
  }

  void usePhoneSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(1179, 2556);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
  }

  testWidgets('머리말에 전체 권수와 읽는 중 권수를 보여준다', (tester) async {
    api.recordsBySlug['mine']!.add(
      record(4, '9788954651134', ReadingStatus.reading, title: '채식주의자'),
    );
    await pumpApp(tester, api);
    expect(find.text('내 서재'), findsOneWidget);
    expect(find.text('3권 · 읽는 중 1'), findsOneWidget);
  });

  testWidgets('카드에 상태와 저자, 별점을 보여준다', (tester) async {
    await pumpApp(tester, api);
    expect(find.text('다 읽음'), findsOneWidget);
    expect(find.text('읽고 싶음'), findsOneWidget);
    expect(find.text('저자'), findsOneWidget);
    expect(find.text('★'), findsOneWidget);
    expect(find.text('5'), findsOneWidget);
  });

  testWidgets('이름 첫 글자를 누르면 설정 화면으로 간다', (tester) async {
    api.displayName = '주희';
    await pumpApp(tester, api);
    expect(find.text('주'), findsOneWidget);

    await tester.tap(find.byTooltip('설정'));
    await tester.pumpAndSettle();
    expect(find.text('서재 주소'), findsOneWidget);
  });

  testWidgets('메뉴에 상태별 권수를 보여준다', (tester) async {
    api.recordsBySlug['mine'] = [
      record(1, '9788936434120', ReadingStatus.reading),
      record(2, '9788936434121', ReadingStatus.want),
      record(3, '9788936434122', ReadingStatus.want),
      record(4, '9788936434123', ReadingStatus.done),
      record(5, '9788936434124', ReadingStatus.done),
      record(6, '9788936434125', ReadingStatus.done),
    ];
    await pumpApp(tester, api);
    await openMenu(tester);

    for (final (label, count) in [
      ('전체', '6'),
      ('읽는 중', '1'),
      ('읽고 싶음', '2'),
      ('다 읽음', '3'),
    ]) {
      final row = find.ancestor(
        of: inMenu(find.text(label)),
        matching: find.byType(InkWell),
      );
      expect(
        find.descendant(of: row.first, matching: find.text(count)),
        findsOneWidget,
        reason: label,
      );
    }
  });

  testWidgets('메뉴에서 고른 상태만 보이고 메뉴는 닫힌다', (tester) async {
    await pumpApp(tester, api);
    await chooseInMenu(tester, '읽고 싶음');

    expect(find.byType(BottomSheet), findsNothing);
    expect(find.text('소년이 온다'), findsNothing);
    expect(find.text('ISBN 9791100000007'), findsOneWidget);

    await chooseInMenu(tester, '읽는 중');
    expect(find.text('이 상태의 기록이 없어요.'), findsOneWidget);

    await chooseInMenu(tester, '전체');
    expect(find.text('소년이 온다'), findsOneWidget);
    expect(find.text('ISBN 9791100000007'), findsOneWidget);
  });

  testWidgets('책장으로 보면 상태별 책장에 책등이 꽂힌다', (tester) async {
    final semantics = tester.ensureSemantics();
    await pumpApp(tester, api);
    await chooseInMenu(tester, '책장으로 보기');

    for (final heading in ['읽는 중', '읽고 싶음', '다 읽음']) {
      expect(find.text(heading), findsOneWidget);
    }
    expect(
      tester.getTopLeft(find.text('읽는 중')).dy,
      lessThan(tester.getTopLeft(find.text('읽고 싶음')).dy),
    );
    expect(
      tester.getTopLeft(find.text('읽고 싶음')).dy,
      lessThan(tester.getTopLeft(find.text('다 읽음')).dy),
    );
    expect(find.bySemanticsLabel('소년이 온다'), findsOneWidget);
    expect(find.bySemanticsLabel('ISBN 9791100000007'), findsOneWidget);

    await tester.tap(find.bySemanticsLabel('소년이 온다'));
    await tester.pumpAndSettle();
    expect(find.textContaining('기록 고치기'), findsOneWidget);
    semantics.dispose();
  });

  testWidgets('다 읽음 책장은 최근 해부터 받침으로 나누고 날짜 없는 책은 끝에 둔다', (tester) async {
    final semantics = tester.ensureSemantics();
    api.recordsBySlug['mine'] = [
      record(
        1,
        '9788936434120',
        ReadingStatus.done,
        title: '옛 책',
        finishedAt: '2025-05-01',
      ),
      record(
        2,
        '9788936434121',
        ReadingStatus.done,
        title: '봄 책',
        finishedAt: '2026-02-01',
      ),
      record(3, '9788936434122', ReadingStatus.done, title: '날짜 모름'),
      record(
        4,
        '9788936434123',
        ReadingStatus.done,
        title: '여름 책',
        finishedAt: '2026-07-01',
      ),
    ];
    usePhoneSize(tester);
    await pumpApp(tester, api);
    await chooseInMenu(tester, '책장으로 보기');

    final order = ['2026', '여름 책', '봄 책', '2025', '옛 책', '날짜 없음', '날짜 모름']
        .map((label) => tester.getTopLeft(find.bySemanticsLabel(label)).dx)
        .toList();
    expect(order, [...order]..sort());
    semantics.dispose();
  });

  testWidgets('책장으로 볼 때 상태를 고르면 그 책장만 보인다', (tester) async {
    await pumpApp(tester, api);
    await chooseInMenu(tester, '책장으로 보기');
    await chooseInMenu(tester, '다 읽음');

    expect(find.text('다 읽음'), findsOneWidget);
    expect(find.text('읽는 중'), findsNothing);
    expect(find.text('읽고 싶음'), findsNothing);
  });

  testWidgets('메뉴에서 완독 달력으로 간다', (tester) async {
    await pumpApp(tester, api);
    await chooseInMenu(tester, '완독 달력');
    expect(find.text('완독 달력'), findsOneWidget);
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('메뉴에서 설정으로 간다', (tester) async {
    await pumpApp(tester, api);
    await chooseInMenu(tester, '설정');
    expect(find.text('서재 주소'), findsOneWidget);
  });

  testWidgets('아래 막대에서 검색으로 간다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.byTooltip('책 검색'));
    await tester.pumpAndSettle();
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.hintText == '제목이나 저자',
      ),
      findsOneWidget,
    );
  });

  testWidgets('아래 막대에서 내 도서관으로 간다', (tester) async {
    await pumpApp(tester, FakeApi());
    await tester.tap(find.byTooltip('내 도서관'));
    await tester.pumpAndSettle();
    expect(find.text('내 도서관 0/3'), findsOneWidget);
  });

  testWidgets('바코드로 담기 버튼은 바코드 화면을 연다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.text('바코드로 담기'));
    await tester.pumpAndSettle();
    expect(find.byType(ScanScreen), findsOneWidget);
  });

  testWidgets('서재가 비어 있으면 첫 책 자리와 안내를 보여준다', (tester) async {
    await pumpApp(tester, FakeApi());
    expect(find.text('0권'), findsOneWidget);
    expect(find.text('아직 기록이 없어요.'), findsOneWidget);
    expect(find.text('책 뒷면 바코드를 찍어 첫 책을 담아 보세요.'), findsOneWidget);
    expect(find.text('바코드로 담기'), findsOneWidget);
  });

  testWidgets('끝까지 내리면 마지막 카드가 아래 막대에 가리지 않는다', (tester) async {
    api.recordsBySlug['mine'] = [
      for (var i = 0; i < 15; i++)
        record(
          i + 1,
          '97889364341${i.toString().padLeft(2, '0')}',
          ReadingStatus.done,
          title: '책 ${i + 1}',
        ),
    ];
    usePhoneSize(tester);
    await pumpApp(tester, api);

    await tester.dragUntilVisible(
      find.text('책 15'),
      find.byType(ListView),
      const Offset(0, -300),
    );
    await tester.drag(find.byType(ListView), const Offset(0, -2000));
    await tester.pumpAndSettle();

    final lastAuthor = tester.getRect(find.text('저자').last);
    final barTop = tester.getRect(find.byTooltip('책 검색')).top;
    expect(lastAuthor.bottom, lessThan(barTop));
  });

  testWidgets('당겨서 새로고침하면 기록을 다시 받는다', (tester) async {
    await pumpApp(tester, api);
    final before = api.calls.where((call) => call == 'records').length;

    await tester.fling(find.text('소년이 온다'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    expect(api.calls.where((call) => call == 'records').length, before + 1);
  });

  testWidgets('불러오지 못하면 다시 시도할 수 있다', (tester) async {
    var fail = true;
    api.onRecords = () async {
      if (fail) {
        throw const ApiException(
          statusCode: 503,
          code: 'NETWORK',
          message: '연결 실패',
        );
      }
      return api.currentRecords;
    };
    await pumpApp(tester, api);
    expect(find.text('서재를 불러오지 못했어요'), findsOneWidget);

    fail = false;
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(find.text('소년이 온다'), findsOneWidget);
  });

  testWidgets('서재에서 기록을 누르면 편집 화면으로 간다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.text('소년이 온다'));
    await tester.pumpAndSettle();

    expect(find.textContaining('기록 고치기'), findsOneWidget);
    expect(find.text('별점'), findsOneWidget);
  });

  testWidgets('로그아웃 후 다른 계정에는 앞사람의 기록이 남지 않는다', (tester) async {
    await pumpApp(tester, api);
    expect(find.text('소년이 온다'), findsOneWidget);

    await confirmLogout(tester);
    await tester.enterText(find.byType(TextField), 'dev-other');
    await tester.tap(find.text('개발 로그인'));
    await tester.pumpAndSettle();

    expect(find.text('소년이 온다'), findsNothing);
    expect(find.text('다른 사람의 책'), findsOneWidget);
  });

  testWidgets('서버가 refresh를 거부하면 로그인 화면으로 간다', (tester) async {
    await pumpApp(tester, api);
    final container = ProviderScope.containerOf(
      tester.element(find.text('내 서재')),
    );
    container.read(signedOutCallbackProvider)();
    await tester.pumpAndSettle();
    expect(find.text('개발 로그인'), findsOneWidget);
  });

  testWidgets('서버 로그아웃에 실패해도 기기에서는 로그아웃한다', (tester) async {
    api.onLogout = () async => throw const ApiException(
      statusCode: 503,
      code: 'NETWORK',
      message: '연결 실패',
    );
    final store = MemoryTokenStore(const Tokens(access: 'a', refresh: 'r'));
    await pumpApp(tester, api, store: store);
    await confirmLogout(tester);

    expect(find.text('개발 로그인'), findsOneWidget);
    expect(await store.read(), isNull);
  });
}
