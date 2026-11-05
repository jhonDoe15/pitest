package org.pitest.mutationtest.execute;

import junit.framework.AssertionFailedError;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.classinfo.ClassName;
import org.pitest.classpath.ClassloaderByteArraySource;
import org.pitest.coverage.TestInfo;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationStatusTestPair;
import org.pitest.mutationtest.engine.Mutant;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.mutationtest.environment.ResetEnvironment;
import org.pitest.testapi.Description;
import org.pitest.testapi.ResultCollector;
import org.pitest.testapi.TestUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pitest.mutationtest.LocationMother.aLocation;
import static org.pitest.mutationtest.LocationMother.aMutationId;

public class MutationTestWorkerReuseTest {

  private static final String TEST = "atest";

  private static final ClassName A = ClassName.fromString("com.example.A");
  private static final ClassName B = ClassName.fromString("com.example.B");
  private static final ClassName C = ClassName.fromString("com.example.C");

  @Mock
  private ClassLoader loader;

  @Mock
  private Mutater mutater;

  @Mock
  private HotSwap hotswap;

  @Mock
  private TimeOutDecoratedTestSource testSource;

  @Mock
  private Reporter reporter;

  @Mock
  private ResetEnvironment reset;

  private final Map<ClassName, byte[]> originals = new HashMap<>();
  private final Map<ClassName, byte[]> active = new HashMap<>();
  private final List<TestUnit> tests = new ArrayList<>();
  private final Map<MutationIdentifier, byte[]> mutantBytes = new HashMap<>();

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    for (ClassName each : Arrays.asList(A, B, C)) {
      this.originals.put(each, new byte[] { 0 });
    }
    when(this.hotswap.insertClass(any(ClassName.class), any(ClassLoader.class), any(byte[].class)))
        .thenAnswer(a -> {
          this.active.put(a.getArgument(0), a.getArgument(2));
          this.latest = a.getArgument(2);
          return true;
        });
    when(this.testSource.translateTests(any())).thenAnswer(a -> this.tests);
  }

  @After
  public void clearProbe() {
    System.clearProperty(ReachProbe.PROPERTY);
  }

  @Test
  public void runsMutantsOfOneClassInTheOrderGivenWhenReuseIsOff() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    final MutationDetails a2 = mutant(A, 3);
    this.tests.add(test("passes", any -> false));

    new MutationTestWorker(this.hotswap, this.mutater, this.loader, this.reset, false)
        .run(Arrays.asList(a1, b1, a2), this.reporter, this.testSource);

    final InOrder order = inOrder(this.reporter);
    order.verify(this.reporter).describe(a1.getId());
    order.verify(this.reporter).describe(b1.getId());
    order.verify(this.reporter).describe(a2.getId());
    verify(this.hotswap, never()).insertClass(any(ClassName.class), any(ClassLoader.class),
        same(this.originals.get(A)));
  }

  @Test
  public void runsMutantsOneClassAtATimeKeepingTheFirstMutantFirst() throws Exception {
    final MutationDetails b1 = mutant(B, 1);
    final MutationDetails a1 = mutant(A, 2);
    final MutationDetails b2 = mutant(B, 3);
    this.tests.add(test("passes", any -> false));

    worker(false).run(Arrays.asList(b1, a1, b2), this.reporter, this.testSource);

    final InOrder order = inOrder(this.reporter);
    order.verify(this.reporter).describe(b1.getId());
    order.verify(this.reporter).describe(b2.getId());
    order.verify(this.reporter).describe(a1.getId());
  }

  @Test
  public void restoresEachClassBeforeTheNextClassRuns() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    final List<String> seenByB = new ArrayList<>();
    this.tests.add(test("records", any -> {
      seenByB.add(this.active.get(A) == this.originals.get(A) ? "original" : "mutant");
      return false;
    }));

    worker(false).run(Arrays.asList(a1, b1), this.reporter, this.testSource);

    assertThat(seenByB).containsExactly("mutant", "original");
    final InOrder order = inOrder(this.hotswap, this.reset);
    order.verify(this.hotswap).insertClass(eq(A), any(ClassLoader.class), same(bytesOf(a1)));
    order.verify(this.hotswap).insertClass(eq(A), any(ClassLoader.class), same(this.originals.get(A)));
    order.verify(this.reset).resetFor(org.mockito.ArgumentMatchers.argThat(
        m -> m.getBytes() == this.originals.get(A)));
    order.verify(this.hotswap).insertClass(eq(B), any(ClassLoader.class), same(bytesOf(b1)));
  }

  @Test
  public void sendsBackKillsByATestThatAlsoFailsWithoutTheMutantAndEndsTheMinion() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    final MutationDetails b2 = mutant(B, 3);
    final MutationDetails c1 = mutant(C, 4);
    this.tests.add(test("polluted", any -> true));

    worker(true).run(Arrays.asList(a1, b1, b2, c1), this.reporter, this.testSource);

    verify(this.reporter).report(eq(a1.getId()), any(MutationStatusTestPair.class));
    verify(this.reporter).retry(b1.getId());
    verify(this.reporter).retry(b2.getId());
    verify(this.reporter, never()).describe(c1.getId());
  }

  @Test
  public void sendsBackSurvivalsDecidedByATestThatFailsWithoutTheMutant() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    final MutationDetails b2 = mutant(B, 3);
    this.tests.add(test("polluted", m -> m != bytesOf(b2)));

    worker(true).run(Arrays.asList(a1, b1, b2), this.reporter, this.testSource);

    verify(this.reporter).retry(b1.getId());
    verify(this.reporter).retry(b2.getId());
  }

  @Test
  public void keepsKillsByATestThatPassesWithoutTheMutant() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    final MutationDetails c1 = mutant(C, 3);
    this.tests.add(test("detects", isMutant()));

    worker(true).run(Arrays.asList(a1, b1, c1), this.reporter, this.testSource);

    verify(this.reporter, never()).retry(any());
    verify(this.reporter).report(eq(b1.getId()), statusIs(DetectionStatus.KILLED));
    verify(this.reporter).report(eq(c1.getId()), statusIs(DetectionStatus.KILLED));
  }

  @Test
  public void neverSendsBackMutantsOfTheFirstClass() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails a2 = mutant(A, 2);
    this.tests.add(test("polluted", any -> true));

    worker(true).run(Arrays.asList(a1, a2), this.reporter, this.testSource);

    verify(this.reporter, never()).retry(any());
    verify(this.reporter).report(eq(a1.getId()), statusIs(DetectionStatus.KILLED));
    verify(this.reporter).report(eq(a2.getId()), statusIs(DetectionStatus.KILLED));
  }

  @Test
  public void doesNotSendBackAnythingWithoutGuards() throws Exception {
    final MutationDetails a1 = mutant(A, 1);
    final MutationDetails b1 = mutant(B, 2);
    this.tests.add(test("polluted", any -> true));

    worker(false).run(Arrays.asList(a1, b1), this.reporter, this.testSource);

    verify(this.reporter, never()).retry(any());
    verify(this.reporter).report(eq(b1.getId()), statusIs(DetectionStatus.KILLED));
  }

  @Test
  public void sendsBackResultsWhenTheMutatedCodeDidNotRun() throws Exception {
    useRealBytes(A);
    useRealBytes(B);
    final MutationDetails a1 = realMutant(A, 1);
    final MutationDetails b1 = realMutant(B, 2);
    this.tests.add(test("does not call the code", any -> false));

    worker(true).run(Arrays.asList(a1, b1), this.reporter, this.testSource);

    verify(this.reporter).report(eq(a1.getId()), statusIs(DetectionStatus.SURVIVED));
    verify(this.reporter).retry(b1.getId());
  }

  @Test
  public void keepsResultsWhenTheMutatedCodeRan() throws Exception {
    useRealBytes(A);
    useRealBytes(B);
    final MutationDetails a1 = realMutant(A, 1);
    final MutationDetails b1 = realMutant(B, 2);
    this.tests.add(test("calls the code", any -> {
      System.setProperty(ReachProbe.PROPERTY, "1");
      return false;
    }));

    worker(true).run(Arrays.asList(a1, b1), this.reporter, this.testSource);

    verify(this.reporter, never()).retry(any());
    verify(this.reporter).report(eq(b1.getId()), statusIs(DetectionStatus.SURVIVED));
  }

  private MutationTestWorker worker(boolean guards) {
    final ClassByteArraySource source = name -> Optional.ofNullable(
        this.originals.get(ClassName.fromString(name)));
    return new MutationTestWorker(this.hotswap, this.mutater, this.loader, this.reset, false,
        source, guards);
  }

  private Predicate<byte[]> isMutant() {
    return bytes -> !this.originals.containsValue(bytes);
  }

  private static MutationStatusTestPair statusIs(DetectionStatus status) {
    return org.mockito.ArgumentMatchers.argThat(r -> r.getStatus() == status);
  }

  private byte[] bytesOf(MutationDetails mutant) {
    return this.mutantBytes.get(mutant.getId());
  }

  // a test that fails when the given check on the bytes of the class most recently
  // inserted is true
  private TestUnit test(String purpose, Predicate<byte[]> failsWhen) {
    return new TestUnit() {
      @Override
      public void execute(ResultCollector rc) {
        rc.notifyStart(getDescription());
        final byte[] latest = MutationTestWorkerReuseTest.this.latest;
        if (failsWhen.test(latest)) {
          rc.notifyEnd(getDescription(), new AssertionFailedError());
        } else {
          rc.notifyEnd(getDescription());
        }
      }

      @Override
      public Description getDescription() {
        return new Description(TEST);
      }
    };
  }

  private byte[] latest;

  private MutationDetails mutant(ClassName clazz, int index) {
    return mutant(clazz, index, new byte[] { (byte) index }, "method", "()V");
  }

  private MutationDetails mutant(ClassName clazz, int index, byte[] bytes, String method,
      String desc) {
    final MutationIdentifier id = aMutationId()
        .withLocation(aLocation().withClass(clazz).withMethod(method).withMethodDescription(desc))
        .withIndex(index).withMutator("mutator").build();
    final MutationDetails md = new MutationDetails(id, "sourceFile", "desc", 42, 0);
    md.addTestsInOrder(Collections.singletonList(new TestInfo(null, TEST, 0, Optional.empty(), 0)));
    when(this.mutater.getMutation(md.getId())).thenReturn(new Mutant(md, bytes));
    this.mutantBytes.put(md.getId(), bytes);
    return md;
  }

  private void useRealBytes(ClassName clazz) {
    this.originals.put(clazz, ClassloaderByteArraySource.fromContext()
        .getBytes(Sample.class.getName()).get());
  }

  private MutationDetails realMutant(ClassName clazz, int index) {
    return mutant(clazz, index, returnTwo(this.originals.get(clazz)), "value", "()I");
  }

  private static byte[] returnTwo(byte[] original) {
    final ClassNode node = new ClassNode();
    new ClassReader(original).accept(node, 0);
    for (MethodNode m : node.methods) {
      if (m.name.equals("value")) {
        for (AbstractInsnNode n : m.instructions) {
          if (n.getOpcode() == Opcodes.ICONST_1) {
            m.instructions.set(n, new InsnNode(Opcodes.ICONST_2));
            break;
          }
        }
      }
    }
    final ClassWriter w = new ClassWriter(0);
    node.accept(w);
    return w.toByteArray();
  }

  public static class Sample {
    public int value() {
      return 1;
    }
  }

}
