import 'dart:async';

import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

SearchItem item(int n) => SearchItem(
  book: Book(
    isbn13: '97911000000${n.toString().padLeft(2, '0')}',
    title: '책 $n',
    author: '저자',
  ),
  sale: Sale(
    status: SaleStatus.onSale,
    salePrice: 13500,
    fetchedAt: DateTime(2026, 10, 1),
  ),
);

void main() {
  late FakeApi api;

  setUp(() => api = FakeApi());

  List<String> searches() =>
      api.calls.where((c) => c.startsWith('search:')).toList();

  Future<void> type(WidgetTester tester, String text, Duration wait) async {
    await tester.enterText(find.byType(TextField), text);
    await tester.pump(wait);
  }

  testWidgets('입력이 400ms 멈춘 뒤 마지막 검색어로 한 번 호출한다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '소년', const Duration(milliseconds: 100));
    await type(tester, '소년이', const Duration(milliseconds: 100));
    await type(tester, '소년이 온다', const Duration(milliseconds: 399));
    expect(searches(), isEmpty);

    await tester.pump(const Duration(milliseconds: 1));
    await tester.pumpAndSettle();
    expect(searches(), ['search:소년이 온다:1']);
  });

  testWidgets('한 글자나 자모만으로 검색하지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '소', const Duration(milliseconds: 500));
    await type(tester, ' 소 ', const Duration(milliseconds: 500));
    await type(tester, 'ㅅㅗ', const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(searches(), isEmpty);
  });

  testWidgets('판매 상태를 표시하고 스크롤하면 다음 10권을 받는다', (tester) async {
    api.onSearch = (query, page) async => page == 1
        ? SearchPage(
            items: [for (var i = 1; i <= 10; i++) item(i)],
            total: 15,
            isEnd: false,
          )
        : SearchPage(
            items: [for (var i = 11; i <= 15; i++) item(i)],
            total: 15,
            isEnd: true,
          );
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '책', const Duration(milliseconds: 10));
    await type(tester, '책들', const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('책 1'), findsOneWidget);
    expect(find.text('책들'), findsNWidgets(2));
    expect(find.text('15권'), findsOneWidget);
    expect(find.text('판매 중'), findsWidgets);
    expect(find.text('13,500원'), findsWidgets);

    await tester.drag(find.byType(ListView), const Offset(0, -3000));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView), const Offset(0, -3000));
    await tester.pumpAndSettle();

    expect(find.text('책 15'), findsOneWidget);
    expect(searches(), ['search:책들:1', 'search:책들:2']);
  });

  testWidgets('늦게 온 이전 검색 응답이 새 결과를 덮지 않는다', (tester) async {
    final slow = Completer<SearchPage>();
    api.onSearch = (query, page) => query == '소년'
        ? slow.future
        : Future.value(SearchPage(items: [item(1)], total: 1, isEnd: true));
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '소년', const Duration(milliseconds: 400));
    await type(tester, '소년이', const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('책 1'), findsOneWidget);

    slow.complete(SearchPage(items: [item(9)], total: 1, isEnd: true));
    await tester.pumpAndSettle();

    expect(find.text('책 9'), findsNothing);
    expect(find.text('책 1'), findsOneWidget);
  });

  testWidgets('검색 상한 오류 메시지를 보여준다', (tester) async {
    api.onSearch = (query, page) => throw const ApiException(
      statusCode: 429,
      code: 'TOO_MANY_REQUESTS',
      message: '오늘은 검색을 너무 많이 했어요. 내일 다시 시도해 주세요',
    );
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '소년이', const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    expect(find.textContaining('오늘은 검색을 너무 많이 했어요'), findsOneWidget);
  });

  testWidgets('지우기 버튼은 입력이 있을 때만 보이고 검색어와 결과를 비운다', (tester) async {
    api.onSearch = (query, page) async =>
        SearchPage(items: [item(1)], total: 1, isEnd: true);
    await pumpApp(tester, api, initialLocation: '/search');
    expect(find.byIcon(Icons.search), findsOneWidget);
    expect(find.byTooltip('지우기'), findsNothing);

    await type(tester, '책들', const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('책 1'), findsOneWidget);

    await tester.tap(find.byTooltip('지우기'));
    await tester.pumpAndSettle();

    expect(find.text('책 1'), findsNothing);
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller!.text,
      isEmpty,
    );
    expect(find.byTooltip('지우기'), findsNothing);
  });

  testWidgets('입력 직후 지우면 이전 검색어로 검색하지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '책들', const Duration(milliseconds: 100));
    await tester.tap(find.byTooltip('지우기'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(searches(), isEmpty);
  });

  testWidgets('검색 결과를 누르면 책 상세로 이동한다', (tester) async {
    api.onSearch = (query, page) async =>
        SearchPage(items: [item(1)], total: 1, isEnd: true);
    await pumpApp(tester, api, initialLocation: '/search');

    await type(tester, '책들', const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.tap(find.text('책 1'));
    await tester.pumpAndSettle();

    expect(api.calls, contains('book:9791100000001'));
  });
}
