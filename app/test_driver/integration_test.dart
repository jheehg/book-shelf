import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

/// 화면 지도용 스크린샷을 build/screen_map/에 저장한다.
Future<void> main() => integrationDriver(
  onScreenshot: (name, bytes, [args]) async {
    final file = File('build/screen_map/$name.png');
    await file.parent.create(recursive: true);
    await file.writeAsBytes(bytes);
    return true;
  },
);
