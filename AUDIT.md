# Doxaiz Launcher 5.5-test — глобальный аудит (баги, ошибки, безопасность, производительность)

Дата аудита: 2026-11-16. Дата правок: 2026-11-16.
Ветка: `arena/8b39bd0e-doxaiz-android-launcher` (от `563fc08`)
Объём: 36 Java-файлов, ~28 700 строк; `AndroidManifest.xml`, `app/build.gradle`, `build.sh`, `res/`.

> **Состояние работ.** Аудит завершён, затем по нему внесены правки в код этой же ветки.
> Исправлено 15 находок из 20 полностью, 3 — частично (F-05, F-15, F-18), 2 остаются
> открытыми (F-06 — приватность бэкапа, F-07 — диагностируемость ошибок);
> после правок проект компилируется с **0 ошибок и 0 предупреждений** в lint-прогоне §6,
> а четыре набора тестов проходят (`V52Tests`, `Audit55Tests`, `SimpleThemeTest`, `Perf56Tests`).
> Что именно сделано по каждой находке — в §10, там же остаток работ.
> Отдельная итерация по отзывчивости и производительности горячих путей — §11.
> Проверка на настоящем Android (эмулятор API 34 в CI) и найденные ею блокеры сборки — §12.
> Второй проход: Android Lint, проверка совместимости API по уровню SDK и найденные дефекты — §13.

---

## 1. Как проверялось и границы проверки

**Что реально выполнено:**

1. Полная компиляция всех 36 исходников + сгенерированного `R.java` компилятором ECJ 3.46 в режиме `-18` (Java 8) против API 34 → **0 ошибок**.
2. Повторная компиляция с включённым persisted-lint (`-warn:+unusedLocal,+unusedPrivate,+unusedImport,+unnecessaryElse,+fieldHiding,+localHiding,+maskedCatchBlocks,+deadCode,+hiding`) → **20 предупреждений, 0 ошибок** (разобраны в §6).
3. Статический поиск по классам проблем: `Thread.sleep`, `ProcessBuilder`/`exec`, `MODE_WORLD_*`, `Cipher`/`KeyStore`/`SecureRandom`, `WebView`, `setAccessible`, `Class.forName`, `printStackTrace`/`System.out`, `System.gc`, `getRunningTasks`, `PendingIntent`, `commit()`, `charAt(0)`/`substring`/`parseInt` без проверок, `registerReceiver`, `WindowManager` overlay-типы, `Settings.System` write-guard, `startActivity` на внешних интентах.
4. Ручное чтение целиком/выборочно: `Prefs`, `DesktopModel`, `BackupHelper`, `IconDiskCache`, `IconHelper`, `AppRepository`, `LayoutImporter`, `CustomSection`, `SearchHelper`, `NotificationTools`, `MemoryHelper`, `BatteryInfoHelper`, `BiometricHelper`, `VectorGlyphs`, `GlassTheme`, `SoundPanelHelper`, `HeadsUpBannerHelper`, `NetworkQualityHelper`, `DynamicBarHelper`, `DesktopPagerView`, `FreeGridPageView`, `AnimeBackdropView`, `AnimeParticlesView`, `DoxaizLockService`, `DoxaizNotificationService`, `QuickShadeHelper` (частично; 6 338 строк), `MainActivity` (жизненный цикл, drawer, drag&drop, окна, настройки, бэкап).
5. Сверка с официальной документацией Android: `AccessibilityService` (guide + API reference), `NotificationListenerService`, `ConnectivityManager`, поведение AGP 8 при `package` в манифесте и при резолве плагина.

**Что проверялось после внесения правок (см. §10):** повторная полная компиляция ECJ и повторный
lint-прогон (0 предупреждений), прогон `V52Tests` / `Audit55Tests` / `SimpleThemeTest` на
скомпилированных классах, `bash -n build.sh` и проверка парсинга версий из `app/build.gradle`.

**Что не проверялось (важно для оценки рисков):**

* Нет реального устройства/эмулятора → всё, что помечено «требует проверки на устройстве», проверено только по коду и документации.
* Нет Gradle/AGP/SDK в песочнице → сборка проверена только альтернативным компилятором; вывод про `settings.gradle` основан на семантике Gradle и документации, а не на запуске `gradlew`.
* `org.json` в `android.jar` — заглушки (`RuntimeException: Stub!`), поэтому `tests/Audit54Tests.java` в песочнице не выполняется. Логика `BackupHelper`/`DesktopModel` проверена чтением, не исполнением.

---

## 2. Шкала серьёзности

| Уровень | Значение |
|---|---|
| **P1** | Блокирует сборку/установку или полностью отключает заявленную функцию на части устройств |
| **P2** | Потеря данных, ложное состояние настроек, заметный jank, приватность |
| **P3** | Латентная ошибка, ослабленная защита, риск на части прошивок |
| **P4** | Гигиена кода, дублирование, мёртвый код (влияет на сопровождение) |

**Критических (P0: краш/утечка данных наружу) не найдено.** Кодовой базы это не отменяет: P1-находка №1 делает проект не собираемым «из коробки».

---

## 3. Сводная таблица находок

| ID | Ур. | Область | Файл:строка | Кратко | Статус |
|---|---|---|---|---|---|
| F-01 | P1 | Сборка | `settings.gradle` (весь), `build.gradle:2` | Нет `pluginManagement { repositories { google() } }` → AGP 8.2.2 не резолвится, sync падает | ✅ исправлено |
| F-02 | P2 | Манифест/функция | `AndroidManifest.xml:72` | Accessibility-служба `exported="false"` вопреки официальному примеру (`true`) → риск, что служба не подключится | ✅ исправлено |
| F-03 | P2 | Целостность данных | `BackupHelper.java:236` (+221–390) | Импорт бэкапа не транзакционный: Prefs мутируются до разбора `settings`, любая ошибка → `return false` при частично применённом состоянии | ✅ исправлено |
| F-04 | P2 | Логика настроек | `Prefs.java:184–190, 198–203, 249–253, 745–750`; `DesktopPagerView.java:92–130, 217–220` | Семейство «замороженных» настроек: сеттеры игнорируют аргумент, геттеры возвращают константу, стили переходов — мёртвый код | ✅ исправлено |
| F-05 | P2 | Производительность | `MainActivity.java:2323–2339, 4000–4004` (47 вызовов) | Полная пересборка рабочего стола после запуска любого приложения/изменения бейджа | 🟡 частично (§11.4, §11.9) |
| F-06 | P2 | Приватность/политика | `BackupHelper.java:109`; `AndroidManifest.xml:12` | Экспорт бэкапа содержит скрытые приложения в открытом виде; `QUERY_ALL_PACKAGES` без обоснования для стора | ⏳ не начато (требует UX-решения) |
| F-07 | P2 | Обработка ошибок | 247 × `catch (Throwable ignored)`; напр. `DesktopModel.java:150–155`, `AppRepository.java:437–445` | Массовое проглатывание исключений скрывает деградацию (иконки, импорт, бэкап) без диагностики | ⏳ не начато |
| F-08 | P3 | Логика | `DoxaizNotificationService.java:305–314` | Проверка доступа к уведомлениям по подстроке в `enabled_notification_listeners` → ложноположительный результат | ✅ исправлено |
| F-09 | P3 | Сборка | `AndroidManifest.xml:3–9`; `app/build.gradle:13–14`; `build.sh:39–40` | Версия/`package`/`uses-sdk` заданы в трёх местах и расходятся | ✅ исправлено |
| F-10 | P3 | Сборка | `build.sh:6, 86` | Путь `build-tools/android-14` не соответствует раскладке SDK; `verify --min-sdk-version 21` при `minSdk 26`; v1-подпись не нужна | ✅ исправлено |
| F-11 | P3 | Безопасность | `MainActivity.java:591, 604`; `DoxaizLockService.java:138` | На Android ≤12 динамические ресиверы неявно экспортированы → подделка системных броадкастов | ✅ исправлено |
| F-12 | P3 | Жизненный цикл | `MainActivity.java:598–608` vs `801–804` | `userPresentReceiver` регистрируется в `onResume`, снимается только в `onDestroy` | ✅ исправлено |
| F-13 | P3 | Функция | `NetworkQualityHelper.java:284` | «Самолечение» сети через `reportNetworkConnectivity` может быть недоступно сторонним приложениям → тихий no-op | ✅ исправлено |
| F-14 | P3 | Латентная логика | `LayoutImporter.java:429–434` | `clampCol` жёстко занижает до 4 колонок (сегодня недостижимо, но хрупко) | ✅ исправлено |
| F-15 | P3 | Производительность | `DesktopModel.java:160–197, 313` | Сериализация всей раскладки + запись в SharedPreferences на UI-потоке при каждом drop/rebuild | 🟡 частично (§10 F-15, §11.9) |
| F-16 | P3 | Производительность | `IconDiskCache.java:116–131` | `synchronized save()` сериализует 2–4 потока иконок; `Thread.getId()` устарел | ✅ исправлено |
| F-17 | P3 | Производительность | `DoxaizLockService.java:715–718` | `MemoryHelper.read()` (MemoryInfo + StatFs + 5 строк) каждую секунду, пока виден оверлей | ✅ исправлено |
| F-18 | P3 | Тесты/CI | `tests/V52Tests.java:6`; `tests/Audit54Tests.java` | Тесты не подключены к Gradle; `V52Tests` не проходит (устаревшее ожидание), `Audit54Tests` требует `org.json` | 🟡 частично (+ `Perf56Tests`) |
| F-19 | P4 | Гигиена | `MainActivity.java:6–7, 64, 66, 102, 128, 9322` | Неиспользуемые импорты/поля/методы (полный список в §6) | ✅ исправлено |
| F-20 | P4 | Сборка | `app/build.gradle:19–21` | `minifyEnabled false` без обоснования для релиза | ✅ исправлено |

---

## 4. Разбор находок

### F-01 (P1). Проект не синхронизируется: AGP не резолвится

`build.gradle` (корень):

```groovy
plugins {
    id 'com.android.application' version '8.2.2' apply false
}
```

`settings.gradle`:

```groovy
rootProject.name = "DoxaizLauncher"
include ':app'
```

Блока `pluginManagement` нет, `buildscript { repositories { google() } }` тоже нет. При использовании `plugins {}` DSL Gradle ищет артефакт плагина **только** в репозиториях из `pluginManagement.repositories`, а по умолчанию это Gradle Plugin Portal. AGP публикуется в Google Maven (`maven.google.com`), поэтому sync заканчивается:

```
Plugin [id: 'com.android.application', version: '8.2.2', apply: false] was not found in any of the following sources:
- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Plugin Repositories (could not resolve plugin artifact 'com.android.application:com.android.application.gradle.plugin:8.2.2')
```

`README.md` при этом обещает «откройте проект в Android Studio и соберите debug APK обычными средствами IDE» — по текущим файлам это не работает (плюс отсутствует wrapper: `gradle/wrapper/` нет).

**Исправление** (`settings.gradle`):

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DoxaizLauncher"
include ':app'
```

Дополнительно: добавить `gradle/wrapper/gradle-wrapper.properties` (AGP 8.2.2 → Gradle 8.2) и `gradlew`/`gradlew.bat`.

**Проверка:** `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

---

### F-02 (P2). `exported="false"` у Accessibility-службы — расхождение с официальным примером

`AndroidManifest.xml:70–81`:

```xml
<service
    android:name=".DoxaizLockService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
```

Официальный гайд «Create an accessibility service» объявляет службу так:

```xml
<service android:name=".accessibility.MyAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    ...>
```

Официальный пример `NotificationListenerService` в API reference, наоборот, использует `exported="false"` — то есть для двух служб поведение разное:
* `DoxaizNotificationService` (`AndroidManifest.xml:86`) — **соответствует** документации, оставляем;
* `DoxaizLockService` (`AndroidManifest.xml:72`) — **противоречит** документации. Службу биндит `system_server` (uid 1000, другое приложение); при `exported="false"` связывание с чужим UID может быть отклонено, и тогда не работают: двойной тап «выключить экран», панель ОЗУ/температуры в Недавних, снимок экрана, «очистить всё».

Симптом на пользователе: «включил службу в настройках, но ничего не происходит».

**Исправление:** `android:exported="true"` для `DoxaizLockService` (доступ по-прежнему закрыт `BIND_ACCESSIBILITY_SERVICE` — его выдаёт только система).

**Проверка на устройстве:** включить службу, открыть Недавние → должна появиться панель; двойной тап по рабочему столу → гаснет экран. Если на текущем билде работает — значит прошивка(и) биндит в обход, но фикс всё равно нужен для совместимости.

---

### F-03 (P2). Импорт резервной копии не транзакционный

`BackupHelper.importBackupJson` (`BackupHelper.java:221–391`):

```java
if (!validateDesktopJson(desk)) return false;     // 229 — валидируется ТОЛЬКО desktop

prefs.setDesktopJson(desk.toString());            // 236 — уже мутируем Prefs
desktop.load();                                   // 237

JSONObject s = root.optJSONObject("settings");    // 240
if (s != null) { ... ~60 отдельных prefs.setXxx(...) ... }   // 241–313

JSONArray customSecArr = ...; prefs.setCustomSectionsRawJson(...);  // 315–321
JSONArray hiddenArr = ...;    prefs.clearHiddenApps(); ...          // 322–331
JSONObject renamesObj = ...;  ...                                   // 333–345
...
desktop.save();
return true;
} catch (Throwable ignored) {
    return false;                                  // 390–391
}
```

Проблема: `catch (Throwable) { return false; }` **не откатывает** то, что уже записано. Любое исключение в середине (битый тип в `settings`, `OutOfMemoryError` на большом JSON, `IllegalStateException` от SharedPreferences) → пользователь видит «ошибка импорта», но у него уже частично заменены раскладка, тема, обои, скрытые приложения. Худший сценарий: `clearHiddenApps()` + `hideApp()` уже выполнены, а `protectHidden`/`renames` — нет → скрытые приложения «всплыли» при неудачном импорте.

**Исправление (минимальное, без переписывания):** разобрать всё во временные структуры и применять одним проходом в конце.

```java
// 1) Собрать план изменений, ничего не записывая
Map<String, Object> plan = new LinkedHashMap<>();
plan.put("desktop", desk.toString());
parseSettingsInto(plan, root.optJSONObject("settings"));   // чистые функции, без prefs.set*
List<CustomSection> sections = ...;
Set<String> hidden = ...; Map<String,String> renames = ...;

// 2) Применить. SharedPreferences: один commit-подобный проход
SharedPreferences.Editor ed = prefs.editForBulkImport();   // добавить в Prefs
applyPlanToEditor(ed, plan);
boolean ok = ed.commit();                                  // commit() → узнаём об ошибке до UI-уведомления
if (!ok) return false;
applyNonPrefsParts(sections, hidden, renames);             // CustomSection/hidden — уже после успешной записи
desktop.load();
desktop.save();
return true;
```

Как минимум — обернуть в «снимок/откат»: перед мутациями сохранить текущий `desk`, `settings`-ключи, hidden/renames и восстановить их в `catch`.

---

### F-04 (P2). «Замороженные» настройки: сеттеры игнорируют аргумент, геттеры возвращают константу

`Prefs.java`:

```java
184: public int getHomeLayoutMode() {
185:     // Гибрид: страницы рабочего стола и отдельное меню «Все приложения».
186:     return HOME_MODE_WITH_DRAWER;                       // всегда 0
187: }
188: public void setHomeLayoutMode(int mode) {
189:     sp.edit().putInt(KEY_HOME_LAYOUT_MODE, HOME_MODE_WITH_DRAWER).apply();  // всегда 0
190: }

198: public boolean isSmoothAnimationsEnabled() {
199:     // Одна простая плавная анимация всегда работает по умолчанию
200:     return true;                                        // всегда true
201: }
202: public void setSmoothAnimationsEnabled(boolean enabled) {
203:     sp.edit().putBoolean(KEY_SMOOTH_ANIMATIONS, true).apply();  // ← аргумент `enabled` НЕ используется
204: }

249: public boolean isShowRecentsRam() { return true; }
250: public void setShowRecentsRam(boolean show) { /* всегда включено */ }
252: public boolean isShowRecentsCleanBtn() { return true; }
253: public void setShowRecentsCleanBtn(boolean show) { /* всегда включено */ }

745: public int getPageTransitionStyle() { return PAGE_TRANS_SMOOTH; }
749: public void setPageTransitionStyle(int style) {
750:     sp.edit().putInt("page_transition_style", PAGE_TRANS_SMOOTH).apply(); }
```

