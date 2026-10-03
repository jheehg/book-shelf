/// 서버 응답 모델. 서버가 나중에 더하는 필드는 무시하고, 없는 선택 필드는 null로 읽는다.
DateTime? _time(Object? value) =>
    value == null ? null : DateTime.parse(value as String).toLocal();

class AuthTokens {
  const AuthTokens({
    required this.accessToken,
    required this.refreshToken,
    required this.newMember,
  });

  final String accessToken;
  final String refreshToken;
  final bool newMember;

  factory AuthTokens.fromJson(Map<String, dynamic> json) => AuthTokens(
    accessToken: json['accessToken'] as String,
    refreshToken: json['refreshToken'] as String,
    newMember: json['newMember'] as bool? ?? false,
  );

  @override
  String toString() => 'AuthTokens(newMember: $newMember)';
}

enum ReadingStatus {
  want('WANT', '읽고 싶다'),
  reading('READING', '읽는 중'),
  done('DONE', '읽음');

  const ReadingStatus(this.wire, this.label);
  final String wire;
  final String label;

  static ReadingStatus fromWire(String value) =>
      values.firstWhere((status) => status.wire == value);
}

/// Flutter의 Visibility 위젯과 구분하기 위해 RecordVisibility로 부른다.
enum RecordVisibility {
  public('PUBLIC', '공개', '서재와 책 페이지에 별점·한줄평이 보여요'),
  ratingOnly('RATING_ONLY', '별점만', '서재에 표지와 별점만 보여요. 한줄평은 나만 봐요'),
  private('PRIVATE', '비공개', '읽었다는 사실도 나만 알아요');

  const RecordVisibility(this.wire, this.label, this.description);
  final String wire;
  final String label;
  final String description;

  static RecordVisibility fromWire(String value) =>
      values.firstWhere((visibility) => visibility.wire == value);
}

class Me {
  const Me({
    required this.slug,
    required this.displayName,
    required this.shelfPublic,
    required this.defaultVisibility,
  });

  final String slug;
  final String displayName;
  final bool shelfPublic;
  final RecordVisibility defaultVisibility;

  factory Me.fromJson(Map<String, dynamic> json) => Me(
    slug: json['slug'] as String,
    displayName: json['displayName'] as String,
    shelfPublic: json['shelfPublic'] as bool,
    defaultVisibility: RecordVisibility.fromWire(
      json['defaultVisibility'] as String,
    ),
  );
}

class Book {
  const Book({
    required this.isbn13,
    required this.title,
    this.author,
    this.publisher,
    this.coverUrl,
  });

  final String isbn13;
  final String title;
  final String? author;
  final String? publisher;
  final String? coverUrl;

  factory Book.fromJson(Map<String, dynamic> json) => Book(
    isbn13: json['isbn13'] as String,
    title: json['title'] as String,
    author: json['author'] as String?,
    publisher: json['publisher'] as String?,
    coverUrl: json['coverUrl'] as String?,
  );
}

enum SaleStatus {
  onSale('ON_SALE'),
  order('ORDER'),
  unknown('UNKNOWN');

  const SaleStatus(this.wire);
  final String wire;

  static SaleStatus? fromWire(String? value) => value == null
      ? null
      : values.firstWhere(
          (status) => status.wire == value,
          orElse: () => unknown,
        );
}

class Sale {
  const Sale({this.status, this.listPrice, this.salePrice, this.fetchedAt});

  final SaleStatus? status;
  final int? listPrice;
  final int? salePrice;
  final DateTime? fetchedAt;

  factory Sale.fromJson(Map<String, dynamic> json) => Sale(
    status: SaleStatus.fromWire(json['status'] as String?),
    listPrice: json['listPrice'] as int?,
    salePrice: json['salePrice'] as int?,
    fetchedAt: _time(json['fetchedAt']),
  );
}

class BookDetail {
  const BookDetail({required this.book, required this.sale});

  final Book book;
  final Sale sale;

  factory BookDetail.fromJson(Map<String, dynamic> json) => BookDetail(
    book: Book.fromJson(json['book'] as Map<String, dynamic>),
    sale: Sale.fromJson(json['sale'] as Map<String, dynamic>),
  );
}

class RecordItem {
  const RecordItem({
    required this.id,
    required this.isbn13,
    required this.status,
    required this.visibility,
    this.rating,
    this.review,
    this.startedAt,
    this.finishedAt,
    this.book,
  });

  final int id;
  final String isbn13;
  final ReadingStatus status;
  final RecordVisibility visibility;
  final int? rating;
  final String? review;
  final String? startedAt;
  final String? finishedAt;
  final Book? book;

  factory RecordItem.fromJson(Map<String, dynamic> json) => RecordItem(
    id: json['id'] as int,
    isbn13: json['isbn13'] as String,
    status: ReadingStatus.fromWire(json['status'] as String),
    visibility: RecordVisibility.fromWire(json['visibility'] as String),
    rating: json['rating'] as int?,
    review: json['review'] as String?,
    startedAt: json['startedAt'] as String?,
    finishedAt: json['finishedAt'] as String?,
    book: json['book'] == null
        ? null
        : Book.fromJson(json['book'] as Map<String, dynamic>),
  );
}

