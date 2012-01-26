package com.intellij.lang.javascript.flex;

import com.intellij.application.options.PathMacrosImpl;
import com.intellij.execution.configurations.CommandLineTokenizer;
import com.intellij.facet.FacetManager;
import com.intellij.facet.FacetType;
import com.intellij.facet.ModifiableFacetModel;
import com.intellij.lang.javascript.flex.build.FlexBuildConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.CompilerOptionInfo;
import com.intellij.lang.javascript.flex.projectStructure.FlexProjectLevelCompilerOptionsHolder;
import com.intellij.lang.javascript.flex.projectStructure.model.FlexBuildConfigurationManager;
import com.intellij.lang.javascript.flex.projectStructure.model.FlexIdeBuildConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.SdkEntry;
import com.intellij.lang.javascript.flex.projectStructure.model.TargetPlatform;
import com.intellij.lang.javascript.flex.run.FlexBaseRunner;
import com.intellij.lang.javascript.flex.sdk.AirMobileSdkType;
import com.intellij.lang.javascript.flex.sdk.AirSdkType;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.lang.javascript.psi.JSFile;
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathMacros;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.ui.configuration.ModuleEditor;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ModuleStructureConfigurable;
import com.intellij.openapi.roots.ui.configuration.projectRoot.StructureConfigurableContext;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PathUtil;
import com.intellij.util.Processor;
import com.intellij.util.SystemProperties;
import com.intellij.util.xml.NanoXmlUtil;
import com.intellij.util.xml.XmlFileHeader;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Maxim.Mossienko
 */
public class FlexUtils {

  @NonNls private static final Pattern INFO_PLIST_EXECUTABLE_PATTERN =
    Pattern.compile("<key>CFBundleExecutable</key>(?:(?:\\s*)(?:<!--(?:.*)-->(?:\\s*))*)<string>(.*)</string>");

  private FlexUtils() {
  }

