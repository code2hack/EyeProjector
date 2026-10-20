# Third-party components

Only the projection modules and their fixtures are included. Source import and modifications are recorded in [the provenance record](../docs/provenance/README.md); this bootstrap makes no new blanket license grant for first-party code.

| Component | Source / use | License and attribution |
| --- | --- | --- |
| scrcpy 5.0.1 | [Exact source a60891aea193d92e7e5c3942700eca63f9d19a5f](https://github.com/Genymobile/scrcpy/tree/a60891aea193d92e7e5c3942700eca63f9d19a5f); unmodified external Android server; framing/control implementation follows its protocol | Copyright Genymobile; [Apache 2.0](scrcpy-LICENSE.txt), copied unchanged from that source. Upstream root has no NOTICE file. |
| Gradle wrapper / distribution 8.11.1 | Checked-in standard wrapper JAR/scripts; wrapper distribution is checksum-pinned | Copyright Gradle contributors; Apache 2.0, notices retained in scripts/JAR. [Upstream license](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE). |
| Kotlin standard library 2.2.21 | Android app transitive runtime dependency | Copyright JetBrains and Kotlin contributors; Apache 2.0. [Exact source](https://github.com/JetBrains/kotlin/tree/v2.2.21). |
| Shizuku API/provider 13.1.5 | Phone-controlled shell UserService bootstrap | Copyright 2021 RikkaW; [MIT](Shizuku-LICENSE.txt), copied from [upstream](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE). |
| AndroidX annotation 1.9.1 | Keep the Shizuku-reflected service constructor and class | Apache 2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/annotation/). |
| JetBrains annotations 13.0 | Kotlin standard library transitive dependency | Apache 2.0; [upstream](https://github.com/JetBrains/java-annotations). |
| Bouncy Castle Java 1.83 (`bcpkix-jdk18on`, `bcprov-jdk18on`, `bcutil-jdk18on`) | J-PAKE password-authenticated pairing and local self-signed X.509 identity generation | Copyright The Legion of the Bouncy Castle Inc.; [upstream MIT-style license](https://github.com/bcgit/bc-java/blob/r1rv83/LICENSE.html). License retained in dependency JARs. |
| JUnit 4.13.2 | JVM tests only | Eclipse Public License 1.0; [source/license](https://github.com/junit-team/junit4/tree/r4.13.2). |
| Hamcrest Core 1.3 | JUnit transitive test dependency | BSD 3-Clause; [source/license](https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3). |

AGP 8.7.3 and Kotlin plugin 2.2.21 are build tools, resolved from the repositories declared in `settings.gradle.kts`. Android framework media, UI and TLS APIs come from the platform. There is no bundled browser engine, proxy, subscription service or separate native media library.

`tools/fetch-scrcpy.sh` fetches only the official release asset `scrcpy-server-v5.0.1` (733,930 bytes) and verifies SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`. `ShellMain` checks those same bytes before bootstrap and each session copy. The server is not redistributed in Git or modified. The Phone host APK packages the verified server, its Apache license, the Shizuku MIT license and this notice for Phone-controlled bootstrap.

The two owned MP4 fixtures contain synthetic video/text and a generated tone. Their generator is `tools/generate-fixture.sh`; no downloaded, camera, microphone or private media is included. FFmpeg is an optional host tool for regenerating these assets and is not shipped in the apps.
