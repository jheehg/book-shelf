import 'package:bookshelf/api/providers.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:bookshelf/app.dart';
import 'package:bookshelf/auth/auth_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_api.dart';

Future<void> pumpApp(
  WidgetTester tester,
  FakeApi api, {
  TokenStore? store,
  String initialLocation = '/',
  List<Override> overrides = const [],
  bool settle = true,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      retry: (retryCount, error) => null,
      overrides: [
        apiProvider.overrideWithValue(api),
        tokenStoreProvider.overrideWithValue(
          store ?? MemoryTokenStore(const Tokens(access: 'a', refresh: 'r')),
        ),
        initialLocationProvider.overrideWithValue(initialLocation),
        signedOutWiring(),
        ...overrides,
      ],
      child: const BookShelfApp(),
    ),
  );
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump();
  }
}

/// 로그인 화면에서 slug를 적고 개발 로그인을 누른다. 작은 화면이면 버튼까지 스크롤한다.
Future<void> submitDevLogin(WidgetTester tester, String slug) async {
  await tester.enterText(find.byType(TextField), slug);
  await tester.ensureVisible(find.text('개발 로그인'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('개발 로그인'));
}
