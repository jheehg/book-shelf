import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/book_shelf_api.dart';
import 'package:bookshelf/api/models.dart';

/// 화면 테스트용 가짜 서버. calls로 호출을 확인하고 필드로 응답을 바꾼다.
class FakeApi implements BookShelfApi {
  final List<String> calls = [];
  String? loggedInSlug;
  Map<String, List<RecordItem>> recordsBySlug = {};
  Future<List<RecordItem>> Function()? onRecords;
  Future<void> Function()? onLogout;
  Future<BookDetail> Function(String isbn)? onBook;
  Future<StoreLinks> Function(String isbn)? onLinks;
  Future<List<Holding>> Function(String isbn)? onHoldings;
  Future<Ebook> Function(String isbn)? onEbook;
  Future<SearchPage> Function(String query, int page)? onSearch;
  Future<RecordItem> Function(String isbn, ReadingStatus status)?
  onCreateRecord;
  Future<RecordItem> Function(int id, RecordUpdate update)? onUpdateRecord;
  Future<List<Library>> Function(List<String> codes)? onSetMyLibraries;
  Future<List<Library>> Function(int region, String? query)? onLibraries;
  Me Function()? onMe;
  Me Function({
    String? slug,
    String? displayName,
    RecordVisibility? defaultVisibility,
  })?
  onUpdateMe;
  Future<void> Function()? onDeleteAccount;
  List<Library> myLibraryList = [];
  List<Region> regionList = const [
    Region(code: 11, label: '서울'),
    Region(code: 21, label: '부산'),
  ];
  Shelf shelfState = const Shelf(
    shelfPublic: false,
    url: 'http://localhost:8080/u/mine',
    doneCount: 2,
    visibleCount: 0,
  );

  List<RecordItem> get currentRecords =>
      recordsBySlug[loggedInSlug ?? 'mine'] ?? const [];

  @override
  Future<AuthTokens> devLogin(String slug) async {
    calls.add('devLogin:$slug');
    loggedInSlug = slug;
    return AuthTokens(
      accessToken: 'a-$slug',
      refreshToken: 'r-$slug',
      newMember: false,
    );
  }

  @override
  Future<void> logout(String refreshToken) async {
    calls.add('logout');
    await onLogout?.call();
  }

  @override
  Future<Me> me() async {
    calls.add('me');
    return onMe?.call() ??
        Me(
          slug: loggedInSlug ?? 'mine',
          displayName: '나',
          shelfPublic: shelfState.shelfPublic,
          defaultVisibility: RecordVisibility.private,
        );
  }

  @override
  Future<Me> updateMe({
    String? slug,
    String? displayName,
    RecordVisibility? defaultVisibility,
  }) async {
    calls.add('updateMe');
    return onUpdateMe?.call(
          slug: slug,
          displayName: displayName,
          defaultVisibility: defaultVisibility,
        ) ??
        Me(
          slug: slug ?? loggedInSlug ?? 'mine',
          displayName: displayName ?? '나',
          shelfPublic: false,
          defaultVisibility: defaultVisibility ?? RecordVisibility.private,
        );
  }

  @override
  Future<void> deleteAccount() async {
    calls.add('deleteAccount');
    await onDeleteAccount?.call();
  }

  @override
  Future<List<RecordItem>> records() async {
    calls.add('records');
    return onRecords?.call() ?? currentRecords;
  }

  @override
  Future<RecordItem> createRecord(String isbn, ReadingStatus status) async {
    calls.add('createRecord:$isbn:${status.wire}');
    return onCreateRecord?.call(isbn, status) ??
        RecordItem(
          id: 99,
          isbn13: isbn,
          status: status,
          visibility: RecordVisibility.private,
        );
  }

  @override
  Future<RecordItem> updateRecord(int id, RecordUpdate update) async {
    calls.add('updateRecord:$id');
    return onUpdateRecord?.call(id, update) ??
        RecordItem(
          id: id,
          isbn13: '9788936434120',
          status: update.status,
          visibility: update.visibility,
          rating: update.rating,
          review: update.review,
        );
  }

  @override
  Future<void> deleteRecord(int id) async => calls.add('deleteRecord:$id');

  @override
  Future<BookDetail> book(String isbn) {
    calls.add('book:$isbn');
    return onBook?.call(isbn) ??
        Future.error(
          const ApiException(
            statusCode: 404,
            code: 'NOT_FOUND',
            message: '찾을 수 없어요',
          ),
        );
  }

  @override
  Future<StoreLinks> links(String isbn) {
    calls.add('links:$isbn');
    return onLinks?.call(isbn) ??
        Future.value(const StoreLinks(newBooks: [], used: [], ebook: []));
  }

  @override
  Future<List<Holding>> holdings(String isbn) {
    calls.add('holdings:$isbn');
    return onHoldings?.call(isbn) ?? Future.value(const []);
  }

  @override
  Future<Ebook> ebook(String isbn) {
    calls.add('ebook:$isbn');
    return onEbook?.call(isbn) ?? Future.value(const Ebook(status: 'NO_MATCH'));
  }

  @override
  Future<SearchPage> search(String query, int page) {
    calls.add('search:$query:$page');
    return onSearch?.call(query, page) ??
        Future.value(const SearchPage(items: [], total: 0, isEnd: true));
  }

  @override
  Future<List<Region>> regions() async {
    calls.add('regions');
    return regionList;
  }

  @override
  Future<List<Library>> libraries(int region, String? query) {
    calls.add('libraries:$region:${query ?? ''}');
    return onLibraries?.call(region, query) ?? Future.value(const []);
  }

  @override
  Future<List<Library>> myLibraries() async {
    calls.add('myLibraries');
    return myLibraryList;
  }

  @override
  Future<List<Library>> setMyLibraries(List<String> libCodes) async {
    calls.add('setMyLibraries:${libCodes.join(',')}');
    if (onSetMyLibraries != null) return onSetMyLibraries!(libCodes);
    return myLibraryList = [
      for (final code in libCodes) Library(libCode: code, name: '$code 도서관'),
    ];
  }

  @override
  Future<Shelf> shelf() async {
    calls.add('shelf');
    return shelfState;
  }

  @override
  Future<Shelf> publishShelf(RecordVisibility mode) async {
    calls.add('publishShelf:${mode.wire}');
    return shelfState = Shelf(
      shelfPublic: true,
      url: shelfState.url,
      doneCount: shelfState.doneCount,
      visibleCount: shelfState.doneCount,
    );
  }

  @override
  Future<Shelf> closeShelf() async {
    calls.add('closeShelf');
    return shelfState = Shelf(
      shelfPublic: false,
      url: shelfState.url,
      doneCount: shelfState.doneCount,
      visibleCount: shelfState.visibleCount,
    );
  }
}

RecordItem record(
  int id,
  String isbn,
  ReadingStatus status, {
  String? title,
  int? rating,
  String? review,
}) => RecordItem(
  id: id,
  isbn13: isbn,
  status: status,
  visibility: RecordVisibility.private,
  rating: rating,
  review: review,
  book: title == null ? null : Book(isbn13: isbn, title: title, author: '저자'),
);
