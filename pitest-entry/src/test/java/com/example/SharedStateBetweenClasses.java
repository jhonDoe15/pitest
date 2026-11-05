package com.example;

import java.util.HashSet;
import java.util.Set;

public class SharedStateBetweenClasses {

  public static class Registry {
    private static final Set<String> NAMES = new HashSet<>();

    public static void register(String name) {
      if (name.startsWith("bad")) {
        throw new IllegalArgumentException(name);
      }
      NAMES.add(name);
    }

    public static boolean known(String name) {
      return NAMES.contains(name);
    }
  }

  public static class Formatter {
    private static int calls;

    public static String format(String name) {
      calls = calls + 1;
      return Registry.known(name) ? "known " + name : name;
    }
  }

}
