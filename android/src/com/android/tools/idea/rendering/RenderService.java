/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.rendering;

import static com.android.tools.rendering.RenderAsyncActionExecutor.*;
import static com.android.tools.rendering.ProblemSeverity.ERROR;

import com.android.ide.common.rendering.api.MergeCookie;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.sdklib.IAndroidTarget;
import com.android.tools.idea.diagnostics.crash.StudioCrashReporter;
import com.android.tools.idea.layoutlib.LayoutLibrary;
import com.android.tools.idea.layoutlib.RenderingException;
import com.android.tools.idea.layoutlib.UnsupportedJavaRuntimeException;
import com.android.tools.idea.rendering.classloading.ClassTransform;
import com.android.tools.layoutlib.LayoutlibFactory;
import com.android.tools.rendering.RenderAsyncActionExecutor;
import com.android.tools.rendering.RenderExecutor;
import com.android.tools.rendering.imagepool.ImagePool;
import com.android.tools.rendering.imagepool.ImagePoolFactory;
import com.android.tools.rendering.parsers.ILayoutPullParserFactory;
import com.android.tools.idea.rendering.parsers.PsiXmlFile;
import com.android.tools.rendering.parsers.TagSnapshot;
import com.android.tools.rendering.HtmlLinkManager;
import com.android.tools.rendering.RenderProblem;
import com.android.tools.rendering.parsers.RenderXmlTag;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.android.uipreview.StudioModuleClassLoaderManager;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * The {@link RenderService} provides rendering and layout information for Android layouts. This is a wrapper around the layout library.
 */
final public class RenderService implements Disposable {
  private static RenderExecutor ourExecutor;

  static {
    ourExecutor = RenderExecutor.create();
  }

  @TestOnly
  public static void initializeRenderExecutor() {
    assert ApplicationManager.getApplication().isUnitTestMode(); // Only to be called from unit testszs

    ourExecutor = RenderExecutor.create();
  }

  static void shutdownRenderExecutor() {
    ourExecutor.shutdown();
  }

  /**
   * Shutdowns the render thread and cancels any pending tasks.
   * @param timeoutSeconds if >0, wait at most this number of seconds before killing any running tasks.
   */
  @TestOnly
  public static void shutdownRenderExecutor(@SuppressWarnings("SameParameterValue") long timeoutSeconds) {
    assert ApplicationManager.getApplication().isUnitTestMode(); // Only to be called from unit tests

    ourExecutor.shutdown(timeoutSeconds);
  }

  private static final String JDK_INSTALL_URL = "https://developer.android.com/preview/setup-sdk.html#java8";

  private final Object myCredential = new Object();

  private final ImagePool myImagePool = ImagePoolFactory.createImagePool();

  private final Consumer<RenderTaskBuilder> myConfigureBuilder;

  @NotNull
  public static RenderAsyncActionExecutor getRenderAsyncActionExecutor() {
    return ourExecutor;
  }

  RenderService(@NotNull Consumer<RenderTaskBuilder> configureBuilder) {
    myConfigureBuilder = configureBuilder;
  }

  @NotNull
  public RenderLogger createLogger(
    @Nullable Project project,
    boolean logFramework,
    @NotNull RenderProblem.RunnableFixFactory fixFactory,
    @NotNull Supplier<HtmlLinkManager> linkManagerFactory) {
    return new RenderLogger(project, myCredential, logFramework, fixFactory, linkManagerFactory);
  }

  @NotNull
  public RenderLogger getNopLogger() {
    return RenderLogger.NOP_RENDER_LOGGER;
  }

  /**
   * Returns a {@link RenderTaskBuilder} that can be used to build a new {@link RenderTask}
   */
  @NotNull
  public RenderTaskBuilder taskBuilder(@NotNull RenderModelModule module,
                                       @NotNull RenderConfiguration configuration,
                                       @NotNull RenderLogger logger) {
    RenderTaskBuilder builder = new RenderTaskBuilder(module, configuration, myImagePool, myCredential, logger);
    myConfigureBuilder.accept(builder);
    return builder;
  }

