package org.pitest.mutationtest.build;

import org.junit.Test;
import org.pitest.mutationtest.engine.MutationDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pitest.mutationtest.build.DefaultGrouperTest.createDetails;

public class ClassPackingGrouperTest {

  @Test
  public void createsOneUnitPerThread() {
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(2, 0)
        .groupMutations(null, mutants("a", 3, "b", 2, "c", 2, "d", 1));

    assertThat(actual).hasSize(2);
    assertThat(sizes(actual)).containsExactlyInAnyOrder(4, 4);
  }

  @Test
  public void keepsEachClassInOneUnit() {
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(2, 0)
        .groupMutations(null, mutants("a", 3, "b", 2, "c", 2, "d", 1));

    for (final List<MutationDetails> unit : actual) {
      for (final MutationDetails each : unit) {
        assertThat(actual.stream().filter(u -> u.stream()
            .anyMatch(m -> m.getClassName().equals(each.getClassName())))).hasSize(1);
      }
    }
  }

  @Test
  public void includesEveryMutantOnce() {
    final List<MutationDetails> mutants = mutants("a", 5, "b", 3, "c", 1);
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(4, 0)
        .groupMutations(null, mutants);

    assertThat(actual.stream().flatMap(List::stream).collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(mutants);
  }

  @Test
  public void createsNoMoreUnitsThanClasses() {
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(8, 0)
        .groupMutations(null, mutants("a", 2, "b", 1));

    assertThat(actual).hasSize(2);
  }

  @Test
  public void createsNoUnitsWhenThereAreNoMutants() {
    assertThat(new ClassPackingGrouper(2, 0).groupMutations(null, Collections.emptyList()))
        .isEmpty();
  }

  @Test
  public void usesMoreUnitsWhenAUnitSizeIsSet() {
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(1, 2)
        .groupMutations(null, mutants("a", 2, "b", 2, "c", 2));

    assertThat(actual).hasSize(3);
    assertThat(sizes(actual)).containsOnly(2);
  }

  @Test
  public void splitsClassesLargerThanTheUnitSize() {
    final List<List<MutationDetails>> actual = new ClassPackingGrouper(1, 2)
        .groupMutations(null, mutants("a", 4));

    assertThat(sizes(actual)).containsExactly(2, 2);
  }

  @Test
  public void treatsZeroThreadsAsOne() {
    assertThat(new ClassPackingGrouper(0, 0).groupMutations(null, mutants("a", 1, "b", 1)))
        .hasSize(1);
  }

  private static List<Integer> sizes(List<List<MutationDetails>> units) {
    return units.stream().map(List::size).collect(Collectors.toList());
  }

  private static List<MutationDetails> mutants(Object... classAndCount) {
    final List<MutationDetails> out = new ArrayList<>();
    for (int i = 0; i < classAndCount.length; i += 2) {
      for (int j = 0; j != (Integer) classAndCount[i + 1]; j++) {
        out.add(createDetails((String) classAndCount[i]));
      }
    }
    return out;
  }

}
