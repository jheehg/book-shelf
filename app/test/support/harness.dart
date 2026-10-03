import 'package:bookshelf/api/providers.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:bookshelf/app.dart';
import 'package:bookshelf/auth/auth_controller.dart';
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