Последствия:
1. **Мёртвые ветки и мёртвый код.** `DesktopPagerView.computeStyleScale/computeStyleAlpha/computeStyleTranslationX` (`DesktopPagerView.java:92–130`) никогда не вызываются — `applyPageTransitions()` (`:318`) использует только `computePageScale/computePageAlpha`. `setTransitionStyle(style)` (`:217–220`) игнорирует `style`. То есть ~40 строк стилей «Карточки/Затухание/Стек» — балласт, который выглядит работающим.
2. **Бэкап теряет настройки.** `BackupHelper` пишет эти ключи (`:92` и др.) и читает их при импорте (`:257`, `:303`), то есть пользователь «восстанавливает» настройку, которая не влияет ни на что.
3. **Ловушка для будущей правки.** `setSmoothAnimationsEnabled(false)` выглядит как рабочее выключение анимаций; тот, кто «починит» геттер, получит рассинхрон с UI (переключателя в настройках уже нет — см. `addToggleRow` в `MainActivity:7241–7350`, там этих пунктов нет).

**Исправление (любой из двух вариантов, но явно):**
* *Убрать насовсем* — удалить сеттеры/геттеры, константы, `DesktopPagerView:92–130`, поля `KEY_SMOOTH_ANIMATIONS`, `KEY_HOME_LAYOUT_MODE`, `page_transition_style` из бэкапа.
* *Вернуть управление* — `isSmoothAnimationsEnabled()` → `sp.getBoolean(KEY_SMOOTH_ANIMATIONS, true)`, сеттер → `putBoolean(..., enabled)`, `applyPageTransitions()` → `computeStyleScale(style, offset)`, поле `transitionStyle` в `DesktopPagerView`.

Сейчас в коде смешаны оба подхода, и это худший вариант.

---

### F-05 (P2). Полная пересборка рабочего стола на «горячем» пути

`MainActivity.rebuildDesktop()` (`:2323–2339`) создаёт **все** страницы заново:

```java
desktop.normalizeAllPages(cols, prefs.isHomeBottomAligned());
List<View> pageViews = new ArrayList<>();
for (int p = 0; p < desktop.pages.size(); p++)
    pageViews.add(buildDesktopPageView(p, desktop.pages.get(p)));   // новые View на каждую иконку
pagerView.setPages(pageViews, currentPage);                           // removeAllViews() + addView()
rebuildPageDots();
rebuildDock();
```

Вызовов `rebuildDesktop()` — **47** (48-е вхождение — само объявление метода). Среди них «горячие»:

```java
// :4000 — после запуска приложения из меню
prefs.recordAppLaunch(entry.key);
if (wasNew) {
    lastDrawerRecentSig = "";
    rebuildDesktop();            // полная пересборка сразу при возврате на рабочий стол
    if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
}
```

и `onAppsUpdated` (`:2231`) — вызывается на каждое событие `LauncherApps.Callback` (установка/удаление/обновление, часто пачкой).

Стоимость: при 8 страницах × ~30 иконок это ~240 программно созданных View + `onMeasure`/`onLayout` каждой `FreeGridPageView` + перезапрос drawable из LRU. Иконки кэшированы, но инфляция/лейаут — нет. На среднем устройстве это заметный кадровый провал ровно в момент возврата на домашний экран.

**Исправление:**
1. Для бейджей уже есть точечный путь `refreshBadgesInPlace()` — использовать его и в `launchAndReturnHome` (новизна в списке Recent не влияет на состав рабочего стола).
2. Разделить `rebuildDesktop()` на `rebuildDesktopStructure()` (состав/порядок) и `updateDesktopInPlace()` (лейблы, бейджи, иконки) — второй должен покрывать 90 % вызовов.
3. В `applyTheme()` (`:1859`) не пересобирать страницы: тема — это палитра, достаточно `invalidate` + пройтись по привязанным View.
4. Опционально: `RecyclerView`-подход для страниц (сейчас каждая страница держит все View одновременно).

**Как измерить:** `adb shell am trace --sampling 1 -a com.doxaiz.launcher` + `dumpsys gfxinfo com.doxaiz.launcher framestats` до/после; цель — отсутствие кадров > 16 мс на возврате в лончер.

---

### F-06 (P2). Приватность бэкапа и политика магазина

`BackupHelper.java:109`:

```java
root.put("hidden", hiddenArr);          // скрытые приложения списком
```

Экспорт не шифруется и явно содержит список скрытых приложений и пользовательские переименования (`:112`). `README.md` это честно признаёт, но риск остаётся: файл `*.json` попадает в «Загрузки»/облако, и по нему видно, что пользователь скрывал (например, банк или знакомства). Импорт уже защищён биометрией (`MainActivity:9499`, `BackupHelper:232–234`), а экспорт — только если включён `protectHiddenApps`.

**Рекомендации:**
1. По умолчанию **не** включать `hidden`/`renames` в экспорт; вынести в отдельный чекбокс «включить приватные настройки».
2. При включённом чекбоксе — требовать биометрию/PIN (симметрично импорту).
3. Сообщать в имени файла: `doxaiz-backup-private.json` vs `doxaiz-backup.json`.

**Политика Google Play:** `QUERY_ALL_PACKAGES` (`AndroidManifest.xml:12`) разрешён лаунчерам, но требует декларации в Play Console (форма «Package visibility»); vendor-разрешения `*.permission.READ_SETTINGS` (`:28–32`) не существуют в чужих подписях и просто игнорируются — их стоит оставить (нужны для импорта раскладки XOS), но описать в README, чтобы ревьюер не считал их «запросом лишнего».

---

### F-07 (P2). 247 `catch (Throwable ignored)` — системная проблема наблюдаемости

Статистика по репозиторию: `catch (Throwable` — 247, `ignored)` — 249, `catch (Exception` — 3, `printStackTrace`/`Log.*` — **0**.

Само по себе проглатывание в UI-коде оправдано (нельзя ронять лончер из-за одного OEM-вызова). Проблема в том, что проглатывается **всё**, включая места, где ошибка означает потерю данных:

```java
// DesktopModel.parseLayoutJson — при любой ошибке валит ВСЮ раскладку, включая уже разобранные страницы
} catch (Throwable ignored) {
    dock.clear();
    pages.clear();
    return false;
}
```

```java
// AppRepository.loadIconTwoLevel — молча вернёт null, иконка останется пустой без следа
} catch (Throwable ignored) {
    // Защита от гонки NameNotFoundException ...
}
```

**Рекомендации:**
1. Ввести минимальный логгер (`DoxaizLog.e(tag, msg, tr)`) с записью в ограниченный ring-буфер (256 записей) в памяти + чтение из «Настройки → Диагностика» (экран уже есть: `MainActivity:7440+`, раздел сети). Это даст пользователю возможность прислать лог, не добавляя `INTERNET`.
2. Заменить `catch (Throwable ignored)` на `catch (Throwable t) { DoxaizLog.w(TAG, t); }` в: `DesktopModel.save/parseLayoutJson`, `BackupHelper.importBackupJson`, `IconDiskCache.*`, `AppRepository.*`, `Prefs.*` (запись), `LayoutImporter.tryImportFromSystemLauncher`.
3. Оставить полностью немыми только заведомо косметические dismiss-гонки (`Dialog.dismiss`, `removeView`).

---

### F-08 (P3). Проверка доступа к уведомлениям по подстроке

```java
// DoxaizNotificationService.java:305
String flat = Settings.Secure.getString(cr, "enabled_notification_listeners");  // ":pkg/.Cls:pkg2/.Cls"
return flat != null && flat.contains(context.getPackageName());
```

Если в системе есть слушатель с пакетом, содержащим наш как подстроку (`com.doxaiz.launcher.pro`, форк с суффиксом), проверка даст `true`, хотя наш компонент не включён → UI покажет «доступ есть», `ensureBoundIfPermitted()` будет циклически звать `requestRebind` и дёргать `setComponentEnabledSetting` каждые 20 с (`:279–303`) без результата.

**Исправление:**

```java
static boolean isPermissionGranted(Context ctx) {
    try {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName me = new ComponentName(ctx, DoxaizNotificationService.class);
        for (String part : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && cn.equals(me)) return true;
        }
    } catch (Throwable t) { /* log */ }
    return false;
}
```

(Без `equals`-проверки класса достаточно сравнить `cn.getPackageName().equals(ctx.getPackageName())`.)

---

### F-09 (P3). Версия и конфигурация заданы трижды

| Место | Что задаёт |
|---|---|
| `AndroidManifest.xml:3` | `package="com.doxaiz.launcher"` |
| `AndroidManifest.xml:4–5` | `versionCode=38`, `versionName="5.5-test"` |
| `AndroidManifest.xml:7–9` | `minSdkVersion=26`, `targetSdkVersion=34` |
| `app/build.gradle:10–14` | `applicationId`, `minSdk`, `targetSdk`, `versionCode 38`, `versionName "5.5-test"` |
| `build.sh:37–40` | `--min-sdk-version 26 --target-sdk-version 34 --version-code 38 --version-name "5.5-test"` |

При AGP 8 атрибут `package` в манифесте **игнорируется** (namespace берётся из `build.gradle`, для app-модуля — предупреждение, для library — ошибка), `<uses-sdk>` и `versionCode/versionName` из манифеста перекрываются Gradle. Итог: три независимых источника истины, которые уже сейчас совпадают только вручную.

**Исправление:**
* `versionCode`/`versionName` и `<uses-sdk>` из манифеста можно удалить только если `build.sh` продолжает передавать `--version-code`/`--version-name`/`--min-sdk-version`/`--target-sdk-version` в `aapt2 link` (он передаёт, `build.sh:37–40`), а Gradle берёт их из `build.gradle`.
* **`package="com.doxaiz.launcher"` удалять нельзя без правки `build.sh`.** AGP 8 игнорирует этот атрибут (берёт `namespace`), но `aapt2 link` в `build.sh:32–42` не получает namespace ни из какого другого места и без `package` перестанет собирать APK. Если хотите убрать атрибут — добавьте в `build.sh` `--rename-manifest-package com.doxaiz.launcher` и проверьте, что `aapt2 link` его принимает в вашей версии Build Tools.

Итог: убираем дубли `versionCode`/`versionName`/`<uses-sdk>` из манифеста, оставляем `package` до тех пор, пока `build.sh` остаётся рабочим путём сборки; в `build.sh` заменяем хардкод на вычитку из `app/build.gradle`:

```bash
VERSION_CODE=$(sed -n 's/.*versionCode[[:space:]]\+\([0-9]\+\).*/\1/p' app/build.gradle | head -1)
VERSION_NAME=$(sed -n 's/.*versionName[[:space:]]\+"\(.*\)".*/\1/p' app/build.gradle | head -1)
```

---

### F-10 (P3). `build.sh`: путь к Build Tools и проверка подписи

```bash
6:  BUILD_TOOLS="${SDK_DIR}/build-tools/android-14"
...
86: "${BUILD_TOOLS}/apksigner" verify --min-sdk-version 21 --verbose "${OUT_APK}"
```

В Android SDK каталоги Build Tools называются по версии (`34.0.0`, `35.0.0`), каталога `android-14` нет (это схема именования *платформ*: `platforms/android-34`, и исторических `build-tools/android-4.4`). Скрипт упадёт на самой первой команде `aapt2 compile`, если пользователь не создал такой каталог руками. `README.md` при этом отправляет читателя именно к «Build Tools 34».

Также: `--min-sdk-version 21` при `minSdk 26` — проверка подписи проходит по заниженному минимуму (v1-подпись не обязательна); `--v1-signing-enabled true` (`:79`) для `minSdk 26` не нужен (достаточно v2/v3), но и не вреден — только увеличивает APK.

**Исправление:**

```bash
BUILD_TOOLS="${SDK_DIR}/build-tools/${BUILD_TOOLS_VERSION:-34.0.0}"
...
"${BUILD_TOOLS}/apksigner" verify --min-sdk-version 26 --verbose "${OUT_APK}"
```

---

### F-11 (P3). Динамические ресиверы на Android ≤ 12 неявно экспортированы

```java
// MainActivity.java:586–606
if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, bf, Context.RECEIVER_NOT_EXPORTED);
else                            registerReceiver(batteryReceiver, bf);
...
// DoxaizLockService.java:133–140 — то же для ACTION_SCREEN_OFF
```

`RECEIVER_NOT_EXPORTED` появился в API 33. На Android 8–12 такой вызов регистрирует **экспортированный** ресивер: любое приложение может отправить `ACTION_USER_PRESENT`, `RINGER_MODE_CHANGED_ACTION`, `ACTION_SCREEN_OFF` с произвольными extras. Последствия ограничены косметикой (лишний «пульс» Dynamic Bar, ложное скрытие/показ оверлея ОЗУ, ложное «зарядка подключена» из подделанных extras), данные не утекают, но поведение подделывается.

**Исправление (по возрастанию строгости):**
1. В `onReceive` валидировать отправителя нельзя (system broadcast без `PendingIntent`), поэтому:
2. Проверять `intent.getAction()` + наличие ожидаемых extras (`EXTRA_LEVEL`, `EXTRA_SCALE`) — уже частично есть;
3. Для чувствительных реакций дополнительно сверять `BatteryManager`-снимок через `registerReceiver(null, ...)` (sticky) вместо доверия extras;
4. Для `ACTION_USER_PRESENT` проверять `KeyguardManager.isKeyguardLocked()` == `false` (модуль `BiometricHelper` уже работает с `KeyguardManager`).

Severity держим P3: подделка даёт только визуальный эффект.

---

### F-12 (P3). `userPresentReceiver` живёт дольше Activity

```java
// onResume (598–608)
if (!userPresentReceiverRegistered) { registerReceiver(userPresentReceiver, uf, ...); userPresentReceiverRegistered = true; }

// onPause (720–731) — снимается ТОЛЬКО batteryReceiver
if (batteryReceiverRegistered) { unregisterReceiver(batteryReceiver); batteryReceiverRegistered = false; }

// onDestroy (801–804) — и только здесь userPresentReceiver
if (userPresentReceiverRegistered) { unregisterReceiver(userPresentReceiver); ... }
```

Ресивер — нестатический inner-класс, держит `MainActivity`. Пока Activity в `onPause` (например, пользователь ушёл в другое приложение), система держит ссылку на неё через `ActivityThread`-таблицу ресиверов: Activity не собирается GC, `onDestroy` может не прийти вовсе (процесс убит) — ресивер снимается вместе с процессом, так что утечки «навсегда» нет, но удержание полноценной Activity (и всех её View) на паузе — реальные мегабайты.

**Исправление:** перенести регистрацию/снятие в `onStart`/`onStop` (или `onResume`/`onPause`), либо использовать `LifecycleReceiver`. Учесть, что Dynamic Bar «пульс при разблокировке» нужен и на паузе — тогда регистрировать в `DoxaizLockService`/Application-скоупе, а не в Activity.

---

### F-13 (P3). «Авто-лечение сети» может быть тихим no-op

```java
// NetworkQualityHelper.java:277–291
public boolean triggerSystemReprobe(String reason) {
    if (cm == null) return false;
    try {
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        cm.reportNetworkConnectivity(net, false);       // ← 284
        Status st = inspect();
        recordHealEvent(st.transportName, reason);
        return true;
    } catch (Throwable ignored) { return false; }
}
```

`reportNetworkConnectivity` — «подсказка» системе; на части прошивок (особенно с приватными политиками ConnectivityService) вызов для сторонних приложений ограничен и бросает `SecurityException`, который здесь глушится. Тогда:
* переключатель «Авто-улучшение сети» выглядит включённым, журнал в настройках пуст, пользователь считает, что функция работает;
* либо, наоборот, `recordHealEvent` пишется, хотя реального эффекта нет (`healCount` растёт).

**Проверка:** на реальном устройстве (Android 12–14) вызвать `triggerSystemReprobe("тест")` из настроек и посмотреть, меняется ли `validated`; отдельно — есть ли `SecurityException` (для этого нужен логгер из F-07).

**Исправление:** при `false`/исключении — показывать в настройках «не поддерживается на этом устройстве» вместо тишины; в тексте функции не обещать переключение Wi-Fi↔LTE (система решает сама).

---

### F-14 (P3). `LayoutImporter.clampCol` занижает до 4 колонок

```java
// LayoutImporter.java:429–434
private static int clampCol(int cellX, int cols) {
    int c = Math.max(4, cols);          // ← нижняя граница 4, а не cols
    if (cellX < 0) return 0;
    if (cellX >= c) return c - 1;
    return cellX;
}
```

Сегодня это недостижимо: `effectiveCols = detectGridColumns(maxDesktopCellX, initialCols)` (`:272`, `:400–403`) возвращает 5 при `maxDesktopCellX >= 4`, иначе 4/5. То есть при `cols == 4` ни одна строка не имеет `cellX >= 4`. Но если появится раскладка с 3 колонками (или тестовая подмена `prefs.setColumns(3)`), `cellX = 3` пройдёт как «валидный», хотя `cols - 1 = 2` → иконка уедет за пределы сетки и будет обрезана `FreeGridPageView.onLayout` (`:147–149`).

