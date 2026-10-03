import 'package:flutter/material.dart';

void main() => runApp(const BookShelfApp());

class BookShelfApp extends StatelessWidget {
  const BookShelfApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '책장',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
    ),
    home: const Scaffold(body: Center(child: Text('책장을 준비하고 있어요.'))),
  );
}
