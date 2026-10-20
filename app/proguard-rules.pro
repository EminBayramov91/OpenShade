# Keep the accessibility service class referenced from the manifest.
-keep class io.github.eminbayramov.openshade.ShadeService { *; }
# Looked up by name in ShizukuShell.
-keepclassmembers class rikka.shizuku.Shizuku { *** newProcess(...); }
