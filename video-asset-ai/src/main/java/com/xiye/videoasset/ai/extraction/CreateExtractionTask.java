package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.api.extraction.ExtractionType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

record CreateExtractionTask(
        @NotNull @Positive Long episodeId, @NotEmpty @Size(max = 6) List<@NotNull ExtractionType> extractTypes,
        boolean regenerate
) {
}
