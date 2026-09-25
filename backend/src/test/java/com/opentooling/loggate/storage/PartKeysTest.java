package com.opentooling.loggate.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.export.OutputFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartKeysTest {

  private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void namesAPartForWhatItHolds() {
    assertThat(PartKeys.part(JOB, 7, OutputFormat.JSON))
        .isEqualTo("jobs/" + JOB + "/parts/000007.jsonl.gz");
    assertThat(PartKeys.part(JOB, 7, OutputFormat.RAW))
        .isEqualTo("jobs/" + JOB + "/parts/000007.log.gz");
    // JSON unless said otherwise, as every export was before there was a choice.
    assertThat(PartKeys.part(JOB, 7)).isEqualTo(PartKeys.part(JOB, 7, OutputFormat.JSON));
  }
}
