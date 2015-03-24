/*
 * Copyright 2014 Google Inc. All Rights Reserved.
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

package com.google.errorprone;

import static com.google.common.collect.Iterables.limit;
import static com.google.common.collect.Iterables.size;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import com.google.errorprone.BugPattern.Instance;
import com.google.errorprone.BugPattern.MaturityLevel;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.BugPattern.Suppressibility;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads each line of the bugpatterns.txt tab-delimited data file, and generates a GitHub
 * Jekyll page for each one.
 *
 * @author alexeagle@google.com (Alex Eagle)
 */
class BugPatternFileGenerator implements LineProcessor<List<Instance>> {
  private final File outputDir;
  private final File exampleDirBase;
  private List<Instance> result;

  /**
   * Enables pygments-style code highlighting blocks instead of github flavoured markdown style
   * code fences, because the latter make jekyll unhappy.
   */
  private final boolean usePygments;

  /**
   * Controls whether yaml front-matter is generated.
   */
  private final boolean generateFrontMatter;

  public BugPatternFileGenerator(
      File outputDir, File exampleDirBase, boolean generateFrontMatter, boolean usePygments) {
    this.outputDir = outputDir;
    this.exampleDirBase = exampleDirBase;
    this.generateFrontMatter = generateFrontMatter;
    this.usePygments = usePygments;
    result = new ArrayList<>();
  }

  private static class ExampleFilter implements FilenameFilter {
    private Pattern matchPattern;

    public ExampleFilter(String checkerName) {
      this.matchPattern = Pattern.compile(checkerName + "(Positive|Negative)Case.*");
    }

    @Override
    public boolean accept(File dir, String name) {
      return matchPattern.matcher(name).matches();
    }
  }

  /**
   * Construct an appropriate page template for this {@code BugPattern}.  Include altNames if
   * there are any, and explain the correct way to suppress.
   */
  private static MessageFormat constructPageTemplate(
      Instance pattern,  boolean generateFrontMatter) {
    StringBuilder result = new StringBuilder();
    if (generateFrontMatter) {
      result.append("---\n"
          + "title: {1}\n"
          + "layout: bugpattern\n"
          + "category: {3}\n"
          + "severity: {4}\n"
          + "maturity: {5}\n"
          + "---\n\n");
    }
    result.append("<div style=\"float:right;\"><table id=\"metadata\">\n"
        + "<tr><td>Category</td><td>{3}</td></tr>\n"
        + "<tr><td>Severity</td><td>{4}</td></tr>\n"
        + "<tr><td>Maturity</td><td>{5}</td></tr>\n"
        + "</table></div>\n\n"
        + "# Bug pattern: {1}\n"
        + "__{8}__\n");
    if (pattern.altNames.length() > 0) {
      result.append("\n_Alternate names: {2}_\n");
    }
    result.append("\n"
        + "## The problem\n"
        + "{9}\n"
        + "\n"
        + "## Suppression\n");

    switch (pattern.suppressibility) {
      case SUPPRESS_WARNINGS:
        result.append("Suppress false positives by adding an `@SuppressWarnings(\"{1}\")` "
            + "annotation to the enclosing element.\n");
        break;
      case CUSTOM_ANNOTATION:
        result.append("Suppress false positives by adding the custom suppression annotation "
            + "`@{7}` to the enclosing element.\n");
        break;
      case UNSUPPRESSIBLE:
        result.append("This check may not be suppressed.\n");
        break;
    }
    return new MessageFormat(result.toString(), Locale.ENGLISH);
  }

  @Override
  public boolean processLine(String line) throws IOException {
    String[] parts = line.split("\t");
    Instance pattern = new Instance();
    pattern.name = parts[1];
    pattern.altNames = parts[2];
    pattern.maturity = MaturityLevel.valueOf(parts[5]);
    pattern.summary = parts[8];
    pattern.severity = SeverityLevel.valueOf(parts[4]);
    pattern.suppressibility = Suppressibility.valueOf(parts[6]);
    pattern.customSuppressionAnnotation = parts[7];
    result.add(pattern);

    // replace spaces in filename with underscores
    Writer writer = Files.newWriter(
        new File(outputDir, pattern.name.replace(' ', '_') + ".md"), UTF_8);
    // replace "\n" with a carriage return for explanation
    parts[9] = parts[9].replace("\\n", "\n");

    MessageFormat wikiPageTemplate = constructPageTemplate(pattern, generateFrontMatter);
    writer.write(wikiPageTemplate.format(parts));

    Iterable<String> classNameParts = Splitter.on('.').split(parts[0]);
    String path = Joiner.on('/').join(limit(classNameParts, size(classNameParts) - 1));
    File exampleDir = new File(exampleDirBase, path);
    if (!exampleDir.exists()) {
      // Not all check dirs have example fields (e.g. threadsafety).
      // TODO(user): clean this up.
      // System.err.println("Warning: cannot find path " + exampleDir);
    } else {
      // Example filename must match example pattern.
      File[] examples = exampleDir.listFiles(new ExampleFilter(
          parts[0].substring(parts[0].lastIndexOf('.') + 1)));
      Arrays.sort(examples);
      if (examples.length > 0) {
        writer.write("\n----------\n\n");
        writer.write("# Examples\n");

        for (File example: examples) {
          writer.write("__" + example.getName() + "__\n\n");
          if (usePygments) {
            writer.write("{% highlight java %}\n");
          } else {
            writer.write("```java\n");
          }
          writer.write(Files.toString(example, UTF_8) + "\n");
          if (usePygments) {
            writer.write("{% endhighlight %}\n");
          } else {
            writer.write("```\n");
          }
          writer.write("\n");
        }
      }
    }
    writer.close();
    return true;
  }

  @Override
  public List<BugPattern.Instance> getResult() {
    return result;
  }
}