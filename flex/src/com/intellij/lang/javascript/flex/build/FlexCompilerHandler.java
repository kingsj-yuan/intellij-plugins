package com.intellij.lang.javascript.flex.build;

import com.intellij.CommonBundle;
import com.intellij.ProjectTopics;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.flex.FlexCommonBundle;
import com.intellij.flex.FlexCommonUtils;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitAfterCompileTask;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitPrecompileTask;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitRunConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.FlexBuildConfigurationChangeListener;
import com.intellij.lang.javascript.flex.projectStructure.ui.ActiveBuildConfigurationWidget;
import com.intellij.lang.javascript.flex.run.FlashRunConfiguration;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.lang.javascript.flex.sdk.FlexmojosSdkType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.ModuleAdapter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.util.Alarm;
import com.intellij.util.Function;
import com.intellij.util.containers.BidirectionalMap;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.text.StringTokenizer;
import gnu.trove.THashMap;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;

/**
 * @author Maxim.Mossienko
 *         Date: Jul 26, 2008
 *         Time: 3:55:46 PM
 */
public class FlexCompilerHandler extends AbstractProjectComponent {

  private static final Logger LOG = Logger.getInstance(FlexCompilerHandler.class.getName());

  private final FlexCompilerDependenciesCache myCompilerDependenciesCache;
  private BuiltInFlexCompilerHandler myBuiltInFlexCompilerHandler;
  private final TObjectIntHashMap<String> commandToIdMap = new TObjectIntHashMap<>();
  private final Map<FlexBuildConfiguration.Type, ModuleOrFacetCompileCache> myCompileCache =
    new EnumMap<>(FlexBuildConfiguration.Type.class);
  @NonNls private static final String FCSH_ASSIGNED_MARKER = "fcsh: Assigned ";
  private boolean mySavingConfigOurselves;
  private boolean myRequestedQuit;

  public static Key<FlexBuildConfiguration> OVERRIDE_BUILD_CONFIG = Key.create("OVERRIDE_FLEX_BUILD_CONFIG");
  private ActiveBuildConfigurationWidget myWidget;

  private String myLastCompilationMessages;

