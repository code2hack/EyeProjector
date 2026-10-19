# Third-party components

Only the projection modules and their fixtures are included. Source import and modifications are recorded in [the provenance record](../docs/provenance/README.md); this bootstrap makes no new blanket license grant for first-party code.

| Component | Source / use | License and attribution |
| --- | --- | --- |
| scrcpy 5.0.1 | [Exact source a60891aea193d92e7e5c3942700eca63f9d19a5f](https://github.com/Genymobile/scrcpy/tree/a60891aea193d92e7e5c3942700eca63f9d19a5f); unmodified external Android server; framing/control implementation follows its protocol | Copyright Genymobile; [Apache 2.0](scrcpy-LICENSE.txt), copied unchanged from that source. Upstream root has no NOTICE file. |
| Gradle wrapper / distribution 8.11.1 | Checked-in standard wrapper JAR/scripts; wrapper distribution is checksum-pinned | Copyright Gradle contributors; Apache 2.0, notices retained in scripts/JAR. [Upstream license](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE). |
| Kotlin standard library 2.2.21 | Android app transitive runtime dependency | Copyright JetBrains and Kotlin contributors; Apache 2.0. [Exact source](https://github.com/JetBrains/kotlin/tree/v2.2.21). |
| JetBrains annotations 13.0 | Kotlin standard library transitive dependency | Apache 2.0; [upstream](https://github.com/JetBrains/java-annotations). |
| JUnit 4.13.2 | JVM tests only | Eclipse Public License 1.0; [source/license](https://github.com/junit-team/junit4/tree/r4.13.2). |
| Hamcrest Core 1.3 | JUnit transitive test dependency | BSD 3-Clause; [source/license](https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3). |

AGP 8.7.3 and Kotlin plugin 2.2.21 are build tools, resolved from the repositories declared in `settings.gradle.kts`. Android framework media, UI and TLS APIs come from the platform. There is no bundled browser engine, proxy, subscription service or separate native media library.

`tools/fetch-scrcpy.sh` fetches only the official release asset `scrcpy-server-v5.0.1` (733,930 bytes) and verifies SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`. `ShellMain` checks those same bytes before bootstrap and each session copy. The server is not redistributed in Git or modified. Its Apache license is included for reproducibility and attribution.

The two owned MP4 fixtures contain synthetic video/text and a generated tone. Their generator is `tools/generate-fixture.sh`; no downloaded, camera, microphone or private media is included. FFmpeg is an optional host tool for regenerating these assets and is not shipped in the apps.
