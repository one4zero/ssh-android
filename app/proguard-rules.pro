# JSch instantiates its algorithm implementations by class name (see JSch.config).
-keep class com.jcraft.jsch.** { *; }

# Optional JSch integrations which are not used on Android
# (logging bridges, Windows agents via JNA, unix sockets, GSSAPI/Kerberos).
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**

# Keep readable stack traces.
-dontobfuscate
