import 'package:bookshelf/api/token_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('메모리 저장소에서 토큰을 쓰고 지울 수 있다', () async {
    final store = MemoryTokenStore();
    expect(await store.read(), isNull);

    const tokens = Tokens(access: 'access-secret', refresh: 'refresh-secret');
    await store.write(tokens);
    expect((await store.read())?.refresh, 'refresh-secret');

    await store.clear();
    expect(await store.read(), isNull);
    expect(tokens.toString(), isNot(contains('secret')));
  });
}
