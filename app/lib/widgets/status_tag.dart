import 'package:flutter/material.dart';

import '../api/models.dart';
import '../theme/reading_status_style.dart';

/// 상태 이름 옆의 화살표 각도로 읽고 싶음, 읽는 중, 다 읽음을 구분한다.
class StatusTag extends StatelessWidget {
  const StatusTag(this.status, {super.key});

  final ReadingStatus status;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    spacing: 6,
    children: [
      Text(
        status.label,
        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
      ),
      Transform.rotate(
        angle: status.arrowAngle,
        child: const Icon(Icons.arrow_forward, size: 26),
      ),
    ],
  );
}
