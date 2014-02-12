package com.shankyank.maven.plugin.resgen;

/**
 *
 * Templated Resource Generator Maven Plugin
 *
 * Copyright (C) 2014 shankyank.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Copies a resource file from a provided, Wagon-compliant template file, optionally performing property replacement from the configured
 * project environment.
 *
 * @author Gordon Shankman
 */
@Mojo(
        name="generate-resource",
        defaultPhase=LifecyclePhase.GENERATE_RESOURCES,
        threadSafe=true
)
public class TemplatedResGenMojo extends AbstractMojo {

    /**
     * The default, system charset.
     */
    private static final Charset SYSTEM_ENCODING = Charset.defaultCharset();

    /**
     * The replacement pattern: ${*}
     */
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{([-a-zA-Z0-9_.]*)\\}");

    /**
     * The Maven Project.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * The Maven Session.
     */
    @Parameter(defaultValue="${session}", required=true, readonly=true)
    private MavenSession session;

    /**
     * The path to the template. Can be specified by -Dresgen.template.
     */
    @Parameter(property = "template", required = true, defaultValue = "${resgen.template}")
    private String template;

    /**
     * The output directory. Defaults to project.build.outputDirectory.
     */
    @Parameter(property = "outputDir", defaultValue = "${project.build.outputDirectory}")
    private File outputDir;

    /**
     * The output file name. Can be specified by -Dresgen.outputFile.
     */
    @Parameter(property = "outputFile", required = true, defaultValue = "${resgen.outputFile}")
    private String outputFileName;

    /**
     * The template encoding. Can be specified by -Dresgen.encoding if both template and output encoding are the same.
     */
    @Parameter(property = "templateEncoding", defaultValue = "${resgen.encoding}")
    private String templateEncoding;

    /**
     * The output encoding. Can be specified by -Dresgen.encoding if both template and output encoding are the same.
     */
    @Parameter(property = "outputEncoding", defaultValue = "${resgen.encoding}")
    private String outputEncoding;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Charset templateCharset = SYSTEM_ENCODING;
        boolean resolvedTemplateCharset = false;
        if (templateEncoding != null) {
            try {
                templateCharset = Charset.forName(templateEncoding);
                resolvedTemplateCharset = true;
            } catch (IllegalArgumentException iae) {
                getLog().warn(String.format("Invalid 'templateEncoding' [%s] provided. Using system default: %s",
                        templateEncoding, SYSTEM_ENCODING.displayName()));
            }
        } else {
            getLog().warn(String.format("No 'templateEncoding' provided.  Using system default: %s", SYSTEM_ENCODING.displayName()));
        }
        Charset outputCharset = templateCharset;
        if (outputEncoding != null) {
            try {
                outputCharset = Charset.forName(outputEncoding);
            } catch (IllegalArgumentException iae) {
                getLog().warn(String.format("Invalid 'outputEncoding' [%s] provided. Using %s: %s", outputEncoding,
                        (resolvedTemplateCharset ? "'templateEncoding'" : "system default"), templateCharset.displayName()));
            }
        } else if (resolvedTemplateCharset) {
            getLog().info(String.format("No 'outputEncoding' provided. Using 'templateEncoding': %s", templateEncoding));
        } else {
            getLog().warn(String.format("No 'outputEncoding' provided. Using system default: %s", SYSTEM_ENCODING.displayName()));
        }

        Properties props = new Properties();
        props.putAll(session.getSystemProperties());
        props.putAll(session.getUserProperties());
        props.putAll(project.getProperties());
        props.putAll(genProjectMetadataProps());

        try (
                BufferedReader reader = openTemplate(templateCharset);
                BufferedWriter writer = openOutputResource(outputCharset);
            )
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                Matcher matcher = PROPERTY_PATTERN.matcher(line);
                StringBuffer outBuffer = new StringBuffer("");
                while (matcher.find()) {
                    String key = matcher.group(1);
                    String replaceValue = props.getProperty(matcher.group(1), "");
                    getLog().debug(String.format("Replacing ${%s} with %s", key, replaceValue));
                    matcher.appendReplacement(outBuffer, replaceValue);
                }
                matcher.appendTail(outBuffer);
                writer.append(outBuffer.toString());
                writer.newLine();
            }
        } catch (IOException ioe) {
            throw new MojoFailureException(String.format("Error generating target resource [%s].", outputFileName), ioe);
        }
    }

    private BufferedReader openTemplate(final Charset charset) throws MojoFailureException {
        getLog().debug(String.format("Opening template [%s] as classpath resource.", template));
        InputStream inTemplate = getClass().getClassLoader().getResourceAsStream(template);
        if (inTemplate == null) {
            getLog().debug(String.format("Opening template [%s] as system resource.", template));
            inTemplate = ClassLoader.getSystemResourceAsStream(template);
        }
        if (inTemplate == null) {
            getLog().debug(String.format("Opening template [%s] as file.", template));
            inTemplate = openTemplateFile(new File(template));
        }
        if (inTemplate == null) {
            File projTemplate = new File(project.getBasedir(), template);
            getLog().debug(String.format("Opening template as file relative to project directory: %s", projTemplate.getPath()));
            inTemplate = openTemplateFile(projTemplate);
        }
        if (inTemplate == null) {
            throw new MojoFailureException(String.format("Unable to open template [%s].", template));
        }
        return new BufferedReader(new InputStreamReader(inTemplate, charset));
    }

    private InputStream openTemplateFile(final File file) {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException | SecurityException ex) {
            return null;
        }
    }

    private BufferedWriter openOutputResource(final Charset charset) throws MojoFailureException {
        File resolvedOutputFile = new File(outputDir, outputFileName);
        File resolvedOutputDir = resolvedOutputFile.getParentFile();
        getLog().debug(String.format("Ensuring full path to output file [%s] exists.", resolvedOutputFile.getPath()));
        if (!(resolvedOutputDir.exists() || resolvedOutputDir.mkdirs())) {
            throw new MojoFailureException(String.format("Unable to create output path: %s", resolvedOutputDir.getPath()));
        }
        try {
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(resolvedOutputFile), charset));
        } catch (FileNotFoundException | SecurityException ex) {
            throw new MojoFailureException(String.format("Unable to open output file [%s].", resolvedOutputFile.getPath()), ex);
        }
    }

    private Map<String, String> genProjectMetadataProps() {
        Model projectModel = project.getModel();
        Map<String, String> props = new HashMap<String, String>();
        props.put("project.name", projectModel.getName());
        props.put("project.groupId", projectModel.getGroupId());
        props.put("project.artifactId", projectModel.getArtifactId());
        props.put("project.version", projectModel.getVersion());
        return props;
    }
}
