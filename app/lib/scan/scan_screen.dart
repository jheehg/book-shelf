import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../theme/app_theme.dart';
import 'isbn_barcode.dart';

const _dark = Color(0xFF1B1B1A);
const _onDarkMuted = Color(0xFFA3A39E);

class ScanScreen extends StatefulWidget {
  const ScanScreen({super.key});

  @override
  State<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends State<ScanScreen> {
  final _controller = MobileScannerController(
    formats: const [BarcodeFormat.ean13],
  );
  final _gate = ScanGate();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    for (final barcode in capture.barcodes) {
      switch (_gate.decide(barcode.rawValue)) {
        case OpenBook(:final isbn):
          _controller.stop();
          context.pushReplacement('/book/$isbn');
          return;
        case NotABook():
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('책 바코드가 아니에요. 978이나 979로 시작하는 바코드를 찍어 주세요'),
            ),
          );
        case IgnoreScan():
          break;
      }
    }
  }

  Future<void> _typeIsbn() async {
    final isbn = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (_) => const _TypedIsbnSheet(),
    );
    if (isbn == null || !mounted) return;
    context.pushReplacement('/book/$isbn');
  }

  @override
  Widget build(BuildContext context) => AnnotatedRegion(
    value: SystemUiOverlayStyle.light,
    child: Scaffold(
      backgroundColor: _dark,
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: _onDetect,
            placeholderBuilder: (_) => const ColoredBox(color: _dark),
            overlayBuilder: (context, constraints) => const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                spacing: 22,
                children: [
                  _AimingFrame(),
                  _OnCamera('978이나 979로 시작하는 바코드를 틀 안에 맞춰 주세요'),
                ],
              ),
            ),
            errorBuilder: (context, error) => ColoredBox(
              color: _dark,
              child: Center(
                child: _OnCamera(
                  error.errorCode == MobileScannerErrorCode.permissionDenied
                      ? '카메라 권한이 없어요. 설정에서 허용하거나 ISBN을 직접 입력해 주세요.'
                      : '카메라를 쓸 수 없어요. ISBN을 직접 입력해 주세요.',
                ),
              ),
            ),
          ),
          SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(left: 4, top: 8),
                  child: IconButton(
                    tooltip: '닫기',
                    color: AppColors.surface,
                    icon: const Icon(Icons.close, size: 26),
                    onPressed: () => Navigator.maybePop(context),
                  ),
                ),
                const Padding(
                  padding: EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: Text.rich(
                    TextSpan(
                      text: '바코드 찍기\n',
                      children: [
                        TextSpan(
                          text: '책 뒷면을 비춰 주세요',
                          style: TextStyle(color: _onDarkMuted),
                        ),
                      ],
                    ),
                    style: TextStyle(
                      color: AppColors.surface,
                      fontSize: 30,
                      height: 1.12,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -0.6,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Align(
            alignment: Alignment.bottomCenter,
            child: SafeArea(
              minimum: const EdgeInsets.only(bottom: 40),
              child: FilledButton.icon(
                onPressed: _typeIsbn,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.surface,
                  foregroundColor: AppColors.ink,
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                ),
                icon: const Icon(Icons.keyboard_outlined, size: 22),
                label: const Text('ISBN 직접 입력'),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

class _AimingFrame extends StatelessWidget {
  const _AimingFrame();

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 290,
    height: 160,
    child: CustomPaint(
      painter: _CornerPainter(),
      child: Center(
        child: Container(
          height: 2,
          margin: const EdgeInsets.symmetric(horizontal: 20),
          color: AppColors.tomato,
        ),
      ),
    ),
  );
}

class _CornerPainter extends CustomPainter {
  static const _arm = 36.0;
  static const _radius = 14.0;
  static const _stroke = 4.0;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = AppColors.surface
      ..style = PaintingStyle.stroke
      ..strokeWidth = _stroke;
    // 테두리 안쪽에 선이 걸치도록 굵기의 절반만큼 들여 그린다.
    const inset = _stroke / 2;
    final corner = Path()
      ..moveTo(inset, _arm)
      ..lineTo(inset, _radius)
      ..arcToPoint(
        const Offset(_radius, inset),
        radius: const Radius.circular(_radius - inset),
      )
      ..lineTo(_arm, inset);
    for (final (dx, dy) in [
      (1.0, 1.0),
      (-1.0, 1.0),
      (1.0, -1.0),
      (-1.0, -1.0),
    ]) {
      canvas
        ..save()
        ..translate(dx < 0 ? size.width : 0, dy < 0 ? size.height : 0)
        ..scale(dx, dy)
        ..drawPath(corner, paint)
        ..restore();
    }
  }

  @override
  bool shouldRepaint(_CornerPainter oldDelegate) => false;
}

class _OnCamera extends StatelessWidget {
  const _OnCamera(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.symmetric(horizontal: 24),
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
    decoration: BoxDecoration(
      color: AppColors.surface.withValues(alpha: 0.14),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Text(
      text,
      textAlign: TextAlign.center,
      style: const TextStyle(
        color: AppColors.surface,
        fontSize: 13,
        fontWeight: FontWeight.w500,
      ),
    ),
  );
}

/// 입력 컨트롤러는 시트가 갖는다. showModalBottomSheet의 await는 닫히는 애니메이션이 끝나기 전에 돌아오므로,
/// 호출한 쪽에서 버리면 닫히는 동안 버려진 컨트롤러를 그린다.
class _TypedIsbnSheet extends StatefulWidget {
  const _TypedIsbnSheet();

  @override
  State<_TypedIsbnSheet> createState() => _TypedIsbnSheetState();
}

class _TypedIsbnSheetState extends State<_TypedIsbnSheet> {
  final _text = TextEditingController();

  String get _isbn => _text.text.replaceAll('-', '').trim();

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final valid = isBookIsbn13(_isbn);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        minimum: const EdgeInsets.fromLTRB(20, 0, 20, 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 18,
          children: [
            const Text.rich(
              TextSpan(
                text: 'ISBN 직접 입력\n',
                children: [
                  TextSpan(
                    text: '바코드 아래 숫자 13자리',
                    style: TextStyle(color: AppColors.muted),
                  ),
                ],
              ),
              style: TextStyle(
                fontSize: 26,
                height: 1.15,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.52,
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: 8,
              children: [
                TextField(
                  controller: _text,
                  autofocus: true,
                  keyboardType: TextInputType.number,
                  onChanged: (_) => setState(() {}),
                  style: const TextStyle(
                    fontSize: 30,
                    fontWeight: FontWeight.w400,
                    letterSpacing: 1.2,
                  ),
                  decoration: const InputDecoration(
                    hintText: 'ISBN',
                    hintStyle: TextStyle(color: AppColors.unknown),
                    contentPadding: EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 14,
                    ),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.all(Radius.circular(22)),
                      borderSide: BorderSide.none,
                    ),
                  ),
                ),
                SizedBox(height: 18, child: valid ? const _Valid() : null),
              ],
            ),
            Row(
              spacing: 8,
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(56),
                      textStyle: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    child: const Text('취소'),
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: FilledButton(
                    onPressed: valid
                        ? () => Navigator.pop(context, _isbn)
                        : null,
                    child: const Text('열기'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Valid extends StatelessWidget {
  const _Valid();

  @override
  Widget build(BuildContext context) => Row(
    spacing: 6,
    children: [
      Container(
        width: 18,
        height: 18,
        decoration: const BoxDecoration(
          color: AppColors.lavender,
          shape: BoxShape.circle,
        ),
        child: const Icon(Icons.check, size: 13, color: AppColors.ink),
      ),
      const Text(
        '13자리 · 책 ISBN이 맞아요',
        style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
      ),
    ],
  );
}
