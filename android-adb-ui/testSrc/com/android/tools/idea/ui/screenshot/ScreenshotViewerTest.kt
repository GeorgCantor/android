/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.ui.screenshot

import com.android.SdkConstants
import com.android.tools.adtui.ImageUtils
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.adtui.swing.findModelessDialog
import com.android.tools.adtui.swing.optionsAsString
import com.android.tools.adtui.swing.selectFirstMatch
import com.android.tools.analytics.UsageTrackerRule
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind.DEVICE_SCREENSHOT_EVENT
import com.google.wireless.android.sdk.stats.DeviceScreenshotEvent
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.fileChooser.impl.FileChooserFactoryImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper.CLOSE_EXIT_CODE
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.EDT
import org.intellij.images.ui.ImageComponent
import org.intellij.images.ui.ImageComponentDecorator
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.EnumSet
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.UIManager

private const val DISPLAY_INFO_PHONE =
  "DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:4619827259835644672\", 1080 x 2400, modeId 1, defaultModeId 1," +
  " supportedModes [{id=1, width=1080, height=2400, fps=60.000004, alternativeRefreshRates=[]}], colorMode 0, supportedColorModes [0]," +
  " hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[], mMaxLuminance=500.0, mMaxAverageLuminance=500.0, mMinLuminance=0.0}," +
  " allmSupported false, gameContentTypeSupported false, density 420, 420.0 x 420.0 dpi, appVsyncOff 1000000, presDeadline 16666666," +
  " cutout DisplayCutout{insets=Rect(0, 136 - 0, 0) waterfall=Insets{left=0, top=0, right=0, bottom=0}" +
  " boundingRect={Bounds=[Rect(0, 0 - 0, 0), Rect(0, 0 - 136, 136), Rect(0, 0 - 0, 0), Rect(0, 0 - 0, 0)]}" +
  " cutoutPathParserInfo={CutoutPathParserInfo{displayWidth=1080 displayHeight=2400 stableDisplayHeight=1080 stableDisplayHeight=2400" +
  " density={2.625} cutoutSpec={M 128,83 A 44,44 0 0 1 84,127 44,44 0 0 1 40,83 44,44 0 0 1 84,39 44,44 0 0 1 128,83 Z @left}" +
  " rotation={0} scale={1.0} physicalPixelDisplaySizeRatio={1.0}}}}, touch INTERNAL, rotation 0, type INTERNAL," +
  " address {port=0, model=0x401cec6a7a2b7b}," +
  " deviceProductInfo DeviceProductInfo{name=EMU_display_0, manufacturerPnpId=GGL, productId=1, modelYear=null," +
  " manufactureDate=ManufactureDate{week=27, year=2006}, connectionToSinkType=0}, state ON, frameRateOverride , brightnessMinimum 0.0," +
  " brightnessMaximum 1.0, brightnessDefault 0.39763778," +
  " FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, installOrientation 0}"

private const val DISPLAY_INFO_WATCH =
  "DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:8141603649153536\", 454 x 454, modeId 1, defaultModeId 1," +
  " supportedModes [{id=1, width=454, height=454, fps=60.000004}], colorMode 0, supportedColorModes [0]," +
  " HdrCapabilities HdrCapabilities{mSupportedHdrTypes=[], mMaxLuminance=500.0, mMaxAverageLuminance=500.0, mMinLuminance=0.0}," +
  " allmSupported false, gameContentTypeSupported false, density 320, 320.0 x 320.0 dpi, appVsyncOff 1000000, presDeadline 16666666," +
  " touch INTERNAL, rotation 0, type INTERNAL, address {port=0, model=0x1cecbed168ea}," +
  " deviceProductInfo DeviceProductInfo{name=EMU_display_0, manufacturerPnpId=GGL, productId=1, modelYear=null," +
  " manufactureDate=ManufactureDate{week=27, year=2006}, relativeAddress=null}, state ON," +
  " FLAG_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_ROUND}"

