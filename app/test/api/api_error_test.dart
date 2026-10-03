import 'package:bookshelf/api/api_error.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final request = RequestOptions(path: '/api/books/9788936434120');

  test('서버 오류 코드와 메시지를 보존한다', () {
    final response = Response<Map<String, dynamic>>(
      requestOptions: request,
      statusCode: 404,
      data: {'code': 'NOT_FOUND', 'message': '찾을 수 없어요'},
    );
    final error = ApiException.from(
      DioException(requestOptions: request, response: response),
    );

    expect(error.notFound, isTrue);
    expect(error.statusCode, 404);
    expect(error.message, '찾을 수 없어요');
  });

  test('응답이 없으면 연결 오류로 안내한다', () {
    final error = ApiException.from(DioException(requestOptions: request));

    expect(error.code, 'NETWORK');
    expect(error.message, '서버에 연결할 수 없어요');
  });

  test('서버 형식이 다르면 안전한 문구를 사용한다', () {
    final error = ApiException.from(
      DioException(
        requestOptions: request,
        response: Response<String>(
          requestOptions: request,
          statusCode: 502,
          data: '내부 요청 정보',
        ),
      ),
    );

    expect(error.code, 'HTTP_502');
    expect(error.message, '잠시 후 다시 시도해 주세요');
    expect(error.toString(), isNot(contains('내부 요청 정보')));
  });
}
