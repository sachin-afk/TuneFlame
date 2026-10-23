# TuneHive

TuneHive is an offline-first Android music player for local audio files.

## Features

- Browse audio files from the device library
- Play local music with Media3 / ExoPlayer
- Queue, shuffle, repeat, and playback-position restoration
- Favorites, play counts, and user playlists
- Album, artist, folder, and recommendation views
- Playback speed and pitch controls
- Device equalizer integration when supported
- Sleep timer
- Home-screen playback widget
- Android Auto / media browser integration

## Technology

- Kotlin
- Jetpack Compose and Material 3
- AndroidX Media3
- Room
- DataStore Preferences
- Kotlin Coroutines
- Coil

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android device or emulator running API 26 or newer

## Build

From the project root:

```text
gradlew.bat :app:assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Run

1. Open the project in Android Studio.
2. Allow Gradle synchronization to complete.
3. Connect an Android device or start an emulator.
4. Run the `app` configuration.
5. Grant the requested music/audio permission.

The app reads local audio through Android MediaStore. It does not require network access for core playback.

## Project structure

```text
app/src/main/java/com/example/offlinemusic/
  MainActivity.kt          Compose UI
  PlaybackService.kt       Media3 playback service
  MusicRepository.kt       MediaStore library scanning
  MusicDatabase.kt         Room entities and DAO
  MusicApp.kt              Application and DataStore setup
  PlaybackRestoration.kt   Queue and playback restoration
  QueuePersistence.kt      Saved queue storage
  MusicWidget.kt           Home-screen widget
```

## Repository hygiene

Local IDE settings, Gradle caches, generated builds, APKs, and `local.properties` are excluded by [`.gitignore`](.gitignore). Do not commit signing keys, passwords, API tokens, or machine-specific configuration.

## License

This repository is intended for private use. No license for redistribution or reuse is granted unless one is added separately.
