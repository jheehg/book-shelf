import 'package:flutter/material.dart';

abstract final class AppColors {
  static const ground = Color(0xFFECECEA);
  static const ink = Color(0xFF111111);
  static const muted = Color(0xFF74746F);
  static const bodyMuted = Color(0xFF5F5F5B);
  static const surface = Color(0xFFFFFFFF);
  static const lavender = Color(0xFFAB9DFF);
  static const tomato = Color(0xFFF2643C);
  static const taupe = Color(0xFFD6CABB);
  static const unknown = Color(0xFFC9C9C5);
}

const _pill = StadiumBorder();
const _sheetRadius = Radius.circular(28);

ThemeData buildAppTheme() {
  const scheme = ColorScheme.light(
    primary: AppColors.ink,
    onPrimary: AppColors.surface,
    // 선택된 칩과 SegmentedButton이 secondaryContainer를 쓰므로 잉크로 칠한다.
    secondary: AppColors.ink,
    onSecondary: AppColors.surface,
    secondaryContainer: AppColors.ink,
    onSecondaryContainer: AppColors.surface,
    primaryContainer: AppColors.ink,
    onPrimaryContainer: AppColors.surface,
    tertiary: AppColors.lavender,
    onTertiary: AppColors.ink,
    surface: AppColors.ground,
    onSurface: AppColors.ink,
    onSurfaceVariant: AppColors.bodyMuted,
    surfaceContainerLowest: AppColors.surface,
    surfaceContainerLow: AppColors.surface,
    surfaceContainer: AppColors.surface,
    surfaceContainerHigh: AppColors.surface,
    surfaceContainerHighest: Color(0xFFDDDDD9),
    outline: AppColors.unknown,
    outlineVariant: Color(0xFFDDDDD9),
    inverseSurface: AppColors.ink,
    onInverseSurface: AppColors.surface,
    surfaceTint: Colors.transparent,
  );

  final base = ThemeData(colorScheme: scheme, useMaterial3: true);
  final text = base.textTheme;

  return base.copyWith(
    scaffoldBackgroundColor: AppColors.ground,
    textTheme: text.copyWith(
      displayLarge: text.displayLarge?.copyWith(fontWeight: FontWeight.w400),
      displayMedium: text.displayMedium?.copyWith(fontWeight: FontWeight.w400),
      displaySmall: text.displaySmall?.copyWith(fontWeight: FontWeight.w400),
      headlineLarge: _heavy(text.headlineLarge),
      headlineMedium: _heavy(text.headlineMedium),
      headlineSmall: _heavy(text.headlineSmall),
      titleLarge: _heavy(text.titleLarge),
      titleMedium: text.titleMedium?.copyWith(fontWeight: FontWeight.w700),
      titleSmall: text.titleSmall?.copyWith(fontWeight: FontWeight.w700),
      bodySmall: text.bodySmall?.copyWith(color: AppColors.bodyMuted),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: AppColors.ground,
      foregroundColor: AppColors.ink,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      elevation: 0,
      centerTitle: false,
      titleTextStyle: _heavy(text.titleLarge)?.copyWith(color: AppColors.ink),
    ),
    cardTheme: const CardThemeData(
      color: AppColors.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(24)),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.ink,
        foregroundColor: AppColors.surface,
        minimumSize: const Size(64, 56),
        shape: _pill,
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: AppColors.ink,
        side: const BorderSide(color: AppColors.ink, width: 1.5),
        shape: _pill,
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.ink,
        shape: _pill,
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    floatingActionButtonTheme: const FloatingActionButtonThemeData(
      backgroundColor: AppColors.ink,
      foregroundColor: AppColors.surface,
      elevation: 2,
      shape: _pill,
      extendedTextStyle: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surface,
      contentPadding: EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(26)),
        borderSide: BorderSide.none,
      ),
    ),
    chipTheme: ChipThemeData(
      color: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? AppColors.ink
            : AppColors.surface,
      ),
      checkmarkColor: AppColors.surface,
      side: BorderSide.none,
      shape: _pill,
      labelStyle: TextStyle(
        fontWeight: FontWeight.w700,
        color: WidgetStateColor.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? AppColors.surface
              : AppColors.ink,
        ),
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: SegmentedButton.styleFrom(
        backgroundColor: AppColors.surface,
        foregroundColor: AppColors.ink,
        selectedBackgroundColor: AppColors.ink,
        selectedForegroundColor: AppColors.surface,
        side: BorderSide.none,
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: AppColors.ground,
      modalBackgroundColor: AppColors.ground,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: AppColors.unknown,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: _sheetRadius),
      ),
    ),
    dialogTheme: const DialogThemeData(
      backgroundColor: AppColors.ground,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(_sheetRadius),
      ),
    ),
    datePickerTheme: const DatePickerThemeData(
      backgroundColor: AppColors.ground,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(_sheetRadius),
      ),
    ),
    snackBarTheme: const SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: AppColors.ink,
      contentTextStyle: TextStyle(color: AppColors.surface),
      actionTextColor: AppColors.lavender,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
    ),
    listTileTheme: const ListTileThemeData(
      iconColor: AppColors.ink,
      subtitleTextStyle: TextStyle(color: AppColors.bodyMuted, fontSize: 13),
    ),
    dividerTheme: const DividerThemeData(color: Color(0xFFDDDDD9)),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: AppColors.ink,
    ),
  );
}

TextStyle? _heavy(TextStyle? style) => style?.copyWith(
  fontWeight: FontWeight.w900,
  letterSpacing: (style.fontSize ?? 14) * -0.02,
);
