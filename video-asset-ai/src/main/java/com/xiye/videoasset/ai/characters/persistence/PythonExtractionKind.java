package com.xiye.videoasset.ai.characters.persistence;

public enum PythonExtractionKind {
    CHARACTER_EXTRACTION(null),
    AUDIO_EXTRACTION("AUDIO"),
    SHOT_EXTRACTION("SHOTS"),
    DIALOGUE_EXTRACTION("DIALOGUE"),
    BACKGROUND_EXTRACTION("BACKGROUND");

    private final String assetBranch;

    PythonExtractionKind(String assetBranch) {
        this.assetBranch = assetBranch;
    }

    public String assetBranch() {
        return assetBranch;
    }
}
