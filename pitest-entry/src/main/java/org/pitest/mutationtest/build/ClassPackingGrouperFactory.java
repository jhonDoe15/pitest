package org.pitest.mutationtest.build;

import java.util.Properties;

import org.pitest.classpath.CodeSource;

public class ClassPackingGrouperFactory implements MutationGrouperFactory {

  @Override
  public String description() {
    return "Packs mutants of several classes into each unit";
  }

  @Override
  public MutationGrouper makeFactory(final Properties props,
      final CodeSource codeSource, final int numberOfThreads, final int unitSize) {
    return new ClassPackingGrouper(numberOfThreads, unitSize);
  }

}
