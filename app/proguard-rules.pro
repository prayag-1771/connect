# Firestore reflects over model classes; keep their shape intact.
-keepclassmembers class com.obsidian.connect.core.model.** {
    <init>();
    <fields>;
}

# Glance widget receivers are referenced from the manifest only.
-keep class com.obsidian.connect.widget.** { *; }
