package com.xiye.videoasset.api.extraction;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExtractionPlan {
    private static final Set<ExtractionType> IMPLEMENTED = Set.of(ExtractionType.CHARACTER, ExtractionType.AUDIO);
    private final Set<ExtractionType> enabled;

    public ExtractionPlan(String configured) {
        this.enabled = Arrays.stream(configured.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(ExtractionType::valueOf).filter(IMPLEMENTED::contains).collect(Collectors.toUnmodifiableSet());
    }

    public boolean supports(ExtractionType type) {
        return enabled.contains(type);
    }

    public List<ExtractionType> validate(Collection<ExtractionType> types) {
        if (types == null || types.isEmpty() || types.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("至少选择一个提取类型");
        }
        return List.copyOf(EnumSet.copyOf(types));
    }

    public static String signature(List<ExtractionType> types) {
        return types.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
