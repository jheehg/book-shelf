import 'package:bookshelf/shelf/review_length.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('앞뒤 공백을 뗀 뒤 서버와 같은 코드포인트 수를 센다', () {
    expect(reviewLength('  좋았다  '), 3);
    expect(reviewLength('📚' * 200), 200);
    expect(reviewLength('👨‍👩‍👧'), 5);
  });

  test('완료일은 시작일보다 앞일 수 없다', () {
    expect(validateReadDates('2026-09-01', '2026-09-20'), isNull);
    expect(validateReadDates('2026-09-20', '2026-09-20'), isNull);
    expect(validateReadDates(null, '2026-09-20'), isNull);
    expect(validateReadDates('2026-09-20', '2026-09-01'), isNotNull);
  });
}
