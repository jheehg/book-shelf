import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_error.dart';
import 'auth_controller.dart';

/// 로컬 개발 서버에서만 열리는 임시 로그인 화면이다.
class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _slug = TextEditingController();
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _slug.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (_busy) return;
    final slug = _slug.text.trim();
    if (!slugPattern.hasMatch(slug)) {
      setState(() => _error = '소문자·숫자·하이픈 3~30자로 적어 주세요');
      return;
    }
    setState(() {
      _error = null;
      _busy = true;
    });
    try {
      await ref.read(authProvider.notifier).devLogin(slug);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = '로그인하지 못했어요 (${e.message})');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '책장',
              style: Theme.of(context).textTheme.headlineMedium,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            const Text(
              '로컬 개발용 로그인이에요. 처음 쓰는 이름이면 새로 만들어요.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            TextField(
              controller: _slug,
              autocorrect: false,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _login(),
              decoration: InputDecoration(
                labelText: '서재 주소(slug)',
                hintText: 'dev-me',
                errorText: _error,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _busy ? null : _login,
              child: const Text('개발 로그인'),
            ),
          ],
        ),
      ),
    ),
  );
}
