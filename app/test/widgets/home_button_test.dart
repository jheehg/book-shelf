import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/shelf/shelf_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

const isbn = '9788936434120';

Future<void> push(WidgetTester tester, String location) async {
  GoRouter.of(tester.element(find.byType(Scaffold).last)).push(location);
  await tester.pumpAndSettle();
}

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..recordsBySlug = {
        'mine': [record(1, isbn, ReadingStatus.reading, title: '소년이 온다')],
      };
  });

  testWidgets('서재 바로 위 화면에는 뒤로 가기만 있다', (tester) async {
    await pumpApp(tester, api);
    await push(tester, '/record/1');

    expect(find.byTooltip('뒤로'), findsOneWidget);
    expect(find.byTooltip('내 서재'), findsNothing);
  });

  testWidgets('여러 화면을 건너왔으면 내 서재 버튼으로 한 번에 돌아간다', (tester) async {
    await pumpApp(tester, api);
    await push(tester, '/search');
    await push(tester, '/book/$isbn');
    await push(tester, '/record/1');

    await tester.tap(find.byTooltip('내 서재'));
    await tester.pumpAndSettle();

    expect(find.byType(ShelfScreen), findsOneWidget);
    expect(find.byTooltip('뒤로'), findsNothing);
  });

  testWidgets('서재를 거치지 않고 열린 화면에도 내 서재 버튼이 있다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/book/$isbn');

    await tester.tap(find.byTooltip('내 서재'));
    await tester.pumpAndSettle();

    expect(find.byType(ShelfScreen), findsOneWidget);
  });
}
