import 'package:bookshelf/api/models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..onBook = (isbn) async =>
          BookDetail(book: Book(isbn13: isbn, title: '소년이 온다'), sale: const Sale());
  });

  Future<void> openTypedIsbnDialog(WidgetTester tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.byIcon(Icons.qr_code_scanner));
    await tester.pumpAndSettle();
    await tester.tap(find.text('직접 입력'));
    await tester.pumpAndSettle();
  }

  testWidgets('직접 입력한 ISBN으로 책 상세를 연다', (tester) async {
    await openTypedIsbnDialog(tester);

    await tester.enterText(find.byType(TextField), '9788936434120');
    await tester.tap(find.text('열기'));
    await tester.pumpAndSettle();

    expect(find.text('소년이 온다'), findsOneWidget);
    expect(api.calls, contains('book:9788936434120'));
  });

  testWidgets('직접 입력을 취소하면 스캔 화면에 그대로 남는다', (tester) async {
    await openTypedIsbnDialog(tester);

    await tester.enterText(find.byType(TextField), '978');
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();

    expect(find.text('바코드 찍기'), findsOneWidget);
    expect(find.byType(AlertDialog), findsNothing);
  });
}
