# Contributing to SMPLink

Thanks for helping out! Bug reports, ideas and pull requests are all welcome.

## Getting started

1. Fork the repo and clone your fork.
2. You need Java 21. Build and run the tests with:
   ```
   ./gradlew build
   ```
   The plugin jar ends up in `build/libs/SMPLink.jar`.
3. To try it on a server, copy the jar into a Paper 1.21+ server's `plugins/` folder, and set a test bot's token
   and server ID in `plugins/SMPLink/config.yml`. Use a separate test bot and Discord server, never a live one.

## Pull requests

- Keep each pull request to one change, and say what it fixes or adds.
- Add or update a test for behaviour you change. Tests live in `src/test/java`, next to the class they test.
- `./gradlew build` must pass. GitHub runs it on every pull request too.
- Don't commit tokens, server IDs or Minecraft's textures. The plugin reads all of those from the server's own files.

## Where to start

Issues labelled **good first issue** are small and self-contained. Comment on one before you start, so two people
don't work on the same thing.

## Code style

Match the code around you: 4-space indents, short methods, and comments that say *why*.
