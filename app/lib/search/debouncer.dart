import 'dart:async';

const Duration searchDebounce = Duration(milliseconds: 400);

class Debouncer {
  Debouncer([this.delay = searchDebounce]);

  final Duration delay;
  Timer? _timer;

  void call(void Function() action) {
    _timer?.cancel();
    _timer = Timer(delay, action);
  }

  void dispose() => _timer?.cancel();
}
