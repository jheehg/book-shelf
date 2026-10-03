import 'package:dio/dio.dart';

import 'token_store.dart';

/// 요청에 access 토큰을 붙인다. 동시 401은 차례로 처리해 refresh를 한 번만 보낸다.
class AuthInterceptor extends QueuedInterceptor {
  AuthInterceptor({
    required this.store,
    required this.plain,
    required this.onSignedOut,
  });

  final TokenStore store;
  final Dio plain;
  final void Function() onSignedOut;

  static const _retried = 'auth.retried';

  static bool _isAuthPath(RequestOptions options) =>
      options.path.startsWith('/api/auth/');

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (!_isAuthPath(options)) {
      final tokens = await store.read();
      if (tokens != null) {
        options.headers['Authorization'] = 'Bearer ${tokens.access}';
      }
    }
    handler.next(options);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    final options = err.requestOptions;
    if (err.response?.statusCode != 401 ||
        _isAuthPath(options) ||
        options.extra[_retried] == true) {
      return handler.next(err);
    }

    final current = await store.read();
    if (current == null) {
      onSignedOut();
      return handler.next(err);
    }

    var fresh = current;
    if (options.headers['Authorization'] == 'Bearer ${current.access}') {
      // 앞선 401이 토큰을 이미 바꿨다면 현재 토큰으로 재전송만 한다.
      try {
        final response = await plain.post<Map<String, dynamic>>(
          '/api/auth/refresh',
          data: {'refreshToken': current.refresh},
        );
        final data = response.data!;
        fresh = Tokens(
          access: data['accessToken'] as String,
          refresh: data['refreshToken'] as String,
        );
        await store.write(fresh);
      } catch (_) {
        await store.clear();
        onSignedOut();
        return handler.next(err);
      }
    }

    options.headers['Authorization'] = 'Bearer ${fresh.access}';
    options.extra[_retried] = true;
    try {
      handler.resolve(await plain.fetch<dynamic>(options));
    } on DioException catch (retryError) {
      if (retryError.response?.statusCode == 401) {
        await store.clear();
        onSignedOut();
      }
      handler.next(retryError);
    }
  }
}
