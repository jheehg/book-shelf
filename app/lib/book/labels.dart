import '../api/models.dart';

/// 판매 정보가 비어 있으면 절판으로 단정하지 않는다.
String saleStatusLabel(Sale sale) {
  if (sale.fetchedAt == null) return '가격 확인 불가';
  return switch (sale.status) {
    SaleStatus.onSale => '판매 중',
    SaleStatus.order => '주문 후 발송',
    SaleStatus.unknown || null => '판매 정보 없음',
  };
}

/// 가격이 없거나 양수가 아니면 숫자를 표시하지 않는다.
String? priceLabel(int? won) {
  final digits = priceDigits(won);
  return digits == null ? null : '$digits원';
}

String? priceDigits(int? won) {
  if (won == null || won <= 0) return null;
  final digits = won.toString();
  final out = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) out.write(',');
    out.write(digits[i]);
  }
  return out.toString();
}

/// 상태가 있는 칸의 기준 시각을 정확한 척하지 않고 대략으로 안내한다.
String basisLabel(DateTime fetchedAt, DateTime now) {
  final age = now.difference(fetchedAt);
  if (age.inHours < 1) return '1시간 이내 기준';
  if (age.inHours < 24) return '${age.inHours}시간 전 기준';
  return '${age.inDays}일 전 기준';
}
