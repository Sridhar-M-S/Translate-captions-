package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsManager
import com.example.service.FloatingOverlayContent
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settingsManager = SettingsManager(context)

    composeTestRule.setContent { 
      MyApplicationTheme(darkTheme = true) { 
        FloatingOverlayContent(
          subtitleFlow = MutableStateFlow("Hello World"),
          translationFlow = MutableStateFlow("உலகிற்கு வணக்கம்"),
          isTranslatingFlow = MutableStateFlow(true),
          isMutedFlow = MutableStateFlow(false),
          settingsManager = settingsManager,
          onToggleMute = {},
          onStartTranslation = {},
          onPauseTranslation = {},
          onStop = {},
          onDrag = { _, _ -> },
          onSelectCaptionArea = {},
          onOpenSettings = {},
          maxMusicVolume = 15,
          currentMusicVolume = 8,
          onOriginalVolumeChanged = {}
        )
      } 
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
