import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

void main() {
  late FakeApi api;

  setUp(() {
    api = FakeApi()
      ..myLibraryList = const [
        Library(libCode: '111', name: '송파도서관'),
        Library(libCode: '222', name: '강동도서관'),
      ];
  });

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.scrollUntilVisible(find.text(text), 200);
    await tester.ensureVisible(find.text(text));
    await tester.pumpAndSettle();
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  Future<void> tapButton(WidgetTester tester, String text) async {
    await tester.tap(find.widgetWithText(TextButton, text));
    await tester.pumpAndSettle();
  }

  testWidgets('이름, 서재 주소, 링크, 내 도서관을 보여준다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    expect(find.text('나'), findsWidgets);
    expect(find.text('@mine'), findsOneWidget);
    expect(find.text('mine'), findsOneWidget);
    expect(find.text('localhost:8080/u/mine'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('송파도서관 외 1곳'), 200);
    expect(find.text('2/3'), findsOneWidget);
  });

  testWidgets('내 도서관이 없으면 아직 없다고 보여준다', (tester) async {
    api.myLibraryList = [];
    await pumpApp(tester, api, initialLocation: '/settings');

    await tester.scrollUntilVisible(find.text('아직 없어요'), 200);
    expect(find.text('0/3'), findsOneWidget);
  });

  testWidgets('내 도서관 카드를 누르면 내 도서관 화면으로 간다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '송파도서관 외 1곳');

    expect(find.text('3곳까지'), findsOneWidget);
  });

  testWidgets('이름을 고치면 서버에 저장하고 새 이름을 보여준다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '이름');
    await tester.enterText(find.byType(TextField), '  책벌레 ');
    await tapButton(tester, '저장');

    expect(api.calls, contains('updateMe::책벌레:'));
    expect(find.text('책벌레'), findsWidgets);
    expect(find.text('나'), findsNothing);
  });

  testWidgets('규칙에 맞지 않는 서재 주소는 서버에 보내지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '서재 주소');
    await tester.enterText(find.byType(TextField), 'Bad Slug');
    await tapButton(tester, '저장');

    expect(find.text('소문자·숫자·하이픈 3~30자로 적어 주세요'), findsWidgets);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(api.calls.where((call) => call.startsWith('updateMe')), isEmpty);
  });

  testWidgets('서재 주소를 바꾸면 새 주소를 보여준다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '서재 주소');
    await tester.enterText(find.byType(TextField), 'new-me');
    await tapButton(tester, '저장');

    expect(api.calls, contains('updateMe:new-me::'));
    expect(find.text('new-me'), findsOneWidget);
  });

  testWidgets('서버가 거절하면 이유를 알려준다', (tester) async {
    api.onUpdateMe = () async => throw const ApiException(
      statusCode: 409,
      code: 'CONFLICT',
      message: '이미 쓰는 주소예요',
    );
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '서재 주소');
    await tester.enterText(find.byType(TextField), 'taken');
    await tapButton(tester, '저장');

    expect(find.text('이미 쓰는 주소예요'), findsOneWidget);
    expect(find.text('mine'), findsOneWidget);
  });

  testWidgets('서재를 공개하면 읽은 기록이 바뀐다고 알리고 고른 방식으로 공개한다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');
    expect(find.text('내 공개 서재 보기'), findsNothing);

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(find.text('다 읽은 기록 2권의 공개 범위가 아래로 바뀌어요'), findsOneWidget);
    expect(find.text(RecordVisibility.public.description), findsOneWidget);

    await tester.tap(
      find.widgetWithText(RadioListTile<RecordVisibility>, '공개'),
    );
    await tester.pumpAndSettle();
    await tapButton(tester, '공개하기');

    expect(api.calls, contains('publishShelf:PUBLIC'));
    expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);
    expect(find.text('내 공개 서재 보기'), findsOneWidget);
  });

  testWidgets('공개 확인을 취소하면 아무것도 바꾸지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    await tapButton(tester, '취소');

    expect(api.calls.where((call) => call.startsWith('publish')), isEmpty);
    expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
  });

  testWidgets('서재 공개를 끄면 바로 닫는다', (tester) async {
    api.shelfState = const Shelf(
      shelfPublic: true,
      url: 'http://localhost:8080/u/mine',
      doneCount: 2,
      visibleCount: 2,
    );
    await pumpApp(tester, api, initialLocation: '/settings');

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();

    expect(api.calls, contains('closeShelf'));
    expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
  });

  testWidgets('새 기록의 공개 범위를 바꾸면 서버에 저장한다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/settings');
    expect(find.text(RecordVisibility.private.description), findsOneWidget);

    await tapText(tester, '별점만');

    expect(api.calls, contains('updateMe:::RATING_ONLY'));
    expect(find.text(RecordVisibility.ratingOnly.description), findsOneWidget);
  });

  testWidgets('로그아웃은 확인을 받은 뒤에만 한다', (tester) async {
    final store = MemoryTokenStore(const Tokens(access: 'a', refresh: 'r'));
    await pumpApp(tester, api, store: store, initialLocation: '/settings');

    await tapText(tester, '로그아웃');
    expect(find.text('로그아웃할까요?'), findsOneWidget);
    await tapButton(tester, '취소');
    expect(find.text('계정 삭제'), findsOneWidget);
    expect(api.calls, isNot(contains('logout')));

    await tapText(tester, '로그아웃');
    await tapButton(tester, '로그아웃');

    expect(find.text('개발 로그인'), findsOneWidget);
    expect(api.calls, contains('logout'));
    expect(await store.read(), isNull);
  });

  testWidgets('계정 삭제를 확인하면 지우고 서버 로그아웃 없이 로그인 화면으로 간다', (tester) async {
    final store = MemoryTokenStore(const Tokens(access: 'a', refresh: 'r'));
    await pumpApp(tester, api, store: store, initialLocation: '/settings');

    await tapText(tester, '계정 삭제');
    expect(find.text('기록과 서재가 모두 지워지고 되돌릴 수 없어요.'), findsOneWidget);
    await tapButton(tester, '지우기');

    expect(find.text('개발 로그인'), findsOneWidget);
    expect(api.calls, contains('deleteAccount'));
    expect(api.calls, isNot(contains('logout')));
    expect(await store.read(), isNull);
  });

  testWidgets('계정 삭제에 실패하면 로그인 상태로 남는다', (tester) async {
    api.onDeleteAccount = () async => throw const ApiException(
      statusCode: 503,
      code: 'NETWORK',
      message: '서버에 연결할 수 없어요',
    );
    await pumpApp(tester, api, initialLocation: '/settings');

    await tapText(tester, '계정 삭제');
    await tapButton(tester, '지우기');

    expect(find.text('서버에 연결할 수 없어요'), findsOneWidget);
    expect(find.text('계정 삭제'), findsOneWidget);
  });
}
