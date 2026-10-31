import 'dart:math' as math;
import 'dart:ui';

import '../api/models.dart';
import 'app_theme.dart';

extension ReadingStatusStyle on ReadingStatus {
  Color get color => switch (this) {
    ReadingStatus.want => AppColors.tomato,
    ReadingStatus.reading => AppColors.lavender,
    ReadingStatus.done => AppColors.taupe,
  };

  double get arrowAngle => switch (this) {
    ReadingStatus.want => 0,
    ReadingStatus.reading => -math.pi / 4,
    ReadingStatus.done => math.pi / 4,
  };
}
