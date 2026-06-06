# OxygenOS AOD TouchBlock

Xposed-модуль для OxygenOS 16 (16.0.7) — на основе FullAod 1.2.

## Функции

1. **AOD весь день** — форсит `isSupportFullAod()` и `getKeyAodAllDaySupportSettings()` в `com.oplus.aod`, чтобы в настройках появился переключатель «AOD весь день»
2. **Блокировка касания AOD** — хукает AOD gesture callback'и в SystemUI и блокирует одиночное касание (single click / tap wake). Экран пробуждается только кнопкой питания.

## Требования

- OxygenOS 16.0.7 (Android 16, API 36)
- LSPosed / Zygisk (или любой Xposed-фреймворк)
- Root

## Сборка

Через GitHub Actions — APK доступен в Artifacts.

Или локально:
```bash
./gradlew assembleDebug
```

## Основан на

- [FullAod 1.2](https://github.com/Qjj7679/ColorOS_Aod_Enhance) — оригинальный модуль на чистом Xposed API
