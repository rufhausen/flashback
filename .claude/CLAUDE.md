# Flashback

Karoo extension (Kotlin/Android): flashes ANT+ rear light only while radar reports a vehicle.

- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=~/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug`
- Not a Laravel/PHP/Docker project; ignore the global Docker/Pint rules here.
- `karoo/` + `io/hammerhead/...` are lifted from szaffarano/karoofirefly v0.7.2. Check that fork for firmware fixes before reverse-engineering anything.
- `RadarFlashController` is pure logic; keep it Android-free so the unit tests stay JVM-only.
