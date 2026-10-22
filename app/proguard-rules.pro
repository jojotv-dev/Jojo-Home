# Jojo Home release rules. Keep reflective serializers and player JNI entrypoints.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class com.squareup.moshi.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
# R8 full mode also needs the concrete TypeToken subclasses kept for their signatures.
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken
-keep class com.nuvio.tv.data.remote.api.** { *; }
-keep class com.nuvio.tv.data.remote.dto.** { *; }
-keep class com.nuvio.tv.domain.model.** { *; }

# Gson uses these field names both on the wire and in the encrypted pilot files.
-keep class com.jojotv.home.progresspilot.PilotMode { *; }
-keep class com.jojotv.home.progresspilot.PilotScope { *; }
-keep class com.jojotv.home.progresspilot.PilotProgressPayload { *; }
-keep class com.jojotv.home.progresspilot.PilotOperation { *; }
-keep class com.jojotv.home.progresspilot.PilotAcknowledgement { *; }
-keep class com.jojotv.home.progresspilot.PilotJournal { *; }
-keep class com.jojotv.home.progresspilot.PilotLocalSettings { *; }
-keep class com.jojotv.home.progresspilot.PilotEndpoint { *; }
-keep class com.jojotv.home.progresspilot.PilotAuthSession { *; }

# Retrofit 2.9 lacks these R8 full-mode rules: suspend API calls inspect the
# Continuation/Response generic signatures at runtime to resolve their DTOs.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# The local FFmpeg JNI library looks up this callback by name in JNI_OnLoad.
# It has no Java caller, so R8 otherwise removes it and disables the decoder.
-keepclassmembers,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
-dontwarn androidx.media3.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# dnsjava's desktop resolvers reference optional JNA/JNDI APIs absent on Android.
# Lombok annotations are compile-time only.
-dontwarn com.sun.jna.Library
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Native
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.Structure$ByReference
-dontwarn com.sun.jna.Structure$FieldOrder
-dontwarn com.sun.jna.Structure
-dontwarn com.sun.jna.WString
-dontwarn com.sun.jna.platform.win32.Win32Exception
-dontwarn com.sun.jna.ptr.IntByReference
-dontwarn com.sun.jna.win32.W32APIOptions
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn lombok.Generated
-dontwarn lombok.NonNull

# Optional JVM-only APIs referenced by SMBJ/MBassador. They are not present on
# Android and are only used by optional authentication/filtering code paths.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn javax.servlet.**

# jcifs-ng discovers the MD4 implementation through Bouncy Castle provider
# mappings. Keep these classes: R8 cannot reliably see that reflective lookup.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }
-keep class org.bouncycastle.crypto.digests.MD4Digest { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.crypto.macs.CMac** { *; }

# jcifs-ng uses internal authentication/session classes through provider and
# protocol mappings that R8 cannot prove safe to rename or remove.
-keep class jcifs.** { *; }

# Wire/cache records for the explicit Supabase progress source (Gson).
-keep class com.jojotv.home.progresspilot.CloudProgressRow { *; }
-keep class com.jojotv.home.progresspilot.CloudProgressOperation { *; }
-keep class com.jojotv.home.progresspilot.CloudProgressAck { *; }
-keep class com.jojotv.home.progresspilot.CloudProgressPage { *; }
-keep class com.jojotv.home.progresspilot.CloudProgressJournal { *; }
-keep class com.jojotv.home.progresspilot.CloudCheckpointOrder { *; }

# Stable account settings RPC and encrypted journal field names (Gson).
-keep class com.jojotv.home.progresspilot.AccountRowId { *; }
-keep class com.jojotv.home.progresspilot.AccountEntry { *; }
-keep class com.jojotv.home.progresspilot.AccountOperation { *; }
-keep class com.jojotv.home.progresspilot.AccountAck { *; }
-keep class com.jojotv.home.progresspilot.AccountPage { *; }
-keep class com.jojotv.home.progresspilot.AccountJournal { *; }
-keep class com.jojotv.home.progresspilot.AccountProfileBinding { *; }
-keep class com.jojotv.home.progresspilot.AccountSeed { *; }
-keep class com.jojotv.home.progresspilot.AccountLocalState { *; }

# Encrypted IPTV activity, delivery receipts and one-time device pairing (Gson).
-keep class com.jojotv.home.progresspilot.AccountContentValue { *; }
-keep class com.jojotv.home.progresspilot.AccountContentRow { *; }
-keep class com.jojotv.home.progresspilot.AccountContentOp { *; }
-keep class com.jojotv.home.progresspilot.AccountContentAck { *; }
-keep class com.jojotv.home.progresspilot.AccountContentPage { *; }
-keep class com.jojotv.home.progresspilot.AccountContentJournal { *; }
-keep class com.jojotv.home.progresspilot.AccountContentState { *; }
# Netflix snapshots use Gson inside the encrypted account journal and RPC payloads.
-keep class com.jojotv.home.progresspilot.AccountNetflixState { *; }
-keep class com.jojotv.home.progresspilot.AccountNetflixValue { *; }
-keep class com.jojotv.home.progresspilot.AccountNetflixSnapshot { *; }
-keep class com.jojotv.home.progresspilot.AccountNetflixObservation { *; }
-keep class com.jojotv.home.progresspilot.AccountPairRequest { *; }
-keep class com.jojotv.home.progresspilot.AccountPairGrant { *; }
-keep class com.jojotv.home.progresspilot.AccountRecoveryGrant { *; }
-keep class com.jojotv.home.progresspilot.AccountLoginChoices { *; }
-keep class com.jojotv.home.progresspilot.AccountResumeMaskSync { *; }
-keep class com.jojotv.home.progresspilot.AccountHomeArtworkChoice { *; }
-keep class com.jojotv.home.progresspilot.AccountHomeArtworkSync { *; }
