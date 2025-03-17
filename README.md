# Nyarlathotep

## Features

- Join random 1.12.2 servers regardless of mods
- Most vanilla things should still work

## Build Instructions:

1. Clone this repository
2. Import the `build.gradle` into your IDE (most preferably IntelliJ IDEA)
3. Once the import has finished, run `gradlew setup`
4. Build with `gradlew build`

## Development Tips:

- Only modify `projects/cleanroom/src/` directory if you want to change vanilla
- Run `gradlew genPatches` before commit, or the changes won't exist
- Modifications on `src/` doesn't need generating patches
