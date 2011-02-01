/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.build;

import com.android.ide.eclipse.adt.AdtPlugin;
import com.android.ide.eclipse.adt.AndroidConstants;
import com.android.ide.eclipse.adt.internal.build.builders.BaseBuilder;
import com.android.ide.eclipse.adt.internal.preferences.AdtPrefs;
import com.android.ide.eclipse.adt.internal.preferences.AdtPrefs.BuildVerbosity;
import com.android.ide.eclipse.adt.internal.project.BaseProjectHelper;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceFolderType;
import com.android.ide.eclipse.adt.internal.sdk.Sdk;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.SdkConstants;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link JavaGenerator} for RenderScript files.
 *
 */
public class RenderScriptGenerator extends JavaGenerator {

    private static final String PROPERTY_COMPILE_RS = "compileRenderScript"; //$NON-NLS-1$

    /**
     * Single line llvm-rs-cc error<br>
     * "&lt;path&gt;:&lt;line&gt;:&lt;col&gt;: &lt;error&gt;"
     */
    private static Pattern sLlvmPattern1 = Pattern.compile("^(.+?):(\\d+):(\\d+):\\s(.+)$"); //$NON-NLS-1$

    private static class RSDeltaVisitor extends GeneratorDeltaVisitor {

        @Override
        protected boolean filterResourceFolder(IContainer folder) {
            return ResourceFolderType.RAW.getName().equals(folder.getName());
        }
    }

    public RenderScriptGenerator(IJavaProject javaProject, IFolder genFolder) {
        super(javaProject, genFolder, new RSDeltaVisitor());
    }

    @Override
    protected String getExtension() {
        return AndroidConstants.EXT_RS;
    }

    @Override
    protected String getSavePropertyName() {
        return PROPERTY_COMPILE_RS;
    }

    @Override
    protected int getCompilationType() {
        return COMPILE_STATUS_CODE | COMPILE_STATUS_RES;
    }

    @Override
    protected void doCompileFiles(List<IFile> sources, BaseBuilder builder,
            IProject project, IAndroidTarget projectTarget, List<IPath> sourceFolders,
            List<IFile> notCompiledOut, IProgressMonitor monitor) throws CoreException {

        String sdkOsPath = Sdk.getCurrent().getSdkLocation();

        IFolder genFolder = getGenFolder();

        IFolder rawFolder = project.getFolder(
                new Path(SdkConstants.FD_RES).append(SdkConstants.FD_RAW));

        // create the command line
        String[] command = new String[13];
        int index = 0;
        command[index++] = sdkOsPath + SdkConstants.OS_SDK_PLATFORM_TOOLS_FOLDER
                + SdkConstants.FN_RENDERSCRIPT;
        command[index++] = "-I";
        command[index++] = projectTarget.getPath(IAndroidTarget.ANDROID_RS_CLANG);
        command[index++] = "-I";
        command[index++] = projectTarget.getPath(IAndroidTarget.ANDROID_RS);
        command[index++] = "-p";
        command[index++] = genFolder.getLocation().toOSString();
        command[index++] = "-o";
        command[index++] = rawFolder.getLocation().toOSString();

        command[index++] = "-d";
        command[index++] = getDependencyFolder().getLocation().toOSString();
        command[index++] = "-MD";

        boolean verbose = AdtPrefs.getPrefs().getBuildVerbosity() == BuildVerbosity.VERBOSE;
        boolean someSuccess = false;

        // loop until we've compile them all
        for (IFile sourceFile : sources) {
            if (verbose) {
                String name = sourceFile.getName();
                IPath sourceFolderPath = getSourceFolderFor(sourceFile);
                if (sourceFolderPath != null) {
                    // make a path to the source file relative to the source folder.
                    IPath relative = sourceFile.getFullPath().makeRelativeTo(sourceFolderPath);
                    name = relative.toString();
                }
                AdtPlugin.printToConsole(project, "RenderScript: " + name);
            }

            // Remove the RS error markers from the source file and the dependencies
            builder.removeMarkersFromFile(sourceFile, AndroidConstants.MARKER_RENDERSCRIPT);
            NonJavaFileBundle bundle = getBundle(sourceFile);
            if (bundle != null) {
                for (IFile dep : bundle.getDependencyFiles()) {
                    builder.removeMarkersFromFile(dep, AndroidConstants.MARKER_RENDERSCRIPT);
                }
            }

            // get the path of the source file.
            IPath sourcePath = sourceFile.getLocation();
            String osSourcePath = sourcePath.toOSString();

            // finish to set the command line.
            command[index] = osSourcePath;

            // launch the process
            if (execLlvmRsCc(builder, project, command, sourceFile, verbose) == false) {
                // llvm-rs-cc failed. File should be marked. We add the file to the list
                // of file that will need compilation again.
                notCompiledOut.add(sourceFile);
            } else {
                // need to parse the .d file to figure out the dependencies and the generated file
                parseDependencyFileFor(sourceFile);
                someSuccess = true;
            }
        }

        if (someSuccess) {
            rawFolder.refreshLocal(IResource.DEPTH_ONE, monitor);
        }
    }