  @Override
  public void dispose() {
    myImagePool.dispose();
  }

  /**
   * @return true if the underlying {@link RenderExecutor} is busy, false otherwise.
   */
  public static boolean isBusy() {
    return ourExecutor.isBusy();
  }

  /**
   * Given a {@link ViewInfo} from a layoutlib rendering, checks that the view info provides
   * valid bounds. This is normally the case. However, there are known scenarios, where
   * for various reasons, the View is left in a state where some of its bounds (left, right, top
   * or bottom) are not properly resolved; they carry MeasureSpec state along, which depending
   * on whether the specification was AT_MOST or EXACTLY this will either be a very large number,
   * or a very small (negative) number. In these cases we don't want to pass on the values to
   * further UI editing processing, since it for example can lead to calling Graphics#drawLine
   * with giant coordinates which can freeze the IDE; see for example
   * https://code.google.com/p/android/issues/detail?id=178690.
   * <p/>
   * To detect this, we simply need to check to see if the MeasureSpec mode bits are set
   * in any of the four bounds fields of the {@link ViewInfo}. Note however that these
   * view bounds are sometimes manipulated (e.g. values added or subtracted if a parent view
   * bound is also invalid) so rather than simply looking for the mode mask strictly, we look
   * in the nearby range too.
   *
   * @param view the {@link ViewInfo} to check
   * @return Normally the {@link ViewInfo} itself, but a placeholder 0-bound {@link ViewInfo} if
   * the view bounds are indeed invalid
   */
  @NotNull
  public static ViewInfo getSafeBounds(@NotNull ViewInfo view) {
    int left = Math.abs(view.getLeft());
    int right = Math.abs(view.getRight());
    int top = Math.abs(view.getTop());
    int bottom = Math.abs(view.getBottom());

    if (left < MAX_MAGNITUDE && right < MAX_MAGNITUDE && top < MAX_MAGNITUDE && bottom < MAX_MAGNITUDE) {
      return view;
    }
    else {
      // Not extracted as a constant; we expect this scenario to be rare
      return new ViewInfo(null, null, 0, 0, 0, 0);
    }
  }

  /**
   * Returns the {@link RenderXmlTag} associated with a {@link ViewInfo}, if any
   *
   * @param view the view to check
   * @return the corresponding tag, if any
   */
  @Nullable
  public static RenderXmlTag getXmlTag(@NotNull ViewInfo view) {
    Object cookie = view.getCookie();
    if (cookie != null) {
      if (cookie instanceof TagSnapshot) {
        TagSnapshot snapshot = (TagSnapshot)cookie;
        return snapshot.tag;
      }
      if (cookie instanceof MergeCookie) {
        cookie = ((MergeCookie) cookie).getCookie();
        if (cookie instanceof TagSnapshot) {
          TagSnapshot snapshot = (TagSnapshot)cookie;
          return snapshot.tag;
        }
      }
      if (cookie instanceof RenderXmlTag) {
        return (RenderXmlTag)cookie;
      }
    }

    return null;
  }

  @NotNull
  public ImagePool getSharedImagePool() {
    return myImagePool;
  }

  /** This is the View.MeasureSpec mode shift */
  private static final int MEASURE_SPEC_MODE_SHIFT = 30;

  /**
   * The maximum absolute value of bounds. This tries to identify values that carry
   * remnants of View.MeasureSpec mode bits, but accounts for the fact that sometimes arithmetic
   * is carried out on these values afterwards to bring them to lower values than they started
   * at, and we want to include those as well; there's a lot of room since the bits are shifted
   * quite a long way compared to the current relevant screen pixel ranges.
   */
  private static final int MAX_MAGNITUDE = 1 << (MEASURE_SPEC_MODE_SHIFT - 5);

  private static final RenderingPriority DEFAULT_RENDERING_PRIORITY = RenderingPriority.HIGH;

