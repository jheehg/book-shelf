import 'dart:async';

import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/book/book_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

const isbn = '9788936434120';
final now = DateTime(2026, 10, 2, 12);

FakeApi happyApi() {
  final api = FakeApi();
  api.onBook = (i) async => BookDetail(
    book: const Book(
      isbn13: isbn,
      title: '소년이 온다',
      author: '한강',
      publisher: '창비',
    ),
    sale: Sale(
      status: SaleStatus.onSale,
      listPrice: 15000,
      salePrice: 13500,
      fetchedAt: now.subtract(const Duration(hours: 3)),
    ),
  );
  api.onLinks = (i) async => const StoreLinks(
    newBooks: [StoreLink(name: '교보문고', url: 'https://example/kyobo')],
    used: [StoreLink(name: '알라딘 중고', url: 'https://example/aladin-used')],
    ebook: [StoreLink(name: '리디', url: 'https://example/ridi')],
  );
  api.onHoldings = (i) async => [
    Holding(
      libCode: 'L1',
      name: '송파도서관',
      state: HoldingState.available,
      fetchedAt: now,
    ),
    Holding(
      libCode: 'L2',
      name: '강동도서관',
      state: HoldingState.onLoan,
      fetchedAt: now,
    ),
  ];
  api.onEbook = (i) async => Ebook(
    status: 'MATCHED',
    price: 10800,
    link: 'https://example/gplay',
    fetchedAt: now,
    attribution: 'powered by Google',
  );
  return api;
}

Future<void> openBook(WidgetTester tester, FakeApi api, {bool settle = true}) =>
    pumpApp(
      tester,
      api,
      initialLocation: '/book/$isbn',
      overrides: [nowProvider.overrideWithValue(() => now)],
      settle: settle,
    );

Finder inSection(String title, Finder child) =>
    find.descendant(of: find.byKey(Key('section-$title')), matching: child);

Finder inHero(Finder child) =>
    find.descendant(of: find.byKey(const Key('book-hero')), matching: child);

