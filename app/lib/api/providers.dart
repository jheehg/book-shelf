import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config.dart';
import 'book_shelf_api.dart';
import 'http_book_shelf_api.dart';
import 'token_store.dart';

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

/// refresh 실패 시 Task 5의 인증 상태가 이 콜백을 로그인 화면 전환에 연결한다.
final signedOutCallbackProvider = Provider<void Function()>((ref) => () {});

final apiProvider = Provider<BookShelfApi>((ref) {
  return HttpBookShelfApi.create(
    baseUrl: apiBaseUrl,
    store: ref.watch(tokenStoreProvider),
    onSignedOut: () => ref.read(signedOutCallbackProvider)(),
  );
});
