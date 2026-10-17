NVCS 3000 MOBILE — ANDROID STUDIO PROJECT (EARLY PORT)

WHAT THIS PROJECT IS
- A native Android starter app with CameraX camera preview.
- Camera initializes when the app launches; press POWER ON to reveal the live feed after a green-static transition.
- Silent 0.8-second power toggle cooldown.
- Low Ambience toggle uses 15% ambience volume and 5x simulated battery drain, with a smooth 450 ms volume transition.
- Original audio, images, and model assets from your uploaded ZIP are bundled under app/src/main/assets/nvcs/.
- Your original main.py and main1.py are preserved under legacy_source/ for reference.

IMPORTANT LIMITATIONS
- This is an early mobile port, NOT a complete conversion of the desktop scanner.
- The desktop Python/Pygame/OpenCV modules, YOLO model inference, Ransom gameplay, and all desktop features have not yet been ported into the native Android app. The YOLO .pt file is bundled but is not used by this starter app.
- The camera and basic UI need to be built and tested on an Android device. No APK was compiled in this environment.
- Android Studio must download Gradle and CameraX dependencies, so a stable connection may be required once.

BUILD ON WINDOWS
1. Extract this ZIP.
2. Install Android Studio and a JDK 17-compatible Android Studio setup.
3. Open the extracted NVCS3000Mobile folder (the folder containing settings.gradle.kts).
4. Let Gradle sync and install Android SDK Platform 35 if prompted.
5. Build > Build Bundle(s) / APK(s) > Build APK(s).
6. Transfer the generated APK to your phone and install it. Grant camera permission when asked.

If Gradle sync reports an error, copy the first actual error text so the project can be corrected.