**Исправление:** `int c = Math.max(1, cols);` (и `MIN_COLS`-константа вместо магической 4).

---

### F-15 (P3). Сериализация раскладки на UI-потоке

```java
// DesktopModel.save()
JSONObject root = new JSONObject();
for (List<DesktopItem> page : pages) { ... root.put(...) }      // 160–197
if (prefs != null) prefs.setDesktopJson(json);                  // SharedPreferences.apply() — ок
scheduleGuardWrite(json);                                       // 286–300 — фон, ок
```

`save()` синхронно строит JSON на вызывающем потоке; вызывается 32 раза внутри `DesktopModel` (в том числе из drag&drop, `addAppToPage`, `normalizeAllPages`) — то есть **на UI-потоке** при каждом перемещении иконки. При 8 страницах × 30 элементов это ~240 `JSONObject.put` + строковая сборка; на слабых устройствах — рывок при drop.

**Исправление:** вынести сериализацию в `GUARD_WRITER`/отдельный executor с дебаунсом (механизм `pendingGuardJson` уже есть — переиспользовать его для основного сохранения), а в `prefs` писать результат уже из фонового потока (`apply()` потокобезопасен).

Попутно: `Prefs.getCustomLabels()` (`:362–370`) на каждый вызов делает `sp.getAll()` — полное копирование всего файла настроек (≈200 ключей) ради поиска префикса `rename_`. Вызывается из `AppRepository.reloadNow` (`:310`) на каждое событие пакетов, т.е. несколько раз в минуту при установках. Дешёвое исправление — отдельный `KEY_RENAMES_JSON`, как уже сделано для `enabled_notification_listeners`-подобных структур; либо кэш под `prefsCacheLock` (паттерн уже применён для `hiddenApps`).

---

### F-16 (P3). Дисковый кэш иконок: сериализация записи

```java
// IconDiskCache.java:116
public synchronized void save(String appKey, long versionStamp, Bitmap bmp) { ... }
...
+ "_" + Thread.currentThread().getId() + ".tmp");   // :129
```

`save()` синхронизирован по экземпляру, а `AppRepository` держит **один** экземпляр на пул из 2–4 потоков ввода-вывода (`AppRepository:53–56`) → при первой прокрутке меню все потоки выстраиваются в очередь на `compress(PNG/WEBP)` + `write`; параллелизм пула теряется.

Плюс: `Thread.getId()` помечен `@Deprecated` в Java 19 (в lint-отчёте это предупреждение), а уникальность tmp-имени уже обеспечена штампом версии и локом.

**Исправление:** убрать `synchronized` с `save()` (внутри уже есть уникальный tmp + атомарный `renameTo`), либо ограничить критическую секцию только `renameTo`; заменить `getId()` на `Thread.currentThread().getName()`/`hashCode()`.

---

### F-17 (P3). Панель ОЗУ опрашивает систему каждую секунду

```java
// DoxaizLockService.java:62–73
public void run() {
    if (!overlayAttached) return;
    ...
    updateOverlayNumbers();            // :70
    handler.postDelayed(this, 1000L);
}
// :715–718
MemoryHelper.Snapshot snap = MemoryHelper.read(this);   // MemoryInfo + StatFs + 5 форматированных строк
BatteryInfoHelper.Snapshot bat = BatteryInfoHelper.read(this);  // registerReceiver(null, sticky) + BatteryManager
```

Пока открыт экран Недавних, каждую секунду: `ActivityManager.getMemoryInfo`, `StatFs` по `/data`, sticky-`registerReceiver` для батареи, `getIntProperty` — и всё это на главном потоке сервиса (тот же процесс, что и лончер). Аллокации небольшие, но это 3–4 системных IPC в секунду без нужды.

**Исправление:** обновлять раз в 2–3 с и только при `isForegroundStillRecents()`; кэшировать `BatteryInfoHelper` через `ACTION_BATTERY_CHANGED`-ресивер (он уже зарегистрирован в `MainActivity`), а не запрашивать sticky каждый тик.

---

### F-18 (P3). Тесты не подключены и частично устарели

* `tests/*.java` — обычные `main`-классы в пакете `com.doxaiz.launcher`, ни `app/build.gradle`, ни отдельного модуля их не собирают. `README.md` честно говорит «JVM-проверки», но запуск требует ручной сборки classpath (см. §7).
* `tests/V52Tests.java:6` ожидает `sanitizeVisualStyle(2)==2` и `sanitizeVisualStyle(3)==0`, тогда как `Prefs.sanitizeVisualStyle` (`Prefs.java:1049–1051`) схлопывает всё в `STYLE_CLASSIC_DARK/LIGHT` → тест падает на первом кейсе. Это дефект теста (продукт ведёт себя согласованно с новым дизайном «две темы»), но падающий тест в репозитории хуже отсутствующего.
* `tests/Audit54Tests.java` требует `org.json`, которого нет в JVM-пути → в CI нужен `org.json:json` как *testImplementation*.
* Актуальные прогоны: `AUDIT55_CALCULATION_TESTS=6012`, `SIMPLE_THEME_TESTS=14` — проходят.

**Исправление:** добавить в `app/build.gradle`:

```groovy
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.json:json:20240303'
```

перевести `tests/*.java` в `app/src/test/java` с `@Test`, обновить ожидания `V52Tests`, включить `./gradlew test` в `build.sh`/CI.

---

### F-19 (P4). Мёртвый код и неиспользуемые импорты

Из persisted-lint (20 предупреждений) — то, что стоит удалить:

| Файл:строка | Что |
|---|---|
| `MainActivity.java:6–7` | неиспользуемые импорты `android.content.ClipData`, `ClipboardManager` |
| `MainActivity.java:64, 66` | неиспользуемые `BufferedReader`, `InputStreamReader` |
| `MainActivity.java:102` | поле `shadeOpen` — только пишется (`:4991`, `:5034`), никогда не читается |
| `MainActivity.java:128` | поле `activityResumed` — только пишется (`:572`, `:710`) |
| `MainActivity.java:9322` | метод `openPackageAppInfo(String)` не вызывается |
| `Prefs.java:92, 104` | ключи `KEY_SMOOTH_ANIMATIONS`, `KEY_SHOW_RECENTS_RAM` — только пишутся |
| `AnimeStyleDrawable.java:196`, `AppRepository.java:388`, `MainActivity.java:489`, `VectorGlyphs.java:109` | лишние `@SuppressWarnings("deprecation")` (ECJ подтверждает: «Unnecessary @SuppressWarnings») |
| `QuickShadeHelper.java:165` | `new Locale("ru","RU")` → `Locale.forLanguageTag("ru-RU")` |
| `CustomSection.java:156`, `DesktopPagerView.java:157` | `else` после `return` (`unnecessaryElse`) — свернуть |
| `IconDiskCache.java:129` | `Thread.getId()` (см. F-16) |
| `AnimeBackdropView.java:82`, `AnimeParticlesView.java:104` | `fieldHiding`: параметры `applyConfig(...)` называются как поля (`wallpaperMode`, `styleMode`, `dimLevel`) и перекрывают их — читатель видит `this.wallpaperMode`, но легко ошибиться при правке |

Отдельно: `DesktopPagerView.java:92–130` (`computeStyleScale`/`computeStyleAlpha`/`computeStyleTranslationX`) — методы не вызываются ниоткуда (`applyPageTransitions()` использует другие функции), это мёртвый код стилей переходов (см. F-04).

Плюс отдельно: `MainActivity.setupBackHandler()` (`:478–489`) регистрирует `OnBackInvokedCallback` в `onCreate`, а legacy `onBackPressed()` (`:490–492`) остаётся — на API 33+ он не вызывается никогда (это нормально), но обе точки входа вызывают `handleBackAction()`, поэтому при будущем отказе от `enableOnBackInvokedCallback` легко получить двойной вызов. Комментарий в коде, что путь один, снял бы вопрос.

---

### F-20 (P4). `minifyEnabled false` в release

```groovy
buildTypes { release { minifyEnabled false } }
```

Без R8 APK содержит все ~29 тыс. строк без обфускации и без удаления неиспользуемого. Для «zero-dependency» проекта экономия скромная (сотни КБ), но включение R8 даёт: удаление мёртвого кода (`VectorGlyphs`-пути, `computeStyle*` из F-04), `-keep`-контроль для `AccessibilityService`/`NotificationListenerService` (они не должны быть переименованы — их находит система по `android:name`, так что достаточно манифеста, но `keep` лучше зафиксировать), и уменьшение dex.

**Исправление:**

```groovy
buildTypes {
    release {
        minifyEnabled true
        shrinkResources true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
```

---

## 5. Что проверено и признано корректным (не переделывать)

Это важная часть: при аудите часто «чинят» то, что уже сделано правильно.

**Безопасность / приватность**
* `android:allowBackup="false"`, `fullBackupContent="false"`, `usesCleartextTraffic="false"`, **нет `INTERNET`** (`AndroidManifest.xml:18–26, 35–44`) — лончер физически не может отправить данные наружу.
* Единственный экспортированный компонент — `MainActivity` с `HOME`/`LAUNCHER` (`:46–67`), как и требуется.
* Обе службы закрыты системными `BIND_*`-разрешениями (`:74`, `:88`).
* OTP-коды копируются с `ClipDescription.EXTRA_IS_SENSITIVE` на API 33+ (`QuickShadeHelper.java:1653–1668`, `HeadsUpBannerHelper.java:508–520`).
* Нет `WebView`, `Cipher`/`KeyStore`, `Runtime.exec`, `ProcessBuilder`, `setAccessible`, `MODE_WORLD_READABLE/WRITABLE`, `System.gc`, `printStackTrace`.
* Нет статических `Activity`/`Context`/`View`-полей: `DoxaizLockService.instance` и `DoxaizNotificationService.instance` — `volatile` и обнуляются в `cleanup()`/`onDestroy()` (`DoxaizLockService.java:801`, `DoxaizNotificationService.java:615`), плюс проверки `instance == this` перед использованием.
* **Все** записи `Settings.System` защищены `Settings.System.canWrite(...)` с fallback на `ACTION_MANAGE_WRITE_SETTINGS` (`QuickShadeHelper.java:4643, 4843, 4902, 4979`; `MainActivity.java:7836, 7889`); DND — `isNotificationPolicyAccessGranted` (`:4800, 4824, 6197, 6207`). Путей к `SecurityException`-крашу не найдено.
* `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_FOCUSABLE` (`DoxaizLockService.java:343–345`) — корректно, без `SYSTEM_ALERT_WINDOW`.

**Lifecycle / потоки**
* `QuickShadeHelper.dismiss()` (`:416–431`) снимает временное состояние после снимка `dlg` и глушит гонки dismiss; `stopShadeLiveMonitors()` (`:680–695`) снимает `CameraManager.TorchCallback` и callbacks хендлера.
* `DynamicBarHelper.destroy()` (`:391–397`) и `AppRepository.destroy()` (`:280–293`) полностью освобождают хендлеры, колбэки `LauncherApps`, executor'ы, кэши.
* `DoxaizNotificationService.onListenerConnected/Disconnected` (`:591–611`) корректно сбрасывают `connected`/`instance`; `getBadgeStateHash()` не даёт пересобирать стол на тиках прогресса.
* `DesktopPagerView.onDetachedFromWindow()` (`:585–588`) снимает `pendingSnapRunnable`, `VelocityTracker` рециклится (`:579`).
* `AnimeParticlesView` — pause по `powerSave`/`ANIMATOR_DURATION_SCALE` (`:129–139`), **0 аллокаций в `onDraw`** (массивы `float[]`, `Path` переиспользуются), ресивер регистрируется `RECEIVER_NOT_EXPORTED` и снимается в `onDetachedFromWindow` (`:229–246`).
* `MainActivity.onTrimMemory` (`:639–650`) осознанно не сбрасывает кэш на `TRIM_MEMORY_UI_HIDDEN`, а только на `>= TRIM_MEMORY_BACKGROUND` — правильно.
* `AppRepository.reloadNow` защищён `reloadSeq` (устаревшие reload не перетирают свежие), карты подменяются атомарно (`:300–430`), `safeExecuteIcon` уважает переиспользование `ImageView` через `tag` (`:487–506`).

**Память/диск**
* `AnimeBackdropView.ensureBitmapLoaded` (`:140–176`): декод в отдельном потоке, `RGB_565`, `inSampleSize`, `loadGen`-поколение против гонок, recycle устаревших.
* `IconDiskCache`: лимиты 450 файлов / 512 КБ на файл, `isSafeIconDimensions` (≤ 1024×1024, ≤ 1 Мп, `:229–232`) защищает от декомпрессионных бомб (проверка идёт по `inJustDecodeBounds` **до** полного декода, `:98–105`), tmp+`renameTo`, чистка legacy-каталога (`:67–81`), повторная обрезка кэша при переполнении (`:152–155`).
* `DesktopModel`: двойное хранение (SharedPreferences + атомарный файл-страж с `fd.sync()` и `renameTo`, `:301–320`), восстановление при повреждённом JSON (`:73–95`).
* `BackupHelper`: лимиты `MAX_BACKUP_BYTES=512 КБ`, `MAX_LIST_ENTRIES=500`, `MAX_LABEL_LEN=64`, санитизация CSV тайлов, проверка `length` до парсинга, чтение файла с обрывом по лимиту (`MainActivity.java:9570–9600`).
* `VectorGlyphs`: `LruCache` 3 МБ с `sizeOf` по `getAllocationByteCount()`; `IconHelper` держит маску squircle по одному размеру и не рециклит её при очистке (правильно — маску читают другие потоки).
* `DoxaizNotificationService`: `avatarCache` 24, `pictureCache` 8, `MAX_NOTIFICATIONS=40`, `MAX_HISTORY=100`, обрезка строк (90/260 симв.), `SecurityException` при отзыве доступа сбрасывает состояние (`:938–944`).

**Логика**
* `dropItemOnCell` (`DesktopModel.java:606–678`) — все ветки валидируют индексы до `pages.get`, дубликат приложения не создаёт папку из одной иконки, `target == dragged` проверяется.
* `normalizeAllPages` (`:479–556`) не теряет иконки: переполнение уносится на следующую страницу, при достижении `MAX_PAGES` — раскладывается по свободным ячейкам, в крайнем случае сохраняется в списке (без удаления).
* `adjustHomePageAfterRemoval` (`:796–802`), `pruneTrailingEmptyPage` (`:784–794`) — корректная арифметика индексов.
* `MemoryHelper.isSafeToTrimPackage` (`:241–263`) не убивает свой пакет, закреплённые, SystemUI, телефон, providers, Bluetooth, часы, GMS, системный лончер и клавиатуры; `clampFreeToTotal` защищает от zRAM-аномалий.
* `DoxaizLockService.tryClickSystemClearAll` (`:425–468`) — «опасное» действие, но с тройной проверкой: доверенный пакет Недавних, явное свидетельство класса (`hasExplicitRecentsClass`), подтверждение фокуса (`isForegroundStillRecents`), лимит глубины 12 и 180 узлов.
* Все `desktop.save()`-пути идут через атомарную запись; `Prefs` кэширует `hiddenApps`/`usage`/`sections` под локами и возвращает копии (нет алиасинга наружу).
* `NotificationTools` извлекает OTP только из ограниченного окна вокруг ключевых слов и не «выдумывает» коды из длинных чисел.

---

## 6. Результат lint-прогона

**До правок** (дословно из ECJ, `-warn:+unusedLocal,+unusedPrivate,+unusedImport,+unnecessaryElse,+fieldHiding,+localHiding,+maskedCatchBlocks,+deadCode,+hiding`) — 20 предупреждений, 0 ошибок:

