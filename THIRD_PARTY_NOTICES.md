# Third-party components

- `core/`: xnet2lua, BSD-2-Clause. Pinned Git commit is recorded by the submodule.
- `core/3rd/minilua.h`: minilua / Lua 5.5, MIT license embedded at the end of the header.
- `core/3rd/yyjson.c`: yyjson, MIT license in the source header.
- `core/3rd/mbedtls3`: Mbed TLS, dual Apache-2.0 / GPL-2.0-or-later as stated in source headers;
  this application uses the Apache-2.0 option.
- `core/3rd/libdeflate`: libdeflate, MIT license in `COPYING`.
- Gradle wrapper: Apache-2.0, preserved source headers.
- Android app uses Kotlin standard library (Apache-2.0) and Android platform APIs.

Bundled Lua agent scripts are a snapshot of the sibling `codua` project, taken 2026-09-17.
No local model configuration, credentials, logs, sessions or personal skills are included.
The original script names (`xagent`) are retained to preserve module imports.

License texts for the bundled native libraries are also included in APK assets under `licenses/`.
