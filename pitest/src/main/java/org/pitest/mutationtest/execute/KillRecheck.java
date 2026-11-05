package org.pitest.mutationtest.execute;

import org.pitest.coverage.TestInfo;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationStatusTestPair;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.testapi.TestUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * When a minion analyses mutants of several classes, a test may fail because of state
 * that an earlier class's mutants or tests left in the jvm (for example an entry in a
 * static registry), rather than because of the current mutant.
 *
 * Once a class's mutants are done and its original bytecode is back, each distinct test
 * that killed one of its mutants is run once more. A test that still fails was not failing
 * because of the mutant. The kills it reported cannot be trusted, and neither can the
 * survivals it took part in, since the same state may have made it pass. They are redone in
 * a fresh minion.
 */
final class KillRecheck {

  private final Predicate<TestUnit> failsWithoutMutant;
  private final Map<String, TestInfo> killers = new LinkedHashMap<>();
  private final Map<String, List<MutationIdentifier>> resultsByTest = new HashMap<>();

  KillRecheck(Predicate<TestUnit> failsWithoutMutant) {
    this.failsWithoutMutant = failsWithoutMutant;
  }

  void record(MutationDetails mutation, MutationStatusTestPair result) {
    if (result.getStatus() == DetectionStatus.KILLED) {
      for (final String killer : result.getKillingTests()) {
        final Optional<TestInfo> test = mutation.getTestsInOrder().stream()
            .filter(t -> t.getName().equals(killer))
            .findFirst();
        if (test.isPresent()) {
          this.killers.putIfAbsent(killer, test.get());
          resultFrom(killer, mutation);
        }
      }
    } else if (result.getStatus() == DetectionStatus.SURVIVED) {
      for (final TestInfo each : mutation.getTestsInOrder()) {
        resultFrom(each.getName(), mutation);
      }
    }
  }

  private void resultFrom(String test, MutationDetails mutation) {
    this.resultsByTest.computeIfAbsent(test, k -> new ArrayList<>()).add(mutation.getId());
  }

  /**
   * Reruns each recorded killing test with no mutant active and forgets what was recorded.
   *
   * @return the mutants killed or survived by a test that also fails without a mutant
   */
  Collection<MutationIdentifier> findPollutedResults(TimeOutDecoratedTestSource tests) {
    final Set<MutationIdentifier> polluted = new LinkedHashSet<>();
    for (final Map.Entry<String, TestInfo> each : this.killers.entrySet()) {
      for (final TestUnit unit : tests.translateTests(Collections.singletonList(each.getValue()))) {
        if (this.failsWithoutMutant.test(unit)) {
          polluted.addAll(this.resultsByTest.get(each.getKey()));
        }
      }
    }
    this.killers.clear();
    this.resultsByTest.clear();
    return polluted;
  }

}