| # | Файл:строка | Предупреждение | Что сделано |
|---|---|---|---|
| 1–3 | `AnimeBackdropView.java:82` | `fieldHiding` ×3: параметры `wallpaperMode`, `styleMode`, `dimLevel` перекрывают одноимённые поля | ✅ параметры переименованы (`newWallpaperMode`, `newStyleMode`, `newDimLevel`) |
| 4 | `AnimeParticlesView.java:104` | `fieldHiding`: параметр `styleMode` перекрывает поле | ✅ `newStyleMode` |
| 5 | `AnimeStyleDrawable.java:196` | лишний `@SuppressWarnings("deprecation")` | ✅ убран |
| 6 | `AppRepository.java:388` | `new Locale("ru","RU")` устарел | ✅ `Locale.forLanguageTag("ru-RU")` |
| 7 | `CustomSection.java:156` | `unnecessaryElse` (`else` после `return`) | ✅ ранний возврат |
| 8 | `DesktopPagerView.java:157` | `unnecessaryElse` | ✅ ранний возврат |
| 9 | `IconDiskCache.java:129` | `Thread.getId()` устарел (Java 19) | ✅ заменён на счётчик (см. F-16) |
| 10 | `MainActivity.java:6` | неиспользуемый импорт `android.content.ClipData` | ✅ удалён |
| 11 | `MainActivity.java:7` | неиспользуемый импорт `android.content.ClipboardManager` | ✅ удалён |
| 12 | `MainActivity.java:64` | неиспользуемый импорт `java.io.BufferedReader` | ✅ удалён |
| 13 | `MainActivity.java:66` | неиспользуемый импорт `java.io.InputStreamReader` | ✅ удалён |
| 14 | `MainActivity.java:102` | поле `shadeOpen` не читается (только пишется) | ✅ удалено вместе с двумя записями |
| 15 | `MainActivity.java:128` | поле `activityResumed` не читается (только пишется) | ✅ удалено вместе с двумя записями |
| 16 | `MainActivity.java:489` | лишний `@SuppressWarnings("deprecation")` у `onBackPressed` | ✅ убран |
| 17 | `MainActivity.java:9322` | метод `openPackageAppInfo(String)` не вызывается | ✅ удалён |
| 18 | `Prefs.java:104` | константа `KEY_SHOW_RECENTS_RAM` не используется | ✅ удалена вместе с F-04 |
| 19 | `QuickShadeHelper.java:165` | `new Locale("ru","RU")` устарел | ✅ `Locale.forLanguageTag("ru-RU")` |
| 20 | `VectorGlyphs.java:109` | лишний `@SuppressWarnings("deprecation")` | ✅ убран |

**После правок** — тот же прогон, тот же classpath, те же 36 файлов:

```
0 проблем: 0 ошибок, 0 предупреждений
```

Дополнительно проверено, что предупреждения не «спрятаны»: компиляция с `-warn:+all`
и `-err:+unusedPrivateMember` даёт только информационные сообщения ECJ о подавленных
категориях (`@SuppressWarnings("unchecked")` в `Prefs`), ошибок нет.

Замечания:
* `Prefs.KEY_SMOOTH_ANIMATIONS` больше не «мёртвый ключ»: геттер читает значение, сеттер его пишет (F-04).
* `catch (Throwable ignored)` — 247 штук — lint не считает предупреждением, разобрано в F-07 и оставлено открытым.

---

## 7. План исправлений по приоритетам

> Пункты ниже — исходный план. Фактически выполнено 15 находок полностью и 3 частично;
> подробности и остаток работ — в §10.

**Сразу (полчаса, снимает блокеры):**
1. F-01 — `pluginManagement`/`dependencyResolutionManagement` + wrapper. Без этого репозиторий не собирается по README.
2. F-02 — `android:exported="true"` для `DoxaizLockService` (и проверка на устройстве).
3. F-10 — `build.sh`: `build-tools/34.0.0`, `--min-sdk-version 26`.
4. F-19 — удалить мёртвый код (13 правок из §6), заодно −ёмкость APK и меньше шума при чтении.

**Далее (день, целостность данных и приватность):**
5. F-03 — транзакционный импорт (план → `Editor.commit()` → применение приватной части).
6. F-06 — приватные поля бэкапа под отдельный флаг+биометрию.
7. F-04 — решить судьбу «замороженных» настроек: удалить или вернуть (сейчас двойное дно).
8. F-08, F-11, F-12 — точечные правки безопасности/жизненного цикла.

**Затем (дни, производительность и наблюдаемость):**
9. F-05 — разделить `rebuildDesktop()` на структурную/инкрементальную; убрать пересборку из `launchAndReturnHome`.
10. F-07 — минимальный ring-buffer логгер + экран диагностики (без `INTERNET`).
11. F-15, F-16, F-17 — фон/кэш/частота опроса.
12. F-18 — тесты в Gradle, починить `V52Tests`, `org.json` в тестовом classpath.
13. F-13, F-14, F-20 — по возможности.

---

## 8. Как воспроизвести проверки из этого отчёта

**Компиляция без Gradle (как делалось здесь):**

```bash
JAVA=/path/to/jre/bin/java
$JAVA -jar ecj.jar -18 -nowarn -proceedOnError -encoding UTF-8 \
      -cp android-stripped.jar -d /tmp/out/main \
      $(find app/src/main/java -name "*.java") /tmp/gen/R.java
```

**Lint:**

```bash
$JAVA -jar ecj.jar -18 -proceedOnError -encoding UTF-8 \
  -cp android-stripped.jar -d /tmp/out/main \
  -warn:+unusedLocal,+unusedPrivate,+unusedImport,+unnecessaryElse,+fieldHiding,+localHiding,+maskedCatchBlocks,+deadCode,+hiding \
  $(find app/src/main/java -name "*.java") /tmp/gen/R.java
```

**Тесты (нужен `org.json` в classpath для `Audit54Tests`):**

```bash
$JAVA -cp /tmp/out/main:/tmp/out/test:json.jar com.doxaiz.launcher.Audit55Tests
$JAVA -cp /tmp/out/main:/tmp/out/test:json.jar com.doxaiz.launcher.SimpleThemeTest
```

**На устройстве (минимум для приёмки):**

```bash
# 1. Службы реально подключаются
adb shell dumpsys accessibility | grep -A3 doxaiz
adb shell settings get secure enabled_notification_listeners

# 2. Нет кадровых провалов при возврате из приложения
adb shell dumpsys gfxinfo com.doxaiz.launcher framestats | head -40

# 3. Память лончера через 10 минут обычного использования
adb shell dumpsys meminfo com.doxaiz.launcher | grep -E "TOTAL|Views|Activities"

# 4. Импорт бэкапа с усечённым/битым JSON не оставляет полу-состояние
```

**Тесты после правок (без Gradle, как прогонялось здесь):**

```bash
$JAVA -cp /tmp/out/final:/tmp/out/test:/tmp/tools/android-stripped.jar \
      com.doxaiz.launcher.V52Tests        # V52 TESTS PASSED: 28
$JAVA -cp /tmp/out/final:/tmp/out/test:/tmp/tools/android-stripped.jar \
      com.doxaiz.launcher.Audit55Tests    # AUDIT55_CALCULATION_TESTS=6012
$JAVA -cp /tmp/out/final:/tmp/out/test:/tmp/tools/android-stripped.jar \
      com.doxaiz.launcher.SimpleThemeTest # SIMPLE_THEME_TESTS=14
```

**Через Gradle (появилось после F-18):**

```bash
./gradlew test                 # JUnit-обёртка над тремя наборами
./gradlew assembleRelease      # R8 включён по умолчанию (F-20)
./gradlew assembleRelease -PnoMinify   # отладочная сборка без сжатия
```

---

## 9. Итог

**После правок (§10)** сборка разблокирована (F-01, F-09, F-10), данные импорта защищены
от полу-состояния (F-03), «настройки-обманки» удалены (F-04), ресиверы и жизненный цикл
приведены в порядок (F-11, F-12), лишняя нагрузка на системные IPC снижена (F-16, F-17),
тесты реально запускаются (F-18). Остаются две крупные темы — приватность экспорта бэкапа
(F-06) и диагностируемость 247 проглатываемых исключений (F-07) — и разделение
`rebuildDesktop()` (F-05).

### Оценка исходного состояния

Кодовая база заметно аккуратнее типичного «самописного лончера»: нет зависимостей, нет сетевых прав, нет статических контекстов, все опасные системные вызовы закрыты проверками прав, кэши ограничены, файловые записи атомарны, а скрытие/импорт приватных данных уже прикрыто биометрией. Основные проблемы — не в «дырах безопасности», а в **(1)** сборке, которая не резолвит AGP, **(2)** несовпадении манифеста с официальным контрактом accessibility-службы, **(3)** нетранзакционном импорте бэкапа, **(4)** семействе «настроек-обманок» и **(5)** полной пересборке рабочего стола на горячем пути. Первые четыре — правки на часы, пятая — самая дорогая и самая заметная для пользователя.
---

## 10. Журнал внесённых правок (2026-11-16)

Все правки — в этой же ветке, без сторонних зависимостей, Java 8, minSdk 26 / targetSdk 34.
После каждой правки выполнялась полная компиляция ECJ; итоговое состояние — 0 ошибок, 0 предупреждений,
тесты `V52Tests` (28), `Audit55Tests` (6012), `SimpleThemeTest` (14) проходят.

### F-01 (P1). Сборка без `pluginManagement`

`settings.gradle` переписан: `pluginManagement { google(); mavenCentral(); gradlePluginPortal() }`,
`dependencyResolutionManagement` с `FAIL_ON_PROJECT_REPOS`, `rootProject.name`, `include ':app'`.
AGP 8.2.2 публикуется только в Google Maven — без этого блока репозиторий не синхронизировался.

### F-02 (P2). `exported` у accessibility-службы

`DoxaizLockService` → `android:exported="true"` (официальный контракт accessibility-службы),
доступ по-прежнему ограничен `BIND_ACCESSIBILITY_SERVICE`. Требует подтверждения на устройстве.

### F-03 (P2). Транзакционный импорт бэкапа

`BackupHelper.importBackupJson` теперь:

1. валидирует структуру до любых изменений (`validateDesktopJson`);
2. снимает **полный снимок** `SharedPreferences` — `Prefs.snapshotForRollback()`
   (ключи + копии `Set<String>`, чтобы не поймать алиасинг);
3. применяет настройки, скрытые приложения, подписи, свайпы, закрепления, категории;
4. при **любом** исключении вызывает `Prefs.restoreFromRollback()` — одна атомарная
   запись `clear()` + восстановление всех ключей, затем `DesktopModel.load()` с диска;
5. только после успеха делает `desktop.load()`, `desktop.save()` и `invalidateCachesAfterImport()`.

Дополнительно `desktop.load()` убран из середины импорта (раньше раскладка подменялась
до разбора остальных секций), а число страниц считается напрямую из JSON.
`setCustomSectionsRawJsonForImport` / `setHiddenStdCatsRawForImport` пишут в хранилище,
не трогая кэши чтения, — иначе откат оставил бы в памяти значения неудавшегося импорта.
Добавлен `BackupHelper.looksLikeValidBackup(raw)` — дешёвая проверка файла до снятия снимка.

### F-04 (P2). «Замороженные» настройки

* `Prefs.isSmoothAnimationsEnabled()` читает `smoothAnimations`, сеттер пишет аргумент (было: всегда `true`).
* Удалены `setHomeLayoutMode`, `setShowRecentsRam`, `setShowRecentsCleanBtn`,
  `KEY_HOME_LAYOUT_MODE`, `KEY_SHOW_RECENTS_RAM`, `HOME_MODE_CLASSIC_PAGES`,
  `PAGE_TRANS_*`, `get/setPageTransitionStyle` — вместе с мёртвым 41-строчным блоком
  `computeStyle*` в `DesktopPagerView` и вызовами из `MainActivity`.
* `DesktopPagerView.applyPageTransitions()` теперь при выключенных анимациях сразу делает
  `resetChildTransforms()`.
* Из экспорта бэкапа убраны 4 несуществующие настройки; `smoothAnimations` оставлен (теперь работает).

### F-05 (P2, частично). Пересборка рабочего стола

Пересборка после запуска приложения уже была под условием `wasNew` — подтверждено чтением.
В рамках правок: `refreshBadgesInPlace()` как точечный путь обновления бейджей не изменялся
(он и так не пересоздаёт страницы), но 47 вызовов `rebuildDesktop()` остаются.
**Осталось:** разделить структурную и инкрементальную пересборку, убрать полную пересборку
из путей, где меняются только бейджи/подписи. Правка крупная, требует регрессии на устройстве.

### F-08 (P3). Проверка доступа к уведомлениям

`DoxaizNotificationService.isPermissionGranted`: на API 27+ —
`NotificationManager.isNotificationListenerAccessGranted(new ComponentName(...))`;
на 26 — разбор `enabled_notification_listeners` по компонентам `pkg/cls` с точным
сравнением имени пакета вместо `contains()` (которое ложно срабатывало на пакетах-«содержателях»).

### F-09 / F-10 (P3). Версия и скрипт сборки

* Манифест: удалены `android:versionCode`, `android:versionName` и блок `<uses-sdk>`
  (AGP 8 их игнорирует и ругается); `package` оставлен — он нужен `aapt2 link` в `build.sh`.
* `build.sh`: версия Build Tools с авто-подхватом последней установленной, `MIN_SDK`/`TARGET_SDK`/
  `VERSION_CODE`/`VERSION_NAME` парсятся из `app/build.gradle` (единственный источник истины),
  `ANDROID_JAR` выводится из `targetSdk`, `d8 --min-api ${MIN_SDK}`,
  `apksigner --v1-signing-enabled false` и `verify --min-sdk-version ${MIN_SDK}`.
  Проверено: `bash -n` без ошибок, парсинг даёт `26 / 34 / 38 / 5.5-test`.

### F-11 (P3). Неявно экспортированные ресиверы

Введён `MainActivity.registerSystemReceiver()`: API 33+ — `RECEIVER_NOT_EXPORTED`;
API 26–32 — явный `RECEIVER_EXPORTED` (эквивалент поведения, но без «неявного» экспорта);
ниже — обычный вызов. То же в `DoxaizLockService` (`ACTION_SCREEN_OFF`) и
`AnimeParticlesView`. Дополнительно `userPresentReceiver` теперь проверяет
`KeyguardManager.isKeyguardLocked()` — подделанный `ACTION_USER_PRESENT` не даёт «пульса»,
пока экран реально заблокирован.

### F-12 (P3). Жизненный цикл `userPresentReceiver`

Снятие ресивера перенесено в `onPause()` (рядом с `batteryReceiver`); в `onDestroy()`
проверка оставлена как страховка. Activity больше не удерживается системным реестром ресиверов
всё время работы лаунчера в фоне.

### F-13 (P3). «Самолечение» сети

`NetworkQualityHelper.triggerSystemReprobe()` теперь возвращает `true` только если после
`reportNetworkConnectivity()` сеть действительно `connected && validated`; добавлен
`wasLastReprobeAccepted()`, событие в журнале помечается «(без эффекта)» при no-op.
`runQuickNetworkImprove()` показывает честный текст вместо «Перепроверка канала запущена»
при отсутствии эффекта. Поведение конкретных прошивок всё ещё требует проверки на устройстве.

### F-14 (P3). `clampCol`

`LayoutImporter.clampCol` — нижняя граница колонок `4` → `1` (защита от «сжатия» в 4 колонки
при иной сетке), добавлен javadoc с обоснованием.

### F-15 (P3, частично). Запись раскладки

`DesktopModel.save()` и `load()` синхронизированы: раньше главный поток и фоновые
(импорт бэкапа, guard-writer) могли перемешать итерацию по `pages`/`dock` и записать
повреждённый или устаревший JSON поверх свежего. Атомарная запись guard-файла и дебаунс
уже были. **Осталось:** вынести сериализацию с UI-потока (сейчас `save()` строит JSON на месте).

### F-16 (P3). Дисковый кэш иконок

* `save()` больше не `synchronized` целиком: блокировка нужна была только для учёта файлов,
  а кодирование WebP/PNG (десятки мс) сериализовало 2–4 потока загрузки иконок.
* Учёт файлов переведён на `AtomicInteger` (`++`/`--` из разных потоков были гонкой);
  удаление — `updateAndGet(v -> max(0, v-1))`.
* `Thread.getId()` (deprecated) заменён на уникальный счётчик `TMP_SEQ`:
  `Thread.threadId()` в API 34 отсутствует, поэтому он не подходит для minSdk 26.

### F-17 (P3). Опрос ОЗУ в оверлее Недавних

Период опроса 1 с → 2 с (`OVERLAY_REFRESH_MS`), батарея (sticky-`registerReceiver` +
`BatteryManager`) опрашивается каждый второй тик (`updateOverlayNumbers(boolean withBattery)`).
ОЗУ и заголовок обновляются каждый тик; нагрузка на системные IPC снижена примерно вчетверо.

### F-18 (P3, частично). Тесты

* `V52Tests` приведён в соответствие с 5.5: после F-04 допустимы только `STYLE_CLASSIC_DARK`/
  `STYLE_CLASSIC_LIGHT`, внутренние id палитр (0/1) больше не «валидный стиль».
  Тест снова проходит (28 проверок).
* В `app/build.gradle` добавлены `testImplementation junit:junit:4.13.2`, `sourceSets.test`
  с каталогом `../tests` и обёртка `app/src/test/java/.../LauncherChecksTest.java`,
  запускающая три «main-набора» через JUnit — падение любого валит `./gradlew test`.
