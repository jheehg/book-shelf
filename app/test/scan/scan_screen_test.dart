import 'package:bookshelf/api/models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..onBook = (isbn) async => BookDetail(
        book: Book(isbn13: isbn, title: '소년이 온다'),
        sale: const Sale(),
      );
  });

  Future<void> openTypedIsbnDialog(WidgetTester tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.byIcon(Icons.qr_code_scanner));
    await tester.pumpAndSettle();
    await tester.tap(find.text('ISBN 직접 입력'));
    await tester.pumpAndSettle();
  }

  TextButton openButton(WidgetTester tester) =>
      tester.widget<TextButton>(find.widgetWithText(TextButton, '열기'));

  testWidgets('카메라 화면이 뜨기 전에는 조준 틀 없이 직접 입력만 보인다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.byIcon(Icons.qr_code_scanner));
    await tester.pumpAndSettle();
    expect(find.text('책 뒷면 바코드를 틀 안에 맞춰 주세요'), findsNothing);
    expect(find.text('ISBN 직접 입력'), findsOneWidget);
  });

  testWidgets('올바른 13자리 ISBN을 입력해야 열기를 누를 수 있다', (tester) async {
    await openTypedIsbnDialog(tester);
    expect(openButton(tester).onPressed, isNull);

    await tester.enterText(find.byType(TextField), '9788936434121');
    await tester.pump();
    expect(openButton(tester).onPressed, isNull);

    await tester.enterText(find.byType(TextField), ' 978-89-364-3412-0 ');
    await tester.pump();
    expect(openButton(tester).onPressed, isNotNull);

    await tester.tap(find.text('열기'));
    await tester.pumpAndSettle();
    expect(api.calls, contains('book:9788936434120'));
  });

  testWidgets('직접 입력한 ISBN으로 책 상세를 연다', (tester) async {
    await openTypedIsbnDialog(tester);

    await tester.enterText(find.byType(TextField), '9788936434120');
    await tester.pump();
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
