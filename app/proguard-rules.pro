# Retain the scanner classes covered by the PR author's device tests for this R8 rollout.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
