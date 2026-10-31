import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';

import '../api/api_error.dart';
import '../api/providers.dart';
import '../api/token_store.dart';

enum AuthStatus { unknown, signedIn, signedOut }

/// 서버 Member.SLUG와 같은 규칙이다.
final slugPattern = RegExp(r'^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$');
const slugRule = '소문자·숫자·하이픈 3~30자로 적어 주세요';

class AuthController extends Notifier<AuthStatus> {
  @override
  AuthStatus build() {
    Future.microtask(_restore);
    return AuthStatus.unknown;
  }

  Future<void> _restore() async {
    final tokens = await ref.read(tokenStoreProvider).read();
    state = tokens == null ? AuthStatus.signedOut : AuthStatus.signedIn;
  }

  /// 서버에서 DEV_LOGIN_ENABLED=true일 때만 사용할 수 있다.
  Future<void> devLogin(String slug) async {
    final tokens = await ref.read(apiProvider).devLogin(slug);
    await ref
        .read(tokenStoreProvider)
        .write(
          Tokens(access: tokens.accessToken, refresh: tokens.refreshToken),
        );
    state = AuthStatus.signedIn;
  }

  /// 서버 요청에 실패해도 기기의 토큰과 인증 상태는 지운다.
  Future<void> logout() async {
    final tokens = await ref.read(tokenStoreProvider).read();
    try {
      if (tokens != null) await ref.read(apiProvider).logout(tokens.refresh);
    } on ApiException {
      // 오프라인이어도 로컬 로그아웃은 계속한다.
    } finally {
      await signOutLocally();
    }
  }

  /// 계정을 지운 뒤에는 서버 세션이 이미 없어 서버 로그아웃을 부르지 않는다.
  Future<void> signOutLocally() async {
    await ref.read(tokenStoreProvider).clear();
    state = AuthStatus.signedOut;
  }

  void signedOutByServer() => state = AuthStatus.signedOut;
}

final authProvider = NotifierProvider<AuthController, AuthStatus>(
  AuthController.new,
);

/// refresh 거부를 라우터가 반영하도록 main과 테스트가 함께 사용한다.
Override signedOutWiring() => signedOutCallbackProvider.overrideWith(
  (ref) =>
      () => ref.read(authProvider.notifier).signedOutByServer(),
);
