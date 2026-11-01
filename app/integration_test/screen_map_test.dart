import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/api/token_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import '../test/support/fake_api.dart';
import '../test/support/harness.dart';

/// 화면 지도: 화면마다 대표 상태를 시뮬레이터에서 찍는다.
/// flutter drive --driver=test_driver/integration_test.dart --target=integration_test/screen_map_test.dart -d <시뮬레이터>
void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final now = DateTime.now();

  const covers = {
    '9788936434120': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F532683%3Ftimestamp%3D20261021110814',
    '9788936434595': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F6042324%3Ftimestamp%3D20261017121055',
    '9791161571188': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F5643183%3Ftimestamp%3D20261006121959',
    '9791161571379': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F6127539%3Ftimestamp%3D20261006122742',
    '9791161571768': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F6609944%3Ftimestamp%3D20261023121028',
    '9788997962129': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F1498793%3Ftimestamp%3D20261003111430',
    '9791198363510': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F6381968%3Ftimestamp%3D20260528150556',
    '9788932034812': 'https://search1.kakaocdn.net/thumb/R120x174.q85/?fname=http%3A%2F%2Ft1.daumcdn.net%2Flbook%2Fimage%2F4368611%3Ftimestamp%3D20241210125930',
  };

  Book book(String isbn, String title, String author, String publisher) => Book(
    isbn13: isbn,
    title: title,
    author: author,
    publisher: publisher,
    coverUrl: covers[isbn],
  );

  final sonyeon = book('9788936434120', '소년이 온다', '한강', '창비');
  final chaesik = book('9788936434595', '채식주의자', '한강', '창비');
  final convenience = book('9791161571188', '불편한 편의점(벚꽃 에디션)', '김호연', '나무옆의자');
  final almond = book('9791198363510', '아몬드', '손원평', '다즐링');
  final yeosu = book('9788932034812', '여수의 사랑', '한강', '문학과지성사');

  RecordItem rec(
    int id,
    Book? b,
    String isbn,
    ReadingStatus s, {
    int? rating,
    String? review,
    String? finishedAt,
  }) => RecordItem(
    id: id,
    isbn13: isbn,
    status: s,
    visibility: RecordVisibility.private,
    rating: rating,
    review: review,
    finishedAt: finishedAt,
    book: b,
  );

  final records = [
    rec(
      1,
      sonyeon,
      sonyeon.isbn13,
      ReadingStatus.done,
      rating: 5,
      review: '오래 남는 책',
      finishedAt: '2026-09-12',
    ),
    rec(2, chaesik, chaesik.isbn13, ReadingStatus.reading),
    rec(3, convenience, convenience.isbn13, ReadingStatus.want),
    rec(
      4,
      almond,
      almond.isbn13,
      ReadingStatus.done,
      rating: 4,
      finishedAt: '2025-11-03',
    ),
    rec(5, null, '9791100000007', ReadingStatus.want),
    rec(6, yeosu, yeosu.isbn13, ReadingStatus.done, rating: 3),
  ];

  const links = StoreLinks(
    newBooks: [
      StoreLink(name: '교보문고', url: 'https://example.com'),
      StoreLink(name: '예스24', url: 'https://example.com'),
      StoreLink(name: '알라딘', url: 'https://example.com'),
      StoreLink(name: '영풍문고', url: 'https://example.com'),
    ],
    used: [
      StoreLink(name: '알라딘 중고', url: 'https://example.com'),
      StoreLink(name: '예스24 중고', url: 'https://example.com'),
    ],
    ebook: [
      StoreLink(name: 'Google Play 도서', url: 'https://example.com'),
      StoreLink(name: '리디', url: 'https://example.com'),
      StoreLink(name: '교보eBook', url: 'https://example.com'),
      StoreLink(name: '밀리의서재', url: 'https://example.com'),
    ],
  );

  FakeApi detailApi() => FakeApi()
    ..recordsBySlug = {'mine': records.where((r) => r.id != 1).toList()}
    ..onBook = ((isbn) async => BookDetail(
      book: sonyeon,
      sale: Sale(
        status: SaleStatus.onSale,
        listPrice: 15000,
        salePrice: 13500,
        fetchedAt: now.subtract(const Duration(hours: 3)),
      ),
    ))
    ..onLinks = ((isbn) async => links)
    ..onHoldings = ((isbn) async => [
      Holding(
        libCode: 'L1',
        name: '송파도서관',
        state: HoldingState.available,
        fetchedAt: now,
      ),
      Holding(
        libCode: 'L2',
        name: '가락몰도서관',
        state: HoldingState.onLoan,
        fetchedAt: now,
      ),
      Holding(
        libCode: 'L3',
        name: '강동구립해공도서관',
        state: HoldingState.notHeld,
        fetchedAt: now,
      ),
    ])
    ..onEbook = ((isbn) async => Ebook(
      status: 'MATCHED',
      price: 10800,
      link: 'https://play.google.com',
      fetchedAt: now.subtract(const Duration(hours: 1)),
      attribution: 'powered by Google',
    ));

  /// 표지 이미지가 네트워크에서 올 시간을 준다. pumpAndSettle은 로딩 표시가 남으면 끝나지 않는다
  Future<void> settle(WidgetTester tester, {int frames = 12}) async {
    for (var i = 0; i < frames; i++) {
      await tester.pump(const Duration(milliseconds: 250));
    }
  }

  Future<void> shot(WidgetTester tester, String name) async {
    await settle(tester);
    await binding.takeScreenshot(name);
  }

  testWidgets('01 로그인', (tester) async {
    await pumpApp(tester, FakeApi(), store: MemoryTokenStore(), settle: false);
    await shot(tester, '01_login');
  });

  testWidgets('02 빈 서재', (tester) async {
    await pumpApp(tester, FakeApi(), settle: false);
    await shot(tester, '02_shelf_empty');
  });

  testWidgets('03 서재', (tester) async {
    await pumpApp(
      tester,
      FakeApi()..recordsBySlug = {'mine': records},
      settle: false,
    );
    await shot(tester, '03_shelf');
  });

  testWidgets('15 서재 메뉴와 책장', (tester) async {
    await pumpApp(
      tester,
      FakeApi()..recordsBySlug = {'mine': records},
      settle: false,
    );
    await settle(tester);
    await tester.tap(find.byTooltip('서재 메뉴'));
    await shot(tester, '15_shelf_menu');
    await tester.tap(find.text('책장으로 보기'));
    await shot(tester, '16_shelf_bookshelf');
  });

  testWidgets('04 검색 결과', (tester) async {
    final api = FakeApi()
      ..onSearch = (q, page) async => SearchPage(
        items: [
          SearchItem(
            book: book('9791161571768', '나의 돈키호테', '김호연', '나무옆의자'),
            sale: Sale(
              status: SaleStatus.onSale,
              listPrice: 18000,
              salePrice: 16200,
              fetchedAt: now,
            ),
          ),
          SearchItem(
            book: book('9791161571379', '불편한 편의점 2(단풍 에디션)', '김호연', '나무옆의자'),
            sale: Sale(
              status: SaleStatus.onSale,
              listPrice: 16800,
              salePrice: 15120,
              fetchedAt: now,
            ),
          ),
          SearchItem(
            book: convenience,
            sale: Sale(
              status: SaleStatus.order,
              listPrice: 16800,
              salePrice: 15120,
              fetchedAt: now,
            ),
          ),
          SearchItem(
            book: book('9788997962129', '망원동 브라더스', '김호연', '나무옆의자'),
            sale: Sale(status: SaleStatus.unknown, fetchedAt: now),
          ),
        ],
        total: 4,
        isEnd: true,
      );
    await pumpApp(tester, api, initialLocation: '/search', settle: false);
    await tester.enterText(find.byType(TextField), '김호연');
    await settle(tester, frames: 3);
    await shot(tester, '04_search');
  });

  testWidgets('05 바코드 스캔', (tester) async {
    await pumpApp(tester, FakeApi(), initialLocation: '/scan', settle: false);
    await shot(tester, '05_scan');
    await tester.tap(find.text('ISBN 직접 입력'));
    await settle(tester, frames: 3);
    await tester.enterText(find.byType(TextField), '9788936434120');
    await shot(tester, '06_scan_typed');
  });

  testWidgets('07 책 상세', (tester) async {
    await pumpApp(
      tester,
      detailApi(),
      initialLocation: '/book/9788936434120',
      settle: false,
    );
    await shot(tester, '07_detail_top');
    await tester.drag(
      find.byType(SingleChildScrollView).first,
      const Offset(0, -2000),
    );
    await shot(tester, '08_detail_bottom');
    await tester.tap(find.text('내 서재에 담기'));
    await shot(tester, '17_detail_add_sheet');
  });

  testWidgets('09 책 상세 확인 불가', (tester) async {
    final api = detailApi()
      ..onBook = ((isbn) async => BookDetail(book: sonyeon, sale: const Sale()))
      ..onHoldings = ((isbn) async => throw const ApiException(
        statusCode: 503,
        code: 'TRY_LATER',
        message: '잠시 후 다시 시도해 주세요',
      ))
      ..onEbook = (isbn) async => const Ebook(status: 'UNKNOWN');
    await pumpApp(
      tester,
      api,
      initialLocation: '/book/9788936434120',
      settle: false,
    );
    await shot(tester, '09_detail_degraded');
  });

  testWidgets('10 서버에 없는 책', (tester) async {
    final api = detailApi()
      ..recordsBySlug = {
        'mine': records.where((r) => r.isbn13 != '9791100000007').toList(),
      }
      ..onBook = (isbn) async => throw const ApiException(
        statusCode: 404,
        code: 'NOT_FOUND',
        message: '찾을 수 없어요',
      );
    await pumpApp(
      tester,
      api,
      initialLocation: '/book/9791100000007',
      settle: false,
    );
    await shot(tester, '10_detail_unknown');
  });

  testWidgets('11 이미 기록한 책과 도서관 미등록', (tester) async {
    final api = detailApi()
      ..recordsBySlug = {'mine': records}
      ..onHoldings = ((isbn) async => const <Holding>[]);
    await pumpApp(
      tester,
      api,
      initialLocation: '/book/9788936434120',
      settle: false,
    );
    await shot(tester, '11_detail_recorded');
  });

  testWidgets('12 기록 고치기', (tester) async {
    await pumpApp(
      tester,
      FakeApi()..recordsBySlug = {'mine': records},
      initialLocation: '/record/1',
      settle: false,
    );
    await shot(tester, '12_record_edit');
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -600));
    await shot(tester, '18_record_edit_bottom');
  });

  testWidgets('19 다 읽은 날 묻기', (tester) async {
    await pumpApp(
      tester,
      FakeApi()..recordsBySlug = {'mine': records},
      initialLocation: '/record/2',
      settle: false,
    );
    await settle(tester);
    await tester.tap(find.text('다 읽음'));
    await shot(tester, '19_finish_date_sheet');
  });

  testWidgets('13 내 도서관', (tester) async {
    final api = FakeApi()
      ..myLibraryList = const [
        Library(libCode: 'L1', name: '송파도서관', address: '서울 송파구 동남로 263'),
      ]
      ..onLibraries = ((region, q) async => const [
        Library(libCode: 'L1', name: '송파도서관', address: '서울 송파구 동남로 263'),
        Library(libCode: 'L2', name: '가락몰도서관', address: '서울 송파구 양재대로 932'),
        Library(libCode: 'L3', name: '송파어린이도서관', address: '서울 송파구 가락로 71'),
        Library(libCode: 'L4', name: '거마도서관', address: '서울 송파구 거마로2길 19'),
      ]);
    await pumpApp(tester, api, initialLocation: '/libraries', settle: false);
    await shot(tester, '13_libraries_start');
    await tester.tap(find.byKey(const Key('region')));
    await settle(tester, frames: 3);
    await tester.tap(find.text('서울').last);
    await settle(tester, frames: 3);
    await tester.tap(
      find.descendant(
        of: find.byKey(const Key('library-results')),
        matching: find.text('가락몰도서관'),
      ),
    );
    await shot(tester, '14_libraries_selected');
  });

  testWidgets('20 설정', (tester) async {
    final api = FakeApi()
      ..displayName = '독자'
      ..slug = 'reader'
      ..defaultVisibility = RecordVisibility.ratingOnly
      ..shelfState = const Shelf(
        shelfPublic: true,
        url: 'https://book-shelf.example/u/reader',
        doneCount: 3,
        visibleCount: 3,
      )
      ..myLibraryList = const [
        Library(libCode: 'L1', name: '송파도서관'),
        Library(libCode: 'L2', name: '가락몰도서관'),
      ];
    await pumpApp(tester, api, initialLocation: '/settings', settle: false);
    await shot(tester, '20_settings_top');
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -2000));
    await shot(tester, '21_settings_bottom');
  });

  testWidgets('22 완독 달력', (tester) async {
    final last = DateTime(now.year, now.month, 0);
    String day(DateTime d) =>
        '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
    String inLast(int d) => day(DateTime(last.year, last.month, d));
    final dated = [
      rec(11, almond, almond.isbn13, ReadingStatus.done, finishedAt: inLast(3)),
      rec(
        12,
        sonyeon,
        sonyeon.isbn13,
        ReadingStatus.done,
        finishedAt: inLast(12),
      ),
      rec(13, yeosu, yeosu.isbn13, ReadingStatus.done, finishedAt: inLast(19)),
      rec(
        14,
        chaesik,
        chaesik.isbn13,
        ReadingStatus.done,
        finishedAt: inLast(19),
      ),
      rec(
        15,
        book('9788997962129', '망원동 브라더스', '김호연', '나무옆의자'),
        '9788997962129',
        ReadingStatus.done,
        finishedAt: inLast(27),
      ),
      rec(
        16,
        convenience,
        convenience.isbn13,
        ReadingStatus.done,
        finishedAt: day(now),
      ),
      rec(17, null, '9791100000007', ReadingStatus.done),
    ];
    await pumpApp(
      tester,
      FakeApi()..recordsBySlug = {'mine': dated},
      initialLocation: '/calendar',
      settle: false,
    );
    await shot(tester, '22_calendar_this_month');
    await tester.tap(find.byTooltip('이전 달'));
    await shot(tester, '23_calendar_last_month');
  });
}
