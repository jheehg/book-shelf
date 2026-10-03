import 'package:bookshelf/scan/isbn_barcode.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('책 ISBN-13은 978·979 접두사와 체크섬이 모두 맞아야 한다', () {
    expect(isBookIsbn13('9788936434120'), isTrue);
    expect(isBookIsbn13('9791100000007'), isTrue);
    expect(isBookIsbn13('9788936434121'), isFalse);
    expect(isBookIsbn13('8801234567893'), isFalse);
    expect(isBookIsbn13('978893643412'), isFalse);
    expect(isBookIsbn13(null), isFalse);
  });

  test('같은 책 바코드를 여러 번 읽어도 상세는 한 번만 연다', () {
    final gate = ScanGate();
    final decisions = [
      for (var i = 0; i < 10; i++) gate.decide('9788936434120'),
    ];

    expect(decisions.whereType<OpenBook>(), hasLength(1));
    expect(decisions.skip(1), everyElement(isA<IgnoreScan>()));
  });

  test('책이 아닌 바코드는 같은 것에 한 번만 알리고 다른 책은 처리한다', () {
    final gate = ScanGate();

    expect(gate.decide('8801234567893'), isA<NotABook>());
    expect(gate.decide('8801234567893'), isA<IgnoreScan>());
    expect(gate.decide('8809999999993'), isA<NotABook>());
    expect(gate.decide('9788936434120'), isA<OpenBook>());
  });

  test('상세를 연 뒤에는 이후 바코드를 모두 무시한다', () {
    final gate = ScanGate()..decide('9788936434120');

    expect(gate.decide('9791100000007'), isA<IgnoreScan>());
    expect(gate.decide('8801234567893'), isA<IgnoreScan>());
  });
}