private const val DISPLAY_INFO_WATCH_SQUARE =
  "DisplayDeviceInfo{\"Built-in Screen\": uniqueId=\"local:8141603649153536\", 454 x 454, modeId 1, defaultModeId 1," +
  " supportedModes [{id=1, width=454, height=454, fps=60.000004}], colorMode 0, supportedColorModes [0]," +
  " HdrCapabilities HdrCapabilities{mSupportedHdrTypes=[], mMaxLuminance=500.0, mMaxAverageLuminance=500.0, mMinLuminance=0.0}," +
  " allmSupported false, gameContentTypeSupported false, density 320, 320.0 x 320.0 dpi, appVsyncOff 1000000, presDeadline 16666666," +
  " touch INTERNAL, rotation 0, type INTERNAL, address {port=0, model=0x1cecbed168ea}," +
  " deviceProductInfo DeviceProductInfo{name=EMU_display_0, manufacturerPnpId=GGL, productId=1, modelYear=null," +
  " manufactureDate=ManufactureDate{week=27, year=2006}, relativeAddress=null}, state ON," +
  " FLAG_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS}"

/**
 * Tests for [ScreenshotViewer].
 */
@RunsInEdt
class ScreenshotViewerTest {
  private val projectRule = ProjectRule()
  private val usageTrackerRule = UsageTrackerRule()
  private val disposableRule = DisposableRule()

  @get:Rule
  val rule = RuleChain(projectRule, EdtRule(), PortableUiFontRule(), HeadlessDialogRule(), disposableRule, usageTrackerRule)

  private val testFrame = object : FramingOption {
    override val displayName = "Test frame"
  }

  @After
  fun tearDown() {
    findModelessDialog { it is ScreenshotViewer }?.close(CLOSE_EXIT_CODE)
  }

  @Test
  @Ignore("b/316591692")
  fun testResizing() {
    val screenshotImage = ScreenshotImage(createImage(100, 200), 0, DeviceType.PHONE, DISPLAY_INFO_PHONE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val zoomModel = ui.getComponent<ImageComponentDecorator>().zoomModel
    val zoomFactor = zoomModel.zoomFactor

    viewer.rootPane.setSize(viewer.rootPane.width + 50, viewer.rootPane.width + 100)
    ui.layoutAndDispatchEvents()
    assertThat(zoomModel.zoomFactor).isWithin(1.0e-6).of(zoomFactor)
  }

  @Test
  @Ignore("b/316591692")
  fun testUpdateEditorImage() {
    val screenshotImage = ScreenshotImage(createImage(100, 200), 0, DeviceType.PHONE, DISPLAY_INFO_PHONE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val zoomModel = ui.getComponent<ImageComponentDecorator>().zoomModel
    val zoomFactor = zoomModel.zoomFactor

    viewer.updateEditorImage()
    ui.layoutAndDispatchEvents()
    assertThat(zoomModel.zoomFactor).isWithin(1.0e-6).of(zoomFactor)
  }

  @Test
  fun testClipRoundScreenshot() {
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)
    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Display Shape")
    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val processedImage: BufferedImage = ui.getComponent<ImageComponent>().document.value
    assertThat(processedImage.getRGB(screenshotImage.width / 2, screenshotImage.height / 2)).isEqualTo(Color.RED.rgb)
    assertThat(processedImage.getRGB(5, 5)).isEqualTo(0)
    assertThat(processedImage.getRGB(screenshotImage.width - 5, screenshotImage.height - 5)).isEqualTo(0)
  }

  @Ignore("b/316591692")
  @Test
  fun testClipRoundScreenshotWithBackgroundColor() {
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Rectangular")
    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val processedImage: BufferedImage = ui.getComponent<ImageComponent>().document.value
    assertThat(processedImage.getRGB(screenshotImage.width / 2, screenshotImage.height / 2)).isEqualTo(Color.RED.rgb)
    assertThat(processedImage.getRGB(5, 5)).isEqualTo(Color.BLACK.rgb)
    assertThat(processedImage.getRGB(screenshotImage.width - 5, screenshotImage.height - 5)).isEqualTo(Color.BLACK.rgb)
  }

  @Ignore("b/316591692")
  @Test
  fun testClipRoundScreenshotWithBackgroundColorInDarkMode() {
    runInEdt {
      UIManager.setLookAndFeel(DarculaLaf())
    }
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)
    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Rectangular")
    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val processedImage: BufferedImage = ui.getComponent<ImageComponent>().document.value
    assertThat(processedImage.getRGB(screenshotImage.width / 2, screenshotImage.height / 2)).isEqualTo(Color.RED.rgb)
    assertThat(processedImage.getRGB(5, 5)).isEqualTo(Color.BLACK.rgb)
    assertThat(processedImage.getRGB(screenshotImage.width - 5, screenshotImage.height - 5)).isEqualTo(Color.BLACK.rgb)
  }

