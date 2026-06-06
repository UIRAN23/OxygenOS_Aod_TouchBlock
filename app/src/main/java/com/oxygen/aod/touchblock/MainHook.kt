package com.oxygen.aod.touchblock

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * OxygenOS AOD TouchBlock — Xposed Module для OxygenOS 16 (16.0.7).
 *
 * Функции:
 * 1. AOD весь день — форсит isSupportFullAod() и getKeyAodAllDaySupportSettings()
 *    в com.oplus.aod, чтобы в настройках появился переключатель
 * 2. Блокировка касания AOD — хукает AOD контроллеры и блокирует
 *    одиночное касание (single click / tap wake), чтобы экран включался
 *    только кнопкой питания
 */
object MainHook : IXposedHookLoadPackage {

    private const val TAG = "AodTouchBlock"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val OPLUS_AOD = "com.oplus.aod"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI && lpparam.packageName != OPLUS_AOD) return

        XposedBridge.log("$TAG: hooked ${lpparam.packageName}")

        when (lpparam.packageName) {
            OPLUS_AOD -> hookAodApp(lpparam.classLoader)
            SYSTEM_UI -> hookSystemUi(lpparam.classLoader)
        }
    }

    // ──────────────────────────────────────────────
    // 1. AOD-приложение: включаем переключатель «весь день»
    // ──────────────────────────────────────────────
    private fun hookAodApp(classLoader: ClassLoader) {
        try {
            val commonUtils = Class.forName("com.oplus.aod.util.CommonUtils", false, classLoader)
            XposedBridge.hookAllMethods(commonUtils, "isSupportFullAod", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                    XposedBridge.log("$TAG: isSupportFullAod → true")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook isSupportFullAod failed: ${t.message}")
        }

        try {
            val settingsUtils = Class.forName("com.oplus.aod.util.SettingsUtils", false, classLoader)
            XposedBridge.hookAllMethods(settingsUtils, "getKeyAodAllDaySupportSettings", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 1
                    XposedBridge.log("$TAG: getKeyAodAllDaySupportSettings → 1")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook getKeyAodAllDaySupportSettings failed: ${t.message}")
        }
    }

    // ──────────────────────────────────────────────
    // 2. SystemUI: панорамный AOD + блокировка касания
    // ──────────────────────────────────────────────
    private fun hookSystemUi(classLoader: ClassLoader) {
        // 2a. SmoothTransitionController — включаем панорамный AOD весь день
        hookPanoramicAllDay(classLoader)

        // 2b. Блокировка одиночного касания AOD (Single Click Wake Up)
        hookSingleClickBlock(classLoader)
    }

    /**
     * Хук SmoothTransitionController: форсит поля panoramic all day
     * после вызова getInstance().
     */
    private fun hookPanoramicAllDay(classLoader: ClassLoader) {
        try {
            val companionClass = Class.forName(
                "com.oplus.systemui.aod.display.SmoothTransitionController\$Companion",
                false, classLoader
            )
            XposedBridge.hookAllMethods(companionClass, "getInstance", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.result ?: return
                    try {
                        XposedHelpers.setBooleanField(instance, "isSupportPanoramicAllDayByPanelFeature", true)
                        XposedHelpers.setBooleanField(instance, "isSupportPanoramicByPanelFeature", true)
                        XposedHelpers.setBooleanField(instance, "isSupportPanoramic", true)
                        XposedHelpers.setBooleanField(instance, "isSupportPanoramicAllDay", true)
                        XposedBridge.log("$TAG: SmoothTransitionController — panoramic all day fields set")
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: set panoramic fields failed: ${t.message}")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook SmoothTransitionController.Companion failed: ${t.message}")
        }

        try {
            val controllerClass = Class.forName(
                "com.oplus.systemui.aod.display.SmoothTransitionController",
                false, classLoader
            )
            XposedBridge.hookAllMethods(controllerClass, "setPanoramicSupportAllDayForApplication", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = true
                }
            })
            XposedBridge.hookAllMethods(controllerClass, "setPanoramicStatusForApplication", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = true
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook SmoothTransitionController failed: ${t.message}")
        }

        // Опционально: убираем проверку RamLess
        try {
            val featureOption = Class.forName(
                "com.oplusos.systemui.common.feature.AodFeatureOption",
                false, classLoader
            )
            XposedBridge.hookAllMethods(featureOption, "isSupportRamLessAod", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook isSupportRamLessAod failed: ${t.message}")
        }
    }

    // ──────────────────────────────────────────────
    // 2b. Блокировка одиночного касания AOD
    // ──────────────────────────────────────────────

    /**
     * Gesture type constant для одиночного касания в AOD.
     * Значение 16 = GESTURE_SINGLE_CLICK в SystemUI.
     */
    private const val GESTURE_SINGLE_CLICK = 16

    /**
     * Список классов-обработчиков жестов AOD, у которых есть
     * метод isSupportGesture(int). Хукаем каждый через hookSafely,
     * т.к. конкретные имена могут отличаться между версиями OxygenOS.
     */
    private val singleClickCallbackClasses = listOf(
        "com.oplus.systemui.aod.scene.AodViewSingleClickWakeUpHolder\$AodSingleClickWakeUpCallback",
        "com.oplus.systemui.aod.scene.PanoramicAodSingleClickWakeUpController\$PanoramicAodSingleClickWakeUpCallback",
        "com.oplus.systemui.aod.scene.AodSceneViewHolder\$AodSceneGestureCallback",
        "com.oplus.systemui.aod.display.OplusWakeUpController\$AodSingleClickWakeUpCallback",
        // Дополнительные варианты для OxygenOS 16.0.7:
        "com.oplus.systemui.aod.scene.AodGestureManager",
        "com.oplus.systemui.aod.display.AodGestureController",
        "com.oplus.systemui.aod.AodManager"
    )

    /**
     * Дополнительные методы касания, которые нужно блокировать
     */
    private val touchHandlerMethods = listOf(
        "isSupportGesture",
        "onSingleTap",
        "onSingleClick",
        "handleSingleTouch",
        "onAodClicked",
        "processTouch"
    )

    private fun hookSingleClickBlock(classLoader: ClassLoader) {
        for (className in singleClickCallbackClasses) {
            hookSafely("SingleClickCallback:$className") {
                val clazz = Class.forName(className, false, classLoader)

                // Хукаем все методы isSupportGesture(int)
                for (methodName in touchHandlerMethods) {
                    hookSafely("Method:$className#$methodName") {
                        try {
                            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    // Если метод принимает int — проверяем gesture
                                    if (param.args.isNotEmpty() && param.args[0] is Int) {
                                        val gesture = param.args[0] as Int
                                        if (gesture == GESTURE_SINGLE_CLICK) {
                                            param.result = false
                                            XposedBridge.log("$TAG: blocked $methodName (gesture=$gesture) in $className")
                                        }
                                    } else {
                                        // Метод без аргументов или с другими типами —
                                        // просто блокируем возврат (return false)
                                        val method = param.method
                                        if (method is java.lang.reflect.Method && method.returnType == Boolean::class.javaPrimitiveType) {
                                            param.result = false
                                            XposedBridge.log("$TAG: blocked $methodName (no gesture arg) in $className")
                                        }
                                    }
                                }
                            })
                            XposedBridge.log("$TAG: hooked $methodName in $className")
                        } catch (_: NoSuchMethodError) {
                            // Метод не найден в этом классе — нормально, пропускаем
                        }
                    }
                }
            }
        }

        // Дополнительно: хукаем WindowManager / PowerManager на уровне InputHandler
        // чтобы перехватывать touch events на AOD
        hookSafely("AodTouchEventHandler") {
            try {
                val aodTouchHandler = Class.forName(
                    "com.oplus.systemui.aod.display.AodTouchEventHandler",
                    false, classLoader
                )
                XposedBridge.hookAllMethods(aodTouchHandler, "onTouchEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: AodTouchEventHandler.onTouchEvent intercepted")
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: hook AodTouchEventHandler failed: ${t.message}")
            }
        }
    }

    private inline fun hookSafely(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: ClassNotFoundException) {
            XposedBridge.log("$TAG: skip $tag — class not found")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: skip $tag — ${t.message}")
        }
    }
}
