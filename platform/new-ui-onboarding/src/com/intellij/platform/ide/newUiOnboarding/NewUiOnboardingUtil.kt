// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.newUiOnboarding

import com.intellij.ide.DataManager
import com.intellij.ide.actions.DistractionFreeModeController
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.ExpandableComboAction
import com.intellij.openapi.wm.impl.ToolbarComboButton
import com.intellij.ui.ColorUtil
import com.intellij.ui.LottieUtils
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.AbstractPopup
import com.intellij.ui.popup.WizardPopup
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import javax.swing.SwingUtilities

@ApiStatus.Internal
object NewUiOnboardingUtil {
  const val ONBOARDING_PROPOSED_VERSION = "experimental.ui.onboarding.proposed.version"
  const val NEW_UI_ON_FIRST_STARTUP = "experimental.ui.on.first.startup"

  private const val LOTTIE_SCRIPT_PATH = "newUiOnboarding/lottie.js"
  private const val LIGHT_SUFFIX = ""
  private const val LIGHT_HEADER_SUFFIX = "_light_header"
  private const val DARK_SUFFIX = "_dark"

  val isOnboardingEnabled: Boolean
    get() = Registry.`is`("ide.experimental.ui.onboarding")
            && !DistractionFreeModeController.shouldMinimizeCustomHeader()
            && NewUiOnboardingBean.isPresent

  fun getHelpLink(topic: String): String {
    val ideHelpName = NewUiOnboardingBean.getInstance().ideHelpName
    return "https://www.jetbrains.com/help/$ideHelpName/$topic"
  }

  inline fun <reified T : Component> findUiComponent(project: Project, predicate: (T) -> Boolean): T? {
    val root = WindowManager.getInstance().getFrame(project) ?: return null
    findUiComponent(root, predicate)?.let { return it }
    for (window in root.ownedWindows) {
      findUiComponent(window, predicate)?.let { return it }
    }
    return null
  }

  inline fun <reified T : Component> findUiComponent(root: Component, predicate: (T) -> Boolean): T? {
    val component = UIUtil.uiTraverser(root).find {
      it is T && it.isVisible && it.isShowing && predicate(it)
    }
    return component as? T
  }

  fun showToolbarComboButtonPopup(button: ToolbarComboButton, action: ExpandableComboAction, disposable: CheckedDisposable): JBPopup? {
    return showNonClosablePopup(
      disposable,
      createPopup = {
        val dataContext = DataManager.getInstance().getDataContext(button)
        val event = AnActionEvent.createFromDataContext(ActionPlaces.NEW_UI_ONBOARDING, action.templatePresentation.clone(), dataContext)
        ActionUtil.lastUpdateAndCheckDumb(action, event, false)
        val popup = action.createPopup(event)
        popup?.addListener(object : JBPopupListener {
          override fun beforeShown(event: LightweightWindowEvent) {
            button.model.setSelected(true)
          }

          override fun onClosed(event: LightweightWindowEvent) {
            button.model.setSelected(false)
          }
        })
        popup
      },
      showPopup = { popup -> popup.showUnderneathOf(button) }
    )
  }

  fun showNonClosablePopup(disposable: CheckedDisposable,
                           createPopup: () -> JBPopup?,
                           showPopup: (JBPopup) -> Unit): JBPopup? {
    val popup = createPopup() ?: return null
    Disposer.register(disposable) { popup.closeOk(null) }

    if (popup is WizardPopup) {
      // do not install PopupDispatcher, otherwise some cancel settings that are installed below won't work.
      popup.setActiveRoot(false)
    }
    (popup as AbstractPopup).apply {
      setCancelOnMouseOutCallback(null)
      setCancelOnOtherWindowOpen(false)
      setCancelOnWindowDeactivation(false)
      isCancelOnClickOutside = false
      isCancelKeyEnabled = false
    }

    popup.addListener(object : JBPopupListener {
      override fun onClosed(event: LightweightWindowEvent) {
        Alarm().addRequest(Runnable {
          if (!disposable.isDisposed) {
            showNonClosablePopup(disposable, createPopup, showPopup)
          }
        }, 500)
      }
    })
    showPopup(popup)
    return popup
  }

