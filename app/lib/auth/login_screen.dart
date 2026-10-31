import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_error.dart';
import '../theme/app_theme.dart';
import '../widgets/brand_mark.dart';
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
      setState(() => _error = slugRule);
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
  Widget build(BuildContext context) => AnnotatedRegion(
    value: SystemUiOverlayStyle.light,
    child: Scaffold(
      body: LayoutBuilder(
        builder: (context, viewport) => SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: viewport.maxHeight),
            child: IntrinsicHeight(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [_hero(), _title(), const Spacer(), _form()],
              ),
            ),
          ),
        ),
      ),
    ),
  );

  Widget _hero() => Container(
    height: 440,
    padding: const EdgeInsets.only(top: 30),
    alignment: Alignment.center,
    decoration: const BoxDecoration(
      color: AppColors.ink,
      borderRadius: BorderRadius.vertical(bottom: Radius.circular(40)),
    ),
    child: const BrandMark(size: 300, cornerWidth: 4, scanLine: true),
  );

  Widget _title() => const Padding(
    padding: EdgeInsets.fromLTRB(24, 28, 24, 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 8,
      children: [
        Text(
          '책장',
          style: TextStyle(
            fontSize: 48,
            height: 1,
            fontWeight: FontWeight.w900,
            letterSpacing: -1.9,
          ),
        ),
        Text(
          '바코드로 3초에 기록하는 서재',
          style: TextStyle(
            fontSize: 20,
            height: 1.3,
            fontWeight: FontWeight.w900,
            letterSpacing: -0.4,
            color: AppColors.muted,
          ),
        ),
      ],
    ),
  );

  Widget _form() => Padding(
    padding: const EdgeInsets.fromLTRB(24, 0, 24, 36),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          '서재 주소(slug)',
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _slug,
          autocorrect: false,
          textInputAction: TextInputAction.go,
          onSubmitted: (_) => _login(),
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          decoration: InputDecoration(
            hintText: 'dev-me',
            helperText: slugRule,
            errorText: _error,
            helperStyle: const TextStyle(
              fontSize: 12,
              color: AppColors.bodyMuted,
            ),
            errorStyle: const TextStyle(fontSize: 12),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 20,
              vertical: 17,
            ),
            border: const OutlineInputBorder(
              borderRadius: BorderRadius.all(Radius.circular(28)),
              borderSide: BorderSide.none,
            ),
          ),
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _login,
          child: const Text('개발 로그인'),
        ),
        const SizedBox(height: 10),
        const Text(
          '로컬 개발용 로그인이에요. 처음 쓰는 이름이면 새로 만들어요.',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 12,
            height: 1.5,
            color: AppColors.bodyMuted,
          ),
        ),
      ],
    ),
  );
}
