# <img src="src/main/resources/images/mapmode/river_icon.svg" height="32"> RiverTracer Plugin for JOSM

![Java Version](https://img.shields.io/badge/JDK-21-informational) ![Build Tool](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle) [![CodeFactor](https://www.codefactor.io/repository/github/comet-builds/rivertracer/badge)](https://www.codefactor.io/repository/github/comet-builds/rivertracer)

RiverTracer is a JOSM plugin that helps map rivers by semi-automatically tracing their path from imagery.

![Screenshot of the plugin in action](https://i.imgur.com/nuHbunR.jpeg)

## Installation

1. Download the latest `RiverTracer.jar` from releases.
2. Copy the file to your JOSM plugins directory.
3. Restart JOSM.
4. Enable the plugin in preferences if not automatically enabled.

## Usage

1. Download map data.
2. Select a high-contrast background imagery layer.
3. Select the RiverTracer tool from the toolbar (Shortcut: `Ctrl+G`) or "Mode" menu.
4. Hover over the center of a river to select the best starting point. Adjust tracing parameters if needed.
5. The plugin will generate a tagged way.

> **Important**: This tool is an assistant, not a replacement for human judgment.
> * Always verify that the generated line matches the imagery.
> * The algorithm may produce excessive nodes. It is highly recommended to run Simplify Way (Shortcut: Shift+Y) after tracing.

## Building from Source

1. Ensure JDK 21 is installed.
2. Clone the repository.
3. Run the build command:
   ```sh
   gradlew clean build
   ```
4. The compiled plugin will be located in the `build/dist` folder.

## Disclaimer
This plugin was originally developed to streamline my personal workflow. The codebase was generated and iteratively refactored using AI assistance, including specific review cycles to align with Java conventions. While I have verified its functionality, I am sharing it in its current state for the benefit of others who might find it useful.
