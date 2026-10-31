import 'dart:async';

import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:bookshelf/auth/auth_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

void main() {
  late FakeApi api;

  setUp(() => api = FakeApi());

  String? fieldError(WidgetTester tester) =>
      tester.widget<TextField>(find.byType(TextField)).decoration?.errorText;

  testWidgets('slug 규칙에 맞지 않으면 서버를 부르지 않고 규칙을 오류로 보여 준다', (tester) async {
    await pumpApp(tester, api, store: MemoryTokenStore());
    expect(fieldError(tester), isNull);
    expect(find.text(slugRule), findsOneWidget);

    await submitDevLogin(tester, 'Me');
    await tester.pumpAndSettle();

    expect(fieldError(tester), slugRule);
    expect(find.text(slugRule), findsOneWidget);
    expect(api.calls.where((call) => call.startsWith('devLogin')), isEmpty);
  });

  testWidgets('서버가 거절하면 화면에 남아 이유를 보여 준다', (tester) async {
    api.onDevLogin = (_) async => throw const ApiException(
      statusCode: 503,
      code: 'NETWORK',
      message: '연결 실패',
    );
    await pumpApp(tester, api, store: MemoryTokenStore());

    await submitDevLogin(tester, 'dev-me');
    await tester.pumpAndSettle();

    expect(find.text('로그인하지 못했어요 (연결 실패)'), findsOneWidget);
    expect(find.text('개발 로그인'), findsOneWidget);
  });

  testWidgets('로그인 중에는 버튼을 다시 누를 수 없다', (tester) async {
    final pending = Completer<void>();
    api.onDevLogin = (_) => pending.future;
    await pumpApp(tester, api, store: MemoryTokenStore());

    await submitDevLogin(tester, 'dev-me');
    await tester.pump();
    await tester.tap(find.text('개발 로그인'), warnIfMissed: false);
    await tester.pump();

    expect(
      api.calls.where((call) => call.startsWith('devLogin')),
      hasLength(1),
    );
    pending.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('키보드가 올라와도 입력칸과 로그인 버튼을 쓸 수 있다', (tester) async {
    tester.view.physicalSize = const Size(1179, 2556);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await pumpApp(tester, api, store: MemoryTokenStore());

    await tester.showKeyboard(find.byType(TextField));
    tester.view.viewInsets = const FakeViewPadding(bottom: 336 * 3);
    await tester.pumpAndSettle();
    await submitDevLogin(tester, 'mine');
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(api.calls, contains('devLogin:mine'));
  });
}
