# Halo — R8 rules
# kotlinx.serialization ships its own rules; keep our @Serializable models' names stable anyway.
-keep,includedescriptorclasses class app.halo.launcher.data.**$$serializer { *; }
-keepclassmembers class app.halo.launcher.data.** { *** Companion; }
-keepclasseswithmembers class app.halo.launcher.data.** { kotlinx.serialization.KSerializer serializer(...); }
