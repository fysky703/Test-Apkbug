import 'package:flutter/services.dart';

/// Direct Android controls. These actions do not use AI, Accessibility
/// scrolling, or screen element detection.
class QuickControlsService {
  static const MethodChannel _channel =
      MethodChannel('com.privateagent/quick_controls');

  Future<bool> isDeviceAdminActive() async {
    return await _channel.invokeMethod<bool>('isDeviceAdminActive') ?? false;
  }

  Future<void> requestDeviceAdmin() async {
    await _channel.invokeMethod('requestDeviceAdmin');
  }

  Future<String> lockScreen() async {
    return await _channel.invokeMethod<String>('lockScreen') ?? 'Unknown result';
  }

  Future<String> wakeScreen() async {
    return await _channel.invokeMethod<String>('wakeScreen') ?? 'Unknown result';
  }

  Future<bool> hasCameraPermission() async {
    return await _channel.invokeMethod<bool>('hasCameraPermission') ?? false;
  }

  Future<String> requestCameraPermission() async {
    return await _channel.invokeMethod<String>('requestCameraPermission') ??
        'Permission request started';
  }

  Future<String> setFlash(bool enabled) async {
    return await _channel.invokeMethod<String>('setFlash', {'enabled': enabled}) ??
        'Unknown result';
  }

  Future<String> playAlert() async {
    return await _channel.invokeMethod<String>('playAlert') ?? 'Unknown result';
  }

  Future<String> vibrate() async {
    return await _channel.invokeMethod<String>('vibrate') ?? 'Unknown result';
  }
}