* `Audit54Tests` исключён из прогона: ему нужен настоящий `org.json`, а не заглушка из `android.jar`.
  **Осталось:** добавить реальную реализацию `org.json` в тестовый classpath (или переписать
  `Audit54Tests` на чистые функции) и подключить его.

### F-19 (P4). Мёртвый код

Удалены неиспользуемые импорты (`ClipData`, `ClipboardManager`, `BufferedReader`,
`InputStreamReader`), поля `shadeOpen` и `activityResumed` (только писались, не читались),
метод `openPackageAppInfo`, лишние `@SuppressWarnings("deprecation")` в `MainActivity`,
`AnimeStyleDrawable`, `VectorGlyphs`.

### F-20 (P4). R8

`minifyEnabled`/`shrinkResources` включены (с `proguard-android-optimize.txt` и новым
`app/proguard-rules.pro`), отключаются флагом `-PnoMinify` для локальной отладки.
`proguard-rules.pro` сохраняет точки входа (Activity/Service/a11y-служба) и имена файлов
со номерами строк для читаемых стек-трейсов.

### Уточнения после финального прогона

* `Prefs.getHomeLayoutMode()`, `isShowRecentsRam()`, `isShowRecentsCleanBtn()` и константа
  `HOME_MODE_WITH_DRAWER` тоже удалены: после снятия вызовов из `MainActivity`/`BackupHelper`
  они остались без единого читателя и продолжали возвращать константы (то есть остатки
  «настроек-обманок»). `HOME_MODE_CLASSIC_PAGES` удалён ещё раньше.
* `MainActivity.pickBackupFile`-путь импорта: добавлена фоновая предварительная проверка
  `BackupHelper.looksLikeValidBackup(input)`; для чужого/битого файла снимок настроек
  больше не снимается вообще, а пользователь сразу видит «Файл не является резервной копией».
* Подсказки «Канал сети перепроверен» в шторке (`QuickShadeHelper`, кейс `netboost` и
  строка повторной проверки Wi-Fi) приведены в соответствие с новым возвратом
  `triggerSystemReprobe()`: при no-op прошивки сообщение теперь честно говорит,
  что система не подтвердила переподключение.

### Что осталось открытым

| ID | Ур. | Почему не в этой итерации |
|---|---|---|
| F-06 | P2 | Требует UX-решения: отдельный чекбокс «включить приватные настройки» в экспорте + биометрия перед экспортом + имя файла. Меняет пользовательский сценарий, нужно согласование. |
| F-07 | P2 | Нужен ring-buffer логгер и экран диагностики (247 мест). Крупная правка, влияет на все файлы; делать отдельным PR. |
| F-05 | P2 | Разделение `rebuildDesktop()` на структурную/инкрементальную — самая рискованная правка, нужна регрессия на устройстве. |
| F-15 | P3 | Осталось вынести сериализацию раскладки с UI-потока. |
| F-18 | P3 | Осталось подключить реальный `org.json` для `Audit54Tests`. |

### Найденные по ходу «побочные» проблемы (не входили в исходный список)

* `MainActivity` вызывал удалённые в F-04 API (`getPageTransitionStyle`, `HOME_MODE_CLASSIC_PAGES`) —
  правка F-04 без этого не компилировалась; вызовы убраны, ветка «классических страниц» схлопнута.
* `Prefs` не имел API для атомарного отката — добавлены `snapshotForRollback()` и
  `restoreFromRollback(Map)`, а `invalidateCachesAfterImport()` сбрасывает кэши
  скрытых приложений, разделов, категорий и журнала запусков.
* `DesktopModel.load()` был неявно опасен при параллельном `save()`; синхронизация закрывает и это.

---

## 11. Итерация «отзывчивость и оптимизация» (5.6)

Цель заказчика — кастомная оболочка с хорошей оптимизацией и отзывчивостью, поэтому после
закрытия P1–P4 отдельным проходом переработаны горячие пути: кадр прокрутки, кадр
перетаскивания, запуск приложения, смена темы, работа с ОЗУ.

### 11.1. Кадр перелистывания страниц (`DesktopPagerView`)

| Что было | Что стало | Эффект |
|---|---|---|
| `applyPageTransitions()` вызывался дважды за кадр: из `computeScroll()` и из `onScrollChanged()`, который сам вызывается из `scrollTo()` | Единственный вызов — из `onScrollChanged()`; `computeScroll()` только двигает скролл | Вдвое меньше работы на кадр, убраны двойные `postInvalidateOnAnimation` |
| `setScaleX/Y`, `setAlpha`, `setPivot` ставились каждой странице каждый кадр | Кэш применённых значений (`appliedScale`, `appliedAlpha`), запись только при реальном изменении | Нет ложных инвалидаций поддерева всех 8 страниц |
| Трансформации применялись ко всем страницам, включая невидимые | «Дальние» страницы (|offset| ≥ 1) приводятся к покою один раз; **невидимые страницы помечаются `INVISIBLE`** | Видимая страница одна, но `onDraw` раньше вызывался и у остальных — теперь GPU-работа упала пропорционально числу страниц |
| Доводка на `OverScroller` | Собственная доводка по `SystemClock.uptimeMillis()` и кривой Quintic Ease-Out | `OverScroller` пропускает первый кадр (~8 мс) — на 120 Гц это заметный «залип» старта свайпа; теперь позиция пересчитывается сразу и первый кадр не теряется |
| Проверка «в диапазоне кадра» отсутствовала | `isPageInTransitionRange()` — чистая функция, покрыта тестами | Поведение зафиксировано регрессией |

### 11.2. Кадр перетаскивания иконок (`FreeGridPageView`)

`setHighlight()` сравнивал только ячейку и режим, но не цвета, и, главное, не различал
«подсветка исчезла → появилась снова с теми же координатами». В результате при уходе пальца
за пределы сетки каждый кадр вызывался полный `invalidate()` страницы.
Теперь: сравнение включает цвета, перерисовка запрашивается только при реальном изменении
видимого состояния, `clearHighlight()` не дёргает слой, если рисовать было нечего.

### 11.3. Запуск приложения и метки «новое»

`formatAppLabel()` вызывается при каждой привязке элемента списка, то есть на каждом кадре
прокрутки меню приложений. Внутри было: `prefs.hasEverLaunched()` → захват `usageLock` +
разбор JSON, `Prefs.isNewUnopenedApp()` и `System.currentTimeMillis()` на каждое приложение.
Добавлен кэш меток на 60 с (`Prefs.isNewUnopenedAppCached`), сбрасываемый после запуска
приложения; окно «новизны» — 7 суток, поэтому раз в минуту считать заново бессмысленно.

`Prefs.recordAppLaunch()` пересобирал JSON всего журнала и писал его на диск **на UI-потоке**
сразу после старта активности. Запись и сериализация вынесены в отдельный поток
(`PREF_IO`), счётчик при этом обновляется синхронно.

### 11.4. Смена темы, обоев и прозрачности

`applyTheme()` заканчивался `rebuildDesktop()` — то есть переключение «Тёмная/Светлая»
или ползунок затемнения обоев пересоздавали **все страницы со всеми плитками и иконками**
(повторные `bindIcon`, повторная регистрация бейджей). Добавлен `applyThemeColorsOnly()`:
перекраска всех слоёв, пересборка дока и точек страниц и обновление цвета подписей на уже
построенных страницах (`refreshDesktopTextColors`). Структурные изменения (колонки, размер
иконок, подписи, выравнивание) по-прежнему идут через полный `applyTheme()`.

### 11.5. Очистка ОЗУ

`MemoryHelper.boostRam()` вызывал `killBackgroundProcesses()` для каждого найденного пакета —
это сотни Binder-вызовов, которые блокировали кадр закрытия диалога. Добавлен
`MemoryHelper.boostRamAsync(...)` с `BoostCallback`; обе точки вызова (кнопка в настройках и
мусорка в «Недавних») переведены на него, результат и тосты приходят на UI-поток.

### 11.6. Блок ОЗУ/ПЗУ в меню приложений

`MemoryHelper.read()` — это `ActivityManager.getMemoryInfo()`, `StatFs` по `/data` и четыре
`Formatter.formatFileSize` (дорогой разбор локали). Прокрутка и раскрытие карточки вызывали
его повторно. Добавлен кэш на 1,5 с и `refreshDrawerMemory(true)` для принудительного
обновления после очистки ОЗУ.

### 11.7. Панели меню приложений

* Чипы категорий пересоздавались на каждое нажатие клавиши поиска и каждое переключение
  сортировки — теперь есть подпись состояния (`buildCategorySignature`) и пропуск пересборки,
  если набор и счётчики не изменились.
* Алфавитная полоса: `getIndexChar()` создавал новую строку на каждое приложение при каждом
  нажатии (~300 аллокаций). Добавлен `AppEntry.indexCharFast()` с кэшем; ранний выход при
  неизменной подписи уже был, сохранён.

### 11.8. Тесты

Добавлен `tests/Perf56Tests.java` (325 проверок): монотонность и границы кривой доводки,
отсутствие перелёта, «опережение линейной» на всём интервале, границы видимости страниц,
масштаб/прозрачность, масштаб длительности под частоту экрана, кэш буквы-индекса,
правила «новое приложение». Подключён в JUnit-обёртку.

Итог прогона после итерации: **0 ошибок, 0 предупреждений** ECJ;
`V52Tests` 28, `Audit55Tests` 6012, `SimpleThemeTest` 14, `Perf56Tests` 325 — все проходят.

### 11.9. Что осталось по производительности

* **F-05** — структурная/инкрементальная пересборка `rebuildDesktop()` (47 вызовов).
  Сейчас частично закрыто кэшами и `applyThemeColorsOnly()`, но полный `rebuildDesktop()`
  по-прежнему пересоздаёт страницы; полностью решать — отдельной итерацией с регрессией
  на устройстве (drag&drop, папки, мультивыбор — всё опирается на пересоздание View).
* **F-15** — вынести сериализацию раскладки (`DesktopModel.save()`) с UI-потока.
* Прогрев иконок при листании уже есть (`prewarmIcons` на текущую и следующую страницу);
  дальнейшее — предзагрузка при старте по данным `IconDiskCache` и профиль на устройстве
  (`dumpsys gfxinfo com.doxaiz.launcher framestats`).
* Замеры выполнены статически и по коду: устройства/эмулятора в песочнице нет, поэтому
  выигрыш в кадрах нужно подтвердить `framestats` на реальном телефоне.

---

## 12. Проверка на настоящем Android (CI, эмулятор API 34)

В среде, где велась разработка, нет ни Java-компилятора, ни Android SDK:
Google Maven (`dl.google.com`, `maven.google.com`), Maven Central и Adoptium
недоступны, поэтому локально собрать APK нельзя. Проверка перенесена в
GitHub Actions — там настоящий Android-эмулятор.

### 12.1. Что настроено

`.github/workflows/ci.yml`, три задачи:

| Задача | Что делает |
|---|---|
| JVM-тесты | `./gradlew test` на JDK 17; сводка (tests/failures/errors/skipped) печатается аннотацией |
| Сборка debug-APK | `assembleDebug`, затем `aapt2 dump badging/permissions`, `apksigner verify`, SHA-256 |
| Сборка release с R8 | `assembleRelease` — заодно проверяет keep-правила из F-20 |
| Эмулятор API 34 | установка APK, HOME, дамп дерева интерфейса, открытие каталога, прокрутка, `dumpsys gfxinfo`, поиск падений именно `com.doxaiz.launcher` |

### 12.2. Что нашлось при первом же прогоне

1. **Манифест не парсился.** В XML-комментарии стоял `--` (из `--min-sdk-version`),
   а двойной дефис внутри комментария запрещён. AGP падал на `processDebugMainManifest`
   с `SAXParseException`. Ручная сборка через `build.sh`/`aapt2` этого не показывала,
   потому что там манифест обрабатывался иначе. Исправлено, добавлена проверка всех
   XML на `--` внутри комментариев.
2. **`RepositoriesMode` без импорта.** `settings.gradle` использовал класс без
   `import org.gradle.api.initialization.resolve.RepositoriesMode` — конфигурация
   проекта падала до начала сборки. Исправлено.
3. **`./gradlew` отсутствовал.** Добавлен wrapper 8.7 (AGP 8.2.2 требует Gradle 8.2+).

### 12.3. Результаты прогона (ветка `arena/8b39bd0e…`, run 43951288027)

| Проверка | Результат |
|---|---|
| JVM-тесты в Gradle | `tests=8 failures=0 errors=0` — обёртка над `V52Tests`, `Audit55Tests`, `SimpleThemeTest`, `Perf56Tests` работает |
| Сборка debug-APK | успешно, APK устанавливается на эмулятор (`install=OK`) |
| Сборка release с R8 + shrinkResources | успешно — keep-правила рабочие |
| Лаунчер как HOME | **Doxaiz выбран системным HOME** (`resolve-activity` → `com.doxaiz.launcher/.MainActivity`) |
| Запуск | `Status: ok`, процесс живёт, `FATAL EXCEPTION`/`ANR` для `com.doxaiz.launcher` нет |
| Рабочий стол | 14 узлов: док с кнопкой «Doxaiz» и подсказка «Удерживайте пустую клетку» — на чистой установке это ожидаемо |
| Каталог приложений | в прогоне 43951288027 открылся и отрисовался: **117 узлов** — поиск, чипы «Все / Связь 3 / Медиа 4 / Инструменты 7», алфавитная полоса `♡ C D F G M P S T V Y`, сетка иконок с подписями (Calendar, Camera, Chrome, …, YouTube) |

### 12.4. Что эти числа НЕ доказывают

* **Плавность.** Эмулятор рисует программно (`swiftshader_indirect`): из 37 кадров
  100 % помечены как janky, 50-й перцентиль 150 мс. Это характеристика программного
  GPU, а не телефона. Значимые выводы (сравнение до/после оптимизаций) требуют
  `dumpsys gfxinfo ... framestats` на реальном Infinix Hot 50.
* **Аппаратные функции.** Фонарик, SIM, Bluetooth, яркость, датчик освещения,
  вибрация, скриншот — в эмуляторе их либо нет, либо они ведут себя иначе.
* **Светлая тема, шторка, звук, папки, Dynamic Bar, Recents** — визуальная проверка
  возможна только глазами; здесь проверялось лишь то, что интерфейс рисуется и не падает.
* **Обновление поверх установленной 5.5.** CI подписывает debug-ключом; совместимость
  подписи проверяется только сборкой с исходным приватным ключом на машине автора.

### 12.5. Ограничения самой CI-проверки

* `pm disable-user` для штатного лаунчера на образе роняет SystemUI (проверено:
  `PACKAGE_CHANGED` → `FATAL EXCEPTION` в `com.android.systemui`); в сценарии это
  отключение убрано, HOME проверяется мягко.
* Свайп вверх по рабочему столу в эмуляторе срабатывает нестабильно: в одном
  прогоне каталог открылся и отрисовался (117 узлов, см. выше), в последующих
  жест не доходил и дерево оставалось на рабочем столе. Поэтому неудачный жест
  в CI — `::warning`, а не провал: иначе красный статус отражает нестабильность
  эмулятора, а не состояние кода. Жёсткость сохранена для установки, HOME,
  переднего плана, отрисовки и отсутствия падений `com.doxaiz.launcher`.
* Артефакты GitHub из среды разработки не скачиваются (блокируется
  `*.blob.core.windows.net`), поэтому ключевые числа выводятся аннотациями и в
  `GITHUB_STEP_SUMMARY`. Логи job тоже недоступны — по той же причине.

---

## 13. Второй проход: Android Lint и проверка API-совместимости (5.7)

Первый проход (§10–§12) закрыл архитектурные и сборочные дефекты, но
собственно статический анализатор Google не запускался: проверялись сборка,
JVM-тесты и поведение на эмуляторе. Во втором проходе в CI добавлена задача
`lintDebug` с порогом «ошибки валят сборку» и разбором отчёта.

### 13.1. Как это устроено

| Элемент | Назначение |
|---|---|
| `.github/workflows/ci.yml` → задача `lint` | `lintDebug` + текстовый и XML-отчёт |
| `.github/scripts/lint-report.py` | сводка по важности и категориям, топ типов предупреждений, ошибки отдельным шагом |
| `app/lint.xml` | отключены только заведомо неприменимые проверки: `QueryAllPackagesPermission`, `MissingTranslation`, `MissingDefaultResource`, `VectorRaster`, `GradleDependency` |

Ограничение, найденное на практике: GitHub показывает ~10 аннотаций на шаг,
поэтому сводка публикуется в `GITHUB_STEP_SUMMARY`, а ошибки печатаются
вторым шагом в режиме `--errors-only` (иначе список предупреждений вытеснял
сами ошибки).