  public static FileChooserDescriptor createFileChooserDescriptor(final String allowedExtension) {
    return new FileChooserDescriptor(true, false, false, false, false, false) {
      public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        return super.isFileVisible(file, showHiddenFiles) && (file.isDirectory() || allowedExtension.equalsIgnoreCase(
          file.getExtension()));
      }
    };
  }

  public static FlexFacet addFlexFacet(final Module module, final @Nullable Sdk flexSdk, final ModifiableRootModel modifiableRootModel) {
    final ModifiableFacetModel facetModel = FacetManager.getInstance(module).createModifiableModel();
    final FacetType<FlexFacet, FlexFacetConfiguration> facetType = FlexFacetType.getInstance();
    final FlexFacet flexFacet = facetType.createFacet(module, facetType.getDefaultFacetName(), facetType.createDefaultConfiguration(),
                                                      null);

    facetModel.addFacet(flexFacet);

    if (flexSdk != null) {
      flexFacet.getConfiguration().setFlexSdk(flexSdk, modifiableRootModel);
    }
    facetModel.commit();
    return flexFacet;
  }

  public static void setupFlexConfigFileAndSampleCode(final Module module,
                                                      final FlexBuildConfiguration config,
                                                      final Sdk sdk,
                                                      final @NonNls @Nullable String customFlexCompilerConfigFileName,
                                                      final VirtualFile flexConfigFileDir,
                                                      final @NonNls @Nullable String sampleFileName,
                                                      final VirtualFile sourceRoot) throws IOException {
    final Project project = module.getProject();
    if (customFlexCompilerConfigFileName != null) {
      final String outputFileName = config.getOutputFileFullPath();
      final VirtualFile file = addFileWithContent(customFlexCompilerConfigFileName,
                                                  "<flex-config xmlns=\"http://www.adobe.com/2006/flex-config\">\n" +
                                                  "  <compiler>\n" +
                                                  "    <debug>true</debug>\n" +
                                                  "    <source-path><path-element>" +
                                                  sourceRoot.getPath() +
                                                  "</path-element></source-path>\n" +
                                                  "  </compiler>\n" +
                                                  "  <file-specs>\n" +
                                                  (sampleFileName != null ? ("    <path-element>" +
                                                                             sourceRoot.getPath() + "/" + sampleFileName +
                                                                             "</path-element>\n")
                                                                          : "") +
                                                  "  </file-specs>\n" +
                                                  "  <output>" +
                                                  outputFileName +
                                                  "</output>\n" +
                                                  "</flex-config>", flexConfigFileDir);
      config.DO_BUILD = true;
      config.USE_CUSTOM_CONFIG_FILE = true;
      config.CUSTOM_CONFIG_FILE = file.getPath();
    }

    if (sdk != null && sampleFileName != null) {
      assert sampleFileName.endsWith(".mxml") || sampleFileName.endsWith(".as");
      config.MAIN_CLASS = FileUtil.getNameWithoutExtension(sampleFileName);

      final TargetPlatform platform = sdk.getSdkType() instanceof AirMobileSdkType
                                      ? TargetPlatform.Mobile
                                      : sdk.getSdkType() instanceof AirSdkType ? TargetPlatform.Desktop : TargetPlatform.Web;
      final boolean flex4 = FlexSdkUtils.isFlex4Sdk(sdk);

      createSampleApp(project, sourceRoot, sampleFileName, platform, flex4);
    }
  }

  public static void createSampleApp(final Project project,
                                     final VirtualFile sourceRoot,
                                     final String sampleFileName,
                                     final TargetPlatform platform,
                                     final boolean isFlex4) throws IOException {
    final String sampleClassName = FileUtil.getNameWithoutExtension(sampleFileName);
    final String extension = FileUtil.getExtension(sampleFileName);
    final String sampleTechnology = platform == TargetPlatform.Mobile ? "AIRMobile" : platform == TargetPlatform.Desktop ? "AIR" : "Flex";

    String suffix = "";
    if ("mxml".equalsIgnoreCase(extension)) {
      if (platform == TargetPlatform.Mobile) {
        suffix = "_ViewNavigator";
      }
      else if (isFlex4) {
        suffix = "_Spark";
      }
    }

    final String helloWorldTemplate = "HelloWorld_" + sampleTechnology + suffix + "." + extension + ".ft";
    final InputStream stream = FlexUtils.class.getResourceAsStream(helloWorldTemplate);
    assert stream != null : helloWorldTemplate;
    final String sampleFileContent = FileUtil.loadTextAndClose(new InputStreamReader(stream)).replace("${class.name}", sampleClassName);
    final VirtualFile sampleApplicationFile = addFileWithContent(sampleFileName, sampleFileContent, sourceRoot);
    if (sampleApplicationFile != null) {
      final Runnable runnable = new Runnable() {
        public void run() {
          FileEditorManager.getInstance(project).openFile(sampleApplicationFile, true);
        }
      };

      if (project.isInitialized()) {
        runnable.run();
      }
      else {
        StartupManager.getInstance(project).registerPostStartupActivity(runnable);
      }
    }
  }

  public static VirtualFile addFileWithContent(final @NonNls String fileName, final @NonNls String fileContent, final VirtualFile dir)
    throws IOException {
    VirtualFile data = dir.findChild(fileName);
    if (data == null) {
      data = dir.createChildData(FlexUtils.class, fileName);
    }
    else if (SystemInfo.isWindows) {
      data.rename(FlexUtils.class, fileName); // ensure the right case
    }
    VfsUtil.saveText(data, fileContent);
    return data;
  }

  public static String getPresentableName(final @NotNull Module module, final @Nullable FlexFacet flexFacet) {
    return flexFacet == null
           ? FlexBundle.message("module.name", module.getName())
           : FlexBundle.message("facet.name", flexFacet.getName(), module.getName());
  }

  @Nullable
  public static Sdk getFlexSdkForFlexModuleOrItsFlexFacets(final @NotNull Module module) {
    return ModuleType.get(module) instanceof FlexModuleType
           ? createFlexSdkWrapper(FlexBuildConfigurationManager.getInstance(module).getActiveConfiguration())
           : null;
  }

  @Nullable
  public static Sdk createFlexSdkWrapper(final FlexIdeBuildConfiguration bc) {
    final SdkEntry sdkEntry = bc.getDependencies().getSdkEntry();
    if (sdkEntry != null) {
      return sdkEntry.findSdk();
    }

    return null;
  }

  public static boolean isXmlExtension(String extension) {
    return "xml".equalsIgnoreCase(extension);
  }

  public static boolean isSwfExtension(String extension) {
    return "swf".equalsIgnoreCase(extension);
  }

  public static boolean isHtmlExtension(String extension) {
    return "htm".equalsIgnoreCase(extension) || "html".equalsIgnoreCase(extension) || "xhtml".equalsIgnoreCase(extension);
  }

  public static String[] suggestHtmlAndSwfFilesToLaunch(final Module module) {
    final List<String> fileNames = new ArrayList<String>();
    for (final FlexBuildConfiguration config : FlexBuildConfiguration.getConfigForFlexModuleOrItsFlexFacets(module)) {
      if (config.DO_BUILD &&
          FlexBuildConfiguration.APPLICATION.equals(config.OUTPUT_TYPE) &&
          !config.USE_CUSTOM_CONFIG_FILE &&
          config.OUTPUT_FILE_NAME.length() > 0) {
        fileNames.add(config.getOutputFileFullPath());
      }
    }

    final String outputFolderPath = VfsUtil.urlToPath(CompilerModuleExtension.getInstance(module).getCompilerOutputUrl());
    if (outputFolderPath.length() > 0) {
      for (final VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
        FlexBaseRunner.processFilesUnderRoot(sourceRoot, new Processor<VirtualFile>() {
          public boolean process(final VirtualFile virtualFile) {
            if (isHtmlExtension(virtualFile.getExtension())) {
              if (htmlFileLooksLikeSwfWrapper(virtualFile)) {
                if (virtualFile.getPath().startsWith(sourceRoot.getPath())) {
                  fileNames.add(outputFolderPath + virtualFile.getPath().substring(sourceRoot.getPath().length()));
                }
              }
            }
            return true;
          }
        });
      }
    }
    return ArrayUtil.toStringArray(fileNames);
  }

  public static boolean htmlFileLooksLikeSwfWrapper(final VirtualFile virtualFile) {
    try {
      if (VfsUtil.loadText(virtualFile).indexOf("application/x-shockwave-flash") != -1) {
        return true;
      }
    }
    catch (IOException e) {
      // ignore
    }
    return false;
  }

  public static String[] collectAirDescriptorsForProject(final Project project) {
    final List<String> result = new ArrayList<String>();
    final ContentIterator contentIterator = new ContentIterator() {
      public boolean processFile(final VirtualFile file) {
        if (isAirDescriptorFile(file)) {
          result.add(FileUtil.toSystemDependentName(file.getPath()));
        }
        return true;
      }
    };
    final ProjectRootManager projectRootManager = ProjectRootManager.getInstance(project);
    projectRootManager.getFileIndex().iterateContent(contentIterator);

    final VirtualFile projectBaseDir = project.getBaseDir();
    if (projectBaseDir != null && !ArrayUtil.contains(projectBaseDir, projectRootManager.getContentRoots())) {
      for (final VirtualFile file : projectBaseDir.getChildren()) {
        if (isAirDescriptorFile(file)) {
          result.add(FileUtil.toSystemDependentName(file.getPath()));
        }
      }
    }
    return ArrayUtil.toStringArray(result);
  }

  public static boolean isAirDescriptorFile(final VirtualFile file) {
    if (file != null && file.isValid() && !file.isDirectory() && isXmlExtension(file.getExtension())) {
      try {
        final XmlFileHeader header = NanoXmlUtil.parseHeaderWithException(file);
        final String namespace = header.getRootTagNamespace();
        return (namespace != null &&
                namespace.startsWith("http://ns.adobe.com/air/application/") &&
                "application".equals(header.getRootTagLocalName()));
      }
      catch (IOException e) {/*ignore*/}
    }
    return false;
  }

  public static boolean isAirDesktopDescriptorFile(final VirtualFile file) {
    try {
      final Map<String, List<String>> elements =
        findXMLElements(file.getInputStream(), Arrays.asList("<application><android>", "<application><iPhone>"));
      return elements.get("<application><android>").isEmpty() && elements.get("<application><iPhone>").isEmpty();
    }
    catch (IOException e) {
      return false;
    }
  }

  public static boolean isAirMobileDescriptorFile(final VirtualFile file) {
    try {
      final Map<String, List<String>> elements =
        findXMLElements(file.getInputStream(), Arrays.asList("<application><android>", "<application><iPhone>"));
      return !elements.get("<application><android>").isEmpty() || !elements.get("<application><iPhone>").isEmpty();
    }
    catch (IOException e) {
      return false;
    }
  }

  /*
  public static boolean isAirAndroidDescriptorFile(final VirtualFile file) {
    try {
      return findXMLElement(file.getInputStream(), "<application><android>") != null;
    }
    catch (IOException e) {
      return false;
    }
  }

  public static boolean isAirIPhoneDescriptorFile(final VirtualFile file) {
    try {
      return findXMLElement(file.getInputStream(), "<application><iPhone>") != null;
    }
    catch (IOException e) {
      return false;
    }
  }
  */

  /**
   * Looks through input stream containing XML document and finds all entries of XML elements listed in <code>xmlElements</code>.
   * Content of these elements is put to result map. XML namespaces are not taken into consideration.
   *
   * @param xmlInputStream input stream with xml content to parse
   * @param xmlElements    list of XML elements to look for.
   *                       Format is: <code>"&lt;root_element&gt;&lt;child_element&gt;&lt;subelement_to_look_for&gt;"</code>.
   *                       Listed XML elements SHOULD NOT contain subelements
   * @return map, keys are XML elements listed in <code>xmlElements</code>,
   *         values are all entries of respective element (may be empty list)
   */
  public static Map<String, List<String>> findXMLElements(final @NotNull InputStream xmlInputStream, final List<String> xmlElements) {
    final Map<String, List<String>> resultMap = new HashMap<String, List<String>>();
    for (final String element : xmlElements) {
      resultMap.put(element, new ArrayList<String>());
    }

    NanoXmlUtil.parse(xmlInputStream, new NanoXmlUtil.IXMLBuilderAdapter() {

      private String currentElement = "";
      private final StringBuilder currentElementContent = new StringBuilder();

      public void startElement(final String name, final String nsPrefix, final String nsURI, final String systemID, final int lineNr)
        throws Exception {
        currentElement += "<" + name + ">";
      }

      public void endElement(final String name, final String nsPrefix, final String nsURI) throws Exception {
        if (xmlElements.contains(currentElement)) {
          resultMap.get(currentElement).add(currentElementContent.toString());
          currentElementContent.delete(0, currentElementContent.length());
        }
        assert currentElement.endsWith("<" + name + ">");
        currentElement = currentElement.substring(0, currentElement.length() - (name.length() + 2));
      }

      public void addPCData(final Reader reader, final String systemID, final int lineNr) throws Exception {
        if (xmlElements.contains(currentElement)) {
          char[] chars = new char[128];
          int read;
          while ((read = reader.read(chars)) > 0) {
            currentElementContent.append(chars, 0, read);
          }
        }
      }
    });

    return resultMap;
  }

  /**
   * Looks through input stream containing XML document and finds first entry of <code>xmlElement</code>.
   * XML namespaces are not taken into consideration.
   *
   * @param xmlInputStream input stream with xml content to parse
   * @param xmlElement     XML element to look for.
   *                       Format is: <code>"&lt;root_element&gt;&lt;child_element&gt;&lt;subelement_to_look_for&gt;"</code>.
   *                       XML element SHOULD NOT contain subelements
   * @return first found value of <code>xmlElement</code> tag, or <code>null</code> if non found or any exception occurs.
   */

  @Nullable
  public static String findXMLElement(final @NotNull InputStream xmlInputStream, final String xmlElement) {
    final Ref<String> result = new Ref<String>();

    NanoXmlUtil.parse(xmlInputStream, new NanoXmlUtil.IXMLBuilderAdapter() {

      private String currentElement = "";
      private final StringBuilder xmlElementContent = new StringBuilder();

      public void startElement(final String name, final String nsPrefix, final String nsURI, final String systemID, final int lineNr)
        throws Exception {
        currentElement += "<" + name + ">";
      }

      public void endElement(final String name, final String nsPrefix, final String nsURI) throws Exception {
        if (xmlElement.equals(currentElement)) {
          result.set(xmlElementContent.toString());
          stop();
        }
        assert currentElement.endsWith("<" + name + ">");
        currentElement = currentElement.substring(0, currentElement.length() - (name.length() + 2));
      }

      public void addPCData(final Reader reader, final String systemID, final int lineNr) throws Exception {
        if (xmlElement.equals(currentElement)) {
          char[] chars = new char[128];
          int read;
          while ((read = reader.read(chars)) > 0) {
            xmlElementContent.append(chars, 0, read);
          }
        }
      }
    });

    return result.get();
  }

  @Nullable
  public static String getMacExecutable(final @NotNull String appFolderPath) {
    try {
      final String text = FileUtil.loadFile(new File(appFolderPath + "/Contents/Info.plist"));
      Matcher m = INFO_PLIST_EXECUTABLE_PATTERN.matcher(text);
      if (!m.find()) return null;
      return appFolderPath + "/Contents/MacOS/" + m.group(1);
    }
    catch (IOException e) {
      return null;
    }
  }

  /**
   * If the first item of ComboBox model is <code>null</code> or not instance of <code>clazz</code> then it will be removed from the model.
   */
  public static void removeIncorrectItemFromComboBoxIfPresent(final JComboBox comboBox, final Class clazz) {
    final int oldSize = comboBox.getModel().getSize();
    final Object firstElement = comboBox.getModel().getElementAt(0);
    if (oldSize > 0 && (firstElement == null || !clazz.isAssignableFrom(firstElement.getClass()))) {
      final Object selectedItem = comboBox.getSelectedItem();
      final Object[] newObjects = new Object[oldSize - 1];
      for (int i = 0; i < newObjects.length; i++) {
        newObjects[i] = comboBox.getModel().getElementAt(i + 1);
      }
      comboBox.setModel(new DefaultComboBoxModel(newObjects));
      comboBox.setSelectedItem(selectedItem);
    }
  }

  public static boolean isFlexModuleOrContainsFlexFacet(final @NotNull Module module) {
    return ModuleType.get(module) instanceof FlexModuleType || FacetManager.getInstance(module).getFacetByType(FlexFacet.ID) != null;
  }

  public static String getFlexCompilerWorkDirPath(final Project project, final @Nullable Sdk flexSdk) {
    final VirtualFile baseDir = project.getBaseDir();
    return FlexSdkUtils.isFlex2Sdk(flexSdk) || FlexSdkUtils.isFlex3_0Sdk(flexSdk)
           ? getTempFlexConfigsDirPath() // avoid problems with spaces in temp dir path (fcsh from Flex SDK 2 is not patched)
           : (baseDir == null ? "" : baseDir.getPath());
  }

  public static VirtualFile getFlexCompilerWorkDir(final Project project, final @Nullable Sdk flexSdk) {
    return LocalFileSystem.getInstance().findFileByPath(getFlexCompilerWorkDirPath(project, flexSdk));
  }

  public static String getTempFlexConfigsDirPath() {
    return FileUtil.toSystemIndependentName(FileUtil.getTempDirectory());
  }

  public static String getPathToMainClassFile(final FlexBuildConfiguration config) {
    if (StringUtil.isEmpty(config.MAIN_CLASS)) return "";

    if (config.getType() == FlexBuildConfiguration.Type.FlexUnit) {
      return getPathToFlexUnitTempDirectory() + "/" + config.MAIN_CLASS + ".mxml";
    }

    return getPathToMainClassFile(config.MAIN_CLASS, config.getModule());
  }

  public static String getPathToMainClassFile(final String mainClassFqn, final Module module) {
    if (StringUtil.isEmpty(mainClassFqn)) return "";

    final String s = mainClassFqn.replace('.', '/');
    final String[] classFileRelPaths = {s + ".mxml", s + ".as"};

    for (final VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
      for (final String classFileRelPath : classFileRelPaths) {
        final VirtualFile mainClassFile = VfsUtil.findRelativeFile(classFileRelPath, sourceRoot);
        if (mainClassFile != null) {
          return mainClassFile.getPath();
        }
      }
    }

    return "";
  }

  public static String getPathToFlexUnitTempDirectory() {
    return FileUtil.getTempDirectory();
  }

  @Nullable
  public static ModuleEditor getModuleEditor(final Module module, final ModuleStructureConfigurable configurable) {
    final StructureConfigurableContext context = configurable == null ? null : configurable.getContext();
    final ModulesConfigurator configurator = context == null ? null : context.getModulesConfigurator();
    return configurator == null ? null : configurator.getModuleEditor(module);
  }

  public static void removeFileLater(final @NotNull VirtualFile file) {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            try {
              if (file.exists()) {
                file.delete(this);
              }
            }
            catch (IOException e) {/*ignore*/}
          }
        });
      }
    });
  }

  public static void processMxmlTags(final XmlTag rootTag,
                                     final JSResolveUtil.JSInjectedFilesVisitor injectedFilesVisitor,
                                     Processor<XmlTag> processor) {
    String namespace = JSResolveUtil.findMxmlNamespace(rootTag);

    XmlBackedJSClassImpl.InjectedScriptsVisitor scriptsVisitor =
      new XmlBackedJSClassImpl.InjectedScriptsVisitor(rootTag, false, false, false, injectedFilesVisitor, processor, true);
    scriptsVisitor.go();

    for (XmlTag s : rootTag.findSubTags("Metadata", namespace)) {
      //JSResolveUtil.processInjectedFileForTag(s, injectedFilesVisitor);
      processor.process(s);
    }
  }

  public static void processMxmlTags(final XmlTag rootTag, final JSResolveUtil.JSInjectedFilesVisitor injectedFilesVisitor) {
    processMxmlTags(rootTag, injectedFilesVisitor,
                    new XmlBackedJSClassImpl.InjectedScriptsVisitor.InjectingProcessor(injectedFilesVisitor, rootTag, true));
  }

  public static void processMetaAttributesForClass(@NotNull PsiElement jsClass, @NotNull final JSResolveUtil.MetaDataProcessor processor) {
    JSResolveUtil.processMetaAttributesForClass(jsClass, processor);
    if (jsClass instanceof XmlBackedJSClassImpl) {
      PsiElement parent = jsClass.getParent();
      if (parent != null) {
        PsiFile file = parent.getContainingFile();
        if (file instanceof XmlFile) {
          XmlDocument document = ((XmlFile)file).getDocument();
          if (document != null) {
            XmlTag rootTag = document.getRootTag();
            if (rootTag != null) {
              JSResolveUtil.JSInjectedFilesVisitor visitor = new JSResolveUtil.JSInjectedFilesVisitor() {
                @Override
                protected void process(JSFile file) {
                  if (file != null) {
                    JSResolveUtil.processMetaAttributesForClass(file, processor);
                  }
                }
              };
              processMxmlTags(rootTag, visitor);
            }
          }
        }
      }
    }
  }

  public static String replacePathMacros(final @NotNull String text, final @NotNull Module module, final String sdkRootPath) {
    final StringBuilder builder = new StringBuilder(text);
    int startIndex;
    int endIndex = 0;

    while ((startIndex = builder.indexOf("${", endIndex)) >= 0) {
      endIndex = builder.indexOf("}", startIndex);
      if (endIndex > startIndex) {
        final String macroName = builder.substring(startIndex + 2, endIndex);
        final String macroValue;
        if (PathMacrosImpl.MODULE_DIR_MACRO_NAME.equals(macroName)) {
          final VirtualFile moduleFile = module.getModuleFile();
          macroValue = moduleFile == null ? null : moduleFile.getParent().getPath();
        }
        else if (PathMacrosImpl.PROJECT_DIR_MACRO_NAME.equals(macroName)) {
          final VirtualFile baseDir = module.getProject().getBaseDir();
          macroValue = baseDir == null ? null : baseDir.getPath();
        }
        else if (PathMacrosImpl.USER_HOME_MACRO_NAME.equals(macroName)) {
          macroValue = StringUtil.trimEnd((StringUtil.trimEnd(SystemProperties.getUserHome(), "/")), "\\");
        }
        else if (CompilerOptionInfo.FLEX_SDK_MACRO_NAME.equals(macroName)) {
          macroValue = sdkRootPath;
        }
        else {
          macroValue = PathMacros.getInstance().getValue(macroName);
        }

        if (macroValue != null && !StringUtil.isEmptyOrSpaces(macroValue)) {
          builder.replace(startIndex, endIndex + 1, macroValue);
          endIndex = endIndex + macroValue.length() - (macroName.length() + 3);
        }
      }
      else {
        break;
      }
    }

    return builder.toString();
  }

  public static String getPathToBundledJar(String filename) {
    final URL url = FlexUtils.class.getResource("");
    String folder;
    if ("jar".equals(url.getProtocol())) {
      // running from build
      folder = "/plugins/flex/lib/";
    }
    else {
      // running from sources
      folder = "/flex/lib/";
    }
    return FileUtil.toSystemDependentName(PathManager.getHomePath() + folder + filename);
  }

  public static List<String> getOptionValues(final String commandLine, final String... optionAndAliases) {
    if (StringUtil.isEmpty(commandLine)) {
      return Collections.emptyList();
    }

    final List<String> result = new LinkedList<String>();

    for (CommandLineTokenizer tokenizer = new CommandLineTokenizer(commandLine); tokenizer.hasMoreTokens(); ) {
      final String token = tokenizer.nextToken();
      for (String option : optionAndAliases) {
        if (token.startsWith("-" + option + "=") || token.startsWith("-" + option + "+=")) {
          result.addAll(StringUtil.split(token.substring(token.indexOf("=") + 1), ","));
        }
        else if (token.equals("-" + option) && tokenizer.countTokens() > 0) {
          if (tokenizer.countTokens() > 0) {
            String nextToken;
            while (tokenizer.hasMoreTokens() && canBeCompilerOptionValue(nextToken = tokenizer.peekNextToken())) {
              tokenizer.nextToken(); // advance tokenizer position
              result.add(nextToken);
            }
          }
        }
      }
    }

    return result;
  }

  public static boolean canBeCompilerOptionValue(final String text) {
    if (text.startsWith("-")) {  // option or negative number
      return text.length() > 1 && Character.isDigit(text.charAt(1));
    }
    return !text.startsWith("+");
  }

  public static <T> boolean equalLists(final List<T> list1, final List<T> list2) {
    if (list1.size() != list2.size()) return false;

    final Iterator<T> iterator = list1.iterator();
    for (final T element : list2) {
      if (!iterator.next().equals(element)) return false;
    }

    return true;
  }

  public static String getContentOrModuleFolderPath(final Module module) {
    final String[] contentRootUrls = ModuleRootManager.getInstance(module).getContentRootUrls();
    return contentRootUrls.length > 0 ? VfsUtil.urlToPath(contentRootUrls[0]) : PathUtil.getParentPath(module.getModuleFilePath());
  }

  @Nullable
  public static VirtualFile createDirIfMissing(final Project project,
                                               final boolean interactive,
                                               final String folderPath,
                                               final String errorMessageTitle) {
    VirtualFile folder = LocalFileSystem.getInstance().findFileByPath(folderPath);
    if (folder == null) {
      try {
        folder = VfsUtil.createDirectories(folderPath);
      }
      catch (IOException e) {
        if (interactive) {
          Messages.showErrorDialog(project,
                                   FlexBundle.message("failed.to.create.folder", FileUtil.toSystemDependentName(folderPath), e.getMessage()),
                                   errorMessageTitle);
        }
        return null;
      }
    }

    if (folder == null) {
      if (interactive) {
        Messages.showErrorDialog(project, FlexBundle.message("failed.to.create.folder", folderPath, "unknown error"), errorMessageTitle);
      }
      return null;
    }
    else if (!folder.isDirectory()) {
      Messages.showErrorDialog(project, FlexBundle.message("selected.path.not.folder", FileUtil.toSystemDependentName(folderPath)),
                               errorMessageTitle);
      return null;
    }

    return folder;
  }

  public static boolean processCompilerOption(final Module module, final FlexIdeBuildConfiguration bc, final String option,
                                              final Processor<Pair<String, String>> processor) {
    String rawValue = bc.getCompilerOptions().getOption(option);
    if (rawValue == null) rawValue = FlexBuildConfigurationManager.getInstance(module).getModuleLevelCompilerOptions().getOption(option);
    if (rawValue == null) {
      rawValue =
        FlexProjectLevelCompilerOptionsHolder.getInstance(module.getProject()).getProjectLevelCompilerOptions().getOption(option);
    }

    if (rawValue == null) return true;

    int pos = 0;
    while (true) {
      int index = rawValue.indexOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR, pos);
      if (index == -1) break;

      String token = rawValue.substring(pos, index);
      final int tabIndex = token.indexOf(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR);

      if (tabIndex > 0 && !processor.process(Pair.create(token.substring(0, tabIndex), token.substring(tabIndex + 1)))) return false;

      pos = index + 1;
    }

    final int tabIndex = rawValue.indexOf(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR, pos);
    if (tabIndex > pos) {
      if (!processor.process(Pair.create(rawValue.substring(pos, tabIndex), rawValue.substring(tabIndex + 1)))) return false;
    }

    return true;
  }
}
