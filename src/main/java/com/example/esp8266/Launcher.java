package com.example.esp8266;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 独立启动器（打包后的 jar 入口）。
 *
 * <p>作用：把 jar 自身 + 同目录下 {@code lib/*.jar} 全部加入类加载器，然后反射调用 Spring Boot 主类。
 * 这样打出来的 jar 只有几十 KB，依赖放在 lib 目录里，不需要：</p>
 * <ul>
 *   <li>maven-shade / spring-boot-maven-plugin 之类的重打包插件；</li>
 *   <li>MANIFEST 里超长的 Class-Path（MANIFEST 规范每行最多 72 字节，很容易踩坑）。</li>
 * </ul>
 * <p>因此在没有网络、Maven 打包插件依赖不全的机器上也能打包运行。</p>
 */
public final class Launcher {

    private static final String MAIN_CLASS = "com.example.esp8266.Esp8266TcpServerApplication";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        File codeSource = new File(Launcher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File home = codeSource.isFile() ? codeSource.getParentFile() : codeSource;

        List<URL> urls = new ArrayList<>();
        // 自身放在最前，保证 com.example.esp8266.* 从本 jar 加载
        urls.add(codeSource.toURI().toURL());
        collectJars(new File(home, "lib"), urls);
        collectJars(new File(home, "target/lib"), urls);

        if (urls.size() <= 1) {
            System.err.println("[Launcher] 未找到依赖 jar，请确认 lib 目录与 jar 同级；"
                    + "可先执行: mvn -o dependency:copy-dependencies -DoutputDirectory=target/lib");
        }

        ClassLoader parent = ClassLoader.getSystemClassLoader();
        URLClassLoader loader = new ChildFirstClassLoader(urls.toArray(new URL[0]), parent);
        Thread.currentThread().setContextClassLoader(loader);

        Class<?> appClass = Class.forName(MAIN_CLASS, true, loader);
        Method main = appClass.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    private static void collectJars(File dir, List<URL> urls) throws Exception {
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            urls.add(file.toURI().toURL());
        }
    }

    /**
     * 关键点：父加载器是应用类加载器，它看不到 lib 目录，因此第三方类
     * （spring / tomcat / jackson ...）必须由本加载器自己去找，否则会 ClassNotFoundException。
     * java.* / javax.* / jdk.* / sun.* 仍然交给父加载器（JDK 不允许自定义这些包）。
     */
    private static final class ChildFirstClassLoader extends URLClassLoader {

        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isJdkClass(name)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException e) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isJdkClass(String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")
                    || name.startsWith("com.sun.")
                    || name.startsWith("org.w3c.")
                    || name.startsWith("org.xml.");
        }
    }
}
