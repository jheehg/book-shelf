import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/http_book_shelf_api.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_adapter.dart';

const unauthorized = FakeResponse(401, {
  'code': 'UNAUTHORIZED',
  'message': '다시 로그인해 주세요',
});
const me = {
  'slug': 'mine',
  'displayName': '나',
  'shelfPublic': false,
  'defaultVisibility': 'PRIVATE',
};

void main() {
  late MemoryTokenStore store;
  late int signedOut;

  setUp(() {
    store = MemoryTokenStore(
      const Tokens(access: 'old-access', refresh: 'old-refresh'),
    );
    signedOut = 0;
  });

  HttpBookShelfApi api(FakeAdapter adapter) => HttpBookShelfApi.create(
    baseUrl: 'http://test',
    store: store,
    onSignedOut: () => signedOut++,
    adapter: adapter,
  );

  String? bearer(RequestOptions options) =>
      options.headers['Authorization'] as String?;

  test('저장된 access 토큰을 붙이고 인증 경로에는 붙이지 않는다', () async {
    final adapter = FakeAdapter(
      (options) async => options.path == '/api/me'
          ? const FakeResponse(200, me)
          : const FakeResponse(204),
    );
    final client = api(adapter);

    await client.me();
    await client.logout('old-refresh');

    expect(bearer(adapter.requests[0]), 'Bearer old-access');
    expect(bearer(adapter.requests[1]), isNull);
  });

  test('네 요청이 동시에 401이어도 refresh는 한 번만 보낸다', () async {
    final adapter = FakeAdapter((options) async {
      if (options.path == '/api/auth/refresh') {
        await Future<void>.delayed(const Duration(milliseconds: 20));
        return const FakeResponse(200, {
          'accessToken': 'new-access',
          'refreshToken': 'new-refresh',
        });
      }
      return bearer(options) == 'Bearer new-access'
          ? const FakeResponse(200, me)
          : unauthorized;
    });

    final client = api(adapter);
    final results = await Future.wait([
      client.me(),
      client.me(),
      client.me(),
      client.me(),
    ]);

    expect(results.map((result) => result.slug), everyElement('mine'));
    expect(adapter.count('/api/auth/refresh'), 1);
    expect(
      adapter.requests.firstWhere((r) => r.path == '/api/auth/refresh').data,
      {'refreshToken': 'old-refresh'},
    );
    expect((await store.read())?.refresh, 'new-refresh');
    expect(signedOut, 0);
  });

  test('refresh가 실패하면 토큰을 지우고 로그아웃을 알린다', () async {
    final adapter = FakeAdapter((_) async => unauthorized);
    final client = api(adapter);

    await expectLater(
      Future.wait([client.me(), client.me()]),
      throwsA(isA<ApiException>()),
    );

    expect(adapter.count('/api/auth/refresh'), 1);
    expect(await store.read(), isNull);
    expect(signedOut, greaterThanOrEqualTo(1));
  });

  test('새 토큰으로 재전송해도 401이면 더 refresh하지 않는다', () async {
    final adapter = FakeAdapter(
      (options) async => options.path == '/api/auth/refresh'
          ? const FakeResponse(200, {
              'accessToken': 'new-access',
              'refreshToken': 'new-refresh',
            })
          : unauthorized,
    );

    await expectLater(
      api(adapter).me(),
      throwsA(
        isA<ApiException>().having((e) => e.code, 'code', 'UNAUTHORIZED'),
      ),
    );

    expect(adapter.count('/api/auth/refresh'), 1);
    expect(adapter.count('/api/me'), 2);
    expect(await store.read(), isNull);
    expect(signedOut, 1);
  });

  test('401이 아닌 오류는 서버 메시지를 그대로 준다', () async {
    final adapter = FakeAdapter(
      (_) async => const FakeResponse(409, {
        'code': 'CONFLICT',
        'message': '이미 쓰는 주소예요',
      }),
    );

    await expectLater(
      api(adapter).updateMe(slug: 'taken'),
      throwsA(
        isA<ApiException>().having(
          (error) => error.message,
          'message',
          '이미 쓰는 주소예요',
        ),
      ),
    );
    expect(adapter.count('/api/auth/refresh'), 0);
  });
}
