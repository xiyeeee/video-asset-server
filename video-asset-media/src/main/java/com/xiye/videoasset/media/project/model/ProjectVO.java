package com.xiye.videoasset.media.project.model;

import java.time.Instant;

public record ProjectVO(String id, String name, String description, Instant createdAt, Instant updatedAt) {
}
