import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/book/labels.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final at = DateTime(2026, 10, 1, 12);

  test('판매 상태를 확인된 범위에서만 표현한다', () {
    expect(
      saleStatusLabel(Sale(status: SaleStatus.onSale, fetchedAt: at)),
      '판매 중',
    );
    expect(
      saleStatusLabel(Sale(status: SaleStatus.order, fetchedAt: at)),
      '주문 후 발송',
    );
    expect(
      saleStatusLabel(Sale(status: SaleStatus.unknown, fetchedAt: at)),
      '판매 정보 없음',
    );
    expect(saleStatusLabel(const Sale()), '가격 확인 불가');
  });

  test('가격이 없거나 양수가 아니면 숫자를 표시하지 않는다', () {
    expect(priceLabel(13500), '13,500원');
    expect(priceLabel(1000000), '1,000,000원');
    expect(priceLabel(900), '900원');
    expect(priceLabel(null), isNull);
    expect(priceLabel(0), isNull);
    expect(priceLabel(-1), isNull);
  });

  test('기준 시각은 대략으로 안내한다', () {
    final now = DateTime(2026, 10, 2, 12);

    expect(
      basisLabel(now.subtract(const Duration(minutes: 30)), now),
      '1시간 이내 기준',
    );
    expect(
      basisLabel(now.subtract(const Duration(hours: 3, minutes: 50)), now),
      '3시간 전 기준',
    );
    expect(basisLabel(now.subtract(const Duration(hours: 50)), now), '2일 전 기준');
  });
}
