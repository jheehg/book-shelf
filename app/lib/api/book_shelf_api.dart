import 'models.dart';

/// 화면이 사용할 서버 API. 모든 HTTP 실패는 ApiException으로 전달한다.
abstract interface class BookShelfApi {
  Future<AuthTokens> devLogin(String slug);
  Future<void> logout(String refreshToken);

  Future<Me> me();
  Future<Me> updateMe({
    String? slug,
    String? displayName,
    RecordVisibility? defaultVisibility,
  });
  Future<void> deleteAccount();

  Future<List<RecordItem>> records();
  Future<RecordItem> createRecord(String isbn, ReadingStatus status);
  Future<RecordItem> updateRecord(int id, RecordUpdate update);
  Future<void> deleteRecord(int id);

  Future<BookDetail> book(String isbn);
  Future<StoreLinks> links(String isbn);
  Future<List<Holding>> holdings(String isbn);
  Future<Ebook> ebook(String isbn);
  Future<SearchPage> search(String query, int page);

  Future<List<Region>> regions();
  Future<List<Library>> libraries(int region, String? query);
  Future<List<Library>> myLibraries();
  Future<List<Library>> setMyLibraries(List<String> libCodes);

  Future<Shelf> shelf();
  Future<Shelf> publishShelf(RecordVisibility mode);
  Future<Shelf> closeShelf();
}
