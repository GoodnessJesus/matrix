package com.tencent.matrix.trace;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by habbyge on 2019/4/24.
 */
public class TraceClassLoader {

    public static URLClassLoader getClassLoader(Collection<File> bootClasspath, Collection<File> inputFiles)
            throws MalformedURLException {

        List<URL> urls = new ArrayList<>();
        for (File classpathFile : bootClasspath) {
            urls.add(classpathFile.toURI().toURL());
        }
        for (File inputFile : inputFiles) {
            urls.add(inputFile.toURI().toURL());
        }

        URL[] classLoaderUrls = urls.toArray(new URL[0]);
        return new URLClassLoader(classLoaderUrls);
    }
}