  public String getLastCompilationMessages() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    return myLastCompilationMessages;
  }

  public void setLastCompilationMessages(final String lastCompilationMessages) {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myLastCompilationMessages = lastCompilationMessages;
  }

  private static class ModuleOrFacetCompileCache {
    public final THashMap<Object, String> moduleOrFacetToCommand = new THashMap<>();
    public final BidirectionalMap<Object, VirtualFile> moduleOrFacetToAutoGeneratedConfig = new BidirectionalMap<>();
    public final THashMap<VirtualFile, Long> configFileToTimestamp = new THashMap<>();
  }

  public FlexCompilerHandler(final Project project) {
    super(project);

    MessageBusConnection connection = project.getMessageBus().connect(project);

    connection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(ModuleRootEvent event) {
        quitCompilerShell();
      }
    });

    connection.subscribe(ProjectTopics.MODULES, new ModuleAdapter() {
      @Override
      public void modulesRenamed(@NotNull Project project, @NotNull List<Module> modules, @NotNull Function<Module, String> oldNameProvider) {
        for (RunnerAndConfigurationSettings settings : RunManagerEx.getInstanceEx(project).getSortedConfigurations()) {
          RunConfiguration runConfiguration = settings.getConfiguration();
          if (runConfiguration instanceof FlashRunConfiguration) {
            ((FlashRunConfiguration)runConfiguration).getRunnerParameters().handleModulesRename(modules, oldNameProvider);
          }
          else if (runConfiguration instanceof FlexUnitRunConfiguration) {
            ((FlexUnitRunConfiguration)runConfiguration).getRunnerParameters().handleModulesRename(modules, oldNameProvider);
          }
        }
      }
    });

    connection.subscribe(FlexBuildConfigurationChangeListener.TOPIC, new FlexBuildConfigurationChangeListener() {
      @Override
      public void buildConfigurationsRenamed(final Map<Pair<String, String>, String> renames) {
        for (RunnerAndConfigurationSettings settings : RunManagerEx.getInstanceEx(project).getSortedConfigurations()) {
          RunConfiguration runConfiguration = settings.getConfiguration();
          if (runConfiguration instanceof FlashRunConfiguration) {
            ((FlashRunConfiguration)runConfiguration).getRunnerParameters().handleBuildConfigurationsRename(renames);
          }
          else if (runConfiguration instanceof FlexUnitRunConfiguration) {
            ((FlexUnitRunConfiguration)runConfiguration).getRunnerParameters().handleBuildConfigurationsRename(renames);
          }
        }
      }
    });

    myCompilerDependenciesCache = new FlexCompilerDependenciesCache(project);

    final MyVirtualFileAdapter myFileListener = new MyVirtualFileAdapter();
    LocalFileSystem.getInstance().addVirtualFileListener(myFileListener);
    Disposer.register(project, new Disposable() {
      public void dispose() {
        LocalFileSystem.getInstance().removeVirtualFileListener(myFileListener);
      }
    });
    myReadErrStreamAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD,project);
  }

  public FlexCompilerDependenciesCache getCompilerDependenciesCache() {
    return myCompilerDependenciesCache;
  }

  public BuiltInFlexCompilerHandler getBuiltInFlexCompilerHandler() {
    if (myBuiltInFlexCompilerHandler == null) {
      myBuiltInFlexCompilerHandler = new BuiltInFlexCompilerHandler(myProject);
    }
    return myBuiltInFlexCompilerHandler;
  }

  private ModuleOrFacetCompileCache getCache(FlexBuildConfiguration.Type type) {
    ModuleOrFacetCompileCache cache = myCompileCache.get(type);
    if (cache == null) {
      cache = new ModuleOrFacetCompileCache();
      myCompileCache.put(type, cache);
    }
    return cache;
  }

  @NotNull
  public String getComponentName() {
    return "FlexCompilerHandler";
  }

  public static FlexCompilerHandler getInstance(Project project) {
    return project.getComponent(FlexCompilerHandler.class);
  }

  public void projectOpened() {
    CompilerManager compilerManager = CompilerManager.getInstance(myProject);
    if (compilerManager != null) {
      compilerManager.addBeforeTask(new ValidateFlashConfigurationsPrecompileTask());
      compilerManager.addBeforeTask(new FlexUnitPrecompileTask(myProject));
      compilerManager.addAfterTask(new FlexUnitAfterCompileTask());

      compilerManager.setValidationEnabled(FlexModuleType.getInstance(), false);
    }

    myWidget = new ActiveBuildConfigurationWidget(myProject);
  }

  public void projectClosed() {
    if (myBuiltInFlexCompilerHandler != null) {
      myBuiltInFlexCompilerHandler.stopCompilerProcess();
    }
    quitCompilerShell();
    myCompilerDependenciesCache.clear();
    FlexCommonUtils.deleteTempFlexConfigFiles(myProject.getName());
    FlexCompilationUtils.deleteUnzippedANEFiles();
    myWidget.destroy();
  }

  public void quitCompilerShell() {
    doQuit();
    clearFcshRelatedCache();
  }

  private void doQuit() {
    if (!processIsAlive()) return;
    myRequestedQuit = true;

    try {
      sendCommand("quit", new CompilerMessagesBuffer(null, false)); // ignoring input/output
    } catch (IOException ex) {
      // process exits
    }
  }

  private void clearFcshRelatedCache() {
    for (ModuleOrFacetCompileCache compileCache : myCompileCache.values()) {
      compileCache.moduleOrFacetToAutoGeneratedConfig.clear();
      compileCache.moduleOrFacetToCommand.clear();
      compileCache.configFileToTimestamp.clear();
    }

    commandToIdMap.clear();
  }

  enum Result {
    OK, TARGET_NOT_FOUND, NEED_TO_REPEAT_COMMAND, OUT_OF_MEMORY
  }

  private Process process;
  private OutputStream is;
  private InputStreamReader out;
  private LineNumberReader err;
  private final char[] buf = new char[8192];

  public void compileFlexModuleOrAllFlexFacets(final Module module, final CompileContext context) throws IOException {
    final FlexBuildConfiguration overriddenConfig = context.getUserData(OVERRIDE_BUILD_CONFIG);

    if (overriddenConfig != null && module == null /*overriddenConfig.getModule()*/) {
      final Pair<Boolean, String> validationResultWithMessage = Pair.create(true, null);
        //FlexCompiler.validateConfiguration(overriddenConfig, module, FlexBundle.message("module.name", module.getName()), false);
      if (!validationResultWithMessage.first) {
        if (validationResultWithMessage.second != null) {
          context.addMessage(CompilerMessageCategory.ERROR, validationResultWithMessage.second, null, -1, -1);
        }
        return;
      }
      compileModuleOrFacet(module, context, overriddenConfig, false);
    }
    else {
      final boolean nothingChangedSincePreviousCompilation = false; //myCompilerDependenciesCache.isNothingChangedSincePreviousCompilation(module);

      if (ModuleType.get(module) instanceof FlexModuleType) {
        final Pair<Boolean, List<VirtualFile>> compilationResult =
          compileModuleOrFacet(module, context, null /*FlexBuildConfiguration.getInstance(module)*/, nothingChangedSincePreviousCompilation);
        if (compilationResult.first && !compilationResult.second.isEmpty()) {
          //myCompilerDependenciesCache.cacheBC(context, module, compilationResult.second);
        }
      }
      else {
        boolean wasFailure = false;
        Collection<List<VirtualFile>> allConfigFiles = new ArrayList<>();

        //for (final FlexFacet facet : FacetManager.getInstance(module).getFacetsByType(FlexFacet.ID)) {
        //  final Pair<Boolean, List<VirtualFile>> compilationResult =
        //    compileModuleOrFacet(module, facet, context, null /*FlexBuildConfiguration.getInstance(facet)*/, nothingChangedSincePreviousCompilation);
        //  if (!compilationResult.first) {
        //    wasFailure = true;
        //  }
        //  if (!compilationResult.second.isEmpty()) {
        //    allConfigFiles.add(compilationResult.second);
        //  }
        //}

        if (!wasFailure && !allConfigFiles.isEmpty()) {
          //myCompilerDependenciesCache.cacheBC(context, module, allConfigFiles);
        }
      }
    }
  }

  /**
   * @return first is false if myCompilerDependenciesCache should not be cached;
   * second is a list of config files, may be empty if compilation skipped
   */
  private Pair<Boolean, List<VirtualFile>> compileModuleOrFacet(final Module module,
                                    //@Nullable final FlexFacet flexFacet,
                                    final CompileContext context,
                                    @NotNull final FlexBuildConfiguration config,
                                    final boolean nothingChangedSincePreviousCompilation) throws IOException {
    if (context.getProgressIndicator().isCanceled()) {
      return Pair.create(false, Collections.<VirtualFile>emptyList());
    }

    if (!config.DO_BUILD) {
      return Pair.create(true, Collections.<VirtualFile>emptyList());
    }

    if (context.isMake() && nothingChangedSincePreviousCompilation) {
      context.addMessage(CompilerMessageCategory.STATISTICS,
                         FlexBundle.message("compilation.skipped.because.nothing.changed.in", module.getName()), null, -1, -1);
      return Pair.create(true, Collections.<VirtualFile>emptyList());
    }

    context.getProgressIndicator().setText(FlexBundle.message("compiling.module", module.getName()));

    final Object moduleOrFacet = module;
    final ModuleOrFacetCompileCache compileCache = getCache(config.getType());

    if (!context.isMake()) {
      dropIncrementalCompilation(moduleOrFacet, compileCache);
    }

    final Sdk flexSdk = FlexUtils.getSdkForActiveBC(module);
    assert flexSdk != null; // checked in FlexCompiler.validateConfiguration()

    final List<VirtualFile> configFiles = Collections.emptyList();  //getConfigFiles(config, module, flexFacet);

    if (updateTimestamps(configFiles, compileCache, moduleOrFacet)) {
      // force non-incremental compilation because fcsh sometimes doesn't detect some changes in custom compiler config file
      dropIncrementalCompilation(moduleOrFacet, compileCache);
    }

    launchFcshIfNeeded(context, flexSdk);

    boolean compilationSuccessful = true;

    for (final String _cssFilePath : config.CSS_FILES_LIST) {
      /*
      final String cssFilePath = FileUtil.toSystemIndependentName(_cssFilePath);
      final FlexBuildConfiguration cssConfig = FlexCompilationUtils.createCssConfig(config, cssFilePath);
      final List<VirtualFile> cssConfigFiles = getConfigFiles(cssConfig, module, flexFacet, cssFilePath);
      final String cssCommand = buildCommand(cssConfigFiles, cssConfig, flexSdk);

      FlexCompilationUtils.ensureOutputFileWritable(myProject, cssConfig.getOutputFileFullPath());
      compilationSuccessful &= sendCompilationCommand(context, flexSdk, cssCommand);

      // no need in incrementality for css files compilation, it's better to release a piece of fcsh heap
      final int commandIndex = commandToIdMap.get(cssCommand);
      if (commandIndex > 0) {
        sendCommand("clear " + commandIndex, new CompilerMessagesBuffer(null, false));
      }
      */
    }

    final String command = buildCommand(configFiles, config, flexSdk);
    final String s = compileCache.moduleOrFacetToCommand.get(moduleOrFacet);
    final int previousCommandId = commandToIdMap.get(command);

    if (!config.USE_CUSTOM_CONFIG_FILE) {
      FlexCompilationUtils.ensureOutputFileWritable(myProject, "config.getOutputFileFullPath()");
    }

    if (s == null || !s.equals(command)) {
      if (s != null) {
        if (previousCommandId > 0) {
          sendCommand("clear " + previousCommandId, new CompilerMessagesBuffer(context, false));
          commandToIdMap.remove(command);
        }
      }

      compileCache.moduleOrFacetToCommand.put(moduleOrFacet, command);

      compilationSuccessful &= sendCompilationCommand(context, flexSdk, command);
    }
    else {
      compilationSuccessful &= sendCompilationCommand(context, flexSdk, previousCommandId > 0 ? "compile " + previousCommandId : null, command);
    }

    if (config.getType() == FlexBuildConfiguration.Type.Default) {
      if (!compilationSuccessful) {
        //myCompilerDependenciesCache.markModuleDirty(module);
      }
    }

    if (!compilationSuccessful) {
      // force non-incremental compilation next time (bug in Flex incremental compiler: it doesn't recompile after failed compilation in some curcumstances)
      dropIncrementalCompilation(moduleOrFacet, compileCache);
    }

    return Pair.create(compilationSuccessful, configFiles);
  }

  private void dropIncrementalCompilation(final Object moduleOrFacet,
                                          final ModuleOrFacetCompileCache compileCache) throws IOException {
    final String removedCommand = compileCache.moduleOrFacetToCommand.remove(moduleOrFacet);
    final int commandId = commandToIdMap.remove(removedCommand);
    if (commandId > 0) {
      sendCommand("clear " + commandId, new CompilerMessagesBuffer(null, false));
    }
  }

  /**
   * @return true if some of timestamps exists and is out of date
   */
  private static boolean updateTimestamps(List<VirtualFile> configFiles, ModuleOrFacetCompileCache compileCache, Object moduleOrFacet) {
    boolean result = false;
    for (VirtualFile configFile : configFiles) {
      final long currentTimestamp = configFile.getModificationCount();

      final Long previousTimestamp = compileCache.configFileToTimestamp.get(configFile);
      if (previousTimestamp == null || !previousTimestamp.equals(currentTimestamp)) {
        if (previousTimestamp != null)  {
          result = true;
        }
        compileCache.configFileToTimestamp.put(configFile, currentTimestamp);
      }
    }
    return result;
  }

  private boolean sendCompilationCommand(final CompileContext context,
                                      final Sdk flexSdk,
                                      final String fullCommand) throws IOException {
    return sendCompilationCommand(context, flexSdk, null, fullCommand, true);
  }

  private boolean sendCompilationCommand(final CompileContext context,
                                      final Sdk flexSdk,
                                      @Nullable final String incrementalCommand,
                                      final String fullCommand) throws IOException {
    return sendCompilationCommand(context, flexSdk, incrementalCommand, fullCommand, true);
  }

  /**
   * @return true if compilation completes successfully
   */
  private boolean sendCompilationCommand(final CompileContext context,
                                      final Sdk flexSdk,
                                      @Nullable final String incrementalCommand,
                                      final String fullCommand,
                                      final boolean relaunchIfOutOfMemory) throws IOException {
    final CompilerMessagesBuffer messagesBuffer = new CompilerMessagesBuffer(context, true);
    Result result = sendCommand(incrementalCommand != null ? incrementalCommand : fullCommand, messagesBuffer);
    if (result != Result.OUT_OF_MEMORY && messagesBuffer.containsOutOfMemoryError()) {
      result = Result.OUT_OF_MEMORY;
    }

    switch (result) {
        case OK:
          messagesBuffer.flush();
          return !messagesBuffer.containsErrors();
        case TARGET_NOT_FOUND:
          messagesBuffer.flush();
          commandToIdMap.remove(fullCommand);
          return sendCompilationCommand(context, flexSdk, null, fullCommand, true);
        case NEED_TO_REPEAT_COMMAND:
          messagesBuffer.flush();
          consumeOutput(null, messagesBuffer);
          return sendCompilationCommand(context, flexSdk, null, fullCommand, true);
        case OUT_OF_MEMORY:
          quitCompilerShell();
          messagesBuffer.removeErrorsAndStackTrace();
          messagesBuffer.flush();
          addOutOfMemoryMessage(context, relaunchIfOutOfMemory);
          if (relaunchIfOutOfMemory) {
            launchFcshIfNeeded(context, flexSdk);
            return sendCompilationCommand(context, flexSdk, null, fullCommand, false);
          }
          else {
            return false;
          }
      }

    return false;
  }

  private static void addOutOfMemoryMessage(final CompileContext context, final boolean willBeRestarted) {
    if (willBeRestarted) {
      context.addMessage(CompilerMessageCategory.WARNING,
                         FlexBundle.message("fcsh.out.of.memory.and.restarted", CommonBundle.settingsActionPath()), null, -1, -1);
    }
    else {
      context.addMessage(CompilerMessageCategory.ERROR,
                         FlexCommonBundle.message("increase.flex.compiler.heap", CommonBundle.settingsActionPath()), null, -1, -1);
    }
  }

  private void launchFcshIfNeeded(final CompileContext context, final Sdk flexSdk) throws IOException {
    if (!processIsAlive() || myRequestedQuit) {
      final StringBuilder classpath = new StringBuilder();

      classpath.append(FlexCommonUtils.getPathToBundledJar("idea-flex-compiler-fix.jar"));
      classpath.append(File.pathSeparatorChar);
      classpath.append(FlexCommonUtils.getPathToBundledJar("idea-fcsh-fix.jar"));

      if (!(flexSdk.getSdkType() instanceof FlexmojosSdkType)) {
        classpath.append(File.pathSeparator).append(FileUtil.toSystemDependentName(flexSdk.getHomePath() + "/lib/fcsh.jar"));
      }
      final List<String> cmdLineParams =
        FlexSdkUtils.getCommandLineForSdkTool(myProject, flexSdk, classpath.toString(), "com.intellij.flex.FcshLauncher", null);
      context.addMessage(CompilerMessageCategory.INFORMATION, StringUtil.join(cmdLineParams, " "), null, -1, -1);

      final ProcessBuilder builder = new ProcessBuilder(cmdLineParams);

      builder.directory(new File(FlexUtils.getFlexCompilerWorkDirPath(myProject, flexSdk)));

      process = builder.start();
      is = process.getOutputStream();
      out = new InputStreamReader(process.getInputStream());
      err = new LineNumberReader(new InputStreamReader(process.getErrorStream()));
      consumeOutput(null, new CompilerMessagesBuffer(context, false));

      clearFcshRelatedCache();
      myRequestedQuit = false;
    }
  }

  private boolean processIsAlive() {
    boolean processIsAlive = process != null;
    if (processIsAlive) {
      try {
        process.exitValue();
        processIsAlive = false;
      } catch (IllegalThreadStateException ignored) {}
    }
    return processIsAlive;
  }

  @NonNls
  @NotNull
  private String buildCommand(final List<VirtualFile> configFiles, final FlexBuildConfiguration config, final Sdk flexSdk) {  // todo change to FlexCompilationUtils.buildCommand()
    final StringBuilder configsParam = new StringBuilder();
    final String workDirPathWithSlash = FlexUtils.getFlexCompilerWorkDirPath(myProject, flexSdk) + "/";

    for (final VirtualFile configFile : configFiles) {
      String relativePathToConfig = configFile.getPath();
      if (configFile.getPath().startsWith(workDirPathWithSlash)) {
        relativePathToConfig = configFile.getPath().substring(workDirPathWithSlash.length());
      }
      if (relativePathToConfig.indexOf(' ') >= 0) {
        relativePathToConfig = "\"" + relativePathToConfig + "\"";
      }
      if (configsParam.length() > 0) {
        configsParam.append(",");
      }
      boolean useSdkConfig = config.USE_DEFAULT_SDK_CONFIG_FILE && !(flexSdk.getSdkType() instanceof FlexmojosSdkType);
      configsParam.append(" -load-config").append(useSdkConfig ? "+=" : "=").append(relativePathToConfig);
    }

    @NonNls String s = config.OUTPUT_TYPE.equals(FlexBuildConfiguration.APPLICATION) ? "mxmlc" : "compc";

    /*
    if (flexSdk.getSdkType() instanceof AirSdkType) {
      s += " +configname=air";
    }
    else if (flexSdk.getSdkType() instanceof AirMobileSdkType) {
      s += " +configname=airmobile";
    }
    */

    s += configsParam;

    if(config.ADDITIONAL_COMPILER_OPTIONS != null && config.ADDITIONAL_COMPILER_OPTIONS.length() > 0) {
      s += " " + FlexUtils.replacePathMacros(config.ADDITIONAL_COMPILER_OPTIONS, null /*config.getModule()*/, flexSdk.getHomePath());
    }

    return s;
  }

  @Nullable
  public static VirtualFile getRealFile(final VirtualFile libFile) {
    if (libFile.getFileSystem() instanceof JarFileSystem) {
      return JarFileSystem.getInstance().getVirtualFileForJar(libFile);
    }
    return libFile;
  }

  private Result sendCommand(@NonNls final String command, final CompilerMessagesBuffer messagesBuffer) throws IOException {
    trace(TraceType.IN, command);
    messagesBuffer.addMessage(CompilerMessageCategory.INFORMATION, command, null, -1, -1);
    if (processIsAlive()) {
      is.write((command + "\n").getBytes());
      is.flush();

      final Runnable runnable = new Runnable() {
        public void run() {
          if (myCancelledReadErrStream) return;
          scanErrorStream(messagesBuffer);

          myReadErrStreamAlarm.addRequest(this, 100);
        }

      };

      if ("quit".equals(command)) return Result.OK;
      myCancelledReadErrStream = false;
      myReadErrStreamAlarm.addRequest(runnable, 100);
    }
    return consumeOutput(command, messagesBuffer);
  }

  private volatile boolean myCancelledReadErrStream;
  private final Alarm myReadErrStreamAlarm;

  private void scanErrorStream(final CompilerMessagesBuffer messagesBuffer) {
    try {
      int available;
      while ((available = process.getErrorStream().available()) > 2 || err.ready()) { // 2 is \r\n, prevent lock of read line to appear
        @NonNls final String errLine = err.readLine();
        trace(TraceType.ERR, errLine);
        if (errLine == null || errLine.length() == 0) continue;

        ApplicationManager.getApplication().runReadAction(() -> dispatchError(errLine, messagesBuffer));
      }
    }
    catch (IOException ex) {
      LOG.error(ex);
    }
  }

  private static void dispatchError(final String errLine, final CompilerMessagesBuffer messagesBuffer) {
    final Matcher matcher = FlexCommonUtils.ERROR_PATTERN.matcher(errLine);

    if (matcher.matches()) {
      final String file = matcher.group(1);
      final String additionalInfo = matcher.group(2);
      final String line = matcher.group(3);
      final String column = matcher.group(4);
      final String type = matcher.group(5);
      final String message = matcher.group(6);

      final CompilerMessageCategory messageCategory =
          "Warning".equals(type) ? CompilerMessageCategory.WARNING : CompilerMessageCategory.ERROR;
      final VirtualFile relativeFile = VfsUtil.findRelativeFile(file, null);

      final StringBuilder fullMessage = new StringBuilder();
      if (relativeFile == null) fullMessage.append(file).append(": ");
      if (additionalInfo != null) fullMessage.append(additionalInfo).append(' ');
      fullMessage.append(message);

      messagesBuffer.addMessage(messageCategory, fullMessage.toString(), relativeFile != null ? relativeFile.getUrl() : null,
                                line != null ? Integer.parseInt(line) : 0, column != null ? Integer.parseInt(column) : 0);
    }
    else if (isErrorMessage(errLine)) {
      final String errorPrefix = "Error: ";
      final String errorText = errLine.startsWith(errorPrefix) ? errLine.substring(errorPrefix.length()) : errLine;
      messagesBuffer.addMessage(CompilerMessageCategory.ERROR, errorText, null, -1, -1);
    }
    else {
      messagesBuffer.addMessage(CompilerMessageCategory.INFORMATION, errLine, null, -1, -1);
    }
  }

  private static boolean isErrorMessage(final String errLine) {
    return errLine.startsWith("Error: ") || errLine.startsWith("Exception in thread \"main\" ");
  }

  private Result consumeOutput(final String command, @Nullable final CompilerMessagesBuffer messagesBuffer) throws IOException {
    Result result = Result.OK;
    String lastRead = "";

    out:
    while(true) {
      int read = out.read(buf);
      if (read == -1) {
        read = err.read(buf);
        if (read > 0) {
          messagesBuffer.addMessage(CompilerMessageCategory.ERROR, new String(buf, 0, read), null, -1, -1);
        }
        break;
      }


      @NonNls String output = lastRead + new String(buf, 0, read);
      trace(TraceType.OUT, output);
      StringTokenizer tokenizer = new StringTokenizer(output, "\r\n");

      while(tokenizer.hasMoreElements()) {
        @NonNls String s = tokenizer.nextElement().trim();
        if (s.length() == 0) continue;

        if (s.startsWith("(fcsh)")) {
          if (s.indexOf("need to repeat command") != -1) { // special marker from idea-fcsh-fix;
            // if fcsh-idea-fix fails for any reason it launches standard fcsh, so we need to repeat command
            result = Result.NEED_TO_REPEAT_COMMAND;
          } else if (s.indexOf("out of memory") != -1) {
            result = Result.OUT_OF_MEMORY;
          }
          myCancelledReadErrStream = true;
          myReadErrStreamAlarm.cancelAllRequests();
          scanErrorStream(messagesBuffer);
          break out;
        }
        if (s.startsWith(FCSH_ASSIGNED_MARKER) && command != null) {
          int id = Integer.parseInt(s.substring(FCSH_ASSIGNED_MARKER.length(), s.indexOf(' ', FCSH_ASSIGNED_MARKER.length())));
          commandToIdMap.put(command, id);
          continue;
        } else if(s.startsWith("fcsh: Target") && s.indexOf("not found") != -1) {
          result = Result.TARGET_NOT_FOUND;
          continue;
        }

        if (!tokenizer.hasMoreElements() && tokenizer.getCurrentPosition() == output.length()) {
          lastRead = s + "\n";
          break;
        }
        messagesBuffer.addMessage(CompilerMessageCategory.INFORMATION, s, null, -1, -1);
      }
    }

    return result;
  }

  enum TraceType {
    IN, OUT, ERR
  }

  private static void trace(TraceType type, String message) {
    System.out.println(type.toString() + ":" + message);
  }

  private class MyVirtualFileAdapter extends VirtualFileAdapter {
    @Override
      public void propertyChanged(@NotNull final VirtualFilePropertyEvent event) {
      handleVirtualFileEvent(event.getFile());
    }

    @Override
      public void contentsChanged(@NotNull final VirtualFileEvent event) {
      handleVirtualFileEvent(event.getFile(), true);
    }

    @Override
      public void fileCreated(@NotNull final VirtualFileEvent event) {
      handleVirtualFileEvent(event.getFile());
    }

    @Override
      public void fileDeleted(@NotNull final VirtualFileEvent event) {
      handleVirtualFileEvent(event.getFile());
    }

    @Override
      public void fileMoved(@NotNull final VirtualFileMoveEvent event) {
      handleVirtualFileEvent(event.getFile());
    }

    @Override
      public void fileCopied(@NotNull final VirtualFileCopyEvent event) {
      handleVirtualFileEvent(event.getFile());
    }

    private void handleVirtualFileEvent(final VirtualFile file) {
      handleVirtualFileEvent(file, false);
    }

    private void handleVirtualFileEvent(final VirtualFile file, boolean contentsChanged) {
      if (file == null) return;
      myCompilerDependenciesCache.markModuleDirtyIfInSourceRoot(file);
      //clearAutoGeneratedConfigsIfNeeded(file, contentsChanged);
    }
  }
}
