export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

apk := "app/build/outputs/apk/debug/app-debug.apk"

# Build debug APK
build:
    ./gradlew assembleDebug

# Install on connected device
install: build
    adb install -r {{apk}}

# Build, install, and launch the app
run: install
    adb shell am start -n com.appobs/.MainActivity

# Show device logs for the app
log:
    adb logcat -s JNI:* AndroidRuntime:* appobs:* --format=brief

# Uninstall from device
uninstall:
    adb uninstall com.appobs

# Pull recordings off the connected device into ./recordings
pull:
    mkdir -p recordings
    adb pull -a /sdcard/Android/data/com.appobs/files/recordings/. recordings/

# Process recordings into the import sheet (needs ANTHROPIC_API_KEY)
process dir="recordings":
    .venv/bin/python process/process.py {{dir}}

# Generate an example import sheet with fake observations
example:
    .venv/bin/python process/make_example.py

# Build the locality gazetteer + species list from Artskart; pass a bbox
localities bbox="10.0,63.3,10.7,63.5":
    .venv/bin/python process/build_localities.py --bbox {{bbox}}

# Build the NB-Whisper (Norwegian, dialect-tuned) model — one-time, ~3GB download
nb-whisper:
    .venv/bin/ct2-transformers-converter --model NbAiLab/nb-whisper-large \
        --output_dir models/nb-whisper-large-ct2 --quantization int8 \
        --copy_files tokenizer.json preprocessor_config.json --force