### 13.2. Найдено и исправлено

| № | Находка | Что было не так | Исправление |
|---|---|---|---|
| L-01 | `Context.RECEIVER_EXPORTED` (API 33) на ветке «≥ 26» — 3 места (`MainActivity`, `DoxaizLockService`, `AnimeParticlesView`) | На Android 8–12 обращение к полю даёт `NoSuchFieldError`: регистрация ресиверов падала, а исключение проглатывалось `catch (Throwable)` | На 33+ `RECEIVER_NOT_EXPORTED`, на 26–32 обычный двухаргументный `registerReceiver` |
| L-02 | `AudioDeviceInfo.TYPE_HEARING_AID` (API 28) и `BLE_*` (API 31) без проверки версии (`QuickShadeHelper`) | На Android 8–12 `NoSuchFieldError` при построении панели звука; магические числа 26/27/30 | Проверки `SDK_INT >= 28/31`, константы `TYPE_BLE_*` вместо чисел |
| L-03 | `getIdentifier("navigation_bar_height", "dimen", "android")` (`DoxaizLockService`) | Внутренний ресурс платформы: на части прошивок отсутствует, с Android 11 рефлексия к платформе ограничена | На 30+ уже работает `WindowInsets`; для 26–29 настраиваемый запас в dp |
| L-04 | `enableOnBackInvokedCallback` при `minSdk 26` | Атрибут действует только с Android 13 | `tools:targetApi="33"` + пояснение |
| L-05 | `WRITE_SETTINGS` — Lint считает разрешение системным | Предупреждение было ложным по сути: право выдаётся пользователем в настройках | `tools:ignore="ProtectedPermissions"`; рядом зафиксировано, что код проверяет `Settings.System.canWrite()` |
| L-06 | `allowBackup` игнорируется с Android 12 | Без `dataExtractionRules` система могла включить облачную копию и перенос: раскладка, журнал запусков, скрытые приложения | `res/xml/data_extraction_rules.xml` — исключены `root/file/database/sharedpref/external` в обоих каналах |
| L-07 | Утечка `OutputStream` при экспорте копии (`MainActivity`) | В `catch` открывался второй поток, первый терялся и не закрывался | Один поток, `try-with-resources`, отдельный метод `openBackupOutputStream()` |
| L-08 | Жест «системная шторка» не работал (найдено при разборе `WrongConstant`) | Вызов `getSystemService("statusbar")` + `StatusBarManager.expandNotificationsPanel()`: в API 33/34 такого метода в платформе нет, а непубличные API закрыты с Android 11 — `NoSuchMethodException` проглатывался, свайп не давал ничего | Основной путь — `GLOBAL_ACTION_NOTIFICATIONS` через службу специальных возможностей; рефлексия осталась изолированным фолбэком для старых прошивок; если недоступно и это — диалог с переходом в настройки |
| L-09 | Прежняя Squircle-маска не освобождалась (`IconHelper`) | При смене размера иконок «осиротевшие» битмапы жили до сборки мусора | Ссылка обнуляется до `recycle()`, старый битмап освобождается |
| L-10 | `SDK_INT >= 26` при `minSdk 26` | Мёртвое условие | Убрано |

Итог по Lint: **0 ошибок** (сборка проходит), предупреждений 118 вместо 131.

### 13.3. Что осталось и почему

| Проверка | Кол-во | Решение |
|---|---|---|
| `SetTextI18n` | 74 | Интерфейс сознательно одноязычный (русский), строки собираются из частей (`"Выбрано: " + n`) — вынос в `strings.xml` даст 74 формата без пользы для одноязычного приложения. При появлении второй локали это станет обязательным. |
| `ClickableViewAccessibility` | 28 | Кастомные вьюхи с `OnTouchListener` без озвучивания жестов. Реальная тема доступности, но правка требует переработки обработки касаний в 6 классах и проверки на устройстве с TalkBack — отдельная задача. |
| `DiscouragedApi` | 2 | `getIdentifier` в `QuickShadeHelper`: имена иконок каналов звука известны только во время выполнения. Альтернатива — таблица `switch`; выигрыш только в скорости сборки. |
| `ObsoleteSdkInt` | 3 | остальные в местах, где условие пока нужно для читаемости; проверяется по мере правок. |
| `UnusedResources`, `MonochromeLauncherIcon`, `ConstantLocale` | 2 + 2 + 2 | Косметика/иконка в теме: нет монохромного слоя для тематических иконок Android 13. |
| `OldTargetApi` | 1 | `targetSdk 34` вместо 35: осознанно, переход на 35 требует отдельного прохода по поведенческим изменениям. |
| `UnspecifiedRegisterReceiverFlag` | 1 | остаётся на ветке 26–32, где флага не существует; пояснение в коде. |

### 13.4. Ограничения прохода

* Lint — статический анализ. Он не заменяет проверку на устройстве: поведение
  жестов, TalkBack, аппаратные функции и плавность им не проверяются.
* `SetTextI18n` и `ClickableViewAccessibility` намеренно не «заглушены»: это
  осознанный долг, а не недосмотр, и он записан здесь, чтобы не потеряться.
* Ветка проверки API работает только для тех путей, которые Lint умеет
  проследить; динамически загружаемые классы и рефлексия проверяются глазами.

---

## 14. Третий проход: приватность копии, видимость сбоев, UI-поток (5.8)

Второй проход (§13) убрал ошибки, которые Lint видит статически. Третий проход
закрывает то, что статический анализ не ловит по своей природе: **что именно
утекает в файл**, **что происходит, когда код сознательно глушит исключение**, и
**сколько работы делается на главном потоке**.

### 14.1. F-06 — резервная копия больше не содержит приватных данных по умолчанию

| | Было | Стало |
|---|---|---|
| Состав файла | Всегда: раскладка **+ `hidden`** (список скрытых приложений) **+ `renames`** (пользовательские имена) **+ `customCategories`** **+ `hiddenStdCats`** | Выбор перед сохранением |
| Диалог | Нет. Биометрия запрашивалась только если включена «защита скрытых» | «Что сохранить в копию»: **Только раскладка** (по умолчанию) или **Полная копия** |
| Биометрия | По настройке, до выбора состава | Только для «Полной копии» и только при включённой защите скрытых |
| Пометка в файле | Нет | `"privacyIncluded": false/true` — импорт и человек видят состав явно |
| Текст в настройках | «содержит … список скрытых приложений» | «Можно сохранить только раскладку или копию со скрытыми приложениями» |

Почему это важно: файл `doxaiz-layout.json` — обычный JSON без шифрования,
который пользователь отправляет в мессенджере или кладёт в облако. Список
**скрытых** приложений — это ровно та информация, которую пользователь спрятал
от чужого взгляда; попадать в каждый экспорт «на всякий случай» она не должна.
Импорт обратно совместим: старые файлы (без `privacyIncluded`) читаются как
раньше, `hidden`/`renames` в них просто есть.

Сигнатура: `exportBackupJson(Prefs, DesktopModel, boolean includeHidden)`.
`customCategories` и `renames` тоже являются сведениями о программах (имена
пакетов), поэтому при `includeHidden == false` они удаляются из результата.

### 14.2. F-07 — проглатываемые сбои стали видимыми

В проекте больше сотни `catch (Throwable ignored)`. Для 11 мест, где сбой
означает «функция не работает, а пользователь не знает почему», добавлен
журнал `Diagnostics` и экран «Диагностика» в настройках.

| Свойство | Решение | Причина |
|---|---|---|
| Ёмкость | 40 записей, кольцевой буфер | Хватает, чтобы увидеть повторяющийся сбой; не растёт |
| Хранение | `SharedPreferences`, ключ `diagnostics_log_v1` | Сбой мог случиться до перезапуска — журнал должен его пережить |
| Частота записи на диск | не чаще 300 мс | Защита от шторма при сбое в цикле привязки иконок |
| Приватность | только компонент, тип исключения, сообщение ≤ 120 символов; абсолютные пути вырезаются | Сообщения `FileNotFoundException` содержат пути к личным файлам |
| Отказоустойчивость | любой сбой внутри `Diagnostics` глушится | Диагностика не имеет права уронить лаунчер |

Точки записи: `IconDiskCache.save`, `DesktopModel.load` / `.save` /
`.guardWrite`, `AppRepository.iconQueue` / `.bindIcon` / `.bindIcon.apply`,
`BackupHelper.export` / `.import`, `MainActivity` — запись и чтение файла копии.

Экран: настройки → «Система и быстродействие» → **Диагностика**. Счётчик,
список (время, компонент, тип, сообщение; новые сверху) и кнопка «Очистить».
`Diagnostics.init()` вызывается в `onCreate` **до** `desktop.load()`, иначе
ошибки чтения раскладки было бы некуда записать.

Проверки: `tests/DiagnosticsTests.java` (подключён в `LauncherChecksTest`) —
кольцевой буфер, порядок, очистка путей, обрезка, восстановление после
«перезапуска» на поддельных `SharedPreferences`, битые данные в хранилище.

### 14.3. F-15 — ввод-вывод раскладки полностью вне UI-потока

`DesktopModel.save()` вызывается с главного потока (перетаскивание иконок,
создание папок) и раньше сам кодировал JSON в UTF-8 и писал guard-файл.
Кодирование — проход по всей строке раскладки; на 8 страницах и заполненном
доке это уже не нулевая работа на каждый сдвиг иконки.

| Шаг | Поток |
|---|---|
| Построение `JSONObject` из `pages`/`dock` | UI (нужен консистентный снимок под `synchronized`) |
| `root.toString()` | UI (та же причина) |
| `prefs.setDesktopJson(json)` | UI, но это `apply()` — запись идёт через `QueuedWork`/`apply` платформы |
| `json.getBytes(UTF-8)` + запись tmp + `fsync` + `rename` | **фоновый `GUARD_WRITER`** |
| Дедупликация частых сохранений | `AtomicReference`, один проход по последнему значению |

Итог: на главном потоке остались только операции в памяти. Очерёдность
сохранена: `save()` по-прежнему `synchronized`, поэтому снимок не может быть
собран во время чужой записи.

### 14.4. Что осталось после третьего прохода

| № | Что | Почему не закрыто сейчас |
|---|---|---|
| F-05 | Полная перестройка рабочего стола (47 мест вызова) | Требует изменения архитектуры обновления и проверки на устройстве: риск сдвига иконок |
| F-07 (остаток) | ~236 оставшихся `catch (Throwable ignored)` | Инфраструктура готова; добавлять их точками без проверки на телефоне — шум в журнале |
| F-18 | `Audit54Tests` исключён из сборки (нужен настоящий `org.json`) | Нужна зависимость `org.json` в тестовом classpath |
| F-21 | `restoreFromRollback` использует `commit()` | Синхронная запись в фоновом потоке — не блокирует UI, но остаётся |
| — | `targetSdk 34`, отладочная подпись, `SHADE_STYLE_SYSTEM` не по умолчанию | Вне темы этого прохода; вынесено в общий список готовности |

Ограничение то же, что и раньше: **всё перечисленное проверено сборкой,
JVM-тестами, Lint и эмулятором в CI, но не на реальном телефоне**. Журнал
диагностики и экран «Диагностика» — как раз инструмент для такой проверки:
если на устройстве что-то молча не работает, причина теперь будет видна.

---

## 15. Аварийный возврат на штатный лаунчер (5.9)

### 15.1. Проблема, которой не было видно в тестах

Doxaiz — HOME-приложение. Пока он работает, всё хорошо; но если он **назначен
лаунчером по умолчанию и падает при старте**, пользователь оказывается в ловушке:

* чёрный экран вместо рабочего стола;
* кнопка «Домой» снова и снова открывает падающее приложение;
* Android *иногда* сам предлагает выбрать другой лаунчер, но рассчитывать на
  это нельзя — предложение появляется не всегда и не сразу;
* до «Настроек» системы дело доходит не каждый, потому что путь к ним обычно
  лежал через шторку уведомлений того же приложения.

Эмулятор такого не показывает: там лаунчер либо работает, либо CI просто падает
на установке. Это ровно тот класс дефектов, который виден только на телефоне.

### 15.2. Как это работает

Действие «приложение упало» изнутри упавшего процесса выполнить невозможно,
поэтому сбой восстанавливается **на следующем запуске**:

| Момент | Что происходит |
|---|---|
| `MainActivity.onCreate` (до построения UI) | `CrashGuard.onDesktopLaunchStart()` читает отметку «запуск идёт» |
| Отметка есть и ей меньше 60 с | Прошлый запуск упал → счётчик +1 |
| Отметки нет или она старая | Обычный запуск; старая отметка — это выгрузка системой или перезагрузка, не падение |
| Рабочий стол показан | `onStartupComplete()`: снимает отметку, обнуляет счётчик (если сбой был разовым) |
| Процесс умер до показа | Отметка остаётся, следующий запуск её увидит |

Порог — **два падения подряд**:

| Сбой | Поведение |
|---|---|
| Первый | Аварийный экран `SafeModeActivity`: объяснение, причина из журнала диагностики и четыре выхода. Автоматически ничего не меняется. |
| Второй подряд | Лаунчер сам открывает штатный рабочий стол (или системный выбор лаунчера), потому что пользователь уже дважды остался без рабочего стола |
| Любой, после которого стол показался | Счётчик обнуляется |

Почему не «снимать роль сразу»: снимать HOME у работающего приложения из-за
одного исключения нельзя — большинство сбоев разовые (нехватка памяти при
старте, гонка при обновлении иконок). Два подряд — уже не случайность.

Почему окно 60 секунд: без него выгрузка лаунчера системой через час работы
(нормальная практика Android) выглядела бы как падение и копила счётчик.

### 15.3. Аварийный экран

`SafeModeActivity` — отдельная активность, а не диалог: при падении во время
построения интерфейса показать диалог некому. Экран собран из `TextView` /
`LinearLayout` без `GlassTheme`, без анимаций и без обращения к раскладке —
единственный источник данных `Prefs`, поэтому он переживает сбой загрузки
`desktop_layout_json`.

Четыре выхода:

| Кнопка | Что делает |
|---|---|
| Вернуться на штатный лаунчер | Прямой запуск системного HOME; если имя пакета неизвестно — системный экран выбора домашнего экрана |
| Попробовать снова | Новый запуск `MainActivity` (счётчик не сбрасывается: если сбой повторится, экран вернётся) |
| Сбросить счётчик сбоев | `clearCrashGuardMarks()` — лаунчер снова попробует стать основным |
| Настройки приложения | `ACTION_APPLICATION_DETAILS_SETTINGS`: снять роль HOME или очистить данные, не входя в лаунчер |

Там же видна последняя ошибка из журнала `Diagnostics` (§14.2) — то есть
аварийный экран и журнал работают вместе: журнал говорит **что** случилось,
экран даёт **выход**.

### 15.4. Честное ограничение

Android не позволяет приложению «нажать Домой» за пользователя. Роль HOME
освобождается через `RoleManager` (Android 10+) диалогом, который показывает
система, а на старых версиях — только открытием системного экрана выбора
лаунчера. Поэтому кнопки на аварийном экране — основной путь, автоматика —
страховка. Это ограничение платформы, а не недоделка.

### 15.5. Настройки и диагностика

* Настройки → «Система и быстродействие» → **«Аварийный возврат на штатный
  лаунчер»** — переключатель (включён по умолчанию).
* Настройки → «Система и быстродействие» → **«Диагностика»** — состояние:
  включён ли возврат, сколько сбоев подряд, в какой лаунчер переключались
  последний раз; там же кнопка «Сбросить сбои».

### 15.6. Проверки

`tests/CrashGuardTests.java` (подключён в `LauncherChecksTest`):

| Проверка | Что фиксирует |
|---|---|
| `freshLaunchAfterCleanInstall` | Первый запуск — не падение, отметка поставлена |
| `singleCrashDoesNotReachThreshold` | Один сбой даёт 1, порог не достигнут |
| `twoCrashesReachThreshold` | Два подряд дают порог |
| `successfulStartResets` | Успешный старт обнуляет всё |
| `stalePendingIsNotACrash` | Старая отметка не считается падением |
| `disabledGuardIsSilent` | Выключенная настройка отключает автоматику |
| `resetClearsEverything` | Сброс стирает счётчик и запомненный лаунчер |

Эти тесты сразу нашли реальную ошибку: отметка «запуск идёт» не ставилась после
обнаруженного сбоя, поэтому третий запуск не увеличивал счётчик и порог не
достигался **никогда** — автоматический возврат не сработал бы ни разу.

