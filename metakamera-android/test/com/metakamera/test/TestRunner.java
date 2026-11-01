package com.metakamera.test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal JUnit-free test runner.
 *
 * Usage: java com.metakamera.test.TestRunner [testRoot] [ClassName|filter ...]
 * Runs every {@code public static void testXxx()} method of every class whose simple name
 * ends with "Test" under test/ (or only the given classes). Exit code 1 on any failure.
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        List<String> classes = new ArrayList<String>();
        String root = "test";
        List<String> filters = new ArrayList<String>();
        for (String a : args) {
            if (new File(a).isDirectory()) root = a;
            else filters.add(a);
        }
        collect(new File(root), "", classes);
        java.util.Collections.sort(classes);
        int pass = 0;
        int fail = 0;
        List<String> failures = new ArrayList<String>();
        for (String cn : classes) {
            if (!filters.isEmpty()) {
                boolean match = false;
                for (String f : filters) if (cn.endsWith(f) || cn.contains(f)) match = true;
                if (!match) continue;
            }
            Class<?> c = Class.forName(cn);
            Method[] ms = c.getDeclaredMethods();
            Arrays.sort(ms, new Comparator<Method>() {
                @Override
                public int compare(Method a, Method b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (Method m : ms) {
                if (!m.getName().startsWith("test") || m.getParameterTypes().length != 0) continue;
                if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) continue;
                String name = c.getSimpleName() + "." + m.getName();
                long t0 = System.nanoTime();
                try {
                    m.invoke(null);
                    pass++;
                    System.out.printf("PASS %-60s %5d ms%n", name, (System.nanoTime() - t0) / 1000000);
                } catch (InvocationTargetException e) {
                    fail++;
                    Throwable t = e.getCause();
                    failures.add(name + ": " + t);
                    System.out.printf("FAIL %-60s%n", name);
                    t.printStackTrace(System.out);
                }
            }
        }
        System.out.println();
        System.out.println("Tests: " + pass + " passed, " + fail + " failed");
        for (String f : failures) System.out.println("  FAILED " + f);
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void collect(File dir, String pkg, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, pkg.isEmpty() ? f.getName() : pkg + "." + f.getName(), out);
            } else if (f.getName().endsWith("Test.java")) {
                String simple = f.getName().substring(0, f.getName().length() - 5);
                out.add(pkg.isEmpty() ? simple : pkg + "." + simple);
            }
        }
    }
}
