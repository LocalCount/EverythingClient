<h1><strong>EverythingClient</strong></h1>

An Android frontend client for browsing and downloading files from an Everything HTTP server, with a fast, modern UI.

<h2><strong>Everything Server (by voidtools)</strong></h2>
Everything is a fast filename search engine for Windows by voidtools.
Official links:
- [Everything (voidtools)](https://www.voidtools.com/support/everything/index.html)
- [Everything HTTP Server docs](https://www.voidtools.com/en-au/support/everything/http/)
- [Everything Options (HTTP Server tab)](https://voidtools.com/support/everything/options/)

<h2><strong>HTTP Server</strong></h2>
Everything includes a built-in HTTP server so you can search and access files from a web browser (alpha versions do not come built-in plugins anymore).
Example URLs:
- [http://localhost:8080](http://localhost:8080)
- [http://ComputerName](http://ComputerName)

<h2><strong>Features</strong></h2>
- Fast search across indexed files with filter and sort controls.
- Scope search to current location or search globally.
- Queue downloads with progress, pause, and resume support.
- Dedicated queue screen for active, paused, and completed items.
- Multi-part downloads to improve throughput on large files.
- Server profiles with quick switching and connection testing.
- Download path selection with Storage Access Framework support.
- Theme options including light, dark, and AMOLED variants.

<h2><strong>Tech</strong></h2>
- Kotlin + Jetpack Compose
- Hilt for DI
- Paging 3 for large result sets

<h2><strong>Vibe Coding Note</strong></h2>
This project was vibe coded using Claude, Codex, and Gemini.

<h2><strong>Screenshots</strong></h2>
<p float="left">
  <img src="Screenshots/Screenshot_20260314-125815_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125823_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125833_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125843_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125902_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125906_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125925_EverythingClient.jpg" width="24%" />
  <img src="Screenshots/Screenshot_20260314-125934_EverythingClient.jpg" width="24%" />
</p>

<h2><strong>Build</strong></h2>
1. Open the project in Android Studio.
2. Sync Gradle.
3. Generate APK.

<h2><strong>License</strong></h2>
MIT License.
