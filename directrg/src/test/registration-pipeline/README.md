# Registration production composition

After the allocated matched protocol/Phone/Glasses production and focused test compiles, run:

```sh
EYEPROJECTOR_REGISTRATION_EVIDENCE=/private/evidence/wire \
python3 directhost/src/test/launch/run.py --registration-only \
  --frozen-dependencies /private/evidence/frozen-dependencies \
  --output-dir /private/evidence/registration
```

The frozen manifest names and hashes the protocol, Phone, generated AIDL, Glasses and Glasses
unit-test class trees, Android API jar and cached compiler jars. The runner verifies these inputs
and snapshots every compiled source. It performs no Gradle, SDK/cache mutation or device work.

The harness composes the actual Function grammar and Glasses Session writer, actual Phone Session
control consumer, Binder-value adapter, reflected registration installer, app FIFO/private store,
reader/resolver, Glasses Session/ReceiverSessionOwner and the complete source-extracted Activity
registration/cancellation/input methods with the actual chooser controller. The full Activity and
Android overlay are separately checked by the matched production compile.

Android Binder handles, TLS, the renderer, the enabled accessibility-service connection and UI
widgets are named host doubles. This checks exact values, routing, state and wire ordering; it
does not prove an Android Binder transaction, a real accessibility grant, physical native reports,
actual overlay hit testing or target-app behavior. The local widget boundary invokes the same
Activity choice callback after a Phone-completed Tap; Android view dispatch remains a device check.

Cases cover explicit Click/Hold, Phone HID Tap selection, Cancel/Back, a later external preference
change, actual window retirement, changed source geometry and unavailable hover. Every case checks
that registration and its consumed Function release emit no app touch, Tap, Scroll or navigation.
Glasses/Phone wire artifacts retain OFFER, exact OPEN before its matching usability, choice and
committed/canceled result for independent inspection.
