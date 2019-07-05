/*
 * Copyright 2018 wanghonglin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wanghonglin.xjavadoc.gles;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.utils.Pair;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.FrequentEventDetector;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

class GLESJavadocDelegate {

    private final Project project;

    private GLESJavadocDelegate(Project project) {
        this.project = project;
    }

    private static GLESJavadocDelegate sInstance;

    static GLESJavadocDelegate defaultDelegate(Project project) {
        synchronized (GLESJavadocDelegate.class) {
            if (sInstance == null) {
                sInstance = new GLESJavadocDelegate(project);
            }
            return sInstance;
        }
    }

    private static final String REGULAR_EXPRESSION_SUFFIX = "(Matrix)?[1-4]?([fi]|(Boolean|Float|Integer))?v?$";
    // to obtain fully qualified class name
    // -bootclasspath is mandatory, we need the fully qualified parameters, so we can jump and show correctly
    // e.g. EGLDisplay with replace with android.opengl.EGLDisplay in parameters link
    private static final String JAVADOC_CMD = "javadoc -XDignore.symbol.file -Xdoclint:none -public -bootclasspath %s -sourcepath %s -d %s %s";
    private static final String JAR_CMD = "jar cvf %s -C %s .";

    interface XmlNodeTraverseCallback {
        void onTraverseXmlNode(org.w3c.dom.Node node);
    }

    private void traverseXmlNode(NodeList nodeList, XmlNodeTraverseCallback traverseCallback) {
        final int length = nodeList.getLength();
        for (int i = 0; i < length; i++) {
            org.w3c.dom.Node item = nodeList.item(i);
            if (traverseCallback != null) {
                traverseCallback.onTraverseXmlNode(item);
            }

            if (item.hasChildNodes()) {
                traverseXmlNode(item.getChildNodes(), traverseCallback);
            }
        }
    }

    private String getAndroidStudioConfigurationXmlPath() {
        // see http://tools.android.com/tech-docs/configuration
        // and https://developer.android.com/studio/intro/studio-config
        final String jdkTableXmlRelativePath = File.separator + "options" + File.separator + "jdk.table.xml";

        String configPath = System.getProperty("idea.config.path", null);
        if (configPath != null && configPath.length() > 0) {
            return configPath + jdkTableXmlRelativePath;
        } else {
            final String configFolder = ApplicationInfoEx.getInstanceEx().getVersionName().replaceAll("\\s", "")
                    + ApplicationInfoEx.getInstanceEx().getMajorVersion() + "." + ApplicationInfoEx.getInstanceEx().getMinorVersion();

            if (SystemUtils.IS_OS_LINUX) {
                configPath = SystemUtils.USER_HOME + File.separator + "." + configFolder;
            } else if (SystemUtils.IS_OS_WINDOWS){
                configPath = System.getenv("USERPROFILE") + File.separator + "." + configFolder;
            } else {
                configPath = SystemUtils.USER_HOME + "/Library/Preferences/" + configFolder;
            }

            configPath += jdkTableXmlRelativePath;
        }

        return configPath;
    }

    private void addDocEntry2AndroidStudioConfigurationXml(final String javaDocFullJarPath,
                                                           String xmlConfigPath) {
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();

        try {
            DocumentBuilder documentBuilder = builderFactory.newDocumentBuilder();
            org.w3c.dom.Document document = documentBuilder.parse(new File(xmlConfigPath));

            traverseXmlNode(document.getChildNodes(), new XmlNodeTraverseCallback() {
                @Override
                public void onTraverseXmlNode(org.w3c.dom.Node node) {
                    if ("javadocPath".equalsIgnoreCase(node.getNodeName())) {

                        org.w3c.dom.Node rootChild = null;

                        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                            if (node.getChildNodes().item(i).getNodeName().equalsIgnoreCase("root")) {
                                rootChild = node.getChildNodes().item(i);
                                break;
                            }
                        }

                        if (rootChild != null && rootChild.hasChildNodes()) {
                            org.w3c.dom.Node newChild = null;
                            org.w3c.dom.Node refChild = null;

                            for (int i = 0; i < rootChild.getChildNodes().getLength(); i++) {
                                if (rootChild.getChildNodes().item(i).getNodeName().equalsIgnoreCase("root")) {
                                    refChild = rootChild.getChildNodes().item(i);
                                    newChild = refChild.cloneNode(false);
                                    break;
                                }
                            }

                            if (newChild instanceof org.w3c.dom.Element) {
                                ((org.w3c.dom.Element) newChild).setAttribute("url", "jar://" + javaDocFullJarPath + "!/");
                            }

                            if (newChild != null) {
                                rootChild.insertBefore(newChild, refChild);
                            }
                        }
                    }
                }
            });

            document.getDocumentElement().normalize();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(document.getDocumentElement()),
                    new StreamResult(new OutputStreamWriter(new FileOutputStream(xmlConfigPath))));
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
            Logger.getInstance(getClass()).debug(e);
        }
    }

    /**
     * Perform the generating task
     */
    void glesJavaDoc() {
        final String userHome = System.getProperty("user.home");

        final String androidSdkRoot = PropertiesComponent.getInstance(project).getValue("android.sdk.path");
        final int androidAPILevel = findHighestAndroidAPILevel(androidSdkRoot);
        final String bootClassPath = androidSdkRoot + File.separator + "platforms" + File.separator +
                "android-" + androidAPILevel + File.separator + "android.jar";

        final String tempDir = System.getProperty("java.io.tmpdir", "/tmp");
        final String sourceOutDir = tempDir + File.separator + "java";
        final String javaDocOut = tempDir + File.separator + "javadoc";
        final String javaDocJarOut = androidSdkRoot + File.separator + "docs" + File.separator + "android-gles-javadoc.jar";

        for (Pair<String, String> pair : Arrays.asList(
                new Pair<>(
                        androidSdkRoot + "/sources/android-" + androidAPILevel + "/android/opengl" + File.separator + "EGL14.java",
                        sourceOutDir + File.separator + "android/opengl" + File.separator + "EGL14.java"),
                new Pair<>(
                        androidSdkRoot + "/sources/android-" + androidAPILevel + "/android/opengl" + File.separator + "GLES20.java",
                        sourceOutDir + File.separator + "android/opengl" + File.separator + "GLES20.java"
                ))) {
            addJavaDoc4JavaSource("/html-es2.0.zip", pair.a, pair.b);
        }

        try {
            int cmdRet = Runtime.getRuntime().exec(String.format(JAVADOC_CMD, bootClassPath, sourceOutDir, javaDocOut, "android.opengl")).waitFor();
            if (cmdRet != 0) {
                Logger.getInstance(GLESJavadocDelegate.class).error("run javadoc command failed with " + cmdRet);
            }
        } catch (IOException | InterruptedException e) {
            Logger.getInstance(GLESJavadocDelegate.class).error(e);
        }

        try {
            int cmdRet = Runtime.getRuntime().exec(String.format(JAR_CMD, javaDocJarOut, javaDocOut)).waitFor();
            if (cmdRet != 0) {
                Logger.getInstance(GLESJavadocDelegate.class).error("run jar command failed with " + cmdRet);
            }
        } catch (IOException | InterruptedException e) {
            Logger.getInstance(GLESJavadocDelegate.class).error(e);
        }

        // clean up
        for (String s : Arrays.asList(sourceOutDir, javaDocOut)) {
            try {
                FileUtils.deleteDirectory(new File(s));
            } catch (IOException e) {
                Logger.getInstance(getClass()).error(e);
            }
        }

        Logger.getInstance(GLESJavadocDelegate.class).debug("write javadoc jar to " + javaDocJarOut);

        final String androidStudioXmlConfigFile = getAndroidStudioConfigurationXmlPath();
        if (androidStudioXmlConfigFile != null) {
            Logger.getInstance(getClass()).debug("jdk.table.xml path " + androidStudioXmlConfigFile);
            try {
                FileUtils.copyFile(new File(androidStudioXmlConfigFile), new File(androidStudioXmlConfigFile + ".backup"));
            } catch (IOException e) {
                Logger.getInstance(GLESJavadocDelegate.class).error(e);
            }

            addDocEntry2AndroidStudioConfigurationXml(javaDocJarOut, androidStudioXmlConfigFile);
        } else {
            Logger.getInstance(getClass()).error("Could not get Android Studio jdk.table.xml configuration");
        }
    }

    private void addJavaDoc4JavaSource(final String htmlDocArchive, String sourceFilePath, String destFilePath) {
        try {
            CompilationUnit compilationUnit = JavaParser.parse(new File(sourceFilePath));

            compilationUnit.findAll(FieldDeclaration.class).forEach(new Consumer<FieldDeclaration>() {

                @Override
                public void accept(FieldDeclaration fieldDeclaration) {
                    if (fieldDeclaration.isPublic() && fieldDeclaration.isStatic() && fieldDeclaration.isFinal()) {
                        if (fieldDeclaration.getChildNodes().size() > 0
                                && fieldDeclaration.getChildNodes().get(0).getChildNodes().size() > 1) {
                            fieldDeclaration.setJavadocComment(fieldDeclaration.getChildNodes().get(0).getChildNodes().get(1).toString() + " {@value}");
                        }
                    }
                }
            });

            byte[] archiveBytes = IOUtils.toByteArray(getClass().getResourceAsStream(htmlDocArchive));
            ZipFile htmlDocArchives = new ZipFile(new SeekableInMemoryByteChannel(archiveBytes));

            compilationUnit.findAll(MethodDeclaration.class)
                    .forEach(new Consumer<MethodDeclaration>() {
                        @Override
                        public void accept(MethodDeclaration methodDeclaration) {
                            if (methodDeclaration.isPublic() /* && methodDeclaration.isNative() */ && methodDeclaration.isStatic()) {
                                /// File javaDocHtmlFile = new File(htmlDocArchive + File.separator + methodDeclaration.getNameAsString() + ".html");
                                ZipArchiveEntry htmlDocArchivesEntry = htmlDocArchives.getEntry(methodDeclaration.getNameAsString() + ".html");

                                if (/* !javaDocHtmlFile.exists() */ htmlDocArchivesEntry == null) {
                                    String newMethodName = methodDeclaration.getNameAsString().replaceAll(REGULAR_EXPRESSION_SUFFIX, "");
                                    // javaDocHtmlFile = new File(htmlDocArchive + File.separator + newMethodName + ".html");
                                    htmlDocArchivesEntry = htmlDocArchives.getEntry(newMethodName + ".html");
                                    Logger.getInstance(getClass()).warn("there is not map for " + methodDeclaration.getNameAsString() + " try replaced by " + newMethodName);
                                }

                                if (/* javaDocHtmlFile.exists() */ htmlDocArchivesEntry != null) {
                                    try {
                                        String javaDocString = methodDeclaration.getNameAsString();
                                        javaDocString = javaDocString.concat(System.lineSeparator());
                                        Document parse = Jsoup.parse(htmlDocArchives.getInputStream(htmlDocArchivesEntry), null, "/");
                                        parse.body().child(0).getAllElements().forEach(new Consumer<Element>() {
                                            @Override
                                            public void accept(final Element element) {
                                                // remove all a tag has name attribute
                                                // otherwise the generated javadoc can't displayed in android studio or idea
                                                if ("a".equalsIgnoreCase(element.tagName()) && element.hasAttr("name")) {
                                                    element.remove();
                                                }
                                            }
                                        });
                                        javaDocString = javaDocString.concat(parse.body().child(0).toString());

                                        final StringBuilder parameterStringBuilder = new StringBuilder();
                                        parameterStringBuilder.append(System.lineSeparator());
                                        parameterStringBuilder.append(System.lineSeparator());

                                        methodDeclaration.getParameters().forEach(new Consumer<Parameter>() {
                                            @Override
                                            public void accept(Parameter parameter) {
                                                parameterStringBuilder.append("@param");
                                                parameterStringBuilder.append(' ');
                                                parameterStringBuilder.append(parameter.getNameAsString());
                                                parameterStringBuilder.append(' ');
                                                parameterStringBuilder.append(parameter.getNameAsString());
                                                parameterStringBuilder.append(System.lineSeparator());
                                            }
                                        });


                                        if (!methodDeclaration.getType().isVoidType()) {
                                            parameterStringBuilder.append(System.lineSeparator());
                                            parameterStringBuilder.append("@return");
                                            parameterStringBuilder.append(' ');
                                            parameterStringBuilder.append(methodDeclaration.getType().asString());
                                            parameterStringBuilder.append(System.lineSeparator());
                                        }

                                        JavadocComment javadocComment = new JavadocComment(javaDocString.concat(parameterStringBuilder.toString()));
                                        methodDeclaration.setJavadocComment(javadocComment);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                    Logger.getInstance(getClass()).debug("add javadoc for methodDeclaration = [" + methodDeclaration.getNameAsString() + "]");
                                } else {
                                    Logger.getInstance(getClass()).debug("ExampleUnitTest.accept could not find the associated html file for " + methodDeclaration.getNameAsString());
                                }
                            }
                        }
                    });

            if (new File(destFilePath).getParentFile().mkdirs()) {
                Logger.getInstance(getClass()).debug("Create source output directory structure");
            }

            htmlDocArchives.close();
            FileUtils.writeStringToFile(new File(destFilePath), compilationUnit.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.getInstance(getClass()).error(e);
        }
    }

    private int findHighestAndroidAPILevel(final String androidSdkRoot) {
        int highestAPILevel = 21;
        for (File file : Objects.requireNonNull(new File(androidSdkRoot + File.separator + "sources").listFiles())) {
            String[] strings = file.getName().split("-");
            if (strings.length > 1) {
                try {
                    int apiLevel = Integer.parseInt(strings[1]);
                    if (apiLevel >= highestAPILevel) {
                        highestAPILevel = apiLevel;
                    }
                } catch (NumberFormatException e) {
                    Logger.getInstance(getClass()).error(e);
                }
            }
        }
        Logger.getInstance(getClass()).debug("findHighestAndroidAPILevel " + highestAPILevel);
        return highestAPILevel;
    }
}
