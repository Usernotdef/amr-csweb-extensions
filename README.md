# AmrStream

AmrStream is a dedicated CloudStream extension for Movy.sx. It focuses on movie, TV series, and anime discovery with playback and subtitle support.

## What is included
- Movie and TV metadata browsing
- TV series and anime episode listings
- Provider-backed playback source resolution
- Subtitle delivery from the provider response stream
- A single-purpose repository layout for the working v10 implementation

## Build
```bash
./gradlew :AmrStream:make --no-daemon --stacktrace
```

## Repository status
This project is kept as an AmrStream-only plugin repository and is not a general multi-plugin fork.

## Notes
- The active implementation is the v10 working source in `AmrStream/src/main/kotlin/com/amrstream`.
- Use the repository manifest (`repo.json`) and plugin manifest (`plugins.json`) to publish the built `AmrStream.cs3` package.
