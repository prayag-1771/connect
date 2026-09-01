# Firestore serializes model classes reflectively, so their field names
# must survive minification or documents deserialize to nulls.
-keepclassmembers class com.obsidian.connect.core.model.** {
    <init>();
    <fields>;
}
