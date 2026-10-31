import 'package:flutter/material.dart';

import '../api/models.dart';
import '../theme/app_theme.dart';

/// 공개, 별점만, 비공개 중 하나를 고르는 알약 모양 선택. onChanged가 null이면 누를 수 없다.
class VisibilityPicker extends StatelessWidget {
  const VisibilityPicker({
    super.key,
    required this.selected,
    required this.onChanged,
  });

  final RecordVisibility selected;
  final ValueChanged<RecordVisibility>? onChanged;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(4),
    decoration: const ShapeDecoration(
      color: AppColors.ground,
      shape: StadiumBorder(),
    ),
    child: Row(
      spacing: 6,
      children: [
        for (final visibility in RecordVisibility.values)
          Expanded(
            child: MergeSemantics(
              child: Semantics(
                inMutuallyExclusiveGroup: true,
                checked: visibility == selected,
                child: TextButton(
                  onPressed: onChanged == null
                      ? null
                      : () => onChanged!(visibility),
                  style: TextButton.styleFrom(
                    minimumSize: const Size.fromHeight(44),
                    backgroundColor: visibility == selected
                        ? AppColors.ink
                        : Colors.transparent,
                    disabledBackgroundColor: visibility == selected
                        ? AppColors.ink
                        : Colors.transparent,
                    foregroundColor: visibility == selected
                        ? AppColors.surface
                        : AppColors.ink,
                    disabledForegroundColor: visibility == selected
                        ? AppColors.surface
                        : AppColors.muted,
                    textStyle: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  child: Text(visibility.label),
                ),
              ),
            ),
          ),
      ],
    ),
  );
}
