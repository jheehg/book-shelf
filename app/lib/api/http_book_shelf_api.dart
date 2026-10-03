import 'package:dio/dio.dart';

import 'api_error.dart';
import 'auth_interceptor.dart';
import 'book_shelf_api.dart';
import 'models.dart';
import 'token_store.dart';

class HttpBookShelfApi implements BookShelfApi {
  HttpBookShelfApi._(this._dio);

  final Dio _dio;

  /// 가짜 네트워크를 넣을 때도 일반 요청과 refresh 요청은 같은 adapter를 사용한다.
  factory HttpBookShelfApi.create({
    required String baseUrl,
    required TokenStore store,
    required void Function() onSignedOut,
    HttpClientAdapter? adapter,
  }) {
    final options = BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 15),
      contentType: Headers.jsonContentType,
    );
    final plain = Dio(options);
    final dio = Dio(options);
    if (adapter != null) {
      plain.httpClientAdapter = adapter;
      dio.httpClientAdapter = adapter;
    }
    dio.interceptors.add(
      AuthInterceptor(store: store, plain: plain, onSignedOut: onSignedOut),
    );
    return HttpBookShelfApi._(dio);
  }

  Future<T> _call<T>(
    Future<Response<dynamic>> Function() send,
    T Function(dynamic data) read,
  ) async {
    try {
      return read((await send()).data);
    } on DioException catch (error) {
      throw ApiException.from(error);
    }
  }

  Map<String, dynamic> _map(dynamic data) => data as Map<String, dynamic>;

  List<T> _list<T>(dynamic data, T Function(Map<String, dynamic>) parse) =>
      (data as List)
          .map((item) => parse(item as Map<String, dynamic>))
          .toList();

  @override
  Future<AuthTokens> devLogin(String slug) => _call(
    () => _dio.post('/api/auth/dev', data: {'slug': slug}),
    (data) => AuthTokens.fromJson(_map(data)),
  );

  @override
  Future<void> logout(String refreshToken) => _call(
    () => _dio.post('/api/auth/logout', data: {'refreshToken': refreshToken}),
    (_) {},
  );

  @override
  Future<Me> me() =>
      _call(() => _dio.get('/api/me'), (data) => Me.fromJson(_map(data)));

  @override
  Future<Me> updateMe({
    String? slug,
    String? displayName,
    RecordVisibility? defaultVisibility,
  }) {
    final changes = <String, dynamic>{};
    if (slug != null) changes['slug'] = slug;
    if (displayName != null) changes['displayName'] = displayName;
    if (defaultVisibility != null) {
      changes['defaultVisibility'] = defaultVisibility.wire;
    }
    return _call(
      () => _dio.patch('/api/me', data: changes),
      (data) => Me.fromJson(_map(data)),
    );
  }

  @override
  Future<void> deleteAccount() =>
      _call(() => _dio.delete('/api/me', data: <String, dynamic>{}), (_) {});

  @override
  Future<List<RecordItem>> records() => _call(
    () => _dio.get('/api/records'),
    (data) => _list(data, RecordItem.fromJson),
  );

  @override
  Future<RecordItem> createRecord(String isbn, ReadingStatus status) => _call(
    () =>
        _dio.post('/api/records', data: {'isbn': isbn, 'status': status.wire}),
    (data) => RecordItem.fromJson(_map(data)),
  );

  @override
  Future<RecordItem> updateRecord(int id, RecordUpdate update) => _call(
    () => _dio.put('/api/records/$id', data: update.toJson()),
    (data) => RecordItem.fromJson(_map(data)),
  );

  @override
  Future<void> deleteRecord(int id) =>
      _call(() => _dio.delete('/api/records/$id'), (_) {});

  @override
  Future<BookDetail> book(String isbn) => _call(
    () => _dio.get('/api/books/$isbn'),
    (data) => BookDetail.fromJson(_map(data)),
  );

  @override
  Future<StoreLinks> links(String isbn) => _call(
    () => _dio.get('/api/books/$isbn/links'),
    (data) => StoreLinks.fromJson(_map(data)),
  );

  @override
  Future<List<Holding>> holdings(String isbn) => _call(
    () => _dio.get('/api/books/$isbn/libraries'),
    (data) => _list(data, Holding.fromJson),
  );

  @override
  Future<Ebook> ebook(String isbn) => _call(
    () => _dio.get('/api/books/$isbn/ebook'),
    (data) => Ebook.fromJson(_map(data)),
  );

  @override
  Future<SearchPage> search(String query, int page) => _call(
    () => _dio.get('/api/search', queryParameters: {'q': query, 'page': page}),
    (data) => SearchPage.fromJson(_map(data)),
  );

  @override
  Future<List<Region>> regions() => _call(
    () => _dio.get('/api/libraries/regions'),
    (data) => _list(data, Region.fromJson),
  );

  @override
  Future<List<Library>> libraries(int region, String? query) => _call(
    () => _dio.get(
      '/api/libraries',
      queryParameters: {
        'region': region,
        if (query != null && query.isNotEmpty) 'q': query,
      },
    ),
    (data) => _list(data, Library.fromJson),
  );

  @override
  Future<List<Library>> myLibraries() => _call(
    () => _dio.get('/api/me/libraries'),
    (data) => _list(data, Library.fromJson),
  );

  @override
  Future<List<Library>> setMyLibraries(List<String> libCodes) => _call(
    () => _dio.put('/api/me/libraries', data: {'libCodes': libCodes}),
    (data) => _list(data, Library.fromJson),
  );

  @override
  Future<Shelf> shelf() => _call(
    () => _dio.get('/api/me/shelf'),
    (data) => Shelf.fromJson(_map(data)),
  );

  @override
  Future<Shelf> publishShelf(RecordVisibility mode) => _call(
    () => _dio.post('/api/me/shelf/publish', data: {'mode': mode.wire}),
    (data) => Shelf.fromJson(_map(data)),
  );

  @override
  Future<Shelf> closeShelf() => _call(
    () => _dio.post('/api/me/shelf/close'),
    (data) => Shelf.fromJson(_map(data)),
  );
}