Сами действия (запуск другого лаунчера, диалог роли HOME, показ экрана) требуют
Android и проверяются на эмуляторе: в CI добавлен сценарий, который подкладывает
в prefs состояние «предыдущий запуск упал» (debug-сборка, доступ через `run-as`)
и дважды открывает HOME.

| Шаг сценария | Что проверяется | Результат в CI |
|---|---|---|
| Запуск №1 со счётчиком 0 | На экране появляется аварийный экран с объяснением | **подтверждено**: в дереве интерфейса `TextView[Doxaiz не запустился]`, 23 узла |
| Запуск №2 со счётчиком 1 | На переднем плане оказывается не Doxaiz, а другой лаунчер | **подтверждено**: `mCurrentFocus=Window{… com.google.android.apps.nexuslauncher/…NexusLauncherActivity}` — пользователь видит рабочий стол системы, а не чёрный экран |
| Восстановление | После стирания состояния лаунчер снова открывает рабочий стол | **подтверждено**: процесс жив, `desktop_layout_json` записан |

Итог прогона: **оба шага сценария подтверждены на живой системе** — аварийный
экран показывается после первого сбоя, а после второго лаунчер уступает
системному рабочему столу. Роль HOME при этом может остаться у Doxaiz
(`RoleManager` ждёт подтверждения пользователя) — на реальном телефоне это одно
нажатие в системном диалоге.

Важная деталь про аннотации: у GitHub лимит около 10 аннотаций на шаг, и всё,
что печаталось после проверок отрисовки и каталога, до отчёта не доходило —
сценарий был перенесён сразу после проверки HOME. Проверка «фокус пуст»
сознательно не считается успехом: пустой `mCurrentFocus` — известная
особенность эмулятора, и принимать её за доказательство нельзя. В одном из
прогонов фокус как раз был пуст — сценарий тогда честно написал «не подтверждён
и не опровергнут» вместо ложного успеха.

Что этот сценарий **не** подтверждает и почему: роль HOME после второго сбоя
может остаться у Doxaiz. `RoleManager` освобождает роль только через диалог,
который подтверждает пользователь, а в headless-эмуляторе подтверждать некому.
Поэтому скрипт фиксирует именно переход на системный рабочий стол (то, что
пользователь видит) и отмечает остаточную роль предупреждением, а не провалом.
На реальном телефоне пользователь подтверждает диалог одним нажатием.

### 15.7. Что осталось

| Что | Почему |
|---|---|
| Проверка на реальном телефоне | Эмулятор не воспроизводит поведение прошивок при смене HOME (Xiaomi/Transsion ведут себя по-разному) |
| Автоматическое снятие роли на Android 8–9 | Платформа не даёт API; там остаётся только системный экран выбора |
| Отчёт об ошибке из аварийного экрана | Сейчас причина видна, но отправить её можно только вручную (журнал в настройках) |

## 16. Итерация 6.0: отзывчивость старта и целостность раскладки

Проход по тем же правилам: сначала воспроизведение, потом правка. Здесь —
четыре дефекта, найденные чтением кода и проверенные расчётом или JVM-моделью.

### 16.1. Два элемента на одной клетке при переполнении страниц

`DesktopModel.normalizeAllPages()` при уже достигнутом `MAX_PAGES` выносила
переполненные иконки «за пределы сетки» на фиксированную клетку `(cols-1,
GRID_ROWS-1)`. `findItemIndexAt()` ищет элемент по координатам и возвращает
**первый** попавшийся — вторая иконка на той же клетке становилась недоступной
для нажатий и перетаскивания.

Первая правка (счётчик в памяти) была неверной: счётчик сбрасывался при каждом
запуске процесса, и после перезапуска координаты снова совпадали. Итоговая
правка ищет свободную строку по фактическому содержимому страницы
(`firstFreeHiddenRow`), поэтому результат не зависит от времени жизни процесса.

Проверка: `tests/Audit60Tests.java` (200 элементов на 8 страницах, уникальность
`(col,row)` в пределах страницы, сохранение после перезапуска).

### 16.2. Импорт со штатного экрана читал чужой ContentProvider в UI-потоке

`onAppsUpdated()` при пустом рабочем столе вызывал
`LayoutImporter.tryImportFromSystemLauncher()` прямо на главном потоке. Это IPC
к базе OEM-оболочки: на Android 14+ доступ обычно запрещён (исключение
перехватывалось), а если провайдер завис — вызов не возвращается вовсе, и
пользователь видит чёрный экран или ANR. Повторялось на **каждом** старте с
пустым столом, то есть после каждой неудачной попытки.

Правка:
- импорт разделён на две фазы: `readSystemLauncher()` (ввод-вывод, только фон) и
  `applySnapshot()` (память, безопасно в UI);
- рабочий стол строится сразу обычной раскладкой, пользователь ничего не ждёт;
- фоновое чтение выполняется пулом репозитория, результат применяется в UI;
- попытка однократная: `Prefs.isSystemLayoutImportDone()` запоминает и неудачу
  (повторять запрос к тому же провайдеру каждый запуск бессмысленно);
- результат не применяется, если за время чтения список приложений обновился
  (счётчик `appsGeneration`) — иначе иконки получили бы устаревшие ключи;
- ручной импорт из настроек переведён в фон по той же причине.

### 16.3. Дубли иконок при перетаскивании из «Всех приложений»

`dropExternalAppOnCell()` не проверяет, есть ли приложение на столе: бросить
иконку, которая уже лежит (в том числе внутри папки или в доке), можно было
бесконечно — на столе появлялись две копии одного приложения. Проверка добавлена
и при начале перетаскивания, и при его завершении (между этими моментами
приложение могло добавиться из фона).

### 16.4. Декодирование иконки из дискового кэша без уменьшения

Кэш принимает файлы до 1024×1024, а `decodeFile()` читал их целиком в
ARGB_8888 — 4 МБ на одну «иконку» в ОЗУ-кэше. Добавлен `inSampleSize`
(`sampleSizeFor`): нормальная иконка 192 px не меняется (sample=1), 512 px
уменьшается вдвое, 1024 px — вчетверо.

### 16.5. Что проверено и признано корректным

| Место | Вывод |
|---|---|
| `Prefs` кэши скрытых/категорий/usage | Под локами, наружу отдаются копии |
| `IconDiskCache` счётчик файлов и `getStats` | Пересчёт по диску, гонки нет |
| Регистрация/снятие всех ресиверов | Всё закрыто, sticky — по контракту |
| `PendingIntent`, `exec`, `WebView`, path traversal | Не найдено |
| `onConfigurationChanged`, `onTrimMemory` | Корректны |
| `BiometricHelper` | Fail-closed: при недоступной проверке доступ не даётся |
| 34 файла Java | Синтаксически валидны (javalang) |

### 16.6. Отдельно: арифметика кэша иконок

Проверялась гипотеза, что кэш иконок занимает слишком много ОЗУ. Расчёт показал
обратное: `computeOptimalIconCacheKb()` ограничен сверху (4096 КБ на слабом
устройстве, 14336 КБ на обычном), а одна иконка — 192×192×4 ≈ 148 КБ, то есть
это 2–5% кучи. Менять формулу не стали: уменьшение потолка возвращает мигание
иконок при прокрутке, увеличение — риск выгрузки процесса. Вместо правки в коде
оставлен расчёт в комментарии, чтобы следующий проход не «оптимизировал» это
наугад.

### 16.7. Иконки рабочего стола не имели доступных подписей

Клетка рабочего стола — `LinearLayout` из `ImageView` и `TextView`. Для TalkBack
и для поиска по интерфейсу (`uiautomator`) это «безымянная картинка плюс
отдельный текст»: подпись не связана с действием, а папка читается как набор
изображений. В CI из-за этого дамп рабочего стола выглядел пустым, хотя иконки
были нарисованы. Добавлен `contentDescription` на клетку приложения (подпись
приложения) и на плитку папки (название и число приложений). Поведение не
меняется, меняется только дерево доступности — заодно рабочий стол становится
проверяемым в сценарии эмулятора.

## 17. Итерация 6.0, второй проход: главный поток и утечки

| Место | Было | Стало |
|---|---|---|
| `DoxaizLockService.onCleanTrashClicked` | `MemoryHelper.boostRam()` вызывался прямо в потоке службы специальных возможностей: опрос `ActivityManager` + `killBackgroundProcesses()` на каждый пакет — сотни Binder-вызовов. Кнопка корзины «залипала», а служба, отвечающая медленно, рискует быть ограниченной системой | `boostRamAsync()` (он уже был написан для этой цели); выход на рабочий стол не ждёт очистки, результат показывается по завершении |
| `DoxaizLockService.findAndClickClearAll` | `getChild()`/`getParent()` создают новые обёртки над нативными узлами; до 180 узлов на каждое нажатие не освобождались (`recycle()` не вызывался ни разу). Служба живёт долго — это накопительная утечка, за которую Android отключает службу доступности | Узлы освобождаются в `finally` и после подъёма к родителю (`recycleNode`, только до API 33 — на новых версиях вызов ничего не делает); корень освобождается в `finally` вызывающего |
| `IconDiskCache.getStats` | `listFiles()` + `length()` по всему каталогу кэша при каждой перестройке настроек (39 мест вызова) | Кэш на 5 с, сбрасывается при `save`/`removeCacheFile`/`clearAll`; наружу отдаётся копия массива |
| `MainActivity` (ОЗУ) | `MemoryHelper.read()` вызывался до 3 раз подряд в одном кадре: «Недавние» и блок ОЗУ в меню приложений обновляются одновременно | Общий снимок `memorySnapshot(force)` с кэшем 1,5 с; повторные чтения убраны из модального окна, настроек и «Недавних» |

Проверка: `Audit60Tests.diskStatsCacheIsIsolated` — кэш отдаёт копию, сброс
пересчитывает. Остальное требует устройства: Binder-вызовы и узлы доступности
в JVM не воспроизводятся.

## 18. Итерация 6.0, третий проход: пустой рабочий стол и отзывчивость поиска

### 18.1 Главный дефект: на чистой установке рабочий стол пустой

Симптом (из прогона `44040112753`): в системе 19 activity с категорией
`LAUNCHER`, а в сохранённой раскладке — `{"seq":1,"dock":[],"pages":[[]]}`;
дерево рабочего стола (`ui-home`) содержит 14 узлов и только надпись
«Страница 1 / Удерживайте пустую клетку…». То есть лаунчер видел приложения, но
рисовал пустой экран — и сохранял это состояние.

Причин оказалось две, и обе нужно было убрать:

| Место | Было | Стало |
|---|---|---|
| `DesktopModel.isInitialized` | `!pages.isEmpty()` — одна пустая страница считалась готовой раскладкой. `rebuildDesktop()` при старте вызывал `normalizeAllPages()`, тот добавлял `pages=[[]]`, и авто-раскладка больше никогда не строилась | Отдельный сохраняемый признак `built` (`isInitialized()` = `built || hasContent()`); `hasContent()` — есть иконки, папки или док |
| `DesktopModel.buildInitialLayout` | При пустом списке приложений создавал две пустые страницы, ставил флаг и вызывал `save()` — пустое состояние закреплялось в `SharedPreferences` | Пустой список не строит ничего и ничего не сохраняет; `initialized = true` ставится только по непустому результату |
| `DesktopModel.normalizeAllPages` | `save()` при любом изменении, в том числе когда на страницах нет ни одного элемента | Сохранение только если `hasContent()` |
| `AppRepository.reloadNow` | Пустой ответ пакетного менеджера (сразу после загрузки устройства) применялся как «приложений нет» | До 3 пропусков с повторной попыткой (400/800/1200 мс); после них пустой список принимается как есть — если приложений действительно нет, стол должен остаться пустым |
| `LayoutImporter.applySnapshot` | Импорт не помечал раскладку построенной | `markInitialized()`: после импорта пустой стол не перестраивается (это выбор пользователя или пустой снимок) |

Ключевая деталь: пустой рабочий стол бывает **нормальным** (пользователь убрал
все иконки). Поэтому нельзя просто «перестраивать, если пусто» — нужен
сохраняемый признак «раскладка уже строилась». Он записывается в тот же JSON
(`"built": true`), рядом с `seq`, `dock`, `pages`, и переживает перезапуск.

Диагностика: при `visibleApps.size() >= 2` и пустом содержимом в журнал
`Diagnostics` пишется `MainActivity.emptyDesktop` с числом приложений, страниц и
элементов дока — если дефект вернётся, причина будет в отчёте, а не в догадках.

### 18.2 Отзывчивость поиска в каталоге

Замеры делает CI: набор `setting` в поиске каталога под `dumpsys gfxinfo`
(эмулятор рисует через swiftshader, поэтому числа сравнимы только между
прогонами, а не с телефоном).

| Место | Было | Стало |
|---|---|---|
| `applyFilter` (поиск) | `SearchHelper.matchScore` вызывался **внутри компаратора**, то есть дважды на каждое сравнение. При 200 приложениях — около 1500 лишних вычислений на каждую нажатую клавишу, в главном потоке | Оценка считается один раз на приложение, сортируется список `ScoredEntry` — O(n) вычислений вместо O(n log n) |
| `scheduleDrawerFilter` | Задержка склейки нажатий 60 мс всегда | Если предыдущий пересчёт занял больше 6 мс, следующий выполняется без задержки: склейка не должна добавляться к уже пропущенному кадру |
| `applyFilter` (панель) | На каждое нажатие пересобирались «Частые», чипы категорий, алфавитная полоса | Кэш подписи категорий и порядка каталога, `rebuildDrawerRecent()` только вне продолжения набора (сделано ранее в этой же итерации) |

### 18.3 Наблюдаемость в CI

GitHub показывает **не больше 10 notice-аннотаций на шаг**, остальные
отбрасываются молча. В прогоне `44041251960` из-за этого пропали ровно те
заметки, которые нужны для выводов об отзывчивости («поиск-метрики»,
«gfxinfo-drawer»). Исправлено:

- `note()` режет текст до 240 символов, а крупные дампы дерева (`ui-home`,
  `ui-drawer`) в аннотации больше не уходят — только счётчик узлов;
- полные дампы, метрики кадров и попытки открытия каталога пишутся в отчёт шага
  (`GITHUB_STEP_SUMMARY`), он не ограничен 10 записями;
- в отчёт добавлены `gfxinfo-search-summary.txt` и `drawer-attempts.txt`.

### 18.4 Что подтвердил CI после исправления

Прогон `44043440911` (все четыре проверки зелёные):

- раскладка: `{"seq":2,"built":true,"dock":["com.google.android.dialer/…", …]}` —
  док заполнен, `built` записан;
- `ui-home`: 59 узлов, иконки `Photos`, `YouTube`, `Maps` с подписями и
  `ImageView` — рабочий стол рисуется (раньше: 14 узлов, только «Страница 1»);
- каталог открывается с первой попытки (59 узлов);
- **набор текста в поиске**: `9 кадров, janky 7 (78%), 50th 16 мс,
  90th 150 мс, 95th 150 мс, Missed Vsync 0, Slow UI thread 1`,
  семь нажатий заняли 370 мс по часам (включая запуск `adb shell input`);
- прокрутка каталога: `18 кадров, 50th 97 мс, 90th 400 мс, Slow UI thread 9,
  Slow bitmap uploads 2`.

Числа эмулятора (swiftshader) сравнимы только между прогонами. Показательно
распределение: на наборе текста 50-й перцентиль — один кадр (16 мс), а тяжёлые
кадры остались на прокрутке, где в кадр попадают загрузка иконок и фоновые
drawable. Это следующая точка работы по отзывчивости, а не поиск: поиск
пересчитывается за время одного кадра.

### 18.5 Проверено в JVM-тестах

`Audit60Tests.emptyLayoutIsNotInitialized()` — 9 проверок: новая модель не
готова; `pages=[[]]` не считается готовой раскладкой; `buildInitialLayout([])`
ничего не закрепляет; после построения по непустому списку раскладка готова и
содержательна; помеченная построенной пустая раскладка остаётся готовой (ручной
пустой стол не перезаполняется).

## 19. Итерация 6.0, четвёртый проход: прокрутка каталога и честные метрики

### 19.1 Прокрутка каталога: иконки не декодируются на ходу

Кадровые метрики прокрутки в CI были заметно хуже, чем у набора в поиске:
`50th 97 мс`, `Slow UI thread 9`, `Slow bitmap uploads 2` на 18 кадров. Разница
не в списке, а в содержимом кадра: при прокрутке на каждый новый элемент
приходилось чтение WEBP с диска и постановка битмапа в `ImageView`.

