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
    if (isBookIsbn13(isbn)) {
      context.pushReplacement('/book/$isbn');
    } else {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('13자리 ISBN이 아니에요')));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('바코드 찍기'),
      actions: [TextButton(onPressed: _typeIsbn, child: const Text('직접 입력'))],
    ),
    body: MobileScanner(controller: _controller, onDetect: _onDetect),
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
      decoration: const InputDecoration(hintText: '13자리'),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('취소'),
      ),
      TextButton(
        onPressed: () =>
            Navigator.pop(context, _text.text.replaceAll('-', '').trim()),
        child: const Text('열기'),
      ),
    ],
  );
}