    private boolean execLlvmRsCc(BaseBuilder builder, IProject project, String[] command,
            IFile sourceFile, boolean verbose) {
        // do the exec
        try {
            if (verbose) {
                StringBuilder sb = new StringBuilder();
                for (String c : command) {
                    sb.append(c);
                    sb.append(' ');
                }
                String cmd_line = sb.toString();
                AdtPlugin.printToConsole(project, cmd_line);
            }

            Process p = Runtime.getRuntime().exec(command);

            // list to store each line of stderr
            ArrayList<String> results = new ArrayList<String>();

            // get the output and return code from the process
            int result = BuildHelper.grabProcessOutput(project, p, results);

            // attempt to parse the error output
            boolean error = parseLlvmOutput(results);

            // If the process failed and we couldn't parse the output
            // we print a message, mark the project and exit
            if (result != 0) {

                if (error || verbose) {
                    // display the message in the console.
                    if (error) {
                        AdtPlugin.printErrorToConsole(project, results.toArray());

                        // mark the project
                        BaseProjectHelper.markResource(project, AndroidConstants.MARKER_ADT,
                                "Unparsed Renderscript error! Check the console for output.",
                                IMarker.SEVERITY_ERROR);
                    } else {
                        AdtPlugin.printToConsole(project, results.toArray());
                    }
                }
                return false;
            }
        } catch (IOException e) {
            // mark the project and exit
            String msg = String.format(
                    "Error executing Renderscript. Please check llvm-rs-cc is present at %1$s",
                    command[0]);
            BaseProjectHelper.markResource(project, AndroidConstants.MARKER_ADT, msg,
                    IMarker.SEVERITY_ERROR);
            return false;
        } catch (InterruptedException e) {
            // mark the project and exit
            String msg = String.format(
                    "Error executing Renderscript. Please check llvm-rs-cc is present at %1$s",
                    command[0]);
            BaseProjectHelper.markResource(project, AndroidConstants.MARKER_ADT, msg,
                    IMarker.SEVERITY_ERROR);
            return false;
        }

        return true;
    }

    /**
     * Parse the output of llvm-rs-cc and mark the file with any errors.
     * @param lines The output to parse.
     * @return true if the parsing failed, false if success.
     */
    private boolean parseLlvmOutput(ArrayList<String> lines) {
        // nothing to parse? just return false;
        if (lines.size() == 0) {
            return false;
        }

        // get the root folder for the project as we're going to ignore everything that's
        // not in the project
        IProject project = getJavaProject().getProject();
        String rootPath = project.getLocation().toOSString();
        int rootPathLength = rootPath.length();

        Matcher m;

        boolean parsing = false;

        for (int i = 0; i < lines.size(); i++) {
            String p = lines.get(i);

            m = sLlvmPattern1.matcher(p);
            if (m.matches()) {
                // get the file path. This may, or may not be the main file being compiled.
                String filePath = m.group(1);
                if (filePath.startsWith(rootPath) == false) {
                    // looks like the error in a non-project file. Keep parsing, but
                    // we'll return true
                    parsing = true;
                    continue;
                }

                // get the actual file.
                filePath = filePath.substring(rootPathLength);
                // remove starting separator since we want the path to be relative
                if (filePath.startsWith(File.separator)) {
                    filePath = filePath.substring(1);
                }

                // get the file
                IFile f = project.getFile(new Path(filePath));

                String lineStr = m.group(2);
                // ignore group 3 for now, this is the col number
                String msg = m.group(4);

                // get the line number
                int line = 0;
                try {
                    line = Integer.parseInt(lineStr);
                } catch (NumberFormatException e) {
                    // looks like the string we extracted wasn't a valid
                    // file number. Parsing failed and we return true
                    return true;
                }

                // mark the file
                BaseProjectHelper.markResource(f, AndroidConstants.MARKER_RENDERSCRIPT, msg, line,
                        IMarker.SEVERITY_ERROR);

                // success, go to the next line
                continue;
            }

            // invalid line format, flag as error, and keep going
            parsing = true;
        }

        return parsing;
    }


