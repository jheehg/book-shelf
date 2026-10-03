import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'auth/auth_controller.dart';

void main() {
  runApp(
    ProviderScope(
      // 서버 오류를 자동 재시도하면 외부 제공자 호출 예산까지 소모할 수 있다.
      retry: (retryCount, error) => null,
      overrides: [signedOutWiring()],
      child: const BookShelfApp(),
    ),
  );
}
