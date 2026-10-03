/// 시뮬레이터는 맥의 localhost를, 실기기는 --dart-define으로 지정한 맥의 LAN 주소를 사용한다.
const String apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);
