# JGit discovers transport implementations via META-INF/services (ServiceLoader).
# R8 can strip these classes since they are only referenced reflectively.
-keep class org.eclipse.jgit.transport.** { *; }
-keep class org.eclipse.jgit.internal.transport.** { *; }
-keepnames class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

-dontwarn org.slf4j.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
