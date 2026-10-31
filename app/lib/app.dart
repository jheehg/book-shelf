import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'auth/auth_controller.dart';
import 'auth/login_screen.dart';
import 'book/book_detail_screen.dart';
import 'calendar/calendar_screen.dart';
import 'library/libraries_screen.dart';
import 'scan/scan_screen.dart';
import 'search/search_screen.dart';
import 'settings/settings_screen.dart';
import 'shelf/record_edit_screen.dart';
import 'shelf/shelf_screen.dart';
import 'theme/app_theme.dart';

final initialLocationProvider = Provider<String>((ref) => '/');

final List<RouteBase> appRoutes = [
  GoRoute(
    path: '/splash',
    builder: (_, _) =>
        const Scaffold(body: Center(child: CircularProgressIndicator())),
  ),
  GoRoute(path: '/login', builder: (_, _) => const LoginScreen()),
  GoRoute(path: '/', builder: (_, _) => const ShelfScreen()),
  GoRoute(path: '/search', builder: (_, _) => const SearchScreen()),
  GoRoute(path: '/scan', builder: (_, _) => const ScanScreen()),
  GoRoute(
    path: '/book/:isbn',
    builder: (_, state) =>
        BookDetailScreen(isbn: state.pathParameters['isbn']!),
  ),
  GoRoute(path: '/libraries', builder: (_, _) => const LibrariesScreen()),
  GoRoute(path: '/settings', builder: (_, _) => const SettingsScreen()),
  GoRoute(path: '/calendar', builder: (_, _) => const CalendarScreen()),
  GoRoute(
    path: '/record/:id',
    builder: (_, state) =>
        RecordEditScreen(id: int.parse(state.pathParameters['id']!)),
  ),
];

final routerProvider = Provider<GoRouter>((ref) {
  final auth = ValueNotifier<AuthStatus>(ref.read(authProvider));
  ref.listen<AuthStatus>(authProvider, (_, next) => auth.value = next);
  final router = GoRouter(
    initialLocation: ref.read(initialLocationProvider),
    refreshListenable: auth,
    redirect: (_, state) => _redirect(
      auth.value,
      state.matchedLocation,
      ref.read(initialLocationProvider),
    ),
    routes: appRoutes,
  );
  ref.onDispose(() {
    router.dispose();
    auth.dispose();
  });
  return router;
});

String? _redirect(AuthStatus auth, String location, String initial) {
  switch (auth) {
    case AuthStatus.unknown:
      return location == '/splash' ? null : '/splash';
    case AuthStatus.signedOut:
      return location == '/login' ? null : '/login';
    case AuthStatus.signedIn:
      if (location == '/login') return '/';
      if (location == '/splash') return initial == '/splash' ? '/' : initial;
      return null;
  }
}

class BookShelfApp extends ConsumerWidget {
  const BookShelfApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: '책장',
    theme: buildAppTheme(),
    locale: const Locale('ko'),
    supportedLocales: const [Locale('ko')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    routerConfig: ref.watch(routerProvider),
  );
}
