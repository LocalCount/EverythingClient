# EverythingClient

An Android frontend client for browsing and downloading files from an Everything HTTP server, with a fast, modern UI.

This project was vibe coded using Claude, Codex, and Gemini.

## Everything Server (by voidtools)
Everything is a fast filename search engine for Windows by voidtools.
Official links:
- [Everything (voidtools)](https://www.voidtools.com/support/everything/index.html)
- [Everything HTTP Server docs](https://www.voidtools.com/en-au/support/everything/http/)
- [How to setup the plugin](https://voidtools.com/support/everything/options/#http_server)

## Features
- Fast search across indexed files with filter and sort controls.
- Scope search to current location or search globally.
- Queue downloads with progress, pause, and resume support.
- Dedicated queue screen for active, paused, and completed items.
- Multi-part downloads to improve throughput on large files.
- Server profiles with quick switching and connection testing.
- Download path selection with Storage Access Framework support.
- Theme options including light, dark, and AMOLED variants.

## Tech
- Kotlin + Jetpack Compose
- Hilt for DI
- Paging 3 for large result sets

## Screenshots
<table>
  <tr>
    <td>
      <a href="screenshots/01_search_screen_amoled.jpg">
        <img src="screenshots/thumbs/01_search_screen_amoled.jpg" alt="Search screen (AMOLED)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/01_search_screen_dark.jpg">
        <img src="screenshots/thumbs/01_search_screen_dark.jpg" alt="Search screen (dark)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/01_search_screen_light.jpg">
        <img src="screenshots/thumbs/01_search_screen_light.jpg" alt="Search screen (light)" width="200" />
      </a>
    </td>
  </tr>
  <tr>
    <td>
      <a href="screenshots/02_queue_screen_amoled.jpg">
        <img src="screenshots/thumbs/02_queue_screen_amoled.jpg" alt="Queue screen (AMOLED)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/02_queue_screen_dark.jpg">
        <img src="screenshots/thumbs/02_queue_screen_dark.jpg" alt="Queue screen (dark)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/02_queue_screen_light.jpg">
        <img src="screenshots/thumbs/02_queue_screen_light.jpg" alt="Queue screen (light)" width="200" />
      </a>
    </td>
  </tr>
  <tr>
    <td>
      <a href="screenshots/03_drawer_amoled.jpg">
        <img src="screenshots/thumbs/03_drawer_amoled.jpg" alt="Navigation drawer (AMOLED)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/03_drawer_dark.jpg">
        <img src="screenshots/thumbs/03_drawer_dark.jpg" alt="Navigation drawer (dark)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/03_drawer_light.jpg">
        <img src="screenshots/thumbs/03_drawer_light.jpg" alt="Navigation drawer (light)" width="200" />
      </a>
    </td>
  </tr>
  <tr>
    <td>
      <a href="screenshots/04_settings_screen_amoled.jpg">
        <img src="screenshots/thumbs/04_settings_screen_amoled.jpg" alt="Settings screen (AMOLED)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/04_settings_screen_dark.jpg">
        <img src="screenshots/thumbs/04_settings_screen_dark.jpg" alt="Settings screen (dark)" width="200" />
      </a>
    </td>
    <td>
      <a href="screenshots/04_settings_screen_light.jpg">
        <img src="screenshots/thumbs/04_settings_screen_light.jpg" alt="Settings screen (light)" width="200" />
      </a>
    </td>
  </tr>
</table>

## Build
1. Open the project in Android Studio.
2. Sync Gradle.
3. Generate APK.

## License
MIT License.
