import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/api/providers.dart';
import 'package:bookshelf/api/token_store.dart';
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

  testWidgets('읽기 상태로 거른다', (tester) async {
    await pumpApp(tester, api);
    await tester.tap(find.widgetWithText(ChoiceChip, '읽고 싶다'));
    await tester.pumpAndSettle();
    expect(find.text('소년이 온다'), findsNothing);
    expect(find.text('ISBN 9791100000007'), findsOneWidget);
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

    await tester.tap(find.byKey(const Key('logout')));
    await tester.pumpAndSettle();
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
    await tester.tap(find.byKey(const Key('logout')));
    await tester.pumpAndSettle();

    expect(find.text('개발 로그인'), findsOneWidget);
    expect(await store.read(), isNull);
  });
}
