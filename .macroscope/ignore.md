# Macroscope ignore file: https://docs.macroscope.com/code-review/ignore-files
# One bare glob per line. This file REPLACES Macroscope's defaults,
# so the defaults that fit this repo are copied below.
# Tests stay excluded by Macroscope's own test detection (ignoreTests is on by default).

# === Crown: documentation, specs, agent prompts ===
**/*.md
openspec/**
.roo/**
LICENSE
AUTHORS

# === Crown: translations (base values/ strings are still reviewed) ===
app/src/*/res/values-*/strings*.xml
app/src/*/res/values-*/arrays.xml

# === Crown: icons and generated resources ===
app/src/*/res/drawable*/**
app/src/*/res/mipmap*/**
app/schemas/**
.github/images/**

# === Crown: Gradle wrapper ===
gradle/wrapper/**
gradlew
gradlew.bat

# === Defaults: build output ===
**/.git/**
**/__pycache__/**
**/venv/**
**/.venv/**
**/node_modules/**
**/vendor/**
**/third_party/**
build/**
out/**
**/build/**
**/.gradle/**
**/target/**
**/dist/**
**/generated/**
**/intermediates/**
**/generated_sources/**
**/generated-sources/**
**/generated-src/**
**/src/main/generated/**
**/*.min.js
**/*.min.css

# === Defaults: package and lock files ===
**/*.properties
**/*.lock
**/go.mod
**/go.sum
**/package.json
**/package-lock.json

# === Defaults: binary and data files ===
**/*.jpg
**/*.jpeg
**/*.png
**/*.gif
**/*.svg
**/*.ico
**/*.webp
**/*.bmp
**/*.woff
**/*.woff2
**/*.ttf
**/*.otf
**/*.mp3
**/*.mp4
**/*.ogg
**/*.zip
**/*.tar
**/*.gz
**/*.7z
**/*.pdf
**/*.db
**/*.sqlite
**/*.json
**/*.jsonl
**/*.csv
**/*.exe
**/*.dll
**/*.so
**/*.bin
**/*.pyc
**/*.class
**/*.jar
**/*.aar
**/*.apk
**/*.aab
**/*.o
**/*.a
**/*.cer
**/*.pem
**/*.p12
**/*.jks
**/*.keystore
**/*.log
**/*.snap
