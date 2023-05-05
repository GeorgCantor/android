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
package com.android.tools.idea.streaming.device

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.RemoteFileMode
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.SocketSpec
import com.android.adblib.shellAsLines
import com.android.adblib.syncSend
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.diagnostics.crash.StudioCrashReporter
import com.android.tools.idea.diagnostics.report.GenericReport
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.DeviceMirroringSettings
import com.android.tools.idea.util.StudioPathManager
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DeviceInfo
import com.google.wireless.android.sdk.stats.DeviceMirroringAbnormalAgentTermination
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PluginPathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.util.Disposer
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.ContainerUtil.createLockFreeCopyOnWriteList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.android.facet.AndroidFacet
import java.awt.Dimension
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val SCREEN_SHARING_AGENT_JAR_NAME = "screen-sharing-agent.jar"
internal const val SCREEN_SHARING_AGENT_SO_NAME = "libscreen-sharing-agent.so"
internal const val SCREEN_SHARING_AGENT_SOURCE_PATH = "tools/adt/idea/streaming/screen-sharing-agent"
internal const val DEVICE_PATH_BASE = "/data/local/tmp/.studio"
private const val MAX_BIT_RATE_EMULATOR = 2000000
private const val VIDEO_CHANNEL_MARKER = 'V'.code.toByte()
private const val CONTROL_CHANNEL_MARKER = 'C'.code.toByte()
// Flag definitions. Keep in sync with flags.h
internal const val START_VIDEO_STREAM = 0x01
internal const val TURN_OFF_DISPLAY_WHILE_MIRRORING = 0x02
internal const val SUPPORT_FOLDING = 0x04
/** Maximum cumulative length of agent messages to remember. */
private const val MAX_TOTAL_AGENT_MESSAGE_LENGTH = 10_000
private const val MAX_ERROR_MESSAGE_AGE_MILLIS = 1000L
private const val CRASH_REPORT_TYPE = "Screen Sharing Agent termination"
private const val REPORT_FIELD_EXIT_CODE = "exitCode"
private const val REPORT_FIELD_RUN_DURATION_MILLIS = "runDurationMillis"
private const val REPORT_FIELD_AGENT_MESSAGES = "agentMessages"
private const val REPORT_FIELD_DEVICE = "device"