  private static Logger getLogger() {
    return Logger.getInstance(RenderService.class);
  }

  public static class RenderTaskBuilder {
    private final RenderContext myContext;
    private final Object myCredential;
    @NotNull private ImagePool myImagePool;
    @Nullable private XmlFile myXmlFile;
    @NotNull private final RenderLogger myLogger;
    @Nullable private ILayoutPullParserFactory myParserFactory;
    private boolean isSecurityManagerEnabled = true;
    private float myQuality = 1f;
    private boolean showDecorations = true;
    private boolean showWithToolsVisibilityAndPosition = true;
    private int myMaxRenderWidth = -1;
    private int myMaxRenderHeight = -1;
    private boolean enableLayoutScanner = false;
    private SessionParams.RenderingMode myRenderingMode = null;
    private boolean useTransparentBackground = false;
    private Function<Object, List<ViewInfo>> myCustomContentHierarchyParser = null;

    /**
     * If two RenderTasks share the same ModuleClassLoader they share the same compose framework. This way they share the state. If we would
     * like to control the state of the framework we want to create a dedicated ClassLoader so that the RenderTask has its own compose
     * framework. Having a dedicated ClassLoader also allows for clearing resources right after the RenderTask no longer used.
     */
    private boolean privateClassLoader = false;

    /**
     * Force classes preloading in RenderTask right after creation.
     */
    private Collection<String> classesToPreload = Collections.emptyList();

    /**
     * Additional bytecode transform to apply to project classes when loaded.
     */
    private ClassTransform myAdditionalProjectTransform = ClassTransform.getIdentity();

    /**
     * Additional bytecode transform to apply to non project classes when loaded.
     */
    private ClassTransform myAdditionalNonProjectTransform = ClassTransform.getIdentity();

    /**
     * Handler called when a new class loader has been instantiated. This allows resetting some state that
     * might be specific to the classes currently loaded.
     */
    @NotNull
    private Runnable myOnNewModuleClassLoader = () -> {};

    /**
     * If true, the {@link RenderTask#render()} will report when the user classes loaded by this class loader are out of date.
     */
    private boolean reportOutOfDateUserClasses = true;
    @NotNull private RenderingPriority myPriority = DEFAULT_RENDERING_PRIORITY;
    private float myMinDownscalingFactor = 0.5f;

    private RenderTaskBuilder(@NotNull RenderModelModule module,
                              @NotNull RenderConfiguration configuration,
                              @NotNull ImagePool defaultImagePool,
                              @NotNull Object credential,
                              @NotNull RenderLogger logger) {
      myContext = new RenderContext(module, configuration);
      myImagePool = defaultImagePool;
      myCredential = credential;
      myLogger = logger;
    }


    /**
     * Forces preloading classes in RenderTask after creation.
     */
    @NotNull
    public RenderTaskBuilder preloadClasses(Collection<String> classesToPreload) {
      this.classesToPreload = classesToPreload;
      return this;
    }

    /**
     * Forces the task to create its own ModuleClassLoader instead of using a shared one from the ModuleClassLoaderManager
     */
    @NotNull
    public RenderTaskBuilder usePrivateClassLoader() {
      privateClassLoader = true;
      return this;
    }

    @NotNull
    public RenderTaskBuilder withPsiFile(@NotNull XmlFile xmlFile) {
      this.myXmlFile = xmlFile;
      return this;
    }

    @NotNull
    public RenderTaskBuilder withParserFactory(@NotNull ILayoutPullParserFactory parserFactory) {
      this.myParserFactory = parserFactory;
      return this;
    }

    public RenderTaskBuilder withLayoutScanner(Boolean enableLayoutScanner) {
      this.enableLayoutScanner = enableLayoutScanner;
      return this;
    }

    /**
     * Disables the image pooling for this render task
     */
    @SuppressWarnings("unused")
    @NotNull
    public RenderTaskBuilder disableImagePool() {
      this.myImagePool = ImagePoolFactory.getNonPooledPool();
      return this;
    }