Future<void> chooseStatus(WidgetTester tester, String status) async {
  await tester.tap(find.text('내 서재에 담기'));
  await tester.pumpAndSettle();
  await tester.tap(find.text(status));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('읽을 수단을 한 화면에 보여준다', (tester) async {
    final api = happyApi();
    await openBook(tester, api);

    expect(find.text('소년이 온다'), findsOneWidget);
    expect(find.text('한강 · 창비'), findsOneWidget);
    expect(find.text('아직 담지 않은 책'), findsOneWidget);
    expect(inSection('새 책', find.text('판매 중 · 3시간 전 기준')), findsOneWidget);
    expect(inSection('새 책', find.text('13,500원')), findsOneWidget);
    expect(inSection('새 책', find.text('15,000원')), findsOneWidget);
    expect(inSection('새 책', find.text('교보문고')), findsOneWidget);
    expect(inSection('중고', find.text('알라딘 중고')), findsOneWidget);
    expect(inSection('빌리기', find.text('내 도서관')), findsOneWidget);
    expect(inSection('빌리기', find.text('대출 가능')), findsOneWidget);
    expect(inSection('빌리기', find.text('대출 중')), findsOneWidget);
    expect(inSection('빌리기', find.text('전날 기준')), findsOneWidget);
    expect(inSection('전자책', find.text('10,800원')), findsOneWidget);
    expect(
      inSection('전자책', find.textContaining('powered by Google')),
      findsOneWidget,
    );
    expect(inSection('전자책', find.text('리디')), findsOneWidget);
  });

  testWidgets('도서관 칸 오류는 그 칸에만 표시한다', (tester) async {
    final api = happyApi()
      ..onHoldings = (i) => throw const ApiException(
        statusCode: 503,
        code: 'TRY_LATER',
        message: '잠시 후 다시 시도해 주세요',
      );
    await openBook(tester, api);

    expect(inSection('빌리기', find.text('확인 불가')), findsOneWidget);
    expect(inSection('빌리기', find.text('당겨서 다시 시도')), findsOneWidget);
    expect(find.text('소년이 온다'), findsOneWidget);
    expect(inSection('새 책', find.text('판매 중 · 3시간 전 기준')), findsOneWidget);
    expect(inSection('전자책', find.text('10,800원')), findsOneWidget);
  });

  testWidgets('전자책이 느려도 다른 칸과 구매 링크를 먼저 표시한다', (tester) async {
    final slow = Completer<Ebook>();
    final api = happyApi()..onEbook = (i) => slow.future;
    await openBook(tester, api, settle: false);
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }

    expect(find.text('소년이 온다'), findsOneWidget);
    expect(inSection('새 책', find.text('교보문고')), findsOneWidget);
    expect(inSection('빌리기', find.text('대출 가능')), findsOneWidget);
    expect(
      inSection('전자책', find.byType(CircularProgressIndicator)),
      findsOneWidget,
    );
    expect(inSection('전자책', find.text('리디')), findsOneWidget);

    slow.complete(const Ebook(status: 'NO_MATCH'));
    await tester.pumpAndSettle();
    expect(
      inSection('전자책', find.byType(CircularProgressIndicator)),
      findsNothing,
    );
  });

  testWidgets('같은 책이 아니면 전자책 가격 없이 링크만 표시한다', (tester) async {
    final api = happyApi()
      ..onEbook = (i) async => const Ebook(status: 'NO_MATCH');
    await openBook(tester, api);

    expect(inSection('전자책', find.textContaining('원')), findsNothing);
    expect(
      inSection('전자책', find.textContaining('powered by Google')),
      findsNothing,
    );
    expect(inSection('전자책', find.text('리디')), findsOneWidget);
  });

  testWidgets('판매 확인 전에는 가격과 기준 시각을 표시하지 않는다', (tester) async {
    final api = happyApi()
      ..onBook = (i) async => const BookDetail(
        book: Book(isbn13: isbn, title: '소년이 온다'),
        sale: Sale(),
      );
    await openBook(tester, api);

    expect(inSection('새 책', find.text('가격 확인 불가')), findsOneWidget);
    expect(inSection('새 책', find.text('당겨서 다시 시도')), findsOneWidget);
    expect(inSection('새 책', find.textContaining('원')), findsNothing);
    expect(inSection('새 책', find.textContaining('기준')), findsNothing);
    expect(inSection('새 책', find.text('교보문고')), findsOneWidget);
  });

  testWidgets('서버에 없는 책도 ISBN만으로 기록할 수 있다', (tester) async {
    final api = happyApi()
      ..onBook = (i) => throw const ApiException(
        statusCode: 404,
        code: 'NOT_FOUND',
        message: '찾을 수 없어요',
      );
    await openBook(tester, api);

    expect(find.text('책 정보를\n찾지 못했어요'), findsOneWidget);
    expect(find.text('ISBN $isbn'), findsOneWidget);
    expect(find.text('그래도 기록해 두면 책 정보는 나중에 채워져요.'), findsOneWidget);
    expect(find.text('책 정보가 없어 서점 링크를 만들 수 없어요'), findsOneWidget);
    expect(find.byKey(const Key('section-중고')), findsNothing);
    expect(find.byKey(const Key('section-전자책')), findsNothing);
    expect(
      api.calls.where(
        (call) => call.startsWith('links:') || call.startsWith('ebook:'),
      ),
      isEmpty,
    );

    await chooseStatus(tester, '다 읽음');
    await tester.tap(find.text('기억 안 나요'));
    await tester.pumpAndSettle();
    expect(api.calls, contains('createRecord:$isbn:DONE'));
  });

  testWidgets('이미 기록한 책은 내 기록과 편집 경로를 보여준다', (tester) async {
    final api = happyApi()
      ..recordsBySlug = {
        'mine': [
          record(
            1,
            isbn,
            ReadingStatus.done,
            title: '소년이 온다',
            rating: 4,
            review: '오래 남는 책',
          ),
        ],
      };
    await openBook(tester, api);

    expect(inHero(find.text('다 읽음')), findsOneWidget);
    expect(inHero(find.text('4')), findsOneWidget);
    expect(inHero(find.text('“오래 남는 책”')), findsOneWidget);
    expect(find.text('아직 담지 않은 책'), findsNothing);
    expect(find.text('내 서재에 담기'), findsNothing);
    await tester.tap(find.text('기록 고치기'));
    await tester.pumpAndSettle();
    expect(find.text('저장'), findsOneWidget);
  });

  testWidgets('제목 없는 앱바에 기록 버튼은 내 서재에 담는다고 알린다', (tester) async {
    await openBook(tester, happyApi());

    expect(
      find.descendant(of: find.byType(AppBar), matching: find.byType(Text)),
      findsNothing,
    );
    expect(find.text('내 서재에 담기'), findsOneWidget);
  });

  testWidgets('서점 링크는 서점 이름과 바깥으로 나가는 표시만 보여준다', (tester) async {
    await openBook(tester, happyApi());

    expect(
      inSection('새 책', find.widgetWithText(OutlinedButton, '교보문고')),
      findsOneWidget,
    );
    expect(find.textContaining('에서 찾기'), findsNothing);
  });

  testWidgets('확인하지 못한 칸은 당겨서 다시 시도하라고 안내한다', (tester) async {
    const tryLater = ApiException(
      statusCode: 503,
      code: 'TRY_LATER',
      message: '잠시 후 다시 시도해 주세요',
    );
    final api = happyApi()..onEbook = (i) => throw tryLater;
    await openBook(tester, api);
    expect(inSection('전자책', find.text('확인 불가')), findsOneWidget);

    api.onBook = (i) => throw tryLater;
    await tester.fling(
      find.byType(SingleChildScrollView),
      const Offset(0, 400),
      1000,
    );
    await tester.pumpAndSettle();
    expect(inSection('새 책', find.text('확인 불가')), findsOneWidget);
    expect(inSection('새 책', find.text('당겨서 다시 시도')), findsOneWidget);
  });

  testWidgets('등록한 도서관이 없으면 등록을 안내한다', (tester) async {
    final api = happyApi()..onHoldings = (i) async => const <Holding>[];
    await openBook(tester, api);

    expect(
      inSection('빌리기', find.text('내 도서관을 등록하면 대출할 수 있는지 바로 보여요.')),
      findsOneWidget,
    );
    await tester.ensureVisible(inSection('빌리기', find.text('도서관 등록')));
    await tester.pumpAndSettle();
    await tester.tap(inSection('빌리기', find.text('도서관 등록')));
    await tester.pumpAndSettle();
    expect(find.text('3곳까지'), findsOneWidget);
  });

  testWidgets('이미 담긴 책이면 오류 없이 내 기록으로 바뀐다', (tester) async {
    final api = happyApi();
    api.onCreateRecord = (i, status) async {
      api.recordsBySlug = {
        'mine': [record(7, isbn, ReadingStatus.reading, title: '소년이 온다')],
      };
      throw const ApiException(
        statusCode: 409,
        code: 'CONFLICT',
        message: '이미 담은 책이에요',
      );
    };
    await openBook(tester, api);
    await chooseStatus(tester, '읽고 싶음');

    expect(find.text('이미 담은 책이에요'), findsNothing);
    expect(inHero(find.text('읽는 중')), findsOneWidget);
    expect(find.text('기록 고치기'), findsOneWidget);
  });

  testWidgets('담지 못하면 이유를 알리고 담기 버튼을 그대로 둔다', (tester) async {
    final api = happyApi()
      ..onCreateRecord = (i, status) => throw const ApiException(
        statusCode: 503,
        code: 'TRY_LATER',
        message: '잠시 후 다시 시도해 주세요',
      );
    await openBook(tester, api);
    await chooseStatus(tester, '읽는 중');

    expect(api.calls, contains('createRecord:$isbn:READING'));
    expect(find.text('잠시 후 다시 시도해 주세요'), findsOneWidget);
    expect(find.text('내 서재에 담기'), findsOneWidget);
  });

  testWidgets('담으면 책 덩어리가 고른 상태로 바뀐다', (tester) async {
    final api = happyApi();
    api.onCreateRecord = (i, status) async {
      final created = record(99, isbn, status, title: '소년이 온다');
      api.recordsBySlug = {
        'mine': [created],
      };
      return created;
    };
    await openBook(tester, api);
    await chooseStatus(tester, '읽는 중');

    expect(find.text('아직 담지 않은 책'), findsNothing);
    expect(inHero(find.text('읽는 중')), findsOneWidget);
    expect(find.text('기록 고치기'), findsOneWidget);
  });

  group('읽음으로 담기', () {
    late FakeApi api;
    late List<(int, RecordUpdate)> updates;

    setUp(() {
      updates = [];
      api = happyApi()
        ..onUpdateRecord = (id, update) async {
          updates.add((id, update));
          return RecordItem(
            id: id,
            isbn13: isbn,
            status: update.status,
            visibility: update.visibility,
            finishedAt: update.finishedAt,
          );
        };
    });

    Future<void> tapDone(WidgetTester tester) async {
      await openBook(tester, api);
      await chooseStatus(tester, '다 읽음');
    }

    Future<void> choose(WidgetTester tester, String choice) async {
      await tester.tap(find.text(choice));
      await tester.pumpAndSettle();
    }

    testWidgets('읽음은 담기 전에 언제 다 읽었는지 묻는다', (tester) async {
      await tapDone(tester);

      expect(find.text('언제 다 읽었어요?'), findsOneWidget);
      expect(api.calls.where((c) => c.startsWith('createRecord')), isEmpty);
    });

    testWidgets('오늘을 고르면 다 읽음으로 담고 오늘을 다 읽은 날로 남긴다', (tester) async {
      await tapDone(tester);
      await choose(tester, '오늘');

      expect(api.calls, contains('createRecord:$isbn:DONE'));
      expect(updates, hasLength(1));
      final (id, update) = updates.single;
      expect(id, 99);
      expect(update.status, ReadingStatus.done);
      expect(update.visibility, RecordVisibility.private);
      expect(update.finishedAt, '2026-10-02');
    });

    testWidgets('날짜를 고르면 그 날을 다 읽은 날로 남긴다', (tester) async {
      await tapDone(tester);
      await choose(tester, '날짜 고르기');
      await tester.tap(find.text('1'));
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();

      expect(api.calls, contains('createRecord:$isbn:DONE'));
      expect(updates.single.$2.finishedAt, '2026-10-01');
    });

    testWidgets('기억 안 나요는 날짜 없이 다 읽음으로 담는다', (tester) async {
      await tapDone(tester);
      await choose(tester, '기억 안 나요');

      expect(api.calls, contains('createRecord:$isbn:DONE'));
      expect(updates, isEmpty);
    });

    testWidgets('묻는 창을 닫으면 아무것도 담지 않는다', (tester) async {
      await tapDone(tester);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();

      expect(find.text('언제 다 읽었어요?'), findsNothing);
      expect(api.calls.where((c) => c.startsWith('createRecord')), isEmpty);
    });

    testWidgets('날짜를 남기지 못하면 담은 것은 두고 고치기를 안내한다', (tester) async {
      api.onUpdateRecord = (id, update) => throw const ApiException(
        statusCode: 503,
        code: 'TRY_LATER',
        message: '잠시 후 다시 시도해 주세요',
      );
      await tapDone(tester);
      await choose(tester, '오늘');

      expect(api.calls, contains('createRecord:$isbn:DONE'));
      expect(find.text('기록은 담았어요. 다 읽은 날은 기록 고치기에서 넣어 주세요.'), findsOneWidget);
    });

    testWidgets('읽고 싶음은 묻지 않고 바로 담는다', (tester) async {
      await openBook(tester, api);
      await chooseStatus(tester, '읽고 싶음');

      expect(find.text('언제 다 읽었어요?'), findsNothing);
      expect(api.calls, contains('createRecord:$isbn:WANT'));
      expect(updates, isEmpty);
    });
  });
}