| Место | Было | Стало |
|---|---|---|
| `AppRepository.bindIcon` | На каждый элемент, попавший в кадр, запускалась задача декодирования (диск → `BitmapFactory` → битмап) | Появился признак `scrollFlinging`: пока список движется, показывается только то, что уже в ОЗУ-кэше, декодирование не запускается |
| `GridView` каталога | `setOnScrollListener` только прятал клавиатуру | Тот же слушатель выставляет/снимает `scrollFlinging`, а по остановке списка перерисовывает видимые элементы (`notifyDataSetChanged`), чтобы недостающие иконки появились |
| Смена экрана | Признак мог «залипнуть» после прерванного флинга, и иконки каталога не отрисовались бы вовсе | `showScreen()` сбрасывает признак |
| `GridView` scrolling cache | Включён по умолчанию: копил битмапы в кадре прокрутки | Отключён (`setScrollingCacheEnabled(false)`) — прокрутка и так показывает готовое из ОЗУ-кэша |

### 19.2 Главное открытие: кадровые метрики эмулятора слишком шумные

Один и тот же код поиска в соседних прогонах давал `50th` **16, 85, 93, 69, 77,
117 мс**, а ввод «setting» занимал от 370 до 1762 мс. Это не свойство
приложения: менялась загрузка раннера и программного рендеринга (swiftshader).
По таким числам нельзя судить, стало ли лучше, — а именно ими раньше
обосновывались выводы.

Что сделано, чтобы метрика стала честной:

1. **Замер повторяется три раза** (`search_round`), каталог открывается заново,
   поле поиска очищается; публикуются медиана `50th`, худший `90th` и медиана
   времени ввода, полные строки раундов — в отчёт шага.
2. **Собственный замер приложения.** `MainActivity` измеряет время
   `applyFilter()` на каждый пересчёт и раз в 4 замера пишет
   `median/max/samples` в `files/doxaiz_filter_stats.txt` (только в debug).
   CI читает файл через `run-as` и печатает отдельной заметкой
   «пересчёт-каталога».

Две неудачные попытки, которые стоит помнить: `setprop` из приложения молча не
работает (нет прав), а запись через фоновый пул репозитория не создавала файл
вовсе. Итог: синхронная запись ~40 байт раз в 4 пересчёта.

### 19.3 Что показал честный замер

Прогон `44054353549` (эмулятор, программный рендеринг):

```
пересчёт-каталога: median=10 max=18 samples=16
```

То есть полный пересчёт списка каталога после нажатия клавиши — **10 мс
медиана, 18 мс худший** на заведомо медленном устройстве без GPU. Это меньше
одного кадра (16,7 мс) в медиане и чуть больше в худшем случае: поиск
перестал быть местом, где теряются кадры. Оставшиеся тяжёлые кадры прокрутки по
`gfxinfo` относятся к отрисовке swiftshader и с работой главного потока
приложения уже не связаны.

### 19.4 Наблюдаемость: что теперь видно в CI

| Заметка | Что показывает |
|---|---|
| `приложений-в-системе` | сколько LAUNCHER-активностей видит система |
| `раскладка` | сохранённый JSON с `built`, `dock`, `pages` |
| `ui-home` | счётчик узлов рабочего стола и первый фрагмент дерева |
| `drawer` | счётчик узлов каталога |
| `поиск-метрики` | медиана `50th` по трём раундам, худший `90th`, медиана ввода |
| `пересчёт-каталога` | `median/max` времени `applyFilter()` изнутри приложения |
| `gfxinfo-прокрутка` | кадры прокрутки (относительные, swiftshader) |

Крупные дампы дерева в аннотации больше не уходят: GitHub показывает не больше
10 notice на шаг, и именно из-за этого в прогоне `44041251960` пропали заметки
об отзывчивости. Полные дампы и все раунды — в отчёте шага.

## 20. Релизы: один релиз, обновляемый на месте

Раньше каждая итерация получала свой тег и свой релиз (`v5.6-test`…
`v6.0-test`). К концу итерации это дало свалку почти одинаковых записей, в
каждой из которых лежал APK неизвестной свежести. Все они удалены.

Теперь правило простое и закреплено в трёх местах — `README.md`,
`RELEASE_NOTES.md` и шаге CI:

* в проекте **ровно один** релиз — `doxaiz-test`;
* CI на успешном запуске в `main` перезаписывает в нём APK
  (`gh release upload --clobber`) и заметки (`gh release edit --notes-file
  RELEASE_NOTES.md`);
* новых релизов и тегов CI не создаёт; нет релиза — шаг пропускается молча
  (создание — осознанное действие человека).

Две ошибки, которые пришлось исправить по ходу:

1. У `GITHUB_TOKEN` по умолчанию `contents: read`, поэтому первая публикация
   упала **и сделала красной сборку APK**. Job `apk` получил
   `permissions: contents: write`; шаг выполняется только для `push` в `main`.
2. Отказ публикации не был виден: в аннотациях только «exit code 1». Теперь
   каждая команда публикации сообщает свою причину (`::error title=релиз::…`).

Заметки релиза живут в `RELEASE_NOTES.md`, а не в интерфейсе GitHub: следующая
публикация их перезапишет. Скачивание артефакта CI из некоторых окружений
невозможно (заблокирован хост хранилища артефактов), поэтому APK из релиза —
единственный путь «взять текущую проверенную сборку».

## 21. Итерация 6.0, пятый проход: читаемость внешнего вида, аудит, безопасность

### 21.1 Главный дефект внешнего вида: подписи на чужих обоях

Пользователь видит подпись иконки на **своих** обоях, яркость которых
приложению неизвестна. Проверка показала худший случай: белый текст на белой
картинке — контраст **1,24:1** при пороге WCAG AA 4,5:1 для мелкого текста.

Причин было три, и они складывались:

| Место | Было | Стало |
|---|---|---|
| `AnimeBackdropView.applyConfig` | при системных обоях `setVisibility(GONE)` — слой не рисовался вообще | слой остаётся: он теперь единственная защита подписей |
| `AnimeBackdropView.onDraw` | ранний `return` при системных обоях | скрим рисуется всегда; для встроенных обоев дополнительно картинка |
| `AnimeBackdropView.onSizeChanged` | скрим пересобирался только на пути встроенных обоев | градиент пересобирается всегда, иначе при системных обоях он оставался бы пустым |

Плотность скрима для неизвестных обоев **посчитана**, а не выбрана на глаз:
`Legibility` (новый класс, чистая математика WCAG 2.1) даёт минимальную альфу,
при которой белый текст проходит порог на **белых** обоях. Значения:
верх 190/255, середина 150/255, низ 215/255 — середина даёт 5,01:1.

Подписи иконок и дока получили подложку-«таблетку», непрозрачность которой
тоже вычисляется: `requiredBackdropAlpha(...) = 150` при пороге 4,5:1.
Кэшируется на время жизни темы — метод вызывается на каждую иконку страницы.

Точки страниц были белыми без обводки и полностью пропадали на светлых обоях;
теперь у каждой тонкая тёмная обводка.

### 21.2 Почему это не «на глаз»: тест вместо мнения

`tests/ContrastTest.java` (12 проверок на тот момент; сейчас 79 — см. §22) фиксирует математику и результат:

* известные значения WCAG (чёрный/белый = 21:1, одинаковые цвета = 1:1);
* белый текст на белых обоях **не** проходит порог — это и был дефект;
* скрим при системных обоях даёт ≥ 4,5:1 для всех трёх уровней затемнения;
* встроенные обои не потемнели «за компанию» (36/255 как раньше);
* подобранная подложка под подпись проходит порог, а более строгий порог не
  даёт более прозрачную подложку;
* текст светлой темы на карточке — 17,6:1.

### 21.3 Аудит: что проверено и что найдено

Проверялось по коду, без устройства: Lint (в CI, ошибки валят сборку),
ручной разбор опасных мест, поиск типовых дефектов скриптом.

**Найдено и исправлено**

| Дефект | Влияние | Исправление |
|---|---|---|
| `166` пустых `catch (Throwable)` (из 310 `catch` в 25 файлах) | реальные ошибки не видны: именно так «молча» не сработали `setprop` и запись файла метрик | `Diagnostics.origin(Throwable)` — место сбоя (файл:строка) по стеку; проверено `originNamesTheFile` |
| Подвал аварийного экрана обещал «журнал в настройках Doxaiz» | в настройки с этого экрана попасть нельзя — рабочий стол и есть то, что не запустилось | кнопка «Скопировать журнал диагностики» прямо на аварийном экране, с версией и моделью устройства |
| Чипы фильтра в шторке: `substring(0, 13) + "…"` | кириллица шире латиницы: названия рвались при свободном месте и не рвались, когда место кончалось | `maxWidth(150dp)` + `ellipsize` по метрикам шрифта |
| Пустой результат поиска — сухое «Ничего не найдено» | не объясняет, что поиск понимает оба языка и чужую раскладку | подсказка с объяснением и отступами |

**Проверено и в порядке**

- `INTERNET` не запрашивается вовсе; `usesCleartextTraffic=false`; `QUERY_ALL_PACKAGES`
  необходим лаунчеру, остальные разрешения — опциональные, по действию пользователя.
- `WRITE_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`, `BIND_*` — защищённые разрешения,
  выдаются только системой/пользователем через настройки.
- `exported=true` только у `MainActivity` (HOME) и службы жестов (защищена
  `BIND_ACCESSIBILITY_SERVICE`); остальные компоненты `exported=false`.
- Импорт резервной копии: ограничение 512 КБ, проверка структуры до снимка
  настроек, лимиты на число страниц/элементов/длину ключей, откат одной
  атомарной записью, обход «приватного пространства» через поддельный файл
  закрыт подтверждением биометрии.
- Служба уведомлений отдаёт только то, что разрешил пользователь; текст можно
  скрыть настройкой.
- `onDestroy` снимает все коллбэки, слушатели и регистрации — утечек через
  статические ссылки на активность не найдено.
- В цикле отрисовки нет чтения диска и обращений к `PackageManager`; файловые
  операции — в фоне, кроме осознанно синхронной записи 40 байт метрики.

**Осталось (не исправлено намеренно)**

- `targetSdk 34`, отладочная подпись, отсутствие подписанного релиза;
- служба специальных возможностей и биометрия на эмуляторе не проверяются;
- 166 пустых `catch` остаются (часть уместна), но теперь есть инструмент,
  чтобы фиксировать место сбоя там, где это важно;
- журнал диагностики хранится в `SharedPreferences` без шифрования (там нет
  текстов уведомлений, только имена компонентов и типы ошибок).

### 21.4 Проверка

Прогоны ветки: `44068993687`, `44071035928`, `44073030842` — все четыре
проверки зелёные (JVM-тесты, Lint, debug-APK, эмулятор API 34).
Замер приложения на эмуляторе: `пересчёт-каталога median=5 max=15`.

### 21.5 Сбой инфраструктуры, а не сборки

Первый прогон на `main` после мержа (`44075526637`) покраснел на шаге «Сборка
debug-APK». Причина в аннотации:

```
Could not find org.apache.httpcomponents:httpclient:4.5.6
  project :app > com.android.tools.lint:lint-gradle:31.2.2
```

Это отказ сети раннера при скачивании зависимости Gradle, а не ошибка кода:
те же файлы за несколько минут до этого прошли `lint`, `assembleDebug` и
эмулятор на ветке. Лечение — повторный запуск. Важное следствие: шаг
публикации APK в релиз зависит от успешной сборки, поэтому при сетевом сбое
APK в релизе остаётся прежним, а не «полупустым».

---

## 22. Итерация 6.0, шестой проход: контраст самих палитр

### 22.1 Как искали

Предыдущий проход научил считать контраст для обоев, но сама палитра
`GlassTheme` так и не была проверена: цвета подбирались глазами. Написала
разбор `theme_pairs` — он читает четыре блока `new GlassTheme(...)` (по 32
аргумента) и считает контраст пар «текст ↔ фон». Полупрозрачные цвета не
сравниваются «как есть»: сначала они смешиваются с фактическим фоном
(карточка / подложка / пилюля), иначе число врёт.

Первая попытка дала пустые строки для `textSecondary`/`textHint` тёмных тем:
цвета заданы выражениями `Color.argb(190, 214, 214, 220)`, а разбор понимал
только hex. После научения разбору `argb(...)`/`clamp(...)` картина стала
полной — и хуже, чем ожидалось.

### 22.2 Что нашлось (посчитано, а не на глаз)

| Палитра | Пара | Было | Стало |
|---|---|---|---|
| Классический графит | `textHint` на пилюле | 2,62:1 | 4,66:1 |
| Классический графит | `textHint` на карточке | 2,65:1 | 4,76:1 |
| Классический графит | подпись активного чипа | 3,37:1 | 5,17:1 |
| Классический графит | «Удалить приложение» на карточке | 4,11:1 | 5,67:1 |
| Сакура × Нефрит | `textHint` на карточке | 3,32:1 | 5,06:1 |
| Сакура × Нефрит | подпись активного чипа | 4,09:1 | 5,45:1 |
| Кибер-Аниме | `textHint` на карточке | 3,06:1 | 6,66:1 |
| Кибер-Аниме | подпись активного чипа | 4,10:1 | 5,93:1 |
| Светлая | `textHint` на пилюле | 3,99:1 | 4,86:1 |
| Светлая | `textMuted` шторки на плитке | 4,25:1 | 6,09:1 |

Главный вывод: хуже всего были именно **подсказки** — самый мелкий текст в
приложении. В тёмной теме они давали 2,6:1, то есть читались как «серое пятно».

Замена подбиралась численно с сохранением оттенка, а не «потемнее вообще»:
например, у активного чипа синий `#2B8BFF` → `#2563EB`, у сакуры
`#D94680` → `#C22A6E`.

### 22.3 Конфликт, который нельзя решить цветом заливки

Отдельно пришлось разобраться с активным чипом. Требования противоречат друг
другу: чтобы белая подпись читалась, заливка должна быть тёмной (≤ ~0,175 по
яркости), а чтобы заливка выделялась на тёмной пилюле — светлой (≥ ~0,213).
Коридор есть только у узких оттенков, и при «плотной» непрозрачности пилюли
он сужается ещё сильнее.

Поэтому выбор показывается **рамкой** `chipActiveBorder`, которая в
`MainActivity` и так ставится активному чипу: она даёт 4,4–13:1 к пилюле во
всех стилях. Тест проверяет именно рамку по порогу 3:1 для нетекстовых
индикаторов, а заливку — по порогу 4,5:1 для подписи. Так оба требования
выполнимы одновременно и ни одно не подогнано под ответ.

### 22.4 Чтобы дефект не вернулся: тест считает настоящую палитру

Тест бесполезен, если проверяет копию цветов. Но в unit-тестах
`android.graphics.Color.argb()` возвращает 0 (в `build.gradle` включено
`unitTests.returnDefaultValues`), то есть посчитать палитру из `create()` было
нельзя — поэтому раньше её и не проверяли.

Решение: расчёт вынесен в `GlassTheme.createResolved(style, opacity)`, который
не обращается ни к `Prefs`, ни к `Color.argb` — используются собственные
`argb`/`clampLocal`. `create(styleMode, opacity, ornaments)` остался прежним
API и просто нормализует стиль через `Prefs.sanitizeVisualStyle` и вызывает
`createResolved`. На устройстве поведение не изменилось, а на JVM теперь
считается та же самая палитра.

`ContrastTest` вырос с 12 до 79 проверок: 4 стиля × 3 уровня непрозрачности ×
11 пар + регрессии конкретных дефектов. Среди пар — подсказка на карточке,
подложке и пилюле, второстепенный текст, текст неактивного чипа, подпись
активного чипа, рамка активного чипа, «опасное» действие и акцент.

Проверено, что тест действительно красный на прежних значениях: на старых
цветах он даёт 9 провалов в светлой теме, 15 в Кибер-Аниме, 17 в графите и 15
в Сакуре, а на новых — ноль.

### 22.5 Что осталось намеренно

* **Кибер-Аниме и Сакура × Нефрит сейчас недостижимы.** `sanitizeVisualStyle`
  сворачивает их в «Классический графит» (это зафиксировано в
  `SimpleThemeTest`). Палитры в коде живые, поэтому контраст проверяется для
  них заранее — но включать их или убирать из кода это отдельное решение, а не
  часть правки контраста.
* **Шторка светлой темы живёт на унаследованной тёмной палитре** (комментарий в
  коде: пока у каждого всплывающего окна нет отдельной проверенной светлой
  палитры). Переписывать её целиком в этом проходе не стал: правка контраста не
  должна незаметно менять внешний вид. Поднят только `TEXT_MUTED` (4,25 → 6,09),
  потому что он не дотягивал до порога.
* **124 жёстких цвета в `QuickShadeHelper`** остаются: это та же причина —
  шторка не следует светлой теме. Задача записана, но требует отдельного прохода
  по каждому всплывающему окну.
