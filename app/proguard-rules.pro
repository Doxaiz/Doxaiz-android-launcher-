# Правила R8 для Doxaiz Launcher.
#
# Кодовая база не использует рефлексию по своим классам, сериализацию Java и
# JSON-маппинг по именам полей, поэтому основной набор правил — стандартный
# proguard-android-optimize.txt из AGP. Ниже — то, что важно сохранить явно.

# Точки входа Android (Activity/Service/Receiver) и так удерживаются AGP через
# манифест; дублируем для прозрачности при ручной правке манифеста.
-keep class com.doxaiz.launcher.MainActivity { *; }
-keep class com.doxaiz.launcher.DoxaizLockService { *; }
-keep class com.doxaiz.launcher.DoxaizNotificationService { *; }

# Accessibility-служба ссылается на себя в accessibility-service XML.
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Имена методов не нужны нигде, кроме отладки; сохраняем номера строк,
# чтобы стек-трейсы из отчётов пользователей оставались читаемыми.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
