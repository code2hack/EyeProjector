# Dark backgrounds for the monochrome display

Owner decision, 2026-10-20: v0.1 uses black/light EyeProjector UI plus native dark themes for projected apps. [SPEC.md](../../SPEC.md#monochrome-display-style) records the selected scope. Automatic background conversion and image inversion remain future work.

## Verified platform facts

The consumer Rokid Glasses family uses a monochrome green waveguide display, with 480×640 per eye in the [manufacturer's introduction](https://global.rokid.com/ja-jp/blogs/academy-glasses/glasses-1-1-overview). Rokid's [AIUI monochrome design guidance](https://github.com/yodaos-project/AIUI/blob/main/design/monochrome/design-system-green.md) describes black as the transparent floor, with bright content and semantics expressed through labels/shapes as well as luminance. Its beta 480×352 reference canvas is design guidance, not a replacement for the actual display geometry.

Android dark themes depend on app resources and implementation. Force Dark transforms eligible views and can be disabled by a theme or individual view. App-local night mode is available from API31; setting it for EyeProjector does not restyle an unrelated target app. A night-mode request alone therefore cannot establish a pure-black result across arbitrary apps. [Dark-theme behavior](https://developer.android.com/develop/ui/views/theming/darktheme), [application night-mode scope](https://developer.android.com/reference/android/app/UiModeManager#setApplicationNightMode(int))

Android can supply decoded video to an OpenGL ES texture through SurfaceTexture, available from API11, and sample that texture in a shader. Receiver-side luminance/inversion processing is therefore an implementation candidate on the API32 Glasses. The current [SurfaceDecoder](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/SurfaceDecoder.kt) instead configures MediaCodec with its supplied display Surface directly. This candidate would change the rendering path and needs latency and stability measurements. [SurfaceTexture interface](https://developer.android.com/reference/android/graphics/SurfaceTexture)

## Engineering assessment

| Content | Feasible direction | Limit |
| --- | --- | --- |
| EyeProjector Glasses controls and projected home | Explicit pure-black surfaces, light text/icons and a visible pointer | Physical readability still depends on ambient light and the display. |
| Supported target app with a suitable dark theme | Use its native dark presentation | Dark gray remains luminous; a dark-theme label does not prove a pure-black background. |
| App with a bright background | Future work: investigate receiver-side inversion and contrast/black-level processing | The final video contains pixels, not reliable UI/background labels. One transform cannot perfectly distinguish arbitrary UI from embedded imagery. |
| Photos, video and camera preview | Qualify their rendering separately | Whole-frame inversion also inverts imagery; aggressive thresholds can remove useful detail. |

The image-processing options above are future research candidates. A simple inversion can turn black-on-white text into light-on-black text, while also turning a dark interface bright and photographs into negatives. Automatic selection or regional treatment would be additional engineering, not an existing backend capability established here.

v0.1 acceptance covers explicit black/light EyeProjector UI and native dark presentation in chosen target apps, recording any remaining gray or bright surfaces and media readability. On the optical display, black means no added light; it cannot obscure a bright real-world background. See the linked Rokid design guidance.

No display setting, system-wide theme, app implementation or device was changed. No shader prototype, physical readability, performance or target-app compatibility test was performed.