internal class DeviceClient(
  disposableParent: Disposable,
  val deviceSerialNumber: String,
  val deviceHandle: DeviceHandle,
  val deviceConfig: DeviceConfiguration,
  private val deviceAbi: String,
  private val project: Project
) : Disposable {

  val deviceName: String = deviceConfig.deviceName
  @Volatile
  var videoDecoder: VideoDecoder? = null
    private set
  @Volatile
  var deviceController: DeviceController? = null
    private set
  internal var startTime = 0L // Time when startAgentAndConnect was called.
  internal var pushEndTime = 0L // Time when the agent push completed.
  internal var startAgentTime = 0L // Time when the command to start the agent was issued.
  internal var channelConnectedTime = 0L // Time when the channels were connected.
  private val clientScope = AndroidCoroutineScope(this)
  private lateinit var controlChannel: SuspendingSocketChannel
  private lateinit var videoChannel: SuspendingSocketChannel
  private val connectionState = AtomicReference<CompletableDeferred<Unit>>()
  private var videoStreamActive = AtomicBoolean()
  private val logger = thisLogger()
  private val agentTerminationListeners = createLockFreeCopyOnWriteList<AgentTerminationListener>()

  init {
    Disposer.register(disposableParent, this)
  }

  /**
   * Asynchronously establishes connection to the screen sharing agent without activating the video stream.
   */
  fun establishAgentConnectionWithoutVideoStreamAsync() {
    clientScope.launch { establishAgentConnection(Dimension(), UNKNOWN_ORIENTATION, false)}
  }

  /**
   * Establishes connection to the screen sharing agent. If the process of establishing connection
   * has already been started, waits for it to complete.
   */
  suspend fun establishAgentConnection(maxVideoSize: Dimension, initialDisplayOrientation: Int, startVideoStream: Boolean) {
    val completion = CompletableDeferred<Unit>()
    val connection = connectionState.compareAndExchange(null, completion) ?: completion
    if (connection === completion) {
      try {
        startAgentAndConnect(maxVideoSize, initialDisplayOrientation, startVideoStream)
        connection.complete(Unit)
      }
      catch (e: Throwable) {
        connectionState.set(null)
        connection.completeExceptionally(adjustException(e))
      }
    }
    connection.await()

    if (startVideoStream && !videoStreamActive.get()) {
      startVideoStream(maxVideoSize)
    }
  }

  /**
   * Starts the screen sharing agent and connects to it.
   */
  private suspend fun startAgentAndConnect(maxVideoSize: Dimension, initialDisplayOrientation: Int, startVideoStream: Boolean) {
    startTime = System.currentTimeMillis()
    val adb = AdbLibService.getSession(project).deviceServices
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerialNumber)
    val agentPushed = coroutineScope {
      async {
        pushAgent(deviceSelector, adb)
      }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    val asyncChannel = AsynchronousServerSocketChannel.open().bind(InetSocketAddress(0))
    val port = (asyncChannel.localAddress as InetSocketAddress).port
    logger.debug("Using port $port")
    SuspendingServerSocketChannel(asyncChannel).use { serverSocketChannel ->
      val socketName = "screen-sharing-agent-$port"
      ClosableReverseForwarding(deviceSelector, SocketSpec.LocalAbstract(socketName), SocketSpec.Tcp(port), adb).use {
        it.startForwarding()
        agentPushed.await()
        startAgent(deviceSelector, adb, socketName, maxVideoSize, initialDisplayOrientation, startVideoStream)
        connectChannels(serverSocketChannel)
        // Port forwarding can be removed since the already established connections will continue to work without it.
      }
    }
    try {
      deviceController = DeviceController(this, controlChannel)
    }
    catch (e: IncorrectOperationException) {
      return // Already disposed.
    }
    videoDecoder = VideoDecoder(videoChannel, clientScope, maxVideoSize).apply { start() }
    videoStreamActive.set(startVideoStream)
  }

  fun addAgentTerminationListener(listener: AgentTerminationListener) {
    agentTerminationListeners.add(listener)
  }

  fun removeAgentTerminationListener(listener: AgentTerminationListener) {
    agentTerminationListeners.remove(listener)
  }

  private fun startVideoStream(maxOutputSize: Dimension) {
    if (videoStreamActive.compareAndSet(false, true)) {
      deviceController?.sendControlMessage(SetMaxVideoResolutionMessage(maxOutputSize.width, maxOutputSize.height))
      deviceController?.sendControlMessage(StartVideoStreamMessage.instance)
    }
  }

  fun stopVideoStream() {
    if (videoStreamActive.compareAndSet(true, false)) {
      deviceController?.sendControlMessage(StopVideoStreamMessage.instance)
    }
  }

  /** Returns the original exception if the device is still connected, or a CancellationException otherwise. */
  private suspend fun adjustException(e: Throwable): Throwable {
    return when {
      e is CancellationException -> e
      isDeviceConnected() == false -> CancellationException()
      else -> e
    }
  }

  /** Checks if the device is connected. Returns null if it cannot be determined. */
  private suspend fun isDeviceConnected(): Boolean? {
    return try {
      return AdbLibService.getSession(project).hostServices.devices().entries.find { it.serialNumber == deviceSerialNumber } != null
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (_: Throwable) {
      null
    }
  }

  private suspend fun connectChannels(serverSocketChannel: SuspendingServerSocketChannel) {
    val channel1 = serverSocketChannel.acceptAndEnsureClosing(this)
    val channel2 = serverSocketChannel.acceptAndEnsureClosing(this)
    // The channels are distinguished by single-byte markers, 'V' for video and 'C' for control.
    // Read the markers to assign the channels appropriately.
    coroutineScope {
      val marker1 = async { readChannelMarker(channel1) }
      val marker2 = async { readChannelMarker(channel2) }
      val m1 = marker1.await()
      val m2 = marker2.await()
      if (m1 == VIDEO_CHANNEL_MARKER && m2 == CONTROL_CHANNEL_MARKER) {
        videoChannel = channel1
        controlChannel = channel2
      }
      else if (m1 == CONTROL_CHANNEL_MARKER && m2 == VIDEO_CHANNEL_MARKER) {
        videoChannel = channel2
        controlChannel = channel1
      }
      else {
        throw RuntimeException("Unexpected channel markers: $m1, $m2")
      }
    }
    channelConnectedTime = System.currentTimeMillis()
    controlChannel.setOption(StandardSocketOptions.TCP_NODELAY, true)
  }

  private suspend fun readChannelMarker(channel: SuspendingSocketChannel): Byte {
    val buf = ByteBuffer.allocate(1)
    channel.read(buf, 5, TimeUnit.SECONDS)
    buf.flip()
    return buf.get()
  }

  override fun dispose() {
  }

  private suspend fun pushAgent(deviceSelector: DeviceSelector, adb: AdbDeviceServices) {
    val soFile: Path
    val jarFile: Path
    if (StudioPathManager.isRunningFromSources()) {
      // Development environment.
      val projectDir = project.guessProjectDir()?.toNioPath()
      if (projectDir != null && projectDir.endsWith(SCREEN_SHARING_AGENT_SOURCE_PATH)) {
        // Development environment for the screen sharing agent.
        // Use the agent built by running "Build > Make Project" in Studio.
        val facet = project.modules.firstNotNullOfOrNull { AndroidFacet.getInstance(it) }
        val buildVariant = facet?.properties?.SELECTED_BUILD_VARIANT ?: "debug"
        soFile = projectDir.resolve(
          "app/build/intermediates/stripped_native_libs/$buildVariant/out/lib/$deviceAbi/$SCREEN_SHARING_AGENT_SO_NAME")
        val apkName = if (buildVariant == "debug") "app-debug.apk" else "app-release-unsigned.apk"
        jarFile = projectDir.resolve("app/build/outputs/apk/$buildVariant/$apkName")
      }
      else {
        // Development environment for Studio.
        // Use the agent built by running "bazel build //tools/adt/idea/streaming/screen-sharing-agent:bundle"
        val binDir = Paths.get(StudioPathManager.getBinariesRoot())
        soFile = binDir.resolve("$SCREEN_SHARING_AGENT_SOURCE_PATH/native/$deviceAbi/$SCREEN_SHARING_AGENT_SO_NAME")
        jarFile = binDir.resolve("$SCREEN_SHARING_AGENT_SOURCE_PATH/$SCREEN_SHARING_AGENT_JAR_NAME")
      }
    }
    else {
      // Installed Studio.
      val pluginDir = PluginPathManager.getPluginHome("android").toPath()
      soFile = pluginDir.resolve("resources/screen-sharing-agent/$deviceAbi/$SCREEN_SHARING_AGENT_SO_NAME")
      jarFile = pluginDir.resolve("resources/screen-sharing-agent/$SCREEN_SHARING_AGENT_JAR_NAME")
    }

    coroutineScope {
      // "chown shell:shell" ensures proper ownership of /data/local/tmp/.studio if adb is rooted.
      val command = "mkdir -p $DEVICE_PATH_BASE; chmod 755 $DEVICE_PATH_BASE; chown shell:shell $DEVICE_PATH_BASE"
      adb.shellAsLines(deviceSelector, command).collect {
        if (it is ShellCommandOutputElement.ExitCode && it.exitCode != 0) {
          logger.warn("Unable to create $DEVICE_PATH_BASE directory: ${it.exitCode}")
        }
      }
      val permissions = RemoteFileMode.fromPosixPermissions(PosixFilePermission.OWNER_READ)
      val nativeLibraryPushed = async {
        adb.syncSend(deviceSelector, soFile, "$DEVICE_PATH_BASE/$SCREEN_SHARING_AGENT_SO_NAME", permissions)
      }
      adb.syncSend(deviceSelector, jarFile, "$DEVICE_PATH_BASE/$SCREEN_SHARING_AGENT_JAR_NAME", permissions)
      nativeLibraryPushed.await()
    }
    pushEndTime = System.currentTimeMillis()
  }

  private suspend fun startAgent(
      deviceSelector: DeviceSelector,
      adb: AdbDeviceServices,
      socketName: String,
      maxVideoSize: Dimension,
      initialDisplayOrientation: Int,
      startVideoStream: Boolean) {
    startAgentTime = System.currentTimeMillis()
    val maxSizeArg =
        if (maxVideoSize.width > 0 && maxVideoSize.height > 0) " --max_size=${maxVideoSize.width},${maxVideoSize.height}" else ""
    val orientationArg = if (initialDisplayOrientation == UNKNOWN_ORIENTATION) "" else " --orientation=$initialDisplayOrientation"
    val flags = (if (startVideoStream) START_VIDEO_STREAM else 0) or
                (if (DeviceMirroringSettings.getInstance().turnOffDisplayWhileMirroring) TURN_OFF_DISPLAY_WHILE_MIRRORING else 0) or
                (if (StudioFlags.DEVICE_MIRRORING_FOLDING_SUPPORT.get()) SUPPORT_FOLDING else 0)
    val flagsArg = if (flags != 0) " --flags=$flags" else ""
    val maxBitRateArg = when {
      deviceSerialNumber.startsWith("emulator-") || deviceConfig.deviceProperties.isVirtual == true ->
          " --max_bit_rate=$MAX_BIT_RATE_EMULATOR"
      StudioFlags.DEVICE_MIRRORING_MAX_BIT_RATE.get() > 0 -> " --max_bit_rate=${StudioFlags.DEVICE_MIRRORING_MAX_BIT_RATE.get()}"
      else -> ""
    }
    val logLevel = StudioFlags.DEVICE_MIRRORING_AGENT_LOG_LEVEL.get()
    val logLevelArg = if (logLevel.isNotBlank()) " --log=$logLevel" else ""
    val codecName = StudioFlags.DEVICE_MIRRORING_VIDEO_CODEC.get()
    val codecArg = if (codecName.isNotBlank()) " --codec=$codecName" else ""
    val command = "CLASSPATH=$DEVICE_PATH_BASE/$SCREEN_SHARING_AGENT_JAR_NAME app_process $DEVICE_PATH_BASE" +
                  " com.android.tools.screensharing.Main --socket=$socketName" +
                  "$maxSizeArg$orientationArg$flagsArg$maxBitRateArg$logLevelArg$codecArg"
    // Use a coroutine scope that not linked to the lifecycle of the client to make sure that
    // the agent has a chance to terminate gracefully when the client is disposed rather than
    // be killed by adb.
    CoroutineScope(Dispatchers.Unconfined).launch {
      val log = Logger.getInstance("ScreenSharingAgent $deviceName")
      val agentStartTime = System.currentTimeMillis()
      val errors = OutputAccumulator(MAX_TOTAL_AGENT_MESSAGE_LENGTH, MAX_ERROR_MESSAGE_AGE_MILLIS)
      try {
        adb.shellAsLines(deviceSelector, command).collect {
          when (it) {
            is ShellCommandOutputElement.StdoutLine -> if (it.contents.isNotBlank()) log.info(it.contents)
            is ShellCommandOutputElement.StderrLine -> {
              if (it.contents.isNotBlank()) {
                log.warn(it.contents)
                errors.addMessage(it.contents.trimEnd())
              }
            }
            is ShellCommandOutputElement.ExitCode -> {
              onDisconnection()
              if (it.exitCode == 0) {
                log.info("terminated")
              } else {
                log.warn("terminated with code ${it.exitCode}")
                recordAbnormalAgentTermination(it.exitCode, System.currentTimeMillis() - agentStartTime, errors)
              }
              for (listener in agentTerminationListeners) {
                listener.agentTerminated(it.exitCode)
              }
              cancel()
            }
          }
        }
      }
      catch (e: EOFException) {
        // Device disconnected. This is not an error.
        log.info("device disconnected")
        onDisconnection()
        for (listener in agentTerminationListeners) {
          listener.deviceDisconnected()
        }
      }
      catch (e: Throwable) {
        throw adjustException(e)
      }
    }
  }

  private fun recordAbnormalAgentTermination(exitCode: Int, runDurationMillis: Long, errors: OutputAccumulator) {
    // Log a metrics event.
    val studioEvent = AndroidStudioEvent.newBuilder()
      .setKind(AndroidStudioEvent.EventKind.DEVICE_MIRRORING_ABNORMAL_AGENT_TERMINATION)
      .setDeviceMirroringAbnormalAgentTermination(
        DeviceMirroringAbnormalAgentTermination.newBuilder()
          .setExitCode(exitCode)
          .setRunDurationMillis(runDurationMillis)
      )
      .setDeviceInfo(
        DeviceInfo.newBuilder()
          .fillFrom(deviceConfig)
          .fillMdnsConnectionType(deviceSerialNumber)
      )

    UsageTracker.log(studioEvent)

    // Create and submit a Crash report.
    val fields = mapOf(
      REPORT_FIELD_EXIT_CODE to exitCode.toString(),
      REPORT_FIELD_RUN_DURATION_MILLIS to runDurationMillis.toString(),
      REPORT_FIELD_AGENT_MESSAGES to errors.getMessages(),
      REPORT_FIELD_DEVICE to deviceConfig.deviceName,
    )
    val report = GenericReport(CRASH_REPORT_TYPE, fields)
    try {
      StudioCrashReporter.getInstance().submit(report.asCrashReport())
    }
    catch (ignore: RuntimeException) {
      // May happen due to exceeded quota.
    }
  }

  private fun onDisconnection() {
    deviceController = null
    videoDecoder = null
    connectionState.set(null)
  }

  private suspend fun SuspendingServerSocketChannel.acceptAndEnsureClosing(parentDisposable: Disposable): SuspendingSocketChannel =
      accept().also { Disposer.register(parentDisposable, DisposableCloser(it)) }

  interface AgentTerminationListener {
    fun agentTerminated(exitCode: Int)
    fun deviceDisconnected()
  }

  private class DisposableCloser(private val channel: SuspendingSocketChannel) : Disposable {

    override fun dispose() {
      // Disconnect the socket channel asynchronously.
      CoroutineScope(Dispatchers.IO).launch {
        try {
          channel.close()
        }
        catch (e: IOException) {
          thisLogger().warn(e)
        }
      }
    }
  }

  private class ClosableReverseForwarding(
    val deviceSelector: DeviceSelector,
    val deviceSocket: SocketSpec,
    val localSocket: SocketSpec,
    val adb: AdbDeviceServices,
  ) : SuspendingCloseable {

    var opened = false

    suspend fun startForwarding() {
      adb.reverseForward(deviceSelector, deviceSocket, localSocket, rebind = true)
      opened = true
    }

    override suspend fun close() {
      if (opened) {
        opened = false
        adb.reverseKillForward(deviceSelector, deviceSocket)
      }
    }
  }

  private class OutputAccumulator(private val maxSize: Int, private val maxAgeMillis: Long) {

    private val messages = ArrayDeque<Message>()
    private var totalSize = 0

    fun addMessage(text: String) {
      val time = System.currentTimeMillis()
      prune(maxSize - text.length, time)
      messages.add(Message(time, text))
      totalSize += text.length
    }

    fun getMessages(): String {
      prune(maxSize, System.currentTimeMillis())
      return messages.joinToString("\n", transform = Message::text)
    }

    private fun prune(size: Int, time: Long) {
      val cutoff = time - maxAgeMillis
      while (totalSize > size || messages.isNotEmpty() && messages.first().timestamp < cutoff) {
        totalSize -= messages.removeFirst().text.length
      }
    }

    private data class Message(val timestamp: Long, val text: String)
  }
}
