import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_doc_scanner/flutter_doc_scanner.dart';
import 'package:path_provider/path_provider.dart';


  final GlobalKey<ScaffoldMessengerState> scaffoldMessengerKey =
    GlobalKey<ScaffoldMessengerState>();
  void main() {
    runApp( const MyApp());
  }

  class MyApp extends StatelessWidget {
    const MyApp({super.key});

    @override
    Widget build(BuildContext context) {
      return MaterialApp(
        home: HomePage(),
      );
    }
  }



class HomePage extends StatefulWidget {
  HomePage({Key? key}) : super(key: key);

  @override
  State<HomePage> createState() => _HomePageState();
  
}

class _HomePageState extends State<HomePage> {
  List<File> _documents = [];

  @override
  void initState() {
    super.initState();
    _loadDocuments();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(
          title: const Text('Document Scanner example app'),
        ),
        body: _documents.isEmpty
        ? const Center(child: Text('No hay documentos'))
        : RefreshIndicator(
          onRefresh: _loadDocuments,
          child: ListView.builder(
            itemCount: _documents.length,
            itemBuilder: (_, index) {
              final file = _documents[index];
              final name = file.path.split('/').last;
        
              return ListTile(
                leading: Image.file(
                  file,
                  width: 50,
                  height: 70,
                  fit: BoxFit.cover,
                ),
                title: Text('${index + 1} -   $name'),
                subtitle: Text(
                  DateTime.fromMillisecondsSinceEpoch(
                    file.lastModifiedSync().millisecondsSinceEpoch,
                  ).toString(),
                ),
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => Scaffold(
                        appBar: AppBar(title: const Text('Imagen')),
                        body: Center(child: Image.file(file)),
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
        floatingActionButton: Padding(
          padding: const EdgeInsets.only(bottom: 16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: ElevatedButton(
                  onPressed: () {

                    
                    // scanDocumentAsImages();
                    _scanDocument( context);
                  },
                  child: const Text("Scan Documents As Image"),
                ),
              ),

            ],
          ),
        ),
    );
  }

    Future<void> _loadDocuments() async {
    final dir = await getApplicationDocumentsDirectory();

    final files = dir
        .listSync()
        .whereType<File>()
        .where((f) =>
            f.path.endsWith('.jpg') || f.path.endsWith('.png'))
        .toList();

    files.sort(
      (a, b) => b.lastModifiedSync().compareTo(a.lastModifiedSync()),
    );

    if (!mounted) return;

    setState(() {
      _documents = files;
    });
  }

    Future<void> _scanDocument(BuildContext contextVoid) async {

    List<File> files = [];

    // Page nos permite el numero de fotos permitidas
    final result = await FlutterDocScanner().getScannedDocumentAsImages(
      page : 3
    );

    if (result == null){

      ScaffoldMessenger.of(contextVoid).showSnackBar(
        const SnackBar(
          content: Text('1. Error al escanear documentos, Reintente nuevamente'),
          duration: Duration(seconds: 2),
        ),
      );
      return; 
    }

    final uris = result['Uri'] ?? '';
    final int count = result['Count'] ?? 0;

    if (count < 1 || uris.isEmpty){
      ScaffoldMessenger.of(contextVoid).showSnackBar(
        const SnackBar(
          content: Text('2. Error al escanear documentos, Reintente nuevamente'),
          duration: Duration(seconds: 2),
        ),
      );
      return;
    }


    files = uris
        .map<File>((u) => File(Uri.parse(u.toString()).toFilePath()))
        .toList();

    if (files.isEmpty){
      ScaffoldMessenger.of(contextVoid).showSnackBar(
        const SnackBar(
          content: Text('3. Error al escanear documentos, Reintente nuevamente'),
          duration: Duration(seconds: 2),
        ),
      );
      return;
    }

    // iOS devuelve 3 imágenes (original, recortada, mejorada)
    // Nos quedamos solo con la recortada
    if (Platform.isIOS ) {
      files = pickCroppedIosImages(files);

    } 

     ScaffoldMessenger.of(contextVoid).showSnackBar(
      const SnackBar(
        content: Text('4. Error al escanear documentos, Reintente nuevamente'),
        duration: Duration(seconds: 2),
      ),
    );

    final List<File> persisted = [];
    for (final file in files) {
      persisted.add(await _persistImage(file));
    }

     ScaffoldMessenger.of(contextVoid).showSnackBar(
      const SnackBar(
        content: Text('5. Error al escanear documentos, Reintente nuevamente'),
        duration: Duration(seconds: 2),
      ),
    );

    if (!mounted) return;

    ScaffoldMessenger.of(contextVoid).showSnackBar(
      const SnackBar(
        content: Text('6. Error al escanear documentos, Reintente nuevamente'),
        duration: Duration(seconds: 2),
      ),
    );

    setState(() {
      _documents.addAll(persisted);
    });

    await _loadDocuments();

  }

  List<File> pickCroppedIosImages(List<File> files) {
    final List<File> cropped = [];

    for (int i = 0; i < files.length; i++) {
      // cada bloque de 3, tomamos el segundo
      if (i % 3 == 1) {
        cropped.add(files[i]);
      }
    }

    return cropped;
  }

  // METODO para eliminar las fotos de CACHE unicamente para iOS
  Future<File> _persistImage(File image) async {
    final dir = await getApplicationDocumentsDirectory();
    final newPath = '${dir.path}/${DateTime.now().millisecondsSinceEpoch}.jpg';

    return image.copy(newPath);
  }

}

