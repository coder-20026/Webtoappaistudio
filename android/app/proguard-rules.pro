# Add project specific ProGuard rules here.
# You can control what code is shrunk/obfuscated using rules.

-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn javax.xml.stream.**
-dontwarn java.awt.**
