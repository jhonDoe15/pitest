package org.pitest.mutationtest.build;

import org.pitest.classinfo.ClassName;
import org.pitest.functional.FCollection;
import org.pitest.mutationtest.engine.MutationDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Groups the mutants of several classes into each unit, for minions that restore each
 * class before moving to the next (reuseMinions).
 *
 * Classes are packed, largest first, into the unit holding the fewest mutants so far.
 * There is one unit per thread, so tests that are expensive to start (for example Spring
 * contexts) start once per thread rather than once per class. If mutationUnitSize is set,
 * classes are split to that size and there are enough units for each to hold about that
 * many mutants.
 */
public class ClassPackingGrouper implements MutationGrouper {

  private final int numberOfThreads;
  private final int unitSize;

  public ClassPackingGrouper(final int numberOfThreads, final int unitSize) {
    this.numberOfThreads = Math.max(1, numberOfThreads);
    this.unitSize = unitSize;
  }

  @Override
  public List<List<MutationDetails>> groupMutations(
      final Collection<ClassName> codeClasses,
      final Collection<MutationDetails> mutations) {
    final Map<ClassName, Collection<MutationDetails>> bucketed = new TreeMap<>(FCollection
        .bucket(mutations, MutationDetails::getClassName));

    final List<List<MutationDetails>> pieces = new ArrayList<>();
    for (final Collection<MutationDetails> each : bucketed.values()) {
      if (this.unitSize > 0) {
        pieces.addAll(FCollection.splitToLength(this.unitSize, each));
      } else {
        pieces.add(new ArrayList<>(each));
      }
    }
    pieces.sort(Comparator.comparingInt(List<MutationDetails>::size).reversed());

    final List<List<MutationDetails>> units = new ArrayList<>();
    for (int i = 0; i < Math.min(numberOfUnits(mutations.size()), pieces.size()); i++) {
      units.add(new ArrayList<>());
    }
    for (final List<MutationDetails> piece : pieces) {
      smallest(units).addAll(piece);
    }
    return units;
  }

  private int numberOfUnits(int mutants) {
    if (this.unitSize > 0) {
      return Math.max(this.numberOfThreads, (mutants + this.unitSize - 1) / this.unitSize);
    }
    return this.numberOfThreads;
  }

  private static List<MutationDetails> smallest(List<List<MutationDetails>> units) {
    List<MutationDetails> smallest = units.get(0);
    for (final List<MutationDetails> each : units) {
      if (each.size() < smallest.size()) {
        smallest = each;
      }
    }
    return smallest;
  }

}
