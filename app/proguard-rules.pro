# Keep line number information for debugging stack traces from release builds.
-keepattributes SourceFile,LineNumberTable
# Hide the original source file names in stack traces (privacy through obfuscation).
-renamesourcefileattribute SourceFile

# If a @JavascriptInterface is ever added, also keep it:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
