import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:bookshelf/book/book_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

/// 화면이 테스트 창보다 길어 아래 카드는 스크롤해야 만들어진다.
Future<void> reveal(WidgetTester tester, Finder finder) async {
  await tester.scrollUntilVisible(
    finder,
    200,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
}

void main() {
  late FakeApi api;
  RecordUpdate? sent;

  setUp(() {
    sent = null;
    api = FakeApi()
      ..recordsBySlug = {
        'mine': [
          record(
            1,
            '9788936434120',
            ReadingStatus.done,
            title: '소년이 온다',
            rating: 5,
            review: '오래 남는다',
          ),
        ],
      }
      ..onUpdateRecord = (id, update) async {
        sent = update;
        return RecordItem(
          id: id,
          isbn13: '9788936434120',
          status: update.status,
          visibility: update.visibility,
        );
      };
  });

  testWidgets('수정한 내용을 전체로 보내고 서재로 돌아간다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');

    await tester.tap(find.byKey(const Key('star-4')));
    await tester.enterText(find.byKey(const Key('review')), '  다시 읽고 싶다 ');
    await tester.tap(find.text('읽는 중'));
    await reveal(tester, find.text('별점만'));
    await tester.tap(find.text('별점만'));
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(sent!.rating, 4);
    expect(sent!.review, '다시 읽고 싶다');
    expect(sent!.visibility, RecordVisibility.ratingOnly);
    expect(sent!.status, ReadingStatus.reading);
    expect(find.text('내 서재'), findsOneWidget);
  });

  testWidgets('선택된 별을 다시 누르면 별점을 지운다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
    await tester.tap(find.byKey(const Key('star-5')));
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
    expect(sent!.rating, isNull);
  });

  testWidgets('코드포인트 200자를 넘으면 저장할 수 없다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
    await tester.enterText(find.byKey(const Key('review')), '👨‍👩‍👧' * 41);
    await tester.pumpAndSettle();

    expect(find.text('205 / 200'), findsOneWidget);
    expect(find.text('한줄평은 200자까지예요'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '저장'))
          .onPressed,
      isNull,
    );
    expect(api.calls.where((call) => call.startsWith('updateRecord')), isEmpty);
  });

  testWidgets('한글 200자는 저장된다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
    await tester.enterText(find.byKey(const Key('review')), '가' * 200);
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
    expect(sent!.review, '가' * 200);
  });

  testWidgets('기록 삭제를 확인하고 서재로 돌아간다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
    await tester.tap(find.byKey(const Key('delete')));
    await tester.pumpAndSettle();
    expect(find.text('이 기록을 지울까요?'), findsOneWidget);
    await tester.tap(find.text('지우기'));
    await tester.pumpAndSettle();

    expect(api.calls, contains('deleteRecord:1'));
    expect(find.text('내 서재'), findsOneWidget);
  });

  testWidgets('완료일이 시작일보다 앞서면 저장을 막는다', (tester) async {
    api.recordsBySlug['mine'] = [
      RecordItem(
        id: 1,
        isbn13: '9788936434120',
        status: ReadingStatus.done,
        visibility: RecordVisibility.private,
        startedAt: '2026-09-20',
        finishedAt: '2026-09-01',
      ),
    ];
    await pumpApp(tester, api, initialLocation: '/record/1');

    await reveal(tester, find.text('다 읽은 날이 시작한 날보다 앞이에요'));
    expect(find.text('다 읽은 날이 시작한 날보다 앞이에요'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '저장'))
          .onPressed,
      isNull,
    );
  });

  testWidgets('날짜가 없으면 날짜 지우기를 보여주지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
    await reveal(tester, find.text('다 읽은 날'));
    expect(find.text('날짜 지우기'), findsNothing);
  });

  testWidgets('날짜 지우기는 두 날짜를 지우고 사라진다', (tester) async {
    api.recordsBySlug['mine'] = [
      RecordItem(
        id: 1,
        isbn13: '9788936434120',
        status: ReadingStatus.done,
        visibility: RecordVisibility.private,
        startedAt: '2026-09-01',
        finishedAt: '2026-09-20',
      ),
    ];
    await pumpApp(tester, api, initialLocation: '/record/1');
    await reveal(tester, find.text('2026. 9. 20'));
    expect(find.text('2026. 9. 1'), findsOneWidget);
    expect(find.text('2026. 9. 20'), findsOneWidget);
    await tester.tap(find.widgetWithText(OutlinedButton, '날짜 지우기'));
    await tester.pumpAndSettle();

    expect(find.text('2026. 9. 1'), findsNothing);
    expect(find.text('날짜 없음'), findsNWidgets(2));
    expect(find.text('날짜 지우기'), findsNothing);
  });

  Future<void> openReading(WidgetTester tester, {String? finishedAt}) async {
    api.recordsBySlug['mine'] = [
      RecordItem(
        id: 1,
        isbn13: '9788936434120',
        status: ReadingStatus.reading,
        visibility: RecordVisibility.private,
        finishedAt: finishedAt,
      ),
    ];
    await pumpApp(
      tester,
      api,
      initialLocation: '/record/1',
      overrides: [
        nowProvider.overrideWithValue(() => DateTime(2026, 10, 31, 23)),
      ],
    );
  }

  Future<void> answer(WidgetTester tester, String choice) async {
    await tester.tap(find.text('다 읽음'));
    await tester.pumpAndSettle();
    expect(find.text('언제 다 읽었어요?'), findsOneWidget);
    await tester.tap(find.text(choice));
    await tester.pumpAndSettle();
  }

  Future<void> save(WidgetTester tester) async {
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
  }

  testWidgets('다 읽음에서 오늘을 고르면 오늘을 다 읽은 날로 저장한다', (tester) async {
    await openReading(tester);
    await answer(tester, '오늘');
    await save(tester);

    expect(sent!.status, ReadingStatus.done);
    expect(sent!.finishedAt, '2026-10-31');
  });

  testWidgets('다 읽음에서 날짜를 고르면 그 날을 저장한다', (tester) async {
    await openReading(tester);
    await answer(tester, '날짜 고르기');
    await tester.tap(find.text('15'));
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
    await save(tester);

    expect(sent!.status, ReadingStatus.done);
    expect(sent!.finishedAt, '2026-10-15');
  });

  testWidgets('기억 안 나요는 날짜 없이 다 읽음으로 저장한다', (tester) async {
    await openReading(tester);
    await answer(tester, '기억 안 나요');
    await save(tester);

    expect(sent!.status, ReadingStatus.done);
    expect(sent!.finishedAt, isNull);
  });

  testWidgets('묻는 창을 닫으면 상태를 바꾸지 않는다', (tester) async {
    await openReading(tester);
    await tester.tap(find.text('다 읽음'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(find.text('언제 다 읽었어요?'), findsNothing);
    await save(tester);

    expect(sent!.status, ReadingStatus.reading);
    expect(sent!.finishedAt, isNull);
  });

  testWidgets('날짜 고르기를 취소하면 상태를 바꾸지 않는다', (tester) async {
    await openReading(tester);
    await answer(tester, '날짜 고르기');
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();
    await save(tester);

    expect(sent!.status, ReadingStatus.reading);
    expect(sent!.finishedAt, isNull);
  });

  testWidgets('다 읽은 날이 이미 있으면 묻지 않고 다 읽음으로 바꾼다', (tester) async {
    await openReading(tester, finishedAt: '2026-09-01');
    await tester.tap(find.text('다 읽음'));
    await tester.pumpAndSettle();
    expect(find.text('언제 다 읽었어요?'), findsNothing);
    await save(tester);

    expect(sent!.status, ReadingStatus.done);
    expect(sent!.finishedAt, '2026-09-01');
  });

  testWidgets('다 읽음에서 다른 상태로 바꿔도 고른 날은 남는다', (tester) async {
    await openReading(tester);
    await answer(tester, '오늘');
    await tester.tap(find.text('읽는 중'));
    await tester.pumpAndSettle();
    await save(tester);

    expect(sent!.status, ReadingStatus.reading);
    expect(sent!.finishedAt, '2026-10-31');
  });

  testWidgets('API 오류는 안내하고 입력 화면에 남는다', (tester) async {
    api.onUpdateRecord = (id, update) async => throw const ApiException(
      statusCode: 503,
      code: 'NETWORK',
      message: '연결을 확인해 주세요',
    );
    await pumpApp(tester, api, initialLocation: '/record/1');
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(find.text('연결을 확인해 주세요'), findsOneWidget);
    expect(find.textContaining('기록 고치기'), findsOneWidget);
  });
}