  fun createPopupFromActionButton(button: ActionButton, doCreatePopup: (AnActionEvent) -> JBPopup?): JBPopup? {
    val action = button.action
    val context = DataManager.getInstance().getDataContext(button)
    val event = AnActionEvent.createFromInputEvent(null, ActionPlaces.NEW_UI_ONBOARDING, button.presentation, context)
    var popup: JBPopup? = null
    if (ActionUtil.lastUpdateAndCheckDumb(action, event, false)) {
      // wrap popup creation into SlowOperations.ACTION_PERFORM, otherwise there can be a lot of exceptions
      ActionUtil.performDumbAwareWithCallbacks(action, event) {
        popup = doCreatePopup(event)
        if (popup != null) {
          Toggleable.setSelected(button.presentation, true)
        }
      }
    }
    return popup
  }

  fun convertPointToFrame(project: Project, source: Component, point: Point): RelativePoint? {
    val frame = WindowManager.getInstance().getFrame(project) ?: return null
    val framePoint = SwingUtilities.convertPoint(source, point, frame)
    return RelativePoint(frame, framePoint)
  }

  /**
   * Creates an HTML page with provided lottie animation
   * @return a pair of html page text and a size of the animation
   */
  fun createLottieAnimationPage(lottieJsonPath: String, classLoader: ClassLoader): Pair<String, Dimension>? {
    val pathVariants = getAnimationPathVariants(lottieJsonPath)
    // read json using provided class loader, because it can be in the plugin jar
    val lottieJson = pathVariants.firstNotNullOfOrNull { readResource(it, classLoader) } ?: run {
      LOG.error("Failed to read all of the following: $pathVariants")
      return null
    }
    val size = getLottieImageSize(lottieJson) ?: return null

    // read js script by platform class loader, because it is in the platform jar
    val lottieScript = readResource(LOTTIE_SCRIPT_PATH, NewUiOnboardingUtil::class.java.classLoader) ?: run {
      LOG.error("Failed to read resource by path: $LOTTIE_SCRIPT_PATH")
      return null
    }
    val background = JBUI.CurrentTheme.GotItTooltip.animationBackground(false)
    val htmlPage = LottieUtils.createLottieAnimationPage(lottieJson, lottieScript, background)
    return htmlPage to size
  }

  private fun getAnimationPathVariants(path: String): List<String> {
    val extension = path.substringAfterLast(".", missingDelimiterValue = "")
    val pathWithNoExt = path.removeSuffix(".$extension")

    fun buildPath(suffix: String) = "$pathWithNoExt$suffix${if (extension.isNotEmpty()) ".$extension" else ""}"

    val lightPath = buildPath(LIGHT_SUFFIX)
    val lightHeaderPath = buildPath(LIGHT_HEADER_SUFFIX)
    val darkPath = buildPath(DARK_SUFFIX)
    val isDark = ColorUtil.isDark(JBUI.CurrentTheme.GotItTooltip.background(false))
    return when {
      isDark && StartupUiUtil.isUnderDarcula -> listOf(darkPath, lightPath, lightHeaderPath)  // Dark theme
      isDark -> listOf(lightPath, lightHeaderPath, darkPath)                                  // Light theme
      else -> listOf(lightHeaderPath, lightPath, darkPath)                                    // Light with light header theme
    }
  }

  private fun readResource(path: String, classLoader: ClassLoader): String? {
    return try {
      classLoader.getResource(path)?.readText()
    }
    catch (t: Throwable) {
      null
    }
  }

  private fun getLottieImageSize(lottieJson: String): Dimension? {
    return try {
      LottieUtils.getLottieImageSize(lottieJson)
    }
    catch (t: Throwable) {
      LOG.error("Failed to parse lottie json", t)
      null
    }
  }

  private val LOG = thisLogger()
}