package cn.managame.jpa.starter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量 classpath 包扫描器。
 *
 * <p>不依赖任何 DI 容器或第三方库:给定包名,返回其(递归)下所有可加载的 {@link Class}。
 * 支持两种 classpath 来源:目录(开发态、exploded 部署)与 jar(打包/容器部署)。
 * <b>不支持</b> Spring Boot 嵌套 jar({@code BOOT-INF/classes!/...} 这类二级归档)。</p>
 *
 * <p>类以 {@code initialize=false} 加载,避免触发静态初始化副作用;加载失败的类
 * (缺可选依赖、链接错误等)会被跳过并告警,不中断整体扫描。</p>
 */
final class ClasspathScanner {

    private static final Logger log = LoggerFactory.getLogger(ClasspathScanner.class);

    private ClasspathScanner() {
    }

    static List<Class<?>> scan(String basePackage, ClassLoader classLoader) {
        ClassLoader loader = resolveLoader(classLoader);
        String path = basePackage.replace('.', '/');
        List<Class<?>> classes = new ArrayList<>();
        Enumeration<URL> resources;
        try {
            resources = loader.getResources(path);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描包失败: " + basePackage, e);
        }
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                File dir = new File(URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8));
                collectFromDirectory(dir, basePackage, loader, classes);
            } else if ("jar".equals(protocol)) {
                collectFromJar(url, path, loader, classes);
            } else {
                log.warn("[GameJpa] 不支持的 classpath 协议 {},跳过: {}", protocol, url);
            }
        }
        return classes;
    }

    private static ClassLoader resolveLoader(ClassLoader classLoader) {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : ClasspathScanner.class.getClassLoader();
    }

    private static void collectFromDirectory(File dir, String packageName, ClassLoader loader,
                                             List<Class<?>> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFromDirectory(file, packageName + "." + file.getName(), loader, out);
            } else if (file.getName().endsWith(".class")) {
                String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
                addClass(packageName + "." + simpleName, loader, out);
            }
        }
    }

    private static void collectFromJar(URL url, String pathPrefix, ClassLoader loader,
                                       List<Class<?>> out) {
        // jar URL 形如 jar:file:/x/app.jar!/com/game/dev —— 取 "!/" 前的归档路径。
        String spec = url.getPath();
        int separator = spec.indexOf("!/");
        String jarPath = separator >= 0 ? spec.substring(0, separator) : spec;
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring("file:".length());
        }
        jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8);
        String entryPrefix = pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/";
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(entryPrefix) && name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    addClass(className, loader, out);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 jar 失败: " + jarPath, e);
        }
    }

    private static void addClass(String className, ClassLoader loader, List<Class<?>> out) {
        if (className.endsWith("package-info") || className.endsWith("module-info")) {
            return;
        }
        try {
            out.add(Class.forName(className, false, loader));
        } catch (Throwable t) {
            // ClassNotFoundException / NoClassDefFoundError / 链接错误等:跳过单个类,不中断扫描。
            log.warn("[GameJpa] 跳过无法加载的类 {}: {}", className, t.toString());
        }
    }
}