/// PUT은 기록 전체를 바꾼다. null인 별점·한줄평·날짜는 서버에서 지운다.
class RecordUpdate {
  const RecordUpdate({
    required this.status,
    required this.visibility,
    this.rating,
    this.review,
    this.startedAt,
    this.finishedAt,
  });

  final ReadingStatus status;
  final RecordVisibility visibility;
  final int? rating;
  final String? review;
  final String? startedAt;
  final String? finishedAt;

  Map<String, dynamic> toJson() => {
    'status': status.wire,
    'visibility': visibility.wire,
    'rating': rating,
    'review': review,
    'startedAt': startedAt,
    'finishedAt': finishedAt,
  };
}

class StoreLink {
  const StoreLink({required this.name, required this.url});

  final String name;
  final String url;

  factory StoreLink.fromJson(Map<String, dynamic> json) =>
      StoreLink(name: json['name'] as String, url: json['url'] as String);
}

class StoreLinks {
  const StoreLinks({
    required this.newBooks,
    required this.used,
    required this.ebook,
  });

  final List<StoreLink> newBooks;
  final List<StoreLink> used;
  final List<StoreLink> ebook;

  factory StoreLinks.fromJson(Map<String, dynamic> json) {
    List<StoreLink> group(String key) => ((json[key] as List?) ?? const [])
        .map((item) => StoreLink.fromJson(item as Map<String, dynamic>))
        .toList();

    return StoreLinks(
      newBooks: group('NEW'),
      used: group('USED'),
      ebook: group('EBOOK'),
    );
  }
}

enum HoldingState {
  available('AVAILABLE', '대출 가능'),
  onLoan('ON_LOAN', '대출 중'),
  notHeld('NOT_HELD', '소장하지 않음'),
  unknown('UNKNOWN', '확인 불가');

  const HoldingState(this.wire, this.label);
  final String wire;
  final String label;

  static HoldingState fromWire(String value) =>
      values.firstWhere((state) => state.wire == value, orElse: () => unknown);
}

class Holding {
  const Holding({
    required this.libCode,
    required this.name,
    required this.state,
    this.fetchedAt,
  });

  final String libCode;
  final String name;
  final HoldingState state;
  final DateTime? fetchedAt;

  factory Holding.fromJson(Map<String, dynamic> json) => Holding(
    libCode: json['libCode'] as String,
    name: json['name'] as String,
    state: HoldingState.fromWire(json['state'] as String),
    fetchedAt: _time(json['fetchedAt']),
  );
}

class Ebook {
  const Ebook({
    required this.status,
    this.price,
    this.link,
    this.fetchedAt,
    this.attribution,
  });

  final String status;
  final int? price;
  final String? link;
  final DateTime? fetchedAt;
  final String? attribution;

  bool get matched => status == 'MATCHED' && price != null;

  factory Ebook.fromJson(Map<String, dynamic> json) => Ebook(
    status: json['status'] as String,
    price: json['price'] as int?,
    link: json['link'] as String?,
    fetchedAt: _time(json['fetchedAt']),
    attribution: json['attribution'] as String?,
  );
}

class SearchItem {
  const SearchItem({required this.book, required this.sale});

  final Book book;
  final Sale sale;

  factory SearchItem.fromJson(Map<String, dynamic> json) => SearchItem(
    book: Book.fromJson(json['book'] as Map<String, dynamic>),
    sale: Sale.fromJson(json['sale'] as Map<String, dynamic>),
  );
}

class SearchPage {
  const SearchPage({
    required this.items,
    required this.total,
    required this.isEnd,
  });

  final List<SearchItem> items;
  final int total;
  final bool isEnd;

  factory SearchPage.fromJson(Map<String, dynamic> json) => SearchPage(
    items: (json['items'] as List)
        .map((item) => SearchItem.fromJson(item as Map<String, dynamic>))
        .toList(),
    total: json['total'] as int,
    isEnd: json['isEnd'] as bool,
  );
}

class Region {
  const Region({required this.code, required this.label});

  final int code;
  final String label;

  factory Region.fromJson(Map<String, dynamic> json) =>
      Region(code: json['code'] as int, label: json['label'] as String);
}

class Library {
  const Library({required this.libCode, required this.name, this.address});

  final String libCode;
  final String name;
  final String? address;

  factory Library.fromJson(Map<String, dynamic> json) => Library(
    libCode: json['libCode'] as String,
    name: json['name'] as String,
    address: json['address'] as String?,
  );
}

class Shelf {
  const Shelf({
    required this.shelfPublic,
    required this.url,
    required this.doneCount,
    required this.visibleCount,
  });

  final bool shelfPublic;
  final String url;
  final int doneCount;
  final int visibleCount;

  factory Shelf.fromJson(Map<String, dynamic> json) => Shelf(
    shelfPublic: json['shelfPublic'] as bool,
    url: json['url'] as String,
    doneCount: json['doneCount'] as int,
    visibleCount: json['visibleCount'] as int,
  );
}
