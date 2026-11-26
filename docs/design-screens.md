# Design screens

A design screen is a screen of the app drawn to a PNG without a phone, an emulator or a browser, in the app's real theme and with the app's real composables. The designer draws one to show Victor what a change will look like; the reviewer later compares what was built with the image Victor approved. A PNG is not a capture from a phone: it has no status bar, no navigation bar and no system font, and the platform's own chrome (the lock screen, the permission sheets) is not in it.

## Draw them

```sh
just screenshots
```

It builds the core as `just android` does (so it needs the same tools), runs the tests in `android/app/src/test/kotlin/io/github/victorbonnet/enclos/screenshots/`, and prints the path of each PNG. The images are in `android/app/build/screenshots/<screen>/`, which git ignores. Two runs on one machine give identical files.

The inbox is drawn six ways, `inbox-<theme>-<phone>[-font-<scale>].png`:

| Theme | Phone | Font |
| --- | --- | --- |
| light, dark | small (360 × 640 dp at 3×) | 1 |
| light, dark | large (412 × 915 dp at 3×) | 1 |
| light, dark | small | 1.3 |

## How it works

The screens are drawn by [Roborazzi](https://github.com/takahirom/roborazzi) on Robolectric's native graphics, the same Robolectric the unit tests use. Roborazzi is a test dependency only (`testImplementation`): it is on no runtime or release classpath, and its Gradle plugin is not used. Without `-Penclos.screenshots`, which only `just screenshots` passes, the screenshot tests skip themselves, so `just android` and CI draw nothing.

## Add a design screen

1. Make a test class in the `screenshots` package that runs with `RobolectricTestRunner` and `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. Copy `InboxScreenshotTest` and change what it draws.
2. Build a model of made-up data, as `InboxScreenshotTest.fixture` does: no real host, agent name, address or message, and nothing from a person's phone.
3. Call `compose.drawn("<screen>", Look(Phone.Small, dark = false)) { ... }` once per way to draw it. `drawn` sets the phone's size and the font size, wraps the content in `EnclosTheme`, waits for the screen to settle and writes the PNG.
4. Keep it the same twice. Anything that reads the clock, a random number or the machine makes a different image each run. The inbox's "2 hours ago" is the clock: the fixture times are hours before the time the test starts, so the label never changes. Pick such offsets away from a boundary (2 hours 30 minutes, not 2 hours exactly).
5. Run `just screenshots` twice and compare the files' hashes (`sha256sum`).

A screen that needs a bridge's answer, a phone service or a system sheet can only be drawn as far as the test can fake it; say in the pull request what the image leaves out.
