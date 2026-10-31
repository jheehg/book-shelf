import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../api/providers.dart';

final meProvider = FutureProvider.autoDispose<Me>(
  (ref) => ref.watch(apiProvider).me(),
);

final shelfProvider = FutureProvider.autoDispose<Shelf>(
  (ref) => ref.watch(apiProvider).shelf(),
);
