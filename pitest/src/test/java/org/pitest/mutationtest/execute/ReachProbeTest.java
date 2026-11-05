package org.pitest.mutationtest.execute;

import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.classpath.ClassloaderByteArraySource;
import org.pitest.mutationtest.DetectionStatus;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

public class ReachProbeTest {

  @After
  public void clearProbe() {
    System.clearProperty(ReachProbe.PROPERTY);
  }

  @Test
  public void recordsThatTheMutatedCodeRan() throws Exception {
    final byte[] original = bytesOf(Sample.class);
    final byte[] mutant = replaceInstruction(original, "branch", Opcodes.ICONST_2, Opcodes.ICONST_3);

    final byte[] probed = ReachProbe.instrument(original, mutant, "branch", "(Z)I");

    assertThat(call(probed, "branch", false)).isEqualTo(1);
    assertThat(System.getProperty(ReachProbe.PROPERTY)).isNull();

    assertThat(call(probed, "branch", true)).isEqualTo(3);
    assertThat(System.getProperty(ReachProbe.PROPERTY)).isEqualTo("1");
  }

  @Test
  public void returnsNothingWhenTheMethodIsUnchanged() {
    final byte[] original = bytesOf(Sample.class);
    assertThat(ReachProbe.instrument(original, original, "branch", "(Z)I")).isNull();
  }

  @Test
  public void returnsNothingWhenTheMethodIsMissing() {
    final byte[] original = bytesOf(Sample.class);
    assertThat(ReachProbe.instrument(original, original, "missing", "()V")).isNull();
  }

  @Test
  public void reportsAMissOnlyForSurvivorsAndKillsWhoseCodeDidNotRun() {
    final byte[] original = bytesOf(Sample.class);
    final byte[] mutant = replaceInstruction(original, "branch", Opcodes.ICONST_2, Opcodes.ICONST_3);
    final ReachProbe probe = ReachProbe.forMutant(original,
        new org.pitest.mutationtest.engine.Mutant(MutationDetailsForSample.branch(), mutant));

    assertThat(probe.isActive()).isTrue();
    probe.reset();
    assertThat(probe.wasMissed(DetectionStatus.SURVIVED)).isTrue();
    assertThat(probe.wasMissed(DetectionStatus.KILLED)).isTrue();
    assertThat(probe.wasMissed(DetectionStatus.TIMED_OUT)).isFalse();

    System.setProperty(ReachProbe.PROPERTY, "1");
    assertThat(probe.wasMissed(DetectionStatus.SURVIVED)).isFalse();
  }

  @Test
  public void isInactiveWhenTheBytesCannotBeRead() {
    final ReachProbe probe = ReachProbe.forMutant(new byte[] { 1 },
        new org.pitest.mutationtest.engine.Mutant(MutationDetailsForSample.branch(), new byte[] { 2 }));
    assertThat(probe.isActive()).isFalse();
    assertThat(probe.wasMissed(DetectionStatus.SURVIVED)).isFalse();
  }

  @Test
  public void neverMissesWhenNoProbeIsUsed() {
    final ReachProbe probe = ReachProbe.none();
    probe.reset();
    assertThat(probe.wasMissed(DetectionStatus.SURVIVED)).isFalse();
  }

  private static int call(byte[] bytes, String method, boolean arg) throws Exception {
    final Class<?> clazz = new DefiningLoader().define(Sample.class.getName(), bytes);
    final Method m = clazz.getMethod(method, boolean.class);
    return (int) m.invoke(clazz.getDeclaredConstructor().newInstance(), arg);
  }

  private static byte[] bytesOf(Class<?> clazz) {
    return ClassloaderByteArraySource.fromContext().getBytes(clazz.getName()).get();
  }

  static byte[] replaceInstruction(byte[] original, String method, int from, int to) {
    final ClassNode node = new ClassNode();
    new ClassReader(original).accept(node, 0);
    for (MethodNode m : node.methods) {
      if (m.name.equals(method)) {
        for (AbstractInsnNode n : m.instructions) {
          if (n.getOpcode() == from) {
            m.instructions.set(n, new InsnNode(to));
            break;
          }
        }
      }
    }
    final ClassWriter w = new ClassWriter(0);
    node.accept(w);
    return w.toByteArray();
  }

  private static class DefiningLoader extends ClassLoader {
    DefiningLoader() {
      super(ReachProbeTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  public static class Sample {
    public int branch(boolean b) {
      if (b) {
        return 2;
      }
      return 1;
    }
  }

  private static class MutationDetailsForSample {
    static org.pitest.mutationtest.engine.MutationDetails branch() {
      return new org.pitest.mutationtest.engine.MutationDetails(
          org.pitest.mutationtest.LocationMother.aMutationId()
              .withLocation(org.pitest.mutationtest.LocationMother.aLocation(Sample.class.getName())
                  .withMethod("branch").withMethodDescription("(Z)I"))
              .withIndex(1).withMutator("mutator").build(),
          "file", "desc", 1, 0);
    }
  }

}