    /**
     * Sets the quality level as a float between 0 and 1.
     * By default, this is set to 1, which is the highest quality.
     */
    @NotNull
    public RenderTaskBuilder withQuality(float quality) {
      this.myQuality = quality;
      return this;
    }

    /**
     * @see RenderTask#setMaxRenderSize(int, int)
     */
    @NotNull
    public RenderTaskBuilder withMaxRenderSize(int maxRenderWidth, int maxRenderHeight) {
      myMaxRenderWidth = maxRenderWidth;
      myMaxRenderHeight = maxRenderHeight;
      return this;
    }

    /**
     * Disables the security manager for the {@link RenderTask}.
     * Bazel has its own security manager. We allow rendering tests to disable the security manager by calling this method. If this method
     * is not called from a test method, it will throw an {@link IllegalStateException}
     */
    @TestOnly
    @VisibleForTesting
    @NotNull
    public RenderTaskBuilder disableSecurityManager() {
      if (!ApplicationManager.getApplication().isUnitTestMode()) {
        throw new IllegalStateException("This method can only be called in unit test mode");
      }
      this.isSecurityManagerEnabled = false;
      return this;
    }

    /**
     * Disables the decorations (status and navigation bars) for the rendered image.
     */
    @NotNull
    public RenderTaskBuilder disableDecorations() {
      this.showDecorations = false;
      return this;
    }

    @NotNull
    public RenderTaskBuilder disableToolsVisibilityAndPosition() {
      this.showWithToolsVisibilityAndPosition = false;
      return this;
    }

    /**
     * @see RenderTask#setRenderingMode(SessionParams.RenderingMode)
     */
    @NotNull
    public RenderTaskBuilder withRenderingMode(@NotNull SessionParams.RenderingMode renderingMode) {
      myRenderingMode = renderingMode;
      return this;
    }

    /**
     * @see RenderTask#setTransparentBackground()
     */
    @NotNull
    public RenderTaskBuilder useTransparentBackground() {
      useTransparentBackground = true;
      return this;
    }

    /**
     * Sets an additional Java bytecode transformation to be applied to the loaded project classes.
     */
    @NotNull
    public RenderTaskBuilder setProjectClassesTransform(@NotNull ClassTransform transform) {
      myAdditionalProjectTransform = transform;
      return this;
    }

    /**
     * Sets an additional Java bytecode transformation to be applied to the loaded non project classes.
     */
    @NotNull
    public RenderTaskBuilder setNonProjectClassesTransform(@NotNull ClassTransform transform) {
      myAdditionalNonProjectTransform = transform;
      return this;
    }

    /**
     * Sets a callback to be notified when a new class loader has been instantiated.
     */
    @NotNull
    public RenderTaskBuilder setOnNewClassLoader(@NotNull Runnable runnable) {
      myOnNewModuleClassLoader = runnable;
      return this;
    }

    /**
     * Stops the render calls from reporting out of date user classes as a warning in the issues.
     */
    @NotNull
    public RenderTaskBuilder doNotReportOutOfDateUserClasses() {
      reportOutOfDateUserClasses = false;
      return this;
    }

    /**
     * Sets a {@link RenderingPriority} for the RenderTask.
     * By default, the priority used is {@link RenderingPriority#HIGH}
     */
    @NotNull
    public RenderTaskBuilder withPriority(@NotNull RenderingPriority priority) {
      myPriority = priority;
      return this;
    }

    /**
     * Sets a minimum downscaling for rendered images. This is to ensure the quality cannot go below a certain level.
     * By default, it is set at 0.5.
     */
    @NotNull
    public RenderTaskBuilder withMinDownscalingFactor(float downscalingFactor) {
      myMinDownscalingFactor = downscalingFactor;
      return this;
    }

