/*
 * Copyright 2010 Henry Coles
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest.execute;

import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationStatusTestPair;
import org.pitest.mutationtest.environment.ResetEnvironment;
import org.pitest.mutationtest.engine.Mutant;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.testapi.Description;
import org.pitest.testapi.TestResult;
import org.pitest.testapi.TestUnit;
import org.pitest.testapi.execute.Container;
import org.pitest.testapi.execute.ExitingResultCollector;
import org.pitest.testapi.execute.MultipleTestGroup;
import org.pitest.testapi.execute.Pitest;
import org.pitest.testapi.execute.containers.ConcreteResultCollector;
import org.pitest.testapi.execute.containers.UnContainer;
import org.pitest.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.pitest.util.Unchecked.translateCheckedException;

public class MutationTestWorker {

  private static final Logger                               LOG   = Log
      .getLogger();

  // micro optimise debug logging
  private static final boolean                              DEBUG = LOG
      .isLoggable(Level.FINE);

  private final Mutater                                     mutater;
  private final ClassLoader                                 loader;
  private final HotSwap                                     hotswap;
  private final boolean                                     fullMutationMatrix;

  private final ResetEnvironment                            reset;

  // when set, mutants of more than one class are analysed in this jvm and
  // each class is returned to these bytes once its mutants are done
  private final ClassByteArraySource                        originals;
  private final boolean                                     guards;


  public MutationTestWorker(HotSwap hotswap,
                            Mutater mutater,
                            ClassLoader loader,
                            ResetEnvironment reset,
                            boolean fullMutationMatrix) {
    this(hotswap, mutater, loader, reset, fullMutationMatrix, null, false);
  }

  /**
   * @param originals source of unmutated bytecode. If not null, the range may hold
   *                  mutants of several classes. They are analysed one class at a time
   *                  and each class is restored to its original bytes before the next.
   * @param guards    when analysing several classes, redo in a fresh minion any result
   *                  that state left in the jvm by an earlier class may have changed
   */
  public MutationTestWorker(HotSwap hotswap,
                            Mutater mutater,
                            ClassLoader loader,
                            ResetEnvironment reset,
                            boolean fullMutationMatrix,
                            ClassByteArraySource originals,
                            boolean guards) {
    this.loader = loader;
    this.reset = reset;
    this.mutater = mutater;
    this.hotswap = hotswap;
    this.fullMutationMatrix = fullMutationMatrix;
    this.originals = originals;
    this.guards = guards && originals != null;
  }

  protected void run(final Collection<MutationDetails> range, final Reporter r,
      final TimeOutDecoratedTestSource testSource) throws IOException {

    if (this.originals == null) {
      for (final MutationDetails mutation : range) {
        processTimed(r, testSource, mutation, false);
      }
      return;
    }

    runOneClassAtATime(range, r, testSource);
  }

  private void runOneClassAtATime(final Collection<MutationDetails> range,
      final Reporter r, final TimeOutDecoratedTestSource testSource) {
    // Keep the first mutant first. The parent assumes it is the one that runs
    // first, and the guards never send back mutants of the first class, so every
    // minion finishes at least one mutant.
    final Map<ClassName, List<MutationDetails>> byClass = new LinkedHashMap<>();
    for (final MutationDetails each : range) {
      byClass.computeIfAbsent(each.getClassName(), k -> new ArrayList<>()).add(each);
    }

    final KillRecheck recheck = new KillRecheck(this::failsWithoutMutant);
    boolean firstClass = true;
    for (final List<MutationDetails> mutants : byClass.values()) {
      final boolean guarded = this.guards && !firstClass;
      for (final MutationDetails mutation : mutants) {
        final MutationStatusTestPair result = processTimed(r, testSource, mutation, guarded);
        if (guarded && result != null) {
          recheck.record(mutation, result);
        }
      }

      restoreOriginal(mutants.get(mutants.size() - 1));

      final Collection<MutationIdentifier> polluted = recheck.findPollutedResults(testSource);
      if (!polluted.isEmpty()) {
        LOG.fine(() -> "A test that killed a mutant also fails without it. Ending minion so "
            + polluted.size() + " mutants it decided are analysed again in a fresh one");
        polluted.forEach(r::retry);
        return;
      }
      firstClass = false;
    }
  }

  private void restoreOriginal(MutationDetails lastAnalysed) {
    final ClassName clazz = lastAnalysed.getClassName();
    final byte[] original = this.originals.getBytes(clazz.asJavaName())
        .orElseThrow(() -> new IllegalStateException("No bytecode found for " + clazz));
    this.hotswap.insertClass(clazz, this.loader, original);
    this.reset.resetFor(new Mutant(lastAnalysed, original));
    if (DEBUG) {
      LOG.fine("restored original bytecode of " + clazz);
    }
  }

  private boolean failsWithoutMutant(TestUnit test) {
    final CheckTestHasFailedResultListener listener = new CheckTestHasFailedResultListener(false);
    new Pitest(listener).run(createNewContainer(), createEarlyExitTestGroup(Collections.singletonList(test)));
    return !listener.getFailingTests().isEmpty();
  }

  private MutationStatusTestPair processTimed(Reporter r,
      TimeOutDecoratedTestSource testSource, MutationDetails mutation, boolean probe) {
    if (DEBUG) {
      LOG.fine("Running mutation " + mutation);
    }
    final long t0 = System.nanoTime();
    final MutationStatusTestPair result = processMutation(r, testSource, mutation, probe);
    if (DEBUG) {
      LOG.fine("processed mutation in " + NANOSECONDS.toMillis(System.nanoTime() - t0)
          + " ms.");
    }
    return result;
  }

  /**
   * @return the reported result, or null if the mutant was sent back to be analysed
   * in a fresh minion
   */
  private MutationStatusTestPair processMutation(Reporter r,
                               TimeOutDecoratedTestSource testSource,
                               MutationDetails mutationDetails,
                               boolean probe) {

    final MutationIdentifier mutationId = mutationDetails.getId();
    final Mutant mutatedClass = this.mutater.getMutation(mutationId);

    reset.resetFor(mutatedClass);

    if (DEBUG) {
      LOG.fine("mutating method " + mutatedClass.getDetails().getMethod());
    }
    final List<TestUnit> relevantTests = testSource
        .translateTests(mutationDetails.getTestsInOrder());

    r.describe(mutationId);

    final ReachProbe reach = probe ? ReachProbe.forMutant(original(mutationDetails), mutatedClass)
        : ReachProbe.none();

    final MutationStatusTestPair mutationDetected = handleMutation(
        mutationDetails, mutatedClass, relevantTests, reach);

    if (reach.wasMissed(mutationDetected.getStatus())) {
      LOG.fine(() -> "Mutated code of " + mutationId + " did not run."
          + " Sending it back to be analysed in a fresh minion");
      r.retry(mutationId);
      return null;
    }

    r.report(mutationId, mutationDetected);
    if (DEBUG) {
      LOG.fine("Mutation " + mutationId + " detected = " + mutationDetected);
    }
    return mutationDetected;
  }

  private byte[] original(MutationDetails mutation) {
    return this.originals.getBytes(mutation.getClassName().asJavaName()).orElse(null);
  }

  private MutationStatusTestPair handleMutation(
      final MutationDetails mutationId, final Mutant mutatedClass,
      final List<TestUnit> relevantTests, final ReachProbe reach) {
    final MutationStatusTestPair mutationDetected;
    if ((relevantTests == null) || relevantTests.isEmpty()) {
      LOG.log(Level.WARNING, "No test coverage for mutation " + mutationId + " in " + mutatedClass.getDetails().getMethod()
              + ". This should have been detected in the outer process so treating as an error");
      mutationDetected =  MutationStatusTestPair.notAnalysed(0, DetectionStatus.RUN_ERROR, Collections.emptyList());
    } else {
      mutationDetected = handleCoveredMutation(mutationId, mutatedClass,
          relevantTests, reach);

    }
    return mutationDetected;
  }

  private MutationStatusTestPair handleCoveredMutation(
      final MutationDetails mutationId, final Mutant mutatedClass,
      final List<TestUnit> relevantTests, final ReachProbe reach) {
    final MutationStatusTestPair mutationDetected;
    if (DEBUG) {
      LOG.fine(relevantTests.size() + " relevant test for "
          + mutatedClass.getDetails().getMethod());
    }

    final Container c = createNewContainer();
    final long t0 = System.nanoTime();

    if (insert(mutationId.getClassName(), mutatedClass, reach)) {
      if (DEBUG) {
        LOG.fine("replaced class with mutant in "
            + NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");
      }

      reach.reset();
      mutationDetected = doTestsDetectMutation(c, relevantTests);
    } else {
      LOG.warning("Mutation " + mutationId + " was not viable ");
      mutationDetected = MutationStatusTestPair.notAnalysed(0,
          DetectionStatus.NON_VIABLE, relevantTests.stream()
              .map(t -> t.getDescription().getQualifiedName())
              .collect(Collectors.toList()));
    }
    return mutationDetected;
  }

  private boolean insert(ClassName clazz, Mutant mutatedClass, ReachProbe reach) {
    if (reach.isActive()) {
      if (this.hotswap.insertClass(clazz, this.loader, reach.bytes())) {
        return true;
      }
      LOG.fine(() -> "Could not insert reach probe into " + mutatedClass.getDetails().getId()
          + ", analysing mutant without it");
      reach.disable();
    }
    return this.hotswap.insertClass(clazz, this.loader, mutatedClass.getBytes());
  }

  private static Container createNewContainer() {
    return new UnContainer() {
      @Override
      public List<TestResult> execute(final TestUnit group) {
        final Collection<TestResult> results = new ConcurrentLinkedDeque<>();
        final ExitingResultCollector rc = new ExitingResultCollector(
            new ConcreteResultCollector(results));
        group.execute(rc);
        return new ArrayList<>(results);
      }
    };
  }



  @Override
  public String toString() {
    return "MutationTestWorker [mutater=" + this.mutater + ", loader="
        + this.loader + ", hotswap=" + this.hotswap + "]";
  }

  private MutationStatusTestPair doTestsDetectMutation(final Container c,
      final List<TestUnit> tests) {
    try {
      final CheckTestHasFailedResultListener listener = new CheckTestHasFailedResultListener(fullMutationMatrix);

      final Pitest pit = new Pitest(listener);

      if (this.fullMutationMatrix) {
        pit.run(c, tests);
      } else {
        pit.run(c, createEarlyExitTestGroup(tests));
      }

      return createStatusTestPair(listener, tests);
    } catch (final Exception ex) {
      throw translateCheckedException(ex);
    }

  }

  private MutationStatusTestPair createStatusTestPair(
      final CheckTestHasFailedResultListener listener, List<TestUnit> relevantTests) {
    List<String> failingTests = listener.getFailingTests().stream()
        .map(Description::getQualifiedName).collect(Collectors.toList());
    List<String> succeedingTests = listener.getSucceedingTests().stream()
        .map(Description::getQualifiedName).collect(Collectors.toList());
    List<String> coveredTests = relevantTests.stream()
        .map(t -> t.getDescription().getQualifiedName()).collect(Collectors.toList());

    return new MutationStatusTestPair(listener.getNumberOfTestsRun(),
        listener.status(), failingTests, succeedingTests, coveredTests);
  }

  private List<TestUnit> createEarlyExitTestGroup(final List<TestUnit> tests) {
    return Collections.singletonList(new MultipleTestGroup(tests));
  }

}
