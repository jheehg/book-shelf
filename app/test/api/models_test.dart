import 'package:bookshelf/api/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('책 정보가 아직 없는 기록은 book이 null이다', () {
    final record = RecordItem.fromJson({
      'id': 3,
      'isbn13': '9791100000007',
      'status': 'WANT',
      'visibility': 'PRIVATE',
      'book': null,
    });

    expect(record.book, isNull);
    expect(record.status, ReadingStatus.want);
    expect(record.visibility, RecordVisibility.private);
  });

  test('판매 상태를 확인하지 못했으면 상태와 기준 시각이 null이다', () {
    final sale = Sale.fromJson({
      'status': null,
      'listPrice': null,
      'salePrice': null,
      'fetchedAt': null,
    });

    expect(sale.status, isNull);
    expect(sale.fetchedAt, isNull);
    expect(SaleStatus.fromWire('SOMETHING_NEW'), SaleStatus.unknown);
  });

  test('서점 링크는 칸별로 나뉘고 없는 칸은 빈 목록이다', () {
    final links = StoreLinks.fromJson({
      'NEW': [
        {'name': '교보문고', 'url': 'https://example.com/1'},
      ],
      'EBOOK': [],
    });

    expect(links.newBooks.single.name, '교보문고');
    expect(links.used, isEmpty);
    expect(links.ebook, isEmpty);
  });

  test('서버가 추가한 필드는 무시한다', () {
    final book = Book.fromJson({
      'isbn13': '9788936434120',
      'title': '소년이 온다',
      'newField': 1,
    });

    expect(book.title, '소년이 온다');
  });

  test('시각은 UTC 입력을 읽고 기기 시간대로 변환한다', () {
    final holding = Holding.fromJson({
      'libCode': 'L1',
      'name': '도서관',
      'state': 'AVAILABLE',
      'fetchedAt': '2026-10-01T03:00:00Z',
    });

    expect(holding.fetchedAt!.toUtc(), DateTime.utc(2026, 10, 1, 3));
    expect(holding.state, HoldingState.available);
  });

  test('토큰은 문자열 표현에 포함되지 않는다', () {
    const tokens = AuthTokens(
      accessToken: 'secret-access',
      refreshToken: 'secret-refresh',
      newMember: true,
    );

    expect(tokens.toString(), isNot(contains('secret')));
  });

  test('기록 수정은 비운 값을 null로 보내 서버에서 지울 수 있다', () {
    const update = RecordUpdate(
      status: ReadingStatus.done,
      visibility: RecordVisibility.private,
    );

    expect(update.toJson(), containsPair('review', null));
    expect(update.toJson(), containsPair('rating', null));
    expect(update.toJson(), containsPair('status', 'DONE'));
  });
}
