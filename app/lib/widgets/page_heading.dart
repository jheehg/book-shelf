import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../theme/app_theme.dart';

/// 굵은 제목 아래 흐린 부제, 오른쪽에 큰 숫자를 두는 화면 머리.
class PageHeading extends StatelessWidget {
  const PageHeading({
    super.key,
    required this.title,
    required this.subtitle,
    this.trailing,
  });

  final String title;
  final String subtitle;
  final Widget? trailing;

  static const _style = TextStyle(
    fontSize: 30,
    height: 1.12,
    fontWeight: FontWeight.w900,
    letterSpacing: -0.6,
  );

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(header: true, child: Text(title, style: _style)),
              Text(subtitle, style: _style.copyWith(color: AppColors.muted)),
            ],
          ),
        ),
        ?trailing,
      ],
    ),
  );
}

/// 60px 안팎의 가는 숫자 뒤에 작은 단위를 붙인다.
class BigNumber extends StatelessWidget {
  const BigNumber(
    this.value, {
    super.key,
    required this.unit,
    this.size = 60,
    this.unitStyle,
  });

  final String value;
  final String unit;
  final double size;
  final TextStyle? unitStyle;

  @override
  Widget build(BuildContext context) => Text.rich(
    TextSpan(
      text: value,
      children: [TextSpan(text: unit, style: unitStyle)],
    ),
    style: TextStyle(
      fontSize: size,
      height: 0.95,
      fontWeight: FontWeight.w400,
      letterSpacing: size * -0.03,
      color: AppColors.ink,
    ),
  );
}

/// 앞 화면이 있을 때만 보이는 뒤로 가기 버튼. AppBar.leading에 넣는다.
Widget? backButton(BuildContext context) => context.canPop()
    ? IconButton(
        tooltip: '뒤로',
        onPressed: context.pop,
        icon: const Icon(Icons.chevron_left, size: 32),
      )
    : null;
