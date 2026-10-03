import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../api/providers.dart';
import '../auth/auth_controller.dart';

/// 인증 상태에 묶어 다른 계정으로 로그인하면 반드시 새 기록을 받는다.
final recordsProvider = FutureProvider<List<RecordItem>>((ref) async {
  if (ref.watch(authProvider) != AuthStatus.signedIn) return const [];
  return ref.watch(apiProvider).records();
});
