import 'package:dio/dio.dart';

/// 서버의 {"code": "...", "message": "..."} 오류를 앱에서 읽는 형태로 옮긴다.
class ApiException implements Exception {
  const ApiException({
    this.statusCode,
    required this.code,
    required this.message,
  });

  final int? statusCode;
  final String code;
  final String message;

  bool get notFound => code == 'NOT_FOUND';

  factory ApiException.from(DioException exception) {
    final response = exception.response;
    final data = response?.data;
    if (data is Map && data['code'] is String) {
      return ApiException(
        statusCode: response?.statusCode,
        code: data['code'] as String,
        message: data['message'] is String ? data['message'] as String : '',
      );
    }
    if (response == null) {
      return const ApiException(code: 'NETWORK', message: '서버에 연결할 수 없어요');
    }
    return ApiException(
      statusCode: response.statusCode,
      code: 'HTTP_${response.statusCode}',
      message: '잠시 후 다시 시도해 주세요',
    );
  }

  /// 원래 요청이나 응답 본문에는 토큰이 있을 수 있어 문자열 표현에 담지 않는다.
  @override
  String toString() => 'ApiException($statusCode $code)';
}
