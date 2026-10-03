final _isbn13 = RegExp(r'^97[89]\d{10}$');

/// 책 바코드(EAN-13 중 978·979, 체크섬 일치)인지 확인한다.
bool isBookIsbn13(String? raw) {
  if (raw == null || !_isbn13.hasMatch(raw)) return false;

  var sum = 0;
  for (var i = 0; i < 12; i++) {
    sum += int.parse(raw[i]) * (i.isEven ? 1 : 3);
  }
  return (10 - sum % 10) % 10 == int.parse(raw[12]);
}

sealed class ScanDecision {
  const ScanDecision();
}

class OpenBook extends ScanDecision {
  const OpenBook(this.isbn);
  final String isbn;
}

class NotABook extends ScanDecision {
  const NotABook(this.raw);
  final String raw;
}

class IgnoreScan extends ScanDecision {
  const IgnoreScan();
}

/// 카메라가 같은 바코드를 반복해서 읽어도 안내와 화면 이동은 한 번만 한다.
class ScanGate {
  bool _opened = false;
  String? _lastRejected;

  ScanDecision decide(String? raw) {
    if (_opened || raw == null) return const IgnoreScan();
    if (isBookIsbn13(raw)) {
      _opened = true;
      return OpenBook(raw);
    }
    if (raw == _lastRejected) return const IgnoreScan();
    _lastRejected = raw;
    return NotABook(raw);
  }
}
