import 'package:flutter/material.dart';

/// 표지 정보가 없거나 이미지를 못 받으면 ISBN을 대신 표시한다.
class Cover extends StatelessWidget {
  const Cover({super.key, this.url, required this.isbn, this.width = 48});

  final String? url;
  final String isbn;
  final double width;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      key: Key('cover-placeholder-$isbn'),
      width: width,
      height: width * 1.45,
      color: Colors.grey.shade300,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(2),
      child: Text(
        isbn,
        style: const TextStyle(fontSize: 7),
        textAlign: TextAlign.center,
      ),
    );
    if (url == null || url!.isEmpty) return placeholder;
    return Image.network(
      url!,
      width: width,
      height: width * 1.45,
      fit: BoxFit.cover,
      errorBuilder: (_, _, _) => placeholder,
    );
  }
}
