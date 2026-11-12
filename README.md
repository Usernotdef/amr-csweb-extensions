# AmrStream

A dedicated CloudStream extension for Movy.sx: movies, TV series, anime, and playback with subtitle support.

## What it includes
- Movie and TV metadata browsing
- TV series and anime episode listings
- Playback source resolution from Movy providers
- Subtitle support from provider sources
- Build automation for a clean AmrStream-only repository

## Repository purpose
This repository is intentionally kept focused on the AmrStream extension only. It is not a generic multi-plugin fork.

## Build
```bash
./gradlew :AmrStream:make --no-daemon --stacktrace
```

## Release manifest
The repository manifest points to `plugins.json` and the built `AmrStream.cs3` package.

## Notes
- The project is based on the working AmrStream v10 source.
- This README is intentionally concise and project-specific.
