# Номера строк в стектрейсах (вместе с mapping.txt из CI позволяют их расшифровать)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ViewModel создаётся фабрикой через рефлексию по конструктору. У lifecycle есть свои правила,
# это страховка на случай, если R8 всё равно вырежет конструктор.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# OkHttp предупреждает про необязательные TLS-провайдеры, которых на Android нет
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
