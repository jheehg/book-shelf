/// 서버 한줄평 제한(ReadingRecord.REVIEW_MAX).
const int reviewMax = 200;

/// 서버처럼 앞뒤 공백을 뗀 뒤 UTF-16 단위가 아닌 코드포인트 수를 센다.
int reviewLength(String text) => text.trim().runes.length;

/// yyyy-MM-dd 문자열은 사전순 비교로 날짜 순서를 확인할 수 있다.
String? validateReadDates(String? startedAt, String? finishedAt) {
  if (startedAt != null &&
      finishedAt != null &&
      finishedAt.compareTo(startedAt) < 0) {
    return '다 읽은 날이 시작한 날보다 앞이에요';
  }
  return null;
}