  @Test
  fun testPlayCompatibleScreenshotIsAvailable() {
    val screenshotImage = ScreenshotImage(createImage(360, 360), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()
    assertThat(clipComboBox.optionsAsString()).contains("Play Store Compatible")
  }

  @Test
  fun testPlayCompatibleScreenshotIsNotAvailableWhenScreenshotIsNot1to1Ratio() {
    val screenshotImage = ScreenshotImage(createImage(384, 500), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()
    assertThat(clipComboBox.optionsAsString()).doesNotContain("Play Store Compatible")
  }

  @Test
  @Ignore("b/316591692")
  fun testPlayCompatibleScreenshot() {
    val screenshotImage = ScreenshotImage(createImage(384, 384), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Play Store Compatible")
    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val processedImage: BufferedImage = ui.getComponent<ImageComponent>().document.value
    assertThat(processedImage.getRGB(screenshotImage.width / 2, screenshotImage.height / 2)).isEqualTo(Color.RED.rgb)
    assertThat(processedImage.getRGB(5, 5)).isEqualTo(Color.BLACK.rgb)
    assertThat(processedImage.getRGB(screenshotImage.width - 5, screenshotImage.height - 5)).isEqualTo(Color.BLACK.rgb)
  }

  @Test
  @Ignore("b/316591692")
  fun testPlayCompatibleScreenshotInDarkMode() {
    runInEdt {
      UIManager.setLookAndFeel(DarculaLaf())
    }
    val screenshotImage = ScreenshotImage(createImage(384, 384), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Play Store Compatible")
    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val processedImage: BufferedImage = ui.getComponent<ImageComponent>().document.value
    assertThat(processedImage.getRGB(screenshotImage.width / 2, screenshotImage.height / 2)).isEqualTo(Color.RED.rgb)
    assertThat(processedImage.getRGB(5, 5)).isEqualTo(Color.BLACK.rgb)
    assertThat(processedImage.getRGB(screenshotImage.width - 5, screenshotImage.height - 5)).isEqualTo(Color.BLACK.rgb)
  }

  @Test
  fun testComboBoxDefaultsToDisplayShapeIfAvailable() {
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()
    assertThat(clipComboBox.selectedItem?.toString()).isEqualTo("Display Shape")
  }

  @Test
  fun testComboBoxDefaultsToPlayStoreCompatibleIfDisplayShapeIsNotAvailable() {
    val screenshotImage = ScreenshotImage(createImage(360, 360), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH_SQUARE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()
    assertThat(clipComboBox.selectedItem?.toString()).isEqualTo("Play Store Compatible")
  }

  @Test
  fun testComboBoxDefaultsToRectangularIfPlayStoreCompatibleAndDisplayShapeAreNotAvailable() {
    val screenshotImage = ScreenshotImage(createImage(360, 360), 0, DeviceType.PHONE, DISPLAY_INFO_PHONE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)

    val clipComboBox = ui.getComponent<JComboBox<*>>()
    assertThat(clipComboBox.selectedItem?.toString()).isEqualTo("Rectangular")
  }

  @Test
  fun testScreenshotUsageIsTracked_OkAction_Phone() {
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.PHONE, DISPLAY_INFO_PHONE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    overrideSaveFileDialog()

    viewer.doOKAction()

    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(usageTrackerRule.screenshotEvents()).containsExactly(
      DeviceScreenshotEvent.newBuilder()
        .setDeviceType(DeviceScreenshotEvent.DeviceType.PHONE)
        .setDecorationOption(DeviceScreenshotEvent.DecorationOption.RECTANGULAR)
        .build()
    )
  }

  @Test
  fun testScreenshotUsageIsTracked_CopyClipboard_Phone() {
    val screenshotImage = ScreenshotImage(createImage(200, 180), 0, DeviceType.PHONE, DISPLAY_INFO_PHONE)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)
    val copyClipboardButton = ui.getComponent<JButton> { it.text == "Copy to Clipboard" }

    copyClipboardButton.doClick()

    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(usageTrackerRule.screenshotEvents()).containsExactly(
      DeviceScreenshotEvent.newBuilder()
        .setDeviceType(DeviceScreenshotEvent.DeviceType.PHONE)
        .setDecorationOption(DeviceScreenshotEvent.DecorationOption.RECTANGULAR)
        .build()
    )
  }

  @Test
  fun testScreenshotUsageIsTracked_OkAction_Wear() {
    val screenshotImage = ScreenshotImage(createImage(384, 384), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)
    val clipComboBox = ui.getComponent<JComboBox<*>>()
    overrideSaveFileDialog()

    clipComboBox.selectFirstMatch("Play Store Compatible")
    viewer.doOKAction()

    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(usageTrackerRule.screenshotEvents()).containsExactly(
      DeviceScreenshotEvent.newBuilder()
        .setDeviceType(DeviceScreenshotEvent.DeviceType.WEAR)
        .setDecorationOption(DeviceScreenshotEvent.DecorationOption.PLAY_COMPATIBLE)
        .build()
    )
  }


  @Test
  fun testScreenshotUsageIsTracked_CopyClipboard_Wear() {
    val screenshotImage = ScreenshotImage(createImage(384, 384), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    val viewer = createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor())
    val ui = FakeUi(viewer.rootPane)
    val copyClipboardButton = ui.getComponent<JButton> { it.text == "Copy to Clipboard" }
    val clipComboBox = ui.getComponent<JComboBox<*>>()

    clipComboBox.selectFirstMatch("Display Shape")
    copyClipboardButton.doClick()

    EDT.dispatchAllInvocationEvents()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(usageTrackerRule.screenshotEvents()).containsExactly(
      DeviceScreenshotEvent.newBuilder()
        .setDeviceType(DeviceScreenshotEvent.DeviceType.WEAR)
        .setDecorationOption(DeviceScreenshotEvent.DecorationOption.DISPLAY_SHAPE_CLIP)
        .build()
    )
  }

  @Test
  fun testScreenshotViewerWithoutFramingOptionsDoesNotAttemptToSelectFrameOption() {
    val screenshotImage = ScreenshotImage(createImage(384, 384), 0, DeviceType.WEAR, DISPLAY_INFO_WATCH)
    ScreenshotViewer.PersistentState.getInstance(projectRule.project).frameScreenshot = true

    // test that no exceptions are thrown
    createScreenshotViewer(screenshotImage, DeviceArtScreenshotPostprocessor(), framingOptions = listOf())
  }

  private fun createImage(width: Int, height: Int): BufferedImage {
    val image = ImageUtils.createDipImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.paint = Color.RED
    graphics.fillRect(0, 0, image.width, image.height)
    graphics.dispose()
    return image
  }

  private fun createScreenshotViewer(screenshotImage: ScreenshotImage,
                                     screenshotPostprocessor: ScreenshotPostprocessor,
                                     framingOptions: List<FramingOption> = listOf(testFrame)): ScreenshotViewer {
    val screenshotFile = FileUtil.createTempFile("screenshot", SdkConstants.DOT_PNG).toPath()
    val viewer = ScreenshotViewer(projectRule.project, screenshotImage, screenshotFile, null, screenshotPostprocessor,
                                  framingOptions, 0, EnumSet.of(ScreenshotViewer.Option.ALLOW_IMAGE_ROTATION))
    viewer.show()
    return viewer
  }

  private fun overrideSaveFileDialog() {
    val tempFile = FileUtil.createTempFile("foo", "screenshot")
    val virtualFileWrapper = VirtualFileWrapper(tempFile)
    val factory = object : FileChooserFactoryImpl() {
      override fun createSaveFileDialog(descriptor: FileSaverDescriptor, project: Project?): FileSaverDialog {
        return object : FileSaverDialog {
          override fun save(baseDir: VirtualFile?, filename: String?) = virtualFileWrapper
          override fun save(baseDir: Path?, filename: String?) = virtualFileWrapper
        }
      }
    }
    ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, factory, disposableRule.disposable)
  }

  private fun UsageTrackerRule.screenshotEvents(): List<DeviceScreenshotEvent> =
    usages.filter { it.studioEvent.kind == DEVICE_SCREENSHOT_EVENT }.map { it.studioEvent.deviceScreenshotEvent }

}