    /**
     * Sets a custom parser for creating the {@link ViewInfo} hierarchy from the layout root view.
     */
    @NotNull
    public RenderTaskBuilder setCustomContentHierarchyParser(@NotNull Function<Object, List<ViewInfo>> parser) {
      myCustomContentHierarchyParser = parser;
      return this;
    }

    /**
     * Builds a new {@link RenderTask}. The returned future always completes successfully but the value might be null if the RenderTask
     * can not be created.
     */
    @NotNull
    public CompletableFuture<RenderTask> build() {
      StackTraceCapture stackTraceCaptureElement = RenderTaskAllocationTrackerKt.captureAllocationStackTrace();

      return CompletableFuture.supplyAsync(() -> {
        RenderModelModule module = myContext.getModule();
        if (module.isDisposed()) {
          getLogger().warn("Module was already disposed");
          return null;
        }
        IAndroidTarget target = myContext.getConfiguration().getTarget();

        if (module.getAndroidPlatform() == null) {
          myContext.getModule().getDependencies().reportMissingSdkDependency(myLogger);
          return null;
        }

        if (target == null) {
          myLogger.addMessage(RenderProblem.createPlain(ERROR, "No render target was chosen"));
          return null;
        }

        LayoutLibrary layoutLib;
        try {
          layoutLib = LayoutlibFactory.getLayoutLibrary(target, module.getAndroidPlatform(), module.getEnvironment().getLayoutlibContext());
        }
        catch (UnsupportedJavaRuntimeException e) {
          RenderProblem.Html javaVersionProblem = RenderProblem.create(ERROR);
          javaVersionProblem.getHtmlBuilder()
            .add(e.getPresentableMessage())
            .newline()
            .addLink("Install a supported JDK", JDK_INSTALL_URL);
          myLogger.addMessage(javaVersionProblem);
          return null;
        }
        catch (RenderingException e) {
          String message = e.getPresentableMessage();
          message = message != null ? message : AndroidBundle.message("android.layout.preview.default.error.message");
          myLogger.addMessage(
            RenderProblem.createPlain(
              ERROR, message, module.getProject(), myLogger.getLinkManager(), e, module.getEnvironment().getRunnableFixFactory()));
          return null;
        }

        try {
          RenderTask task =
            new RenderTask(myContext, StudioModuleClassLoaderManager.get(), myLogger, layoutLib,
                           myCredential, StudioCrashReporter.getInstance(), myImagePool,
                           myParserFactory, isSecurityManagerEnabled, myQuality, stackTraceCaptureElement,
                           privateClassLoader, myAdditionalProjectTransform, myAdditionalNonProjectTransform, myOnNewModuleClassLoader,
                           classesToPreload, reportOutOfDateUserClasses, myPriority, myMinDownscalingFactor);

          if (myXmlFile != null) {
            task.setXmlFile(new PsiXmlFile(myXmlFile));
          }

          task
            .setDecorations(showDecorations)
            .setShowWithToolsVisibilityAndPosition(showWithToolsVisibilityAndPosition)
            .setEnableLayoutScanner(enableLayoutScanner);

          if (myMaxRenderWidth != -1 && myMaxRenderHeight != -1) {
            task.setMaxRenderSize(myMaxRenderWidth, myMaxRenderHeight);
          }

          if (useTransparentBackground) {
            task.setTransparentBackground();
          }

          if (myRenderingMode != null) {
            task.setRenderingMode(myRenderingMode);
          }

          if (myCustomContentHierarchyParser != null) {
            task.setCustomContentHierarchyParser(myCustomContentHierarchyParser);
          }

          return task;
        } catch (NoDeviceException e) {
          myLogger.addMessage(RenderProblem.createPlain(ERROR, "No device selected"));
          return null;
        } catch (IllegalStateException | IncorrectOperationException | AssertionError e) {
          // Ignore the exception if it was generated when the facet is being disposed (project is being closed)
          if (!module.isDisposed()) {
            throw e;
          }
        }

        return null;
      }, AppExecutorUtil.getAppExecutorService());
    }
  }
}
