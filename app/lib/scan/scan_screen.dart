import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'isbn_barcode.dart';

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
    final isbn = await showDialog<String>(
      context: context,
      builder: (_) => const _TypedIsbnDialog(),
    );
    if (isbn == null || !mounted) return;
    context.pushReplacement('/book/$isbn');
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('바코드 찍기')),
    body: Stack(
      fit: StackFit.expand,
      children: [
        MobileScanner(
          controller: _controller,
          onDetect: _onDetect,
          overlayBuilder: (context, constraints) => Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 280,
                  height: 150,
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.white, width: 2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                const SizedBox(height: 16),
                const _OnCamera('책 뒷면 바코드를 틀 안에 맞춰 주세요'),
              ],
            ),
          ),
          errorBuilder: (context, error) => ColoredBox(
            color: Colors.black,
            child: Center(
              child: _OnCamera(
                error.errorCode == MobileScannerErrorCode.permissionDenied
                    ? '카메라 권한이 없어요. 설정에서 허용하거나 ISBN을 직접 입력해 주세요.'
                    : '카메라를 쓸 수 없어요. ISBN을 직접 입력해 주세요.',
              ),
            ),
          ),
        ),
        Align(
          alignment: Alignment.bottomCenter,
          child: SafeArea(
            minimum: const EdgeInsets.only(bottom: 32),
            child: FilledButton.tonal(
              onPressed: _typeIsbn,
              child: const Text('ISBN 직접 입력'),
            ),
          ),
        ),
      ],
    ),
  );
}

class _OnCamera extends StatelessWidget {
  const _OnCamera(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.symmetric(horizontal: 24),
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
    decoration: BoxDecoration(
      color: Colors.black54,
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      text,
      textAlign: TextAlign.center,
      style: const TextStyle(color: Colors.white),
    ),
  );
}

/// 입력 컨트롤러는 대화상자가 갖는다. showDialog의 await는 닫히는 애니메이션이 끝나기 전에 돌아오므로,
/// 호출한 쪽에서 버리면 닫히는 동안 버려진 컨트롤러를 그린다.
class _TypedIsbnDialog extends StatefulWidget {
  const _TypedIsbnDialog();

  @override
  State<_TypedIsbnDialog> createState() => _TypedIsbnDialogState();
}

class _TypedIsbnDialogState extends State<_TypedIsbnDialog> {
  final _text = TextEditingController();

  String get _isbn => _text.text.replaceAll('-', '').trim();

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('ISBN 직접 입력'),
    content: TextField(
      controller: _text,
      keyboardType: TextInputType.number,
      onChanged: (_) => setState(() {}),
      decoration: const InputDecoration(hintText: '13자리'),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('취소'),
      ),
      TextButton(
        onPressed: isBookIsbn13(_isbn)
            ? () => Navigator.pop(context, _isbn)
            : null,
        child: const Text('열기'),
      ),
    ],
  );
}
