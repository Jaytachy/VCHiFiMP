# Third-party notices

USB Direct is free software distributed under the GNU General Public License,
version 3 or (at your option) any later version. See [LICENSE](LICENSE).

This file lists third-party components included in, or depended on by, USB
Direct. Each component remains governed by its own license. The notices below
are provided for attribution and do not alter those licenses.

## Bundled components

### libsamplerate 0.2.2

- License: BSD 2-Clause
- Copyright: Copyright (c) 2012-2016, Erik de Castro Lopo
- Location: `android/app/src/main/cpp/libsamplerate/`
- License text: `android/app/src/main/cpp/libsamplerate/COPYING`
- Additional notice: `android/app/src/main/cpp/libsamplerate/docs/license.md`
- Upstream: <https://github.com/libsndfile/libsamplerate>

The vendored source retains its original copyright and license headers.

## Android dependencies

The following AndroidX and Kotlin components are distributed under the
Apache License, Version 2.0: <https://www.apache.org/licenses/LICENSE-2.0>.

| Component | Coordinate | Version | License |
| --- | --- | --- | --- |
| AndroidX Activity | `androidx.activity:activity-compose` | 1.9.3 | Apache-2.0 |
| AndroidX Lifecycle | `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | Apache-2.0 |
| AndroidX Compose BOM | `androidx.compose:compose-bom` | 2024.12.01 | Apache-2.0 |
| Compose UI | `androidx.compose.ui:ui`, `androidx.compose.ui:ui-tooling-preview` | managed by Compose BOM 2024.12.01 | Apache-2.0 |
| Material3 | `androidx.compose.material3:material3` | managed by Compose BOM 2024.12.01 | Apache-2.0 |
| Material Icons Extended | `androidx.compose.material:material-icons-extended` | managed by Compose BOM 2024.12.01 | Apache-2.0 |
| AndroidX Graphics Path | `androidx.graphics:graphics-path` | 1.1.0 | Apache-2.0 |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache-2.0 |

Copyright in these components is held by their respective contributors,
including The Android Open Source Project and JetBrains s.r.o.

## Test-only components

JUnit 4 (`junit:junit:4.13.2`) is used only for local unit testing. It is not
included in, or distributed with, the USB Direct application package. JUnit is
available under the Eclipse Public License 1.0.

## Acknowledgements

The raw usbfs claim and detach sequence used by this project was informed by
public documentation in the following projects:

- [ostrichbat2k/libusbdac](https://github.com/ostrichbat2k/libusbdac) — GPL-3.0
- [unom-io/usbfs-iso](https://github.com/unom-io/usbfs-iso) — Apache-2.0

The implementation in USB Direct is independent; no code was copied from either
project.
