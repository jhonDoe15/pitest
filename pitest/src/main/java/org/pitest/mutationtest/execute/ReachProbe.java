package org.pitest.mutationtest.execute;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.Mutant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records whether the code changed by a mutant ran.
 *
 * When a minion analyses mutants of several classes, code that runs once per jvm (a static
 * initialiser reached from an earlier class, a lazily filled cache, a bean in a cached
 * Spring context) may already have run before the mutant was inserted. The tests then pass
 * or fail without ever running the mutated code, and the result says nothing about the
 * mutant.
 *
 * The probe is a call to System.setProperty inserted just before the first instruction
 * that differs from the original method. A jdk class is used so the call resolves from any
 * classloader. If a test clears system properties, the probe looks missed and the only
 * cost is that the mutant is analysed again.
 */
final class ReachProbe {

  static final String PROPERTY = "pitest.mutant.reached";

  private final byte[] bytes;
  private boolean active;

  private ReachProbe(byte[] bytes) {
    this.bytes = bytes;
    this.active = bytes != null;
  }

  static ReachProbe none() {
    return new ReachProbe(null);
  }

  static ReachProbe forMutant(byte[] original, Mutant mutant) {
    if (original == null) {
      return none();
    }
    try {
      final Location location = mutant.getDetails().getId().getLocation();
      return new ReachProbe(instrument(original, mutant.getBytes(),
          location.getMethodName(), location.getMethodDesc()));
    } catch (final RuntimeException ex) {
      return none();
    }
  }

  boolean isActive() {
    return this.active;
  }

  byte[] bytes() {
    return this.bytes;
  }

  void disable() {
    this.active = false;
  }

  void reset() {
    if (this.active) {
      System.clearProperty(PROPERTY);
    }
  }

  boolean wasMissed(DetectionStatus status) {
    return this.active
        && (status == DetectionStatus.SURVIVED || status == DetectionStatus.KILLED)
        && System.getProperty(PROPERTY) == null;
  }

  static byte[] instrument(byte[] original, byte[] mutated, String method, String desc) {
    final MethodNode originalMethod = find(read(original), method, desc);
    final ClassNode mutatedClass = read(mutated);
    final MethodNode mutatedMethod = find(mutatedClass, method, desc);
    if (originalMethod == null || mutatedMethod == null) {
      return null;
    }

    final List<AbstractInsnNode> before = instructions(originalMethod.instructions);
    final List<AbstractInsnNode> after = instructions(mutatedMethod.instructions);
    int i = 0;
    while (i < before.size() && i < after.size() && same(before.get(i), after.get(i))) {
      i++;
    }
    if (i >= after.size()) {
      return null;
    }

    mutatedMethod.instructions.insertBefore(after.get(i), probe());
    final ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    mutatedClass.accept(w);
    return w.toByteArray();
  }

  private static InsnList probe() {
    final InsnList probe = new InsnList();
    probe.add(new LdcInsnNode(PROPERTY));
    probe.add(new LdcInsnNode("1"));
    probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false));
    probe.add(new InsnNode(Opcodes.POP));
    return probe;
  }

  private static ClassNode read(byte[] bytes) {
    final ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  private static MethodNode find(ClassNode clazz, String name, String desc) {
    for (final MethodNode each : clazz.methods) {
      if (each.name.equals(name) && each.desc.equals(desc)) {
        return each;
      }
    }
    return null;
  }

  // labels, line numbers and frames are left out, they do not run
  private static List<AbstractInsnNode> instructions(InsnList list) {
    final List<AbstractInsnNode> out = new ArrayList<>();
    for (AbstractInsnNode n = list.getFirst(); n != null; n = n.getNext()) {
      if (n.getOpcode() >= 0) {
        out.add(n);
      }
    }
    return out;
  }

  private static boolean same(AbstractInsnNode x, AbstractInsnNode y) {
    if (x.getOpcode() != y.getOpcode() || x.getType() != y.getType()) {
      return false;
    }
    if (x instanceof VarInsnNode) {
      return ((VarInsnNode) x).var == ((VarInsnNode) y).var;
    }
    if (x instanceof IntInsnNode) {
      return ((IntInsnNode) x).operand == ((IntInsnNode) y).operand;
    }
    if (x instanceof LdcInsnNode) {
      return Objects.equals(((LdcInsnNode) x).cst, ((LdcInsnNode) y).cst);
    }
    if (x instanceof IincInsnNode) {
      return ((IincInsnNode) x).var == ((IincInsnNode) y).var
          && ((IincInsnNode) x).incr == ((IincInsnNode) y).incr;
    }
    if (x instanceof MethodInsnNode) {
      final MethodInsnNode p = (MethodInsnNode) x;
      final MethodInsnNode q = (MethodInsnNode) y;
      return p.owner.equals(q.owner) && p.name.equals(q.name) && p.desc.equals(q.desc);
    }
    if (x instanceof FieldInsnNode) {
      final FieldInsnNode p = (FieldInsnNode) x;
      final FieldInsnNode q = (FieldInsnNode) y;
      return p.owner.equals(q.owner) && p.name.equals(q.name) && p.desc.equals(q.desc);
    }
    if (x instanceof TypeInsnNode) {
      return ((TypeInsnNode) x).desc.equals(((TypeInsnNode) y).desc);
    }
    return true;
  }

}
