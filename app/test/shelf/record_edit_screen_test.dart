import 'package:bookshelf/api/api_error.dart';
import 'package:bookshelf/api/models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/fake_api.dart';
import '../support/harness.dart';

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
    await tester.tap(find.text('별점만'));
    await tester.tap(find.text('읽는 중'));
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

    expect(find.text('205/200'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(find.widgetWithText(TextButton, '저장'))
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

    expect(find.text('다 읽은 날이 시작한 날보다 앞이에요'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(find.widgetWithText(TextButton, '저장'))
          .onPressed,
      isNull,
    );
  });

  testWidgets('날짜가 없으면 날짜 지우기를 보여주지 않는다', (tester) async {
    await pumpApp(tester, api, initialLocation: '/record/1');
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
    await tester.tap(find.widgetWithText(TextButton, '날짜 지우기'));
    await tester.pumpAndSettle();

    expect(find.text('시작한 날'), findsOneWidget);
    expect(find.text('다 읽은 날'), findsOneWidget);
    expect(find.text('날짜 지우기'), findsNothing);
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
    expect(find.text('기록 고치기'), findsOneWidget);
  });
}
