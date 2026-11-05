package org.pitest.mutationtest.execute;

import org.junit.Test;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationStatusTestPair;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.util.Id;
import org.pitest.util.SafeDataInputStream;
import org.pitest.util.SafeDataOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pitest.mutationtest.LocationMother.aMutationId;

public class ReceiveTest {

  private final Map<MutationIdentifier, MutationStatusTestPair> results = new HashMap<>();
  private final Receive testee = new Receive(this.results);
  private final MutationIdentifier mutant = aMutationId().withIndex(0).withMutator("foo").build();

  @Test
  public void recordsReportedStatus() {
    final MutationStatusTestPair killed = new MutationStatusTestPair(1, DetectionStatus.KILLED, "atest");
    this.testee.apply(Id.REPORT, streamOf(this.mutant, killed));

    assertThat(this.results.get(this.mutant)).isEqualTo(killed);
  }

  @Test
  public void marksRetriedMutantAsNotStartedSoItIsAnalysedAgain() {
    this.testee.apply(Id.REPORT, streamOf(this.mutant,
        new MutationStatusTestPair(1, DetectionStatus.KILLED, "atest")));
    this.testee.apply(Id.RETRY, streamOf(this.mutant));

    assertThat(this.results.get(this.mutant).getStatus()).isEqualTo(DetectionStatus.NOT_STARTED);
  }

  private static SafeDataInputStream streamOf(Serializable... values) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final SafeDataOutputStream out = new SafeDataOutputStream(bytes);
    for (final Serializable each : values) {
      out.write(each);
    }
    out.flush();
    return new SafeDataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
  }

}