    @Override
    protected void doRemoveFiles(NonJavaFileBundle bundle) throws CoreException {
        // call the super implementation, it will remove the output files
        super.doRemoveFiles(bundle);

        // now remove the dependency file.
        IFile depFile = getDependencyFileFor(bundle.getSourceFile());
        if (depFile.exists()) {
            depFile.getLocation().toFile().delete();
        }
    }

    @Override
    protected void loadOutputAndDependencies() {
        Collection<NonJavaFileBundle> bundles = getBundles();
        for (NonJavaFileBundle bundle : bundles) {
            parseDependencyFileFor(bundle.getSourceFile());
        }
    }

    private void parseDependencyFileFor(IFile sourceFile) {
        IFile depFile = getDependencyFileFor(sourceFile);
        File f = depFile.getLocation().toFile();
        if (f.exists()) {
            NonJavaFileBundle bundle = getBundle(sourceFile);
            if (bundle == null) {
                bundle = new NonJavaFileBundle(sourceFile);
                addBundle(bundle);
            }
            parseDependencyFile(bundle, f);
        }
    }

    private IFolder getDependencyFolder() {
        return getJavaProject().getProject().getFolder(SdkConstants.FD_OUTPUT);
    }

    private IFile getDependencyFileFor(IFile sourceFile) {
        IFolder depFolder = getDependencyFolder();
        return depFolder.getFile(sourceFile.getName().replaceAll(AndroidConstants.RE_RS_EXT,
                AndroidConstants.DOT_DEP));
    }

    /**
     * Parses the given dependency file and fills the given {@link NonJavaFileBundle} with it.
     *
     * @param bundle the bundle to fill.
     * @param file the dependency file
     */
    private void parseDependencyFile(NonJavaFileBundle bundle, File dependencyFile) {
        //contents = file.getContents();
        String content = AdtPlugin.readFile(dependencyFile);

        // we're going to be pretty brutal here.
        // The format is something like:
        // output1 output2 [...]: dep1 dep2 [...]
        // expect it's likely split on several lines. So let's move it back on a single line
        // first
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.endsWith("\\")) {
                line = line.substring(0, line.length() - 1);
            }

            sb.append(line);
        }

        // split the left and right part
        String[] files = sb.toString().split(":");

        // get the output files:
        String[] outputs = files[0].trim().split(" ");

        // and the dependency files:
        String[] dependencies = files[1].trim().split(" ");

        List<IFile> outputFiles = new ArrayList<IFile>();
        List<IFile> dependencyFiles = new ArrayList<IFile>();

        fillList(outputs, outputFiles);
        fillList(dependencies, dependencyFiles);

        bundle.setOutputFiles(outputFiles);
        bundle.setDependencyFiles(dependencyFiles);
    }

    private void fillList(String[] paths, List<IFile> list) {
        // get the root folder for the project as we're going to ignore everything that's
        // not in the project
        IProject project = getJavaProject().getProject();
        String rootPath = project.getLocation().toOSString();
        int rootPathLength = rootPath.length();

        // all those should really be in the project
        for (String p : paths) {

            if (p.startsWith(rootPath)) {
                p = p.substring(rootPathLength);
                // remove starting separator since we want the path to be relative
                if (p.startsWith(File.separator)) {
                    p = p.substring(1);
                }

                // get the file
                IFile f = project.getFile(new Path(p));
                list.add(f);
            }
        }
    }
}
