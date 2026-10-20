package com.code2hack.eyeprojector.directrg

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Math/gesture evidence only; synthetic poses and keys do not prove hardware ergonomics. */
class ProjectorInputTest {
    private fun pose(ms: Long, yaw: Double = 0.0, pitch: Double = 0.0): RotationSample {
        val y = Math.toRadians(yaw) / 2
        val p = Math.toRadians(pitch) / 2
        return RotationSample(ms * 1_000_000, (cos(y) * sin(p)).toFloat(), (sin(y) * cos(p)).toFloat(),
            (-sin(y) * sin(p)).toFloat(), (cos(y) * cos(p)).toFloat())
    }
    private fun pointer(rotation: Int = 0, gain: Double = 2.0) = HeadPointerModel().apply {
        resize(PointerBounds(8f, 8f, 472f, 632f), rotation, 32f)
        sensitivity(gain, 0); start(); sample(pose(1), 1_000_000)
    }
    private fun settle(model: HeadPointerModel, yaw: Double, pitch: Double = 0.0) {
        for (t in 21L..1001L step 20) { model.sample(pose(t, yaw, pitch), t * 1_000_000); model.advance(t * 1_000_000) }
    }
    @Test fun headLocalAxesApplyInEveryDisplayRotation() {
        val yawDirections = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        val pitchDirections = listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
        fun direction(model: HeadPointerModel, expected: Pair<Int, Int>, sign: Int, description: String) {
            val dx = model.position.x - 240f; val dy = model.position.y - 320f
            if (expected.first == 0) assertEquals(description, 0f, dx, .1f)
            else assertTrue("$description: x=$dx", dx * expected.first * sign > 5f)
            if (expected.second == 0) assertEquals(description, 0f, dy, .1f)
            else assertTrue("$description: y=$dy", dy * expected.second * sign > 5f)
        }
        for (rotation in 0..3) for (sign in listOf(-1, 1)) {
            val yaw = pointer(rotation); settle(yaw, -sign * 5.0)
            direction(yaw, yawDirections[rotation], sign, "rotation=$rotation head right sign=$sign")
            val pitch = pointer(rotation); settle(pitch, 0.0, -sign * 5.0)
            direction(pitch, pitchDirections[rotation], sign, "rotation=$rotation head down sign=$sign")
        }
    }
    @Test fun gainAndRotationRetainScale() {
        val standard = pointer(gain = 1.0); settle(standard, -5.0)
        val twice = pointer(); settle(twice, -5.0)
        assertEquals((standard.position.x - 240) * 2, twice.position.x - 240, .1f)
        val rotated = pointer(rotation = 1); settle(rotated, -5.0)
        assertTrue(rotated.position.y > 320)
        assertEquals(240f, rotated.position.x, .1f)
    }
    @Test fun headMotionKeepsItsAxesAfterRollSinceStartup() {
        fun afterRoll(rollDegrees: Double, yaw: Double, pitch: Double): PointerPosition {
            val model = pointer()
            val radians = Math.toRadians(rollDegrees) / 2
            val roll = HeadOrientation(0.0, 0.0, sin(radians), cos(radians))
            fun sample(t: Long, orientation: HeadOrientation) {
                model.sample(RotationSample(t * 1_000_000, orientation.x.toFloat(), orientation.y.toFloat(),
                    orientation.z.toFloat(), orientation.w.toFloat()), t * 1_000_000)
                model.advance(t * 1_000_000)
            }
            for (t in 21L..1001L step 20) sample(t, roll)
            val moved = roll * checkNotNull(HeadOrientation.from(pose(1, yaw, pitch)))
            for (t in 1021L..2001L step 20) sample(t, moved)
            return model.position
        }
        for (roll in listOf(0.0, 180.0, -180.0, 90.0, -90.0)) for (sign in listOf(-1, 1)) {
            val yaw = afterRoll(roll, -sign * 5.0, 0.0)
            assertTrue("roll=$roll local yaw sign=$sign: $yaw", (yaw.x - 240) * sign > 5)
            assertEquals(320f, yaw.y, .1f)
            val pitch = afterRoll(roll, 0.0, -sign * 5.0)
            assertTrue("roll=$roll local pitch sign=$sign: $pitch", (pitch.y - 320) * sign > 5)
            assertEquals(240f, pitch.x, .1f)
        }
    }
    @Test fun observedWornRightTurnMovesRight() {
        // Rounded sensorservice samples captured while the Owner held forward, then right.
        // This is a regression fixture, not a replacement for physical verification.
        val model = pointer()
        model.stop(); model.start()
        model.sample(RotationSample(1_000_000, .49f, .46f, .53f, .51f), 1_000_000)
        for (t in 21L..1001L step 20) {
            model.sample(RotationSample(t * 1_000_000, .55f, .29f, .41f, .66f), t * 1_000_000)
            model.advance(t * 1_000_000)
        }
        assertTrue("Observed right turn: ${model.position}", model.position.x > 240f)
    }
    @Test fun stripsStayBoundedAndInwardTravelImmediatelyReturns() {
        val model = pointer(); settle(model, 0.0, -80.0)
        assertEquals(664f, model.position.y, .1f)
        for (t in 1021L..1221L step 20) { model.sample(pose(t, 0.0, -76.0), t * 1_000_000); model.advance(t * 1_000_000) }
        assertTrue(model.position.y < 640)
        assertEquals(8f, model.motionBounds.left, 0f)
        assertEquals(-24f, model.motionBounds.top, 0f)
    }
    @Test fun sensorLossStopAndRestartDiscardOldAim() {
        val model = pointer(); settle(model, -12.0)
        model.advance(1_251_000_000); assertFalse(model.position.available)
        assertTrue(model.sample(pose(1300, -70.0), 1_300_000_000))
        assertEquals(240f, model.position.x, .1f)
        model.stop(); assertFalse(model.sample(pose(1400), 1_400_000_000))
        model.start(); assertFalse(model.position.available)
        assertFalse(model.sample(pose(1500).copy(x = Float.NaN), 1_500_000_000))
    }
    @Test fun scrollStopsAtVisibleBoundaryExpiryAndPauseWithoutCatchup() {
        val motion = EdgeScrollMotion(); val bounds = PointerBounds(0f, 0f, 480f, 640f)
        val outside = PointerPosition(240f, 650f, true)
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 1_000_000, 240))
        assertEquals(12, motion.step(outside, bounds, Long.MAX_VALUE, 51_000_000, 240))
        assertEquals(0, motion.step(outside.copy(y = 639f), bounds, Long.MAX_VALUE, 71_000_000, 240))
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 91_000_000, 240))
        assertEquals(12, motion.step(outside, bounds, Long.MAX_VALUE, 5_000_000_000, 240))
        assertEquals(0, motion.step(outside, bounds, 5_000_000_000, 5_000_000_000, 240))
        motion.stop()
        assertEquals(0, motion.step(outside, bounds, Long.MAX_VALUE, 6_000_000_000, 240))
    }
    @Test fun oneShortTapDoubleBackSwipeAndCancel() {
        var taps = 0; var backs = 0; val scrolls = mutableListOf<Int>()
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, { scrolls.add(it) }, {})
        fun press(key: PadGestureRecognizer.Key, start: Long, duration: Long = 30) {
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, start, start), start)
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, start + duration, start), start + duration)
        }
        press(PadGestureRecognizer.Key.TAP, 1000)
        gestures.confirm(1330); assertEquals(1, taps)
        press(PadGestureRecognizer.Key.TAP, 1500); press(PadGestureRecognizer.Key.TAP, 1600)
        gestures.confirm(2000); assertEquals(1, taps)
        assertEquals(1, backs)
        press(PadGestureRecognizer.Key.FORWARD, 2100); press(PadGestureRecognizer.Key.BACKWARD, 2500)
        assertEquals(listOf(160, -160), scrolls)
        press(PadGestureRecognizer.Key.TAP, 3000); gestures.cancel(); gestures.confirm(4000)
        assertEquals(1, taps)
        press(PadGestureRecognizer.Key.TAP, 5000, 600); gestures.confirm(6000)
        assertEquals(1, taps)
    }
    @Test fun vendorDoubleAndTwoTapReportingSendOneBackWithoutLeakingATap() {
        var taps = 0; var backs = 0
        lateinit var gestures: PadGestureRecognizer
        gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++; gestures.cancel() }, {}, {})
        fun press(key: PadGestureRecognizer.Key, start: Long) {
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, start, start), start)
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, start + 20, start), start + 20)
        }
        press(PadGestureRecognizer.Key.TAP, 1000)
        press(PadGestureRecognizer.Key.TAP, 1100)
        press(PadGestureRecognizer.Key.DOUBLE, 1140)
        gestures.confirm(1500)
        assertEquals(1, backs); assertEquals(0, taps)
        press(PadGestureRecognizer.Key.DOUBLE, 1800)
        gestures.confirm(2120)
        assertEquals(2, backs); assertEquals(0, taps)
        press(PadGestureRecognizer.Key.TAP, 2300); gestures.confirm(2700)
        assertEquals(1, taps)
    }
    @Test fun canceledHeldAndRetiredDoubleSequencesDoNotReplayBack() {
        var backs = 0
        val gestures = PadGestureRecognizer(300, 500, {}, { backs++ }, {}, {})
        fun event(phase: PadGestureRecognizer.Phase, at: Long, down: Long) =
            PadGestureRecognizer.Event(PadGestureRecognizer.Key.DOUBLE, phase, at, down)
        gestures.accept(event(PadGestureRecognizer.Phase.DOWN, 1000, 1000), 1000)
        gestures.cancel()
        gestures.accept(event(PadGestureRecognizer.Phase.UP, 1020, 1000), 1020)
        gestures.accept(event(PadGestureRecognizer.Phase.DOWN, 2000, 2000), 2000)
        gestures.accept(event(PadGestureRecognizer.Phase.UP, 2600, 2000), 2600)
        gestures.accept(event(PadGestureRecognizer.Phase.DOWN, 3000, 3000), 3500)
        gestures.accept(event(PadGestureRecognizer.Phase.UP, 3020, 3000), 3500)
        gestures.confirm(4000)
        assertEquals(0, backs)
    }
    @Test fun threeTapsDisconnectOnceWithoutBackOrTouch() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        fun press(key: PadGestureRecognizer.Key, at: Long) {
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 20, at), at + 20)
        }
        press(PadGestureRecognizer.Key.TAP, 1000); press(PadGestureRecognizer.Key.TAP, 1100)
        assertEquals(0, backs); assertEquals(0, taps); assertEquals(0, exits)
        press(PadGestureRecognizer.Key.TAP, 1200)
        assertEquals(1, exits); assertEquals(0, backs); assertEquals(0, taps)
        press(PadGestureRecognizer.Key.DOUBLE, 1240) // Retired OEM tail must not replay Back.
        gestures.confirm(2000)
        assertEquals(1, exits); assertEquals(0, backs); assertEquals(0, taps)
    }
    @Test fun twoTapsWaitExactlyOneContinuationInterval() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        fun tap(at: Long) {
            gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.TAP, PadGestureRecognizer.Phase.DOWN, at, at), at)
            gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.TAP, PadGestureRecognizer.Phase.UP, at + 20, at), at + 20)
        }
        tap(1000); tap(1100)
        gestures.confirm(1419); assertEquals(0, backs)
        gestures.confirm(1420); assertEquals(1, backs)
        gestures.confirm(2000); assertEquals(1, backs)
        assertEquals(0, taps); assertEquals(0, exits)
    }
    @Test fun threeTapOemFormsDeduplicatePairsAndRepeatedEvents() {
        for (form in listOf("oem", "raw-and-oem", "single-then-oem")) {
            var taps = 0; var backs = 0; var exits = 0
            val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
            fun press(key: PadGestureRecognizer.Key, at: Long) {
                gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
                gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 20, at), at + 20)
            }
            when (form) {
                "oem" -> { press(PadGestureRecognizer.Key.DOUBLE, 1000); press(PadGestureRecognizer.Key.DOUBLE, 1000) }
                "raw-and-oem" -> { press(PadGestureRecognizer.Key.TAP, 900); press(PadGestureRecognizer.Key.TAP, 1000)
                    press(PadGestureRecognizer.Key.DOUBLE, 1040) }
                else -> { press(PadGestureRecognizer.Key.TAP, 900); press(PadGestureRecognizer.Key.DOUBLE, 1000) }
            }
            assertEquals(form, 0, exits); assertEquals(form, 0, backs); assertEquals(form, 0, taps)
            press(PadGestureRecognizer.Key.TAP, 1200); gestures.confirm(2000)
            assertEquals(form, 1, exits); assertEquals(form, 0, backs); assertEquals(form, 0, taps)
        }
    }
    @Test fun cancelSwipeHoldAndTwoOemPairsDoNotTriggerDisconnect() {
        var taps = 0; var backs = 0; var exits = 0; var scrolls = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, { scrolls++ }, { exits++ })
        fun press(key: PadGestureRecognizer.Key, at: Long, duration: Long = 20) {
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + duration, at), at + duration)
        }
        press(PadGestureRecognizer.Key.TAP, 1000); press(PadGestureRecognizer.Key.TAP, 1100)
        gestures.cancel(); press(PadGestureRecognizer.Key.TAP, 1200); gestures.confirm(1700)
        press(PadGestureRecognizer.Key.TAP, 2000); press(PadGestureRecognizer.Key.TAP, 2100)
        press(PadGestureRecognizer.Key.FORWARD, 2200); gestures.confirm(2600)
        press(PadGestureRecognizer.Key.TAP, 3000); press(PadGestureRecognizer.Key.TAP, 3100)
        press(PadGestureRecognizer.Key.TAP, 3200, 600)
        press(PadGestureRecognizer.Key.DOUBLE, 3900); gestures.confirm(4500)
        press(PadGestureRecognizer.Key.DOUBLE, 5000); press(PadGestureRecognizer.Key.DOUBLE, 5200); gestures.confirm(5600)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits); assertEquals(1, scrolls)
    }
    @Test fun ordinaryAndroidBackIsOneBackAndNotPartOfTriple() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        fun press(key: PadGestureRecognizer.Key, at: Long) {
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.DOWN, at, at), at)
            gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, at + 20, at), at + 20)
        }
        press(PadGestureRecognizer.Key.BACK, 1000); press(PadGestureRecognizer.Key.TAP, 1100)
        gestures.confirm(1500)
        assertEquals(1, backs); assertEquals(0, taps); assertEquals(0, exits)
        press(PadGestureRecognizer.Key.TAP, 2000); press(PadGestureRecognizer.Key.BACK, 2100)
        gestures.confirm(2500)
        assertEquals(2, backs); assertEquals(0, taps); assertEquals(0, exits)
    }
    @Test fun onlyTheNativePadBackReportCanRepresentATapPair() {
        assertEquals(PadGestureRecognizer.Key.DOUBLE, nativePadKey("ROKID,PSOC-TP-R", 0x101, 4))
        assertEquals(PadGestureRecognizer.Key.DOUBLE, nativePadKey("ROKID,PSOC-TP-R", 0x101, 291))
        assertNull(nativePadKey("Virtual", 0x101, 4))
        assertNull(nativePadKey("ROKID,PSOC-TP-R", 0, 4))
    }
    private fun nativePress(gestures: PadGestureRecognizer, code: Int, down: Long, up: Long = down + 20) {
        val key = nativePadKey("ROKID,PSOC-TP-R", 0x101, code)
        assertNotNull("Observed native key $code must reach the recognizer", key)
        gestures.accept(PadGestureRecognizer.Event(key!!, PadGestureRecognizer.Phase.DOWN, down, down), down)
        gestures.accept(PadGestureRecognizer.Event(key, PadGestureRecognizer.Phase.UP, up, down), up)
    }
    @Test fun capturedNativeNotificationContactsDisconnectWithoutConstituentActions() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        assertNull(nativePadKey("Virtual", 0x101, 83))
        assertNull(nativePadKey("ROKID,PSOC-TP-R", 0, 83))
        // Actual KEY_DASHBOARD/key204 -> NOTIFICATION83, rounded to uptime ms.
        // These observed three contacts had no Enter/Back classification afterward.
        nativePress(gestures, 83, 17048181, 17048204)
        nativePress(gestures, 83, 17048371, 17048392)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits)
        nativePress(gestures, 83, 17048524, 17048548)
        gestures.confirm(17049000)
        assertEquals(1, exits); assertEquals(0, taps); assertEquals(0, backs)
    }
    @Test fun contactModeSuppressesLateFirmwareSingleAndDoubleSummaries() {
        var taps = 0; var backs = 0; var exits = 0
        lateinit var gestures: PadGestureRecognizer
        gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++; gestures.cancel() }, {}, { exits++; gestures.cancel() })
        nativePress(gestures, 83, 1000); gestures.confirm(1320)
        assertEquals(1, taps)
        nativePress(gestures, 66, 1500, 1524) // Observed firmware Enter can arrive about500ms later.
        gestures.confirm(1900); assertEquals(1, taps)
        nativePress(gestures, 83, 2000); nativePress(gestures, 83, 2180)
        nativePress(gestures, 4, 2420) // Firmware Back summary must not fire before the raw pair's deadline.
        assertEquals(0, backs)
        gestures.confirm(2500); assertEquals(1, backs)
        nativePress(gestures, 291, 2600); gestures.confirm(3000)
        assertEquals(1, backs); assertEquals(1, taps); assertEquals(0, exits)
        nativePress(gestures, 83, 3500); nativePress(gestures, 83, 3670); nativePress(gestures, 83, 3840)
        nativePress(gestures, 66, 4400); nativePress(gestures, 4, 4700); gestures.confirm(5200)
        assertEquals(1, exits); assertEquals(1, backs); assertEquals(1, taps)
    }
    @Test fun canceledContactsAndTheirFirmwareTailCannotReplayAfterPause() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        nativePress(gestures, 83, 1000); nativePress(gestures, 83, 1170)
        gestures.cancel(); nativePress(gestures, 83, 1320)
        nativePress(gestures, 66, 1850); nativePress(gestures, 4, 2100); gestures.confirm(2600)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits)
        nativePress(gestures, 83, 3000); nativePress(gestures, 83, 3170); nativePress(gestures, 83, 3340)
        assertEquals(1, exits); assertEquals(0, taps); assertEquals(0, backs)
    }
    @Test fun contactModeStillHonorsOrdinaryNonPadAndroidBack() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        nativePress(gestures, 83, 1000)
        gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.BACK, PadGestureRecognizer.Phase.DOWN, 1100, 1100), 1100)
        gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.BACK, PadGestureRecognizer.Phase.UP, 1120, 1100), 1120)
        nativePress(gestures, 4, 1350); gestures.confirm(1700)
        assertEquals(1, backs); assertEquals(0, taps); assertEquals(0, exits)
    }
    @Test fun contactBurstTailIsRetiredAcrossDisconnectUntilAQuietBoundary() {
        var taps = 0; var backs = 0; var exits = 0
        lateinit var gestures: PadGestureRecognizer
        gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++; gestures.cancel() })
        for (at in listOf(1000L, 1170L, 1340L, 1510L, 1680L, 1850L)) nativePress(gestures, 83, at)
        gestures.confirm(2200)
        nativePress(gestures, 66, 2350); nativePress(gestures, 4, 2600); gestures.confirm(2950)
        assertEquals(1, exits); assertEquals(0, taps); assertEquals(0, backs)
        nativePress(gestures, 83, 3200); gestures.confirm(3520)
        assertEquals(1, taps); assertEquals(1, exits); assertEquals(0, backs)
    }
    @Test fun missingThirdUpExpiresAtExistingHoldBoundWithoutFallbackBack() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        nativePress(gestures, 83, 1000); nativePress(gestures, 83, 1170)
        gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 1340, 1340), 1340)
        assertEquals(1840L, gestures.deadline)
        gestures.confirm(1839); assertTrue(gestures.hasWork)
        gestures.confirm(1840); assertFalse(gestures.hasWork)
        gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP, 1850, 1340), 1850)
        nativePress(gestures, 83, 2000); nativePress(gestures, 66, 2500); gestures.confirm(2900)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits)
        nativePress(gestures, 83, 3200); gestures.confirm(3520)
        assertEquals(1, taps); assertEquals(0, backs); assertEquals(0, exits)
    }
    @Test fun newContactDownCannotReplaceAMissingThirdUp() {
        var taps = 0; var backs = 0; var exits = 0
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, {}, { exits++ })
        nativePress(gestures, 83, 1000); nativePress(gestures, 83, 1170)
        gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN, 1340, 1340), 1340)
        nativePress(gestures, 83, 1510); gestures.confirm(2000)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits)
        nativePress(gestures, 83, 3000); gestures.confirm(3320)
        assertEquals(1, taps); assertEquals(0, backs); assertEquals(0, exits)
    }
    @Test fun nativeSwipeSupersedesContactWithoutAnExtraTap() {
        var taps = 0; var backs = 0; var exits = 0; val scrolls = mutableListOf<Int>()
        val gestures = PadGestureRecognizer(300, 500, { taps++ }, { backs++ }, { scrolls.add(it) }, { exits++ })
        nativePress(gestures, 83, 1000); nativePress(gestures, 21, 1100)
        nativePress(gestures, 83, 1200); nativePress(gestures, 66, 1700); gestures.confirm(2100)
        assertEquals(listOf(-160), scrolls)
        assertEquals(0, taps); assertEquals(0, backs); assertEquals(0, exits)
    }
}
