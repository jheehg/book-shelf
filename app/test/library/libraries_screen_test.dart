import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

const seoul = [
  Library(libCode: 'L1', name: '송파도서관', address: '서울 송파구'),
  Library(libCode: 'L2', name: '강동도서관', address: '서울 강동구'),
  Library(libCode: 'L3', name: '가락몰도서관', address: '서울 송파구'),
  Library(libCode: 'L4', name: '노원도서관', address: '서울 노원구'),
];

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..onLibraries = (region, q) async => region == 11 ? seoul : const [];
  });

  Future<void> chooseSeoul(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('region')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('서울').last);
    await tester.pumpAndSettle();
  }

  testWidgets('지역을 고르면 그 지역 도서관이 나오고, 세 곳까지 고를 수 있다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/libraries');
    await chooseSeoul(tester);

    for (final name in ['송파도서관', '강동도서관', '가락몰도서관']) {
      await tester.tap(find.widgetWithText(ListTile, name));
      await tester.pumpAndSettle();
    }
    expect(find.text('내 도서관 3/3'), findsOneWidget);

    await tester.tap(find.widgetWithText(ListTile, '노원도서관'));
    await tester.pumpAndSettle();

    expect(find.text('도서관은 3곳까지 등록할 수 있어요'), findsOneWidget);
    expect(find.text('내 도서관 3/3'), findsOneWidget);
    expect(api.calls.where((c) => c.startsWith('setMyLibraries')), isEmpty);
  });

  testWidgets('저장을 누를 때 고른 순서대로 한 번 보낸다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/libraries');
    await chooseSeoul(tester);
    await tester.tap(find.widgetWithText(ListTile, '강동도서관'));
    await tester.tap(find.widgetWithText(ListTile, '송파도서관'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(api.calls.where((c) => c.startsWith('setMyLibraries')), [
      'setMyLibraries:L2,L1',
    ]);
  });

  testWidgets('이미 등록한 도서관이 처음부터 보이고, 빼고 저장할 수 있다', (tester) async {
    api.myLibraryList = [seoul[0]];
    await pumpApp(tester, api, initialLocation: '/libraries');

    expect(find.text('내 도서관 1/3'), findsOneWidget);
    await tester.tap(find.byKey(const Key('remove-L1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(api.calls, contains('setMyLibraries:'));
  });

  testWidgets('하루 변경 횟수를 넘으면 서버 안내를 보여주고 고른 내용을 보존한다', (tester) async {
    api.onSetMyLibraries = (codes) => throw const ApiException(
      statusCode: 429,
      code: 'TOO_MANY_CHANGES',
      message: '도서관은 하루 3번까지 바꿀 수 있어요. 내일 다시 해 주세요',
    );
    await pumpApp(tester, api, initialLocation: '/libraries');
    await chooseSeoul(tester);
    await tester.tap(find.widgetWithText(ListTile, '송파도서관'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(find.textContaining('하루 3번까지'), findsWidgets);
    expect(find.text('내 도서관 1/3'), findsOneWidget);
  });

  testWidgets('이름으로 좁혀 찾는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/libraries');
    await chooseSeoul(tester);

    await tester.enterText(find.byKey(const Key('library-query')), '송파');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();

    expect(api.calls, contains('libraries:11:송파'));
  });
}
