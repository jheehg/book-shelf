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

  testWidgets('서재에서 제목 검색 화면으로 이동할 수 있다', (tester) async {
    await pumpApp(tester, api);

    await tester.tap(find.byTooltip('책 검색'));
    await tester.pumpAndSettle();

    expect(find.byType(TextField), findsOneWidget);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.hintText == '제목이나 저자',
      ),
      findsOneWidget,
    );
  });

  testWidgets('서재에서 내 도서관 관리 화면으로 이동할 수 있다', (tester) async {
    await pumpApp(tester, FakeApi(), initialLocation: '/');

    await tester.tap(find.byTooltip('내 도서관'));
    await tester.pumpAndSettle();

    expect(find.text('내 도서관 0/3'), findsOneWidget);
  });

  testWidgets('서재에서 설정 화면으로 이동할 수 있다', (tester) async {
    await pumpApp(tester, api);

    await tester.tap(find.byTooltip('설정'));
    await tester.pumpAndSettle();

    expect(find.text('설정'), findsOneWidget);
    expect(find.text('서재 주소'), findsOneWidget);
  });

  testWidgets('읽기 상태로 거른다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.widgetWithText(ChoiceChip, '읽고 싶음 1'));
    await tester.pumpAndSettle();
    expect(find.text('소년이 온다'), findsNothing);
    expect(find.text('ISBN 9791100000007'), findsOneWidget);

    await tester.tap(find.widgetWithText(ChoiceChip, '읽는 중 0'));
    await tester.pumpAndSettle();
    expect(find.text('이 상태의 기록이 없어요.'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, '바코드 찍기'), findsNothing);
  });

  testWidgets('거르기 칩에 상태별 권수를 보여준다', (tester) async {
    await pumpApp(tester, api);
    for (final label in ['전체 2', '읽고 싶음 1', '읽는 중 0', '다 읽음 1']) {
      expect(find.widgetWithText(ChoiceChip, label), findsOneWidget);
    }
  });

  testWidgets('권수가 두 자리여도 거르기 칩은 한 줄에 놓인다', (tester) async {
    api.recordsBySlug['mine'] = [
      for (var i = 0; i < 12; i++)
        record(
          i + 1,
          '97889364341${i.toString().padLeft(2, '0')}',
          ReadingStatus.done,
        ),
    ];
    tester.view.physicalSize = const Size(1179, 2556);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await pumpApp(tester, api);

    expect(
      tester.getTopLeft(find.byType(ChoiceChip).last).dy,
      tester.getTopLeft(find.byType(ChoiceChip).first).dy,
    );
  });

  testWidgets('기록에 저자와 상태, 별점을 함께 보여준다', (tester) async {
    await pumpApp(tester, api);
    expect(find.text('저자 · 다 읽음 · ★★★★★'), findsOneWidget);
    expect(find.text('읽고 싶음'), findsOneWidget);
  });

  testWidgets('서재가 비어 있으면 바코드로 첫 책을 담도록 안내한다', (tester) async {
    await pumpApp(tester, FakeApi());
    expect(find.text('아직 기록이 없어요.'), findsOneWidget);
    expect(find.text('책 뒷면 바코드를 찍어 첫 책을 담아 보세요.'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, '바코드 찍기'), findsNothing);
    expect(find.byIcon(Icons.qr_code_scanner), findsOneWidget);
  });

  testWidgets('바코드로 담기 버튼은 바코드 화면을 연다', (tester) async {
    await pumpApp(tester, api);
    expect(find.byIcon(Icons.qr_code_scanner), findsOneWidget);

    await tester.tap(find.text('바코드로 담기'));
    await tester.pumpAndSettle();
    expect(find.byType(ScanScreen), findsOneWidget);
    expect(find.text('바코드 찍기'), findsOneWidget);
  });

  testWidgets('끝까지 내리면 마지막 기록이 바코드 버튼에 가리지 않는다', (tester) async {
    api.recordsBySlug['mine'] = [
      for (var i = 0; i < 15; i++)
        record(
          i + 1,
          '97889364341${i.toString().padLeft(2, '0')}',
          ReadingStatus.done,
          title: '책 ${i + 1}',
        ),
    ];
    tester.view.physicalSize = const Size(1179, 2556);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await pumpApp(tester, api);

    await tester.dragUntilVisible(
      find.text('책 15'),
      find.byType(ListView),
      const Offset(0, -300),
    );
    await tester.drag(find.byType(ListView), const Offset(0, -2000));
    await tester.pumpAndSettle();

    final lastRow = tester.getRect(find.widgetWithText(ListTile, '책 15'));
    final fab = tester.getRect(find.byType(FloatingActionButton));
    expect(lastRow.overlaps(fab), isFalse);
  });

  testWidgets('서재에서 기록을 누르면 편집 화면으로 간다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.text('소년이 온다'));
    await tester.pumpAndSettle();

    expect(find.text('기록 고치기'), findsOneWidget);
    expect(find.text('공개 범위'), findsOneWidget);
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
