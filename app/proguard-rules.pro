# 友盟统计
-keep class com.umeng.** { *; }
-keep class org.repackage.** { *; }
-keep class uk.co.senab.photoview.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}

# Apache MINA & FTP Server
-keep class org.apache.mina.** { *; }
-keep class org.apache.ftpserver.** { *; }
-keepclassmembers class * extends org.apache.mina.core.service.IoService {
    public <init>(java.util.concurrent.ExecutorService);
    public <init>(java.util.concurrent.Executor);
}
-keepclassmembers class * implements org.apache.mina.core.session.IoSessionInitializer {
    <init>(...);
}

# R8: 忽略 ftpserver/mina 引用的可选依赖（未打包到 APK）
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn org.springframework.beans.factory.**
-dontwarn org.springframework.beans.factory.config.**
-dontwarn org.springframework.beans.factory.support.**
-dontwarn org.springframework.beans.factory.xml.**
-dontwarn org.springframework.context.support.**
-dontwarn org.springframework.util.**
-dontwarn org.springframework.util.xml.**
