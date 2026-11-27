# =====================================================================
# Light-Stick-Music R8/ProGuard 규칙 (release, releaseTest 공통)
# 주의: 실제 빌드/실행으로 검증되지 않은 초기 규칙. releaseTest 로 전 기능 확인 필요.
# =====================================================================

# 크래시 로그 역추적용 (mapping.txt 와 함께 보관할 것)
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------
# LightStick SDK: AAR 에 consumer rules 가 없고 BLE(GATT) 쪽에서 리플렉션을 사용하므로
# SDK 패키지는 전체 유지. (SDK 가 consumer rules 를 제공하게 되면 범위를 줄일 것)
#
# 예전에는 com.lightstick.** 로 지정해 앱 패키지(com.lightstick.music)까지 통째로 유지돼
# 앱 코드 축소·난독화가 거의 되지 않았다(D1). SDK 2.200.0 AAR 의 패키지만 나열한다.
# SDK 에 새 최상위 패키지가 생기면 여기에 추가해야 한다.
# ---------------------------------------------------------------------
-keep class com.lightstick.* { *; }
-keep interface com.lightstick.* { *; }
-keep class com.lightstick.config.** { *; }
-keep class com.lightstick.control.** { *; }
-keep class com.lightstick.device.** { *; }
-keep class com.lightstick.efx.** { *; }
-keep class com.lightstick.game.** { *; }
-keep class com.lightstick.internal.** { *; }
-keep class com.lightstick.ota.** { *; }
-keep class com.lightstick.types.** { *; }
-keep interface com.lightstick.config.** { *; }
-keep interface com.lightstick.control.** { *; }
-keep interface com.lightstick.device.** { *; }
-keep interface com.lightstick.efx.** { *; }
-keep interface com.lightstick.game.** { *; }
-keep interface com.lightstick.internal.** { *; }
-keep interface com.lightstick.ota.** { *; }
-keep interface com.lightstick.types.** { *; }
-dontwarn com.lightstick.samples.**

# ---------------------------------------------------------------------
# 영속 저장된 enum 이름을 valueOf()/name 으로 복원하므로 앱 enum 은 이름 유지
# (예: EffectViewModel 의 BaseEffectType.valueOf(dto.baseType))
# ---------------------------------------------------------------------
-keep enum com.lightstick.music.** { *; }

# ---------------------------------------------------------------------
# kotlinx.serialization (앱의 @Serializable DTO)
# ---------------------------------------------------------------------
-keepclassmembers @kotlinx.serialization.Serializable class com.lightstick.music.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.lightstick.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lightstick.music.**$$serializer { *; }

# ---------------------------------------------------------------------
# Parcelable (kotlin-parcelize, 예: MusicItem)
# ---------------------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---------------------------------------------------------------------
# 오디오 분석 라이브러리: TarsosDSP(jvm)가 Android 에 없는 javax.sound/java.awt 를 참조
# ---------------------------------------------------------------------
-dontwarn javax.sound.**
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn be.tarsos.dsp.io.jvm.**
-dontwarn org.jtransforms.**
# JTransforms 의존 라이브러리(JLargeArrays)가 참조하는 JVM 내부 클래스 (Android 에 없음)
-dontwarn sun.misc.**
-dontwarn pl.edu.icm.jlargearrays.**

# ---------------------------------------------------------------------
# 앱 로그 유틸: 릴리즈에서는 출력하지 않으므로 호출 자체를 제거해 인자 문자열 생성 비용도 없앤다
# ---------------------------------------------------------------------
-assumenosideeffects class com.lightstick.music.core.util.Log {
    public int v(...);
    public int d(...);
    public int i(...);
    public int w(...);
    public int e(...);
}
