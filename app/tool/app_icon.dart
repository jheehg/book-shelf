// 앱 아이콘을 BrandMark 그대로 그려 iOS·Android 아이콘 파일을 다시 만든다.
// 실행: cd app && flutter test tool/app_icon.dart
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:bookshelf/theme/app_theme.dart';
import 'package:bookshelf/widgets/brand_mark.dart';
import 'package:flutter_test/flutter_test.dart';

const _iosDir = 'ios/Runner/Assets.xcassets/AppIcon.appiconset';

const _android = {
  'mdpi': 48,
  'hdpi': 72,
  'xhdpi': 96,
  'xxhdpi': 144,
  'xxxhdpi': 192,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('앱 아이콘을 다시 만든다', () async {
    final contents = jsonDecode(
      File('$_iosDir/Contents.json').readAsStringSync(),
    ) as Map<String, dynamic>;
    for (final image in (contents['images'] as List).cast<Map>()) {
      final points = double.parse((image['size'] as String).split('x').first);
      final scale = int.parse((image['scale'] as String).replaceAll('x', ''));
      final file = File('$_iosDir/${image['filename']}');
      file.writeAsBytesSync(await _render((points * scale).round()));
    }
    for (final MapEntry(key: density, value: px) in _android.entries) {
      File('android/app/src/main/res/mipmap-$density/ic_launcher.png')
          .writeAsBytesSync(await _render(px));
    }
  });
}

/// 잉크 바탕을 꽉 채우고 가운데에 표식을 놓는다. 캔버스 시안의 220px 타일 속 170px 표식 비율.
Future<Uint8List> _render(int px) async {
  final recorder = ui.PictureRecorder();
  final canvas = ui.Canvas(recorder);
  final side = px.toDouble();
  canvas.drawRect(
    ui.Rect.fromLTWH(0, 0, side, side),
    ui.Paint()..color = AppColors.ink,
  );
  final mark = side * 170 / 220;
  canvas.translate((side - mark) / 2, (side - mark) / 2);
  const BrandMarkPainter().paint(canvas, ui.Size.square(mark));
  final image = await recorder.endRecording().toImage(px, px);
  final rgba = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
  return _opaquePng(rgba.buffer.asUint8List(), px);
}

/// App Store는 알파 채널이 있는 아이콘을 거절하므로 RGB PNG로 직접 쓴다.
Uint8List _opaquePng(Uint8List rgba, int px) {
  final raw = BytesBuilder();
  for (var y = 0; y < px; y++) {
    raw.addByte(0);
    for (var x = 0; x < px; x++) {
      final i = (y * px + x) * 4;
      raw.add(rgba.sublist(i, i + 3));
    }
  }
  final header = ByteData(13)
    ..setUint32(0, px)
    ..setUint32(4, px)
    ..setUint8(8, 8)
    ..setUint8(9, 2);
  return (BytesBuilder()
        ..add([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        ..add(_chunk('IHDR', header.buffer.asUint8List()))
        ..add(_chunk('IDAT', ZLibCodec(level: 9).encode(raw.takeBytes())))
        ..add(_chunk('IEND', const [])))
      .takeBytes();
}

List<int> _chunk(String type, List<int> data) {
  final body = [...ascii.encode(type), ...data];
  final out = ByteData(8 + data.length + 4);
  out.setUint32(0, data.length);
  final bytes = out.buffer.asUint8List()..setRange(4, 4 + body.length, body);
  out.setUint32(4 + body.length, _crc32(body));
  return bytes;
}

int _crc32(List<int> bytes) {
  var crc = 0xFFFFFFFF;
  for (final b in bytes) {
    crc ^= b;
    for (var k = 0; k < 8; k++) {
      crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1;
    }
  }
  return crc ^ 0xFFFFFFFF;
}
