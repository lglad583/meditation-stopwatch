# Nix dev shell for building Meditation Stopwatch on NixOS.
#   NIXPKGS_ALLOW_UNFREE=1 nix-shell   (or: nix-shell --arg allowUnfree true)
#   ./gradlew assembleDebug
# With --arg emulator true the shell also provides the Android emulator and an API 35 x86_64
# system image (about 1.5 GB more to download) – see emulate.sh.
{ pkgs ? import <nixpkgs> { config.allowUnfree = true; config.android_sdk.accept_license = true; }
, emulator ? false }:
let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
    includeEmulator = emulator;
    includeSystemImages = emulator;
    systemImageTypes = [ "google_apis" ];
    abiVersions = [ "x86_64" ];
    includeNDK = false;
    includeSources = false;
  };
  sdk = android.androidsdk;
in
pkgs.mkShell {
  packages = [ pkgs.jdk17 sdk pkgs.gradle_8 ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  JAVA_HOME = pkgs.jdk17;
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";
  # The SDK lives in the read-only store, so AVDs and emulator state go into the project.
  ANDROID_USER_HOME = toString ./.android;
  ANDROID_AVD_HOME = toString ./.android/avd;
}
