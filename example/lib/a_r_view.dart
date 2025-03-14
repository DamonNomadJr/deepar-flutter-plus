import 'dart:developer';
import 'dart:io';

import 'package:deepar_flutter_plus/deepar_flutter_plus.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

class ARView extends StatefulWidget {
  const ARView({
    super.key,
  });

  @override
  State<ARView> createState() => _ARViewState();
}

class _ARViewState extends State<ARView> {
  final DeepArController _controller = DeepArController();
  bool isInitialized = false;
  String? _localEffectPath;
  final String effectURL = 'YOUR_EFFECT_URL_HERE';

  Future<String> _downloadAndSaveEffect() async {
    try {
      // TODO: Implement caching using flutter_cache_manager package to:
      //  - Reduce bandwidth usage by caching downloaded effects
      //  - Handle offline access to previously downloaded effects

      // Get the documents directory
      final appDir = await getApplicationDocumentsDirectory();
      final effectsDir = Directory('${appDir.path}/deepar/effects');

      // Create effects directory if it doesn't exist
      if (!await effectsDir.exists()) {
        await effectsDir.create(recursive: true);
      }

      // Generate unique filename based on URL
      final fileName = 'effect_${DateTime.now().millisecondsSinceEpoch}.deepar';
      final localPath = '${effectsDir.path}/$fileName';

      // Check if file already exists
      if (await File(localPath).exists()) {
        return localPath;
      }

      // Download the file
      final response = await http.get(
        Uri.parse(
          effectURL,
        ),
      );
      if (response.statusCode != 200) {
        throw Exception('Failed to download effect: ${response.statusCode}');
      }

      // Save the file
      final file = File(localPath);
      await file.writeAsBytes(response.bodyBytes);

      log('Effect downloaded successfully to: $localPath');
      return localPath;
    } catch (e, s) {
      log('Error downloading effect: $e', stackTrace: s);
      rethrow;
    }
  }

  @override
  void initState() {
    _initializeAR();
    super.initState();
  }

  Future<void> _initializeAR() async {
    try {
      // Initialize DeepAR
      await _controller.initialize(
        androidLicenseKey: "<YOUR-ANDROID-LICENSE-KEY>",  // Add placeholder
        iosLicenseKey: "<YOUR-IOS-LICENSE-KEY>",  // Add placeholder
        resolution: Resolution.medium,
      );
      // Download and get local path
      _localEffectPath = await _downloadAndSaveEffect();

      _controller.switchEffect(_localEffectPath);
      Future.delayed(
        const Duration(seconds: 2),
        () {
          setState(() {
            isInitialized = true;
          });
        },
      );
    } catch (e, s) {
      log('Error initializing AR: $e', stackTrace: s);
    }
  }

  @override
  void dispose() {
    _controller.destroy();

    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return isInitialized
        ? Transform.scale(
            scale: _controller.aspectRatio * 1.3, //change value as needed
            child: DeepArPreview(_controller),
          )
        : const Center(
            child: CircularProgressIndicator(),
          );
  }
}
