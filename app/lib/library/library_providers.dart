import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../api/providers.dart';

/// 서버 MyLibraries.MAX_LIBRARIES와 같은 제한이다.
const int maxLibraries = 3;

final regionsProvider = FutureProvider<List<Region>>(
  (ref) => ref.watch(apiProvider).regions(),
);

final myLibrariesProvider = FutureProvider.autoDispose<List<Library>>(
  (ref) => ref.watch(apiProvider).myLibraries(),
);

/// 로컬 도서관 목록만 조회한다. 키는 (지역 코드, 검색어)다.
final librarySearchProvider = FutureProvider.autoDispose
    .family<List<Library>, (int, String)>(
      (ref, key) => ref
          .watch(apiProvider)
          .libraries(key.$1, key.$2.isEmpty ? null : key.$2),
    );
