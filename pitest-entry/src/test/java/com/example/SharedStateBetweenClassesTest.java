package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.pitest.simpletest.TestAnnotationForTesting;

public class SharedStateBetweenClassesTest {

  // a mutant that removes the check leaves "bad" registered for later tests
  @TestAnnotationForTesting
  public void rejectsBadNames() {
    try {
      SharedStateBetweenClasses.Registry.register("bad");
      fail();
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @TestAnnotationForTesting
  public void formatsUnknownNames() {
    assertEquals("bad", SharedStateBetweenClasses.Formatter.format("bad"));
  }

}
