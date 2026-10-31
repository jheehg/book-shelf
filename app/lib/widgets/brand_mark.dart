import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// 앱 아이콘과 로그인 화면이 함께 쓰는 표식: 스캔 틀 네 모서리 속의 책.
class BrandMark extends StatelessWidget {
  const BrandMark({
    super.key,
    required this.size,
    this.cornerWidth = 6,
    this.scanLine = false,
  });

  final double size;

  /// 100 단위 viewBox 기준의 모서리 선 두께.
  final double cornerWidth;
  final bool scanLine;

  @override
  Widget build(BuildContext context) => CustomPaint(
    size: Size.square(size),
    painter: BrandMarkPainter(cornerWidth: cornerWidth, scanLine: scanLine),
  );
}

class BrandMarkPainter extends CustomPainter {
  const BrandMarkPainter({this.cornerWidth = 6, this.scanLine = false});

  final double cornerWidth;
  final bool scanLine;

  static const _corner = Radius.circular(4);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 100, size.height / 100);

    final corners = Path()
      ..moveTo(14, 30)
      ..lineTo(14, 18)
      ..arcToPoint(const Offset(18, 14), radius: _corner)
      ..lineTo(30, 14)
      ..moveTo(70, 14)
      ..lineTo(82, 14)
      ..arcToPoint(const Offset(86, 18), radius: _corner)
      ..lineTo(86, 30)
      ..moveTo(86, 70)
      ..lineTo(86, 82)
      ..arcToPoint(const Offset(82, 86), radius: _corner)
      ..lineTo(70, 86)
      ..moveTo(30, 86)
      ..lineTo(18, 86)
      ..arcToPoint(const Offset(14, 82), radius: _corner)
      ..lineTo(14, 70);
    canvas.drawPath(
      corners,
      Paint()
        ..color = AppColors.ground
        ..style = PaintingStyle.stroke
        ..strokeWidth = cornerWidth
        ..strokeCap = StrokeCap.round,
    );

    canvas.drawRRect(
      RRect.fromLTRBR(31, 26, 38, 76, const Radius.circular(3)),
      Paint()..color = AppColors.lavender,
    );
    final cover = Path()
      ..moveTo(40, 26)
      ..lineTo(60, 26)
      ..cubicTo(64.4, 26, 68, 29.6, 68, 34)
      ..lineTo(68, 68)
      ..cubicTo(68, 72.4, 64.4, 76, 60, 76)
      ..lineTo(40, 76)
      ..close();
    canvas.drawPath(cover, Paint()..color = AppColors.tomato);

    if (scanLine) {
      canvas.drawRRect(
        RRect.fromLTRBR(22, 50, 78, 51.4, const Radius.circular(0.7)),
        Paint()..color = AppColors.ground.withValues(alpha: 0.9),
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(BrandMarkPainter oldDelegate) =>
      oldDelegate.cornerWidth != cornerWidth ||
      oldDelegate.scanLine != scanLine;
}
